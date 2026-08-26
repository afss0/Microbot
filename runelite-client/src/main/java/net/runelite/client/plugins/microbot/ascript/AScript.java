package net.runelite.client.plugins.microbot.ascript;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.ascript.crafting.CraftingScript;
import net.runelite.client.plugins.microbot.ascript.fletching.FletchingScript;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;

import java.util.concurrent.TimeUnit;

/**
 * aScript — AIO orchestrator (standard Script, no state machine).
 * <p>
 * Each tick: check config → resolve phase → bank if needed → craft.
 * Sub-scripts (crafting, etc.) are dispatched directly.
 * <p>
 * If a sub-script detects missing materials in both bank AND inventory,
 * it stops immediately and sets scriptSelection to NONE.
 */
@Slf4j
public class AScript extends Script {

    /** High-level phase exposed to the overlay. */
    public enum Phase { DISABLED, IDLE, BANKING, CRAFTING, ERROR }

    private AScriptConfig config;

    @Getter
    private Phase currentPhase = Phase.DISABLED;

    private boolean stopRequested;
    /** Last time the auto zoom-out ran (rate-limited — players don't re-zoom every tick). */
    private long lastZoomOutTime;
    /** Current auto-eat HP% threshold (0 = needs a roll; re-rolled after every successful bite). */
    private int eatThreshold;
    /** Antiban intensity to restore when the Crafting module deactivates (null = nothing captured). */
    private ActivityIntensity previousMouseIntensity;
    /** True while we've forced VERY_LOW for the Crafting module. */
    private boolean craftingMouseSpeedApplied;
    /** Consecutive doBank failures — stops script after threshold. */
    private int consecutiveBankFailures = 0;
    private static final int MAX_BANK_FAILURES = 3;

    // ── Sub-scripts ─────────────────────────────────────────────
    private final CraftingScript craftingScript = new CraftingScript();
    private CraftingScript.Phase craftingActivity = CraftingScript.Phase.NONE;
    /** Previous tick's activity — used to detect NONE → active transitions. */
    private CraftingScript.Phase lastCraftingActivity = CraftingScript.Phase.NONE;

    private final FletchingScript fletchingScript = new FletchingScript();
    private FletchingScript.Phase fletchingActivity = FletchingScript.Phase.NONE;
    private FletchingScript.Phase lastFletchingActivity = FletchingScript.Phase.NONE;

    // ── Main loop ───────────────────────────────────────────────

