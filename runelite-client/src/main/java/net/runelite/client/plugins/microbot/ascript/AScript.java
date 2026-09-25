package net.runelite.client.plugins.microbot.ascript;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher.BarbarianVillageFisherScript;
import net.runelite.client.plugins.microbot.ascript.crafting.CraftingScript;
import net.runelite.client.plugins.microbot.ascript.fletching.FletchingScript;
import net.runelite.client.plugins.microbot.ascript.gemcrabkiller.GemCrabKillerScript;
import net.runelite.client.plugins.microbot.ascript.jewellenchant.JewelEnchantScript;
import net.runelite.client.plugins.microbot.ascript.motherloadmine.MotherloadMineScript;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * aScript — AIO orchestrator (standard Script, no state machine).
 * <p>
 * Each tick: check config → resolve phase → bank if needed → craft.
 * Sub-modules implement {@link AModule}; the orchestrator dispatches generically.
 * <p>
 * Adding a module = implement AModule + add to {@link #MODULES}. No tick() changes needed.
 */
@Slf4j
public class AScript extends Script {

    /** High-level phase exposed to the overlay. */
    public enum Phase { DISABLED, IDLE, BANKING, CRAFTING, ERROR }

    // ── Module registry ─────────────────────────────────────
    /** All modules, in dispatch priority order. First active module wins. */
    private static final List<AModule> MODULES = List.of(
            new CraftingScript(),
            new FletchingScript(),
            new MotherloadMineScript(),
            new GemCrabKillerScript(),
            new BarbarianVillageFisherScript(),
            new JewelEnchantScript()
    );

    /** Previous tick's active state per module — for NONE → active transition detection. */
    private final Map<AModule, Boolean> wasActive = new IdentityHashMap<>();

    // ── State ───────────────────────────────────────────────
    private AScriptConfig config;

    @Getter
    private Phase currentPhase = Phase.DISABLED;

    private boolean stopRequested;
    private long lastZoomOutTime;
    /** Current auto-eat HP% threshold (0 = needs a roll; re-rolled after every successful bite). */
    private int eatThreshold;
    /** Consecutive auto-eat attempts that did not consume food — stops the script after the threshold. */
    private int consecutiveEatFailures = 0;
    private static final int MAX_EAT_FAILURES = 3;
    /** True while we've forced VERY_LOW for a precision module. */
    private boolean precisionMouseSpeedApplied;
    /** Consecutive doBank failures — stops script after threshold. */
    private int consecutiveBankFailures = 0;
    private static final int MAX_BANK_FAILURES = 3;
    /** Track if the player was logged in at least once — triggers the logout watcher. */
    private boolean playerWasLoggedIn = false;
    /** Prevent repeated logout notifications. */
    private boolean logoutHandled = false;

    // ── Main loop ───────────────────────────────────────────

    public boolean run(AScriptConfig config) {
        this.config = config;
        this.playerWasLoggedIn = false;
        this.logoutHandled = false;
        log.info("[AScript] Starting AIO script");

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                // Logout watcher: detect login screen while script was active
                if (!Microbot.isLoggedIn()) {
                    GameState gs = Microbot.getClient() != null
                            ? Microbot.getClient().getGameState() : GameState.UNKNOWN;
                    boolean isAtLoginScreen = gs == GameState.LOGIN_SCREEN
                            || gs == GameState.LOGIN_SCREEN_AUTHENTICATOR;

                    if (playerWasLoggedIn && isAtLoginScreen && !logoutHandled) {
                        stopWithMessage("Unexpected Logout",
                                "aScript detected player logged out while script was running.");
                        logoutHandled = true;
                    }
                    return;
                }

                // Mark player as logged in and reset logout guard
                playerWasLoggedIn = true;
                if (logoutHandled) logoutHandled = false;

                if (!super.run()) return; // heartbeat / pause guard
                tick();
            } catch (Exception ex) {
                log.error("[AScript] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    private void tick() {
        // 1. Config check
        if (config == null || !config.enabled() || config.scriptSelection() == ScriptType.NONE) {
            currentPhase = Phase.DISABLED;
            stopRequested = false;
            consecutiveBankFailures = 0;
            consecutiveEatFailures = 0;
            eatThreshold = 0;
            return;
        }

        // 1b. QOL: keep the camera zoomed out (rate-limited)
        if (config.autoZoomOut()
                && System.currentTimeMillis() - lastZoomOutTime > 60_000) {
            lastZoomOutTime = System.currentTimeMillis();
            Rs2Camera.zoomOutFully();
        }

        // 1c. QOL: auto-eat when hitpoints fall below the rolled threshold.
        if (!autoEatEnabled(config)) {
            // Auto-eat off, or a module that owns its own food: drop the rolled threshold
            // (re-enabling must roll fresh from the current slider values, not reuse a
            // threshold rolled under the old settings) and clear the strike count.
            eatThreshold = 0;
            consecutiveEatFailures = 0;
        } else {
            if (eatThreshold <= 0) rollEatThreshold();
            // Hitpoint check FIRST: eatReady() below has a side effect (it clicks to cancel a
            // pending selection) and must not run on ticks where no bite is due.
            if (belowEatThreshold()
                    && eatReady()
                    && eatBestFood()) {
                // The Eat/Drink op being dispatched proves nothing: the dispatch routes through
                // Rs2Inventory.interact(), which returns true unconditionally and can bail
                // silently inside invokeMenu. Confirm the bite landed before re-rolling the
                // threshold; an unconfirmed bite is counted so a dead auto-eat stops the
                // script loudly instead of stalling the loop forever.
                if (waitForBite()) {
                    consecutiveEatFailures = 0;
                    rollEatThreshold();
                } else {
                    failEat();
                }
            }
        }

        // 2. Resolve all module phases + detect NONE → active transitions
        for (AModule mod : MODULES) {
            boolean prevActive = wasActive.getOrDefault(mod, false);
            mod.resolvePhase(config);
            boolean active = mod.isActive();
            if (active && !prevActive) {
                mod.resetExitFlag();
            }
            wasActive.put(mod, active);
        }

        // 3. Validate selection (first error wins)
        String selectionError = null;
        for (AModule mod : MODULES) {
            if (!mod.isActive()) continue;
            selectionError = mod.validateSelection(config);
            if (selectionError != null) break;
        }
        if (selectionError != null) {
            stopWithMessage("aScript Stopped", selectionError);
            return;
        }

        // 4. Find the active module that needs the bank (at most one)
        AModule bankModule = null;
        for (AModule mod : MODULES) {
            if (mod.isActive() && mod.needsBank(config)) {
                bankModule = mod;
                break;
            }
        }

        if (bankModule != null) {
            // Open bank to check stock; if opening fails, wait for the next tick.
            if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
                currentPhase = Phase.BANKING;
                return;
            }

            if (bankModule.isBankMissingMaterials(config)) {
                stopWithMessage("aScript Stopped — No Materials",
                        bankModule.describeMissing(config));
                return;
            }
            // Bank HAS materials — fall through to BANKING
        } else {
            // No module needs bank — close bank if open
            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
            }
        }

        // 5. Manage precision mouse speed (after phase resolution)
        managePrecisionMouseSpeed();

        // 6. Dispatch
        if (bankModule != null) {
            currentPhase = Phase.BANKING;
            boolean banked = bankModule.doBank(config);
            if (banked) {
                consecutiveBankFailures = 0;
            } else {
                consecutiveBankFailures++;
                if (consecutiveBankFailures >= MAX_BANK_FAILURES) {
                    stopWithMessage("aScript Stopped — No Materials",
                            "Bank failed " + consecutiveBankFailures + " times in a row");
                }
            }
        } else {
            for (AModule mod : MODULES) {
                if (mod.isActive()) {
                    currentPhase = Phase.CRAFTING;
                    mod.doAction(config);
                    break;
                }
            }
            // No active module → IDLE
            if (currentPhase != Phase.CRAFTING) {
                currentPhase = Phase.IDLE;
            }
        }
    }

    // ── QOL: auto-eat ───────────────────────────────────────

    /**
     * Rolls a new auto-eat threshold: normal-distributed between the config
     * bounds, shifted down when real-world weather is bad.
     */
    private void rollEatThreshold() {
        int min = Math.min(config.autoEatMinHpPercent(), config.autoEatMaxHpPercent());
        int max = Math.max(config.autoEatMinHpPercent(), config.autoEatMaxHpPercent());
        int roll = Rs2Random.fancyNormalSample(min, max);

        WeatherModulation.ensureFresh();
        int weatherOffset = (int) Math.round((WeatherModulation.combinedSpeedFactor() - 1.0) * 15);
        eatThreshold = Math.max(Math.max(1, min - 5), Math.min(max, roll + weatherOffset));
    }

    /**
     * True when hitpoints are at or below the rolled auto-eat threshold.
     * <p>
     * Compares the hitpoint LEVELS instead of {@link Rs2Player#getHealthPercentage()}, which
     * divides by the real level: while that level still reads 0 (skill data not loaded yet —
     * the client-thread read also falls back to 0 on timeout) the ratio is NaN (0/0) or
     * Infinity, every comparison against it is false, and the auto-eat silently does nothing.
     * An unloaded level returns false here and the next tick retries — which also keeps
     * {@link Rs2Player#eatAt(int)}'s own division away from the zero denominator.
     * <p>
     * Same "at or below" semantics as the ratio it replaces.
     */
    private boolean belowEatThreshold() {
        int real = Rs2Player.getRealSkillLevel(Skill.HITPOINTS);
        if (real <= 0) return false; // hitpoint level not loaded — retry next tick
        return (double) (Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS) * 100) / real <= eatThreshold;
    }

    /**
     * True when the auto-eat QOL may run for the current selection: the user enabled
     * it, and the active module does not own its own food logic.
     * <p>
     * Gem Crab Killer keeps HP management to itself — its own eat thresholds (50%
     * normal, 2% emergency), its own bank-for-food trip at 25% and the Dharok mode
     * that deliberately holds HP at 10 for the set effect. A global 35–60% eat would
     * double-eat in normal mode and silently break Dharok mode, so the QOL stays out.
     */
    private boolean autoEatEnabled(AScriptConfig config) {
        if (!config.autoEat()) return false;
        return config.scriptSelection() != ScriptType.GEM_CRAB_KILLER;
    }

    /**
     * Eat the best food in the inventory, preferring the largest heal per bite.
     * <p>
     * {@link Rs2Player#eatAt(int)} delegates to {@code useFood()}, which eats the FIRST food in
     * slot order — a shark in slot 3 always wins over a summer pie in slot 20, whatever the
     * heal. The selection here mirrors {@code useFood()}'s rules (unnoted items only, blighted
     * food first in the Wilderness, "jug of wine" is drunk rather than eaten) but sorts by the
     * per-bite heal in {@link Rs2Food}, so the biggest heal available goes first. Food with no
     * heal in the table (level-scaled or random heals) sorts last; ties fall back to the lowest
     * slot so the choice stays deterministic.
     *
     * @return true when the Eat/Drink op was dispatched — see {@link #waitForBite()} for the
     *         confirmation, the dispatch itself proves nothing
     */
    private boolean eatBestFood() {
        List<Rs2ItemModel> foods = Rs2Inventory.getInventoryFood().stream()
                .filter(food -> !food.isNoted())
                .collect(Collectors.toList());
        if (foods.isEmpty()) return false;

        if (Microbot.getVarbitValue(Varbits.IN_WILDERNESS) == 1) {
            List<Rs2ItemModel> blighted = foods.stream()
                    .filter(food -> food.getName().toLowerCase().contains("blighted"))
                    .collect(Collectors.toList());
            if (!blighted.isEmpty()) foods = blighted;
        }

        Rs2ItemModel food = foods.stream()
                .max(Comparator.comparingInt(this::foodHeal)
                        .thenComparingInt(candidate -> -candidate.getSlot()))
                .orElse(null);
        if (food == null) return false;

        return food.getName().toLowerCase().contains("jug of wine")
                ? Rs2Inventory.interact(food, "drink")
                : Rs2Inventory.interact(food, "eat");
    }

    /** Per-bite heal of {@code food} from {@link Rs2Food}; 0 when the item is not in the table. */
    private int foodHeal(Rs2ItemModel food) {
        for (Rs2Food known : Rs2Food.values()) {
            if (known.getId() == food.getId()) {
                return known.getHeal();
            }
        }
        return 0;
    }

    /**
     * True when an Eat op can actually land: no spell/item is left selected on the cursor.
     * <p>
     * {@code Rs2Inventory.invokeMenu()} flips the op to {@code WIDGET_TARGET_ON_WIDGET}
     * whenever {@code isWidgetSelected()} is true — with a spell selected the "Eat" is sent
     * as "cast the selected spell at the food", so the food is never eaten. The pending
     * selection is cancelled with the same idiom the bank / deposit box / GE utils use
     * ({@code if (isItemSelected()) mouse.click()}: a click at the current position drops
     * it), then confirmed gone. When it cannot be cancelled the attempt is skipped rather
     * than dispatched as a wrong action — counting it would burn the
     * {@link #MAX_EAT_FAILURES} budget against a state the food cannot fix.
     * <p>
     * Note: {@code Rs2Inventory.deselect()} is NOT usable here — it re-uses the selected
     * widget's item id, which is -1 for a spell, so it no-ops.
     *
     * @return true when it is safe to dispatch the Eat
     */
    private boolean eatReady() {
        if (!Rs2Inventory.isItemSelected()) return true;
        Microbot.getMouse().click();
        boolean cleared = sleepUntil(() -> !Rs2Inventory.isItemSelected(),
                Rs2Random.logNormalBounded(400, 900));
        if (!cleared) {
            log.debug("[AScript] Auto-eat skipped: a spell/item is still selected on the cursor");
        }
        return cleared;
    }

    /**
     * Confirm an auto-eat actually landed: inventory food was consumed, or hitpoints
     * rose. {@link Rs2Player#eatAt(int)} only proves the Eat op was dispatched.
     * <p>
     * Bounded by {@link Rs2Random} timing and NOT by {@link Rs2Player#waitForAnimation()}:
     * that helper waits for ANY animation to start (the module's own mining/crafting/combat
     * animation is usually already running) and can hold this 600 ms loop for up to ~10 s
     * per bite, stalling bank trips and click verification with it.
     *
     * @return true when the bite is visible in game state
     */
    private boolean waitForBite() {
        int foodBefore = edibleFoodCount();
        if (foodBefore <= 0) return false; // nothing edible — the dispatch could not have landed
        int hpBefore = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);

        WeatherModulation.ensureFresh();
        double weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();
        return sleepUntil(() -> Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS) > hpBefore
                        || edibleFoodCount() < foodBefore,
                Rs2Random.logNormalBounded(1200, 2500, weatherMultiplier));
    }

    /** Total quantity of UNNOTED food in the inventory (banknotes cannot be eaten). */
    private int edibleFoodCount() {
        return Rs2Inventory.getInventoryFood().stream()
                .filter(food -> !food.isNoted())
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();
    }

    /**
     * Count a dispatched-but-unconfirmed bite and stop the script (status, log, Discord
     * notification, config reset) after {@link #MAX_EAT_FAILURES} in a row: a broken
     * safety net must be loud, not a silent wait every tick while hitpoints drain.
     */
    private void failEat() {
        consecutiveEatFailures++;
        log.warn("[AScript] Auto-eat not confirmed ({}/{})", consecutiveEatFailures, MAX_EAT_FAILURES);
        if (consecutiveEatFailures >= MAX_EAT_FAILURES) {
            consecutiveEatFailures = 0;
            stopWithMessage("aScript Stopped — Auto Eat Failed",
                    "Auto-eat sent \"Eat\" " + MAX_EAT_FAILURES
                            + " times without the food being consumed — check the food in the inventory");
        }
    }

    // ── Precision mouse speed ────────────────────────────────

    /**
     * Forces {@link ActivityIntensity#VERY_LOW} mouse speed while a precision
     * module is active and restores the previous intensity when it stops.
     * <p>
     * Delegates to each module's {@link AModule#isPrecisionModule()} — no
     * hardcoded ScriptType checks needed.
     */
    private void managePrecisionMouseSpeed() {
        boolean anyPrecisionActive = false;
        for (AModule mod : MODULES) {
            if (mod.isActive() && mod.isPrecisionModule()) {
                anyPrecisionActive = true;
                break;
            }
        }

        if (anyPrecisionActive && !precisionMouseSpeedApplied) {
            Rs2Antiban.setActivityIntensity(ActivityIntensity.VERY_LOW);
            precisionMouseSpeedApplied = true;
            log.debug("[AScript] Mouse speed -> VERY_LOW (precision module active)");
        } else if (!anyPrecisionActive && precisionMouseSpeedApplied) {
            ActivityIntensity current = Rs2Antiban.getActivityIntensity();
            if (current != null && current != ActivityIntensity.VERY_LOW) {
                Rs2Antiban.setActivityIntensity(current);
                log.debug("[AScript] Mouse speed restored to {} (was VERY_LOW)", current);
            } else {
                Rs2Antiban.setActivityIntensity(ActivityIntensity.MODERATE);
                log.debug("[AScript] Mouse speed restored to MODERATE (previous was VERY_LOW or null)");
            }
            precisionMouseSpeedApplied = false;
        }
    }

    // ── Lifecycle ───────────────────────────────────────────

    /** Stop script, notify Discord, and reset config. */
    private void stopWithMessage(String title, String message) {
        if (stopRequested) return; // already stopping
        stopRequested = true;
        consecutiveBankFailures = 0;
        Microbot.status = "STOPPED — " + message;
        log.warn("[AScript] {}: {}", title, message);
        AScriptNotify.notify(title, message);
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
        }
        for (AModule mod : MODULES) {
            if (mod.isActive()) mod.resetExitFlag();
        }
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "enabled", false);
        currentPhase = Phase.ERROR;
    }

    @Override
    public void shutdown() {
        // Restore the global antiban intensity if the loop is cancelled while a
        // precision module is active — tick() can no longer do it.
        if (precisionMouseSpeedApplied) {
            ActivityIntensity current = Rs2Antiban.getActivityIntensity();
            if (current != null && current != ActivityIntensity.VERY_LOW) {
                Rs2Antiban.setActivityIntensity(current);
            } else {
                Rs2Antiban.setActivityIntensity(ActivityIntensity.MODERATE);
            }
            precisionMouseSpeedApplied = false;
        }
        stopRequested = false;
        consecutiveBankFailures = 0;
        consecutiveEatFailures = 0;
        eatThreshold = 0;
        playerWasLoggedIn = false;
        logoutHandled = false;
        for (AModule mod : MODULES) {
            wasActive.put(mod, false);
            mod.resetExitFlag();
        }
        currentPhase = Phase.DISABLED;
        super.shutdown();
    }
}