    public boolean run(AScriptConfig config) {
        this.config = config;
        log.info("[AScript] Starting AIO script");

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return; // heartbeat / pause guard
                tick();
            } catch (Exception ex) {
                log.error("[AScript] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    private void tick() {
        // 0. Antiban: Very Low mouse speed while the Crafting module is active,
        // restore the previous intensity when it stops / switches module
        manageCraftingMouseSpeed();

        // 1. Config check
        if (config == null || !config.enabled() || config.scriptSelection() == ScriptType.NONE) {
            currentPhase = Phase.DISABLED;
            stopRequested = false;
            consecutiveBankFailures = 0;
            eatThreshold = 0;
            return;
        }

        // 1b. QOL: keep the camera zoomed out (rate-limited —
        // players don't re-zoom every tick)
        if (config.autoZoomOut()
                && System.currentTimeMillis() - lastZoomOutTime > 60_000) {
            lastZoomOutTime = System.currentTimeMillis();
            Rs2Camera.zoomOutFully();
        }

        // 1c. QOL: auto-eat when hitpoints fall below the rolled threshold.
        // eatAt is non-blocking (one item per call); the threshold is held
        // across ticks and only re-rolled after an actual bite.
        if (config.autoEat()) {
            if (eatThreshold <= 0) rollEatThreshold();
            if (Rs2Player.eatAt(eatThreshold)) {
                Rs2Player.waitForAnimation();
                rollEatThreshold();
            }
        }

        // 2. Resolve crafting activity
        craftingActivity = craftingScript.resolvePhase(config);

        // 2a. Resolve fletching activity
        fletchingActivity = fletchingScript.resolvePhase(config);

        // 2b. NONE -> active transitions clear module exit flags so a module that
        // self-stopped (furnace not found, persistent craft failures) un-sticks
        // when the user re-selects it.
        if (craftingActivity != CraftingScript.Phase.NONE && lastCraftingActivity == CraftingScript.Phase.NONE) {
            craftingScript.resetExitFlag();
        }
        lastCraftingActivity = craftingActivity;

        if (fletchingActivity != FletchingScript.Phase.NONE && lastFletchingActivity == FletchingScript.Phase.NONE) {
            fletchingScript.resetExitFlag();
        }
        lastFletchingActivity = fletchingActivity;

        // 2c. Invalid sub-selection (e.g. GEM_CUTTING with no gem) → stop once, clear message
        String selectionError = craftingScript.validateSelection(config, craftingActivity);

        // Also check fletching selection error
        if (selectionError == null) {
            selectionError = fletchingScript.validateSelection(config, fletchingActivity);
        }

        if (selectionError != null) {
            stopWithMessage("aScript Stopped", selectionError);
            return;
        }

        // 3. Missing materials from both bank+inventory → stop once

        boolean craftNeedsBank = craftingActivity != CraftingScript.Phase.NONE
                && craftingScript.needsBank(config, craftingActivity);
        boolean fletchNeedsBank = fletchingActivity != FletchingScript.Phase.NONE
                && fletchingScript.needsBank(config, fletchingActivity);

        if (craftNeedsBank || fletchNeedsBank) {

            // Open bank to check stock; if opening fails, wait for the next tick.
            // Checking materials against a stale/empty bank snapshot (bank never opened)
            // causes false "no materials" stops.
            if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
                currentPhase = Phase.BANKING;
                return;
            }

            boolean bankMissingCraft = craftNeedsBank
                    && craftingScript.isBankMissingMaterials(config, craftingActivity);
            boolean bankMissingFletch = fletchNeedsBank
                    && fletchingScript.isBankMissingMaterials(config, fletchingActivity);

            if (bankMissingCraft || bankMissingFletch) {
                String missing = bankMissingCraft
                        ? craftingScript.describeMissing(config, craftingActivity)
                        : fletchingScript.describeMissing(config, fletchingActivity);
                stopWithMessage("aScript Stopped — No Materials", missing);
                return;
            }
            // Bank HAS materials — fall through to BANKING
        } else {
            // Inventory has everything (or no activity) — close bank if open
            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
            }
        }

        // 4. Dispatch
        if (craftingActivity == CraftingScript.Phase.NONE && fletchingActivity == FletchingScript.Phase.NONE) {
            currentPhase = Phase.IDLE;
            return;
        }

        if (craftNeedsBank) {
            currentPhase = Phase.BANKING;
            boolean banked = craftingScript.doBank(config, craftingActivity);
            if (banked) {
                consecutiveBankFailures = 0;
            } else {
                consecutiveBankFailures++;
                if (consecutiveBankFailures >= MAX_BANK_FAILURES) {
                    stopWithMessage("aScript Stopped — No Materials",
                            "Bank failed " + consecutiveBankFailures + " times in a row");
                }
            }
        } else if (fletchNeedsBank) {
            currentPhase = Phase.BANKING;
            boolean banked = fletchingScript.doBank(config, fletchingActivity);
            if (banked) {
                consecutiveBankFailures = 0;
            } else {
                consecutiveBankFailures++;
                if (consecutiveBankFailures >= MAX_BANK_FAILURES) {
                    stopWithMessage("aScript Stopped — No Materials",
                            "Bank failed " + consecutiveBankFailures + " times in a row");
                }
            }
        } else if (craftingActivity != CraftingScript.Phase.NONE) {
            currentPhase = Phase.CRAFTING;
            craftingScript.doCraft(config, craftingActivity);
        } else if (fletchingActivity != FletchingScript.Phase.NONE) {
            currentPhase = Phase.CRAFTING;
            fletchingScript.doCraft(config, fletchingActivity);
        }
    }

    // ── QOL: auto-eat ───────────────────────────────────────────

    /**
     * Rolls a new auto-eat threshold: normal-distributed between the config
     * bounds, shifted down when real-world weather is bad (slower, more
     * hesitant reactions → the player eats later).
     */
    private void rollEatThreshold() {
        int min = Math.min(config.autoEatMinHpPercent(), config.autoEatMaxHpPercent());
        int max = Math.max(config.autoEatMinHpPercent(), config.autoEatMaxHpPercent());
        int roll = Rs2Random.fancyNormalSample(min, max);

        WeatherModulation.ensureFresh();
        // combinedSpeedFactor() ≤ 1.0 (~0.75 cold/storm … 1.0 mild/clear) —
        // map it to a small downward offset so bad weather eats later,
        // without drifting far below the configured range.
        int weatherOffset = (int) Math.round((WeatherModulation.combinedSpeedFactor() - 1.0) * 15);
        eatThreshold = Math.max(Math.max(1, min - 5), Math.min(max, roll + weatherOffset));
    }

    // ── Lifecycle ───────────────────────────────────────────────

    /**
     * Forces {@link ActivityIntensity#VERY_LOW} mouse speed while a precision
     * module is active (Crafting — jewelry widget; Fletching — combine widgets)
     * and restores the previous intensity when it stops or the user switches to
     * another module.
     * <p>
     * Note: {@code Rs2Antiban.setActivityIntensity} is global (it also disables
     * dynamic intensity), so the previous value must be captured and restored —
     * otherwise other scripts would inherit Very Low after this one stops.
     */
    private void manageCraftingMouseSpeed() {
        boolean precisionModuleActive = config != null && config.enabled()
                && (config.scriptSelection() == ScriptType.CRAFTING
                    || config.scriptSelection() == ScriptType.FLETCHING);

        if (precisionModuleActive && !craftingMouseSpeedApplied) {
            previousMouseIntensity = Rs2Antiban.getActivityIntensity();
            Rs2Antiban.setActivityIntensity(ActivityIntensity.VERY_LOW);
            craftingMouseSpeedApplied = true;
            log.debug("[AScript] Mouse speed -> VERY_LOW (precision module active), previous: {}",
                    previousMouseIntensity);
        } else if (!precisionModuleActive && craftingMouseSpeedApplied) {
            if (previousMouseIntensity != null) {
                Rs2Antiban.setActivityIntensity(previousMouseIntensity);
            }
            craftingMouseSpeedApplied = false;
            log.debug("[AScript] Mouse speed restored to {}", previousMouseIntensity);
        }
    }

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
        if (craftingActivity != CraftingScript.Phase.NONE) craftingScript.resetExitFlag();
        if (fletchingActivity != FletchingScript.Phase.NONE) fletchingScript.resetExitFlag();
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
        currentPhase = Phase.ERROR;
    }

    @Override
    public void shutdown() {
        // Restore the global antiban intensity if the loop is cancelled while a
        // precision module is active — tick() can no longer do it.
        if (craftingMouseSpeedApplied) {
            if (previousMouseIntensity != null) {
                Rs2Antiban.setActivityIntensity(previousMouseIntensity);
            }
            craftingMouseSpeedApplied = false;
            previousMouseIntensity = null;
        }
        stopRequested = false;
        consecutiveBankFailures = 0;
        lastCraftingActivity = CraftingScript.Phase.NONE;
        lastFletchingActivity = FletchingScript.Phase.NONE;
        craftingScript.resetExitFlag();
        fletchingScript.resetExitFlag();
        currentPhase = Phase.DISABLED;
        super.shutdown();
    }
}
