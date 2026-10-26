package net.runelite.client.plugins.microbot.ascript.gemcrabkiller;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.eventdismiss.LampUtility;
import net.runelite.client.plugins.microbot.ascript.motherloadmine.Pickaxe;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptFood;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptPick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Gem Crab Killer sub-script — stateless helper called by AScript's CRAFTING state.
 * <p>
 * Adapted from chsami/Microbot-Hub's GemCrabKillerPlugin.
 * The entire combat loop runs inside {@link #doFight} per tick.
 */
@Slf4j
public class GemCrabKillerScript implements AModule {

    /** Module phase — exposed to AScript orchestrator. */
    public enum Phase { NONE, GEM_CRAB_KILLER }

    private Phase currentPhase = Phase.NONE;

    // ── Constants ──────────────────────────────────────────────

    private static final int CAVE_ENTRANCE_ID = 57631;
    private static final int CRAB_NPC_ID = 14779;
    private static final int CRAB_NPC_DEAD_ID = 14780;
    private static final WorldPoint CLOSEST_CRAB_LOCATION_TO_BANK = new WorldPoint(1274, 3168, 0);

    // ── Internal state ─────────────────────────────────────────

    private enum InternalState { WALKING, FIGHTING, BANKING, WAITING }

    private InternalState internalState = InternalState.WALKING;
    private boolean exitRequested = false;
    private boolean hasLooted = false;
    private Instant waitingTimeStart = null;
    private int consecutiveFightFailures = 0;
    private static final int MAX_CONSECUTIVE_FIGHT_FAILURES = 10;
    /** Consecutive bank trips that may fail before the module stops (one notification). */
    private static final int MAX_BANK_FAILURES = 3;
    /**
     * Edible foods the module will withdraw, by exact id. A name lookup resolves the
     * SHORTEST match, so "Shark" also matches "Raw shark"/"Burnt shark" and can fill the
     * inventory with items that cannot be eaten.
     */
    private static final int[] FOOD_IDS = {ItemID.SHARK, ItemID.MONKFISH, ItemID.SWORDFISH};
    /** Consecutive failed bank trips; reset by any successful trip. */
    private int bankFailureCount = 0;
    /** True when the last doBank() call yielded to the lamp blocking event (not a failure). */
    private boolean bankYieldedToLamp = false;
    /** Index of the last corpse counted; -1 until the first kill of the session. */
    private int lastCountedCorpseIndex = -1;
    /** Loot clicks spent on the current corpse (bounded so a failed click cannot loop). */
    private int lootAttempts = 0;
    private static final int MAX_LOOT_ATTEMPTS = 3;
    /** Deadline for the current WAITING stay, rolled once when the state is entered. */
    private long waitingDeadlineMs = 0L;
    /** Set once per doFight() call; used for weather-modulated timing. */
    private double weatherMultiplier = 1.0;
    /** Timestamp of the last random AFK — minimum 5 s between AFKs. */
    private long lastAfkTime = 0L;
    /**
     * One post-kill break per completed kill: set when the break runs and cleared when a new
     * fight is engaged (the {@link #hasLooted} lifecycle — a corpse still in the scene never
     * earns a second break).
     */
    private boolean postKillBreakDone = false;

    // ── Combat thresholds ──────────────────────────────────────
    /** HP% below which we bank for food (when useFood enabled). */
    private static final double BANK_FOOD_HP_THRESHOLD = 25.0;
    /** HP% at which normal-mode eating triggers. */
    private static final int EAT_NORMAL_HP = 50;
    /** HP% for emergency eat in Dharok mode (near death). */
    private static final int EAT_EMERGENCY_HP = 2;
    /** HP% target for Dharok mode — guzzle locator orb/rock cake down to this. */
    private static final int DHAROK_LOW_HP = 10;

    /** Total crab kills this session. Accessible from AScript for overlay display. */
    @lombok.Getter
    private int totalCrabKills = 0;

    /** Session start time for XP tracking. */
    @lombok.Getter
    private Instant sessionStartTime = null;

    // ── Module API (called by AScript orchestrator) ────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null || config.scriptSelection() != ScriptType.GEM_CRAB_KILLER) {
            this.currentPhase = Phase.NONE; return;
        }
        this.currentPhase = Phase.GEM_CRAB_KILLER;
    }

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.GEM_CRAB_KILLER) {
            return "no gem crab killer activity selected";
        }
        return null;
    }

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        // The module banks through its own BANKING state: the bank is a long walk from the
        // cave, so the orchestrator's bank cycle (which expects to bank in place) cannot
        // drive it. The orchestrator therefore never sees a bank need for this module —
        // failure handling lives in doAction(), which counts consecutive failed trips.
        return false;
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        // Never consulted: needsBank() is constant false, so the orchestrator has no bank
        // cycle to short-circuit. A bank without food is caught by the module-local
        // MAX_BANK_FAILURES counter in doAction(), which stops after three failed trips.
        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        return "no food in bank";
    }

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        bankYieldedToLamp = false;

        // Lamp-vs-bank gate: never bank with an XP lamp in the inventory — it must be
        // used first (lamps are never deposited). Yields (returns false) until the
        // lamp-use blocking event has consumed it; doAction() does not count a yield
        // as a bank failure.
        if (LampUtility.yieldBankingToLamp(config.eventDismissLampSkill())) {
            bankYieldedToLamp = true;
            return false;
        }

        Microbot.status = "WALKING TO BANK";
        Rs2Walker.walkTo(BankLocation.TAL_TEKLAN.getWorldPoint());

        Microbot.status = "OPENING BANK";
        Rs2Bank.openBank();
        if (!sleepUntil(Rs2Bank::isOpen, Rs2Random.logNormalBounded(8000, 15000, weatherMultiplier))) {
            return false;
        }
        if (!AScriptBank.ensureItemWithdrawMode()) {
            log.warn("[GemCrabKiller] bank would not switch to 'Withdraw as item'");
            Rs2Bank.closeBank();
            return false;
        }

        Microbot.status = "DEPOSITING";
        // Keep the tools and supplies the trip is supposed to bring back: every pickaxe id,
        // the Dharok lowering items (never deposited), and any potion the inventory holds —
        // depositAll() would bank them and never restore them. A refused sweep (keeper lock
        // unavailable) deposited NOTHING; failing the trip is the honest answer, otherwise
        // the loot/tools stay in the bag while the module withdraws food over them.
        if (!AScriptBank.depositAllExcept(bankKeepIds())) {
            log.warn("[GemCrabKiller] keeper sweep failed — bank trip will retry");
            AScriptBank.restoreWithdrawMode();
            Rs2Bank.closeBank();
            return false;
        }

        boolean useFood = config.gemCrabUseFood();
        boolean isDharok = config.gemCrabDharokMode();
        if (useFood && !isDharok) {
            Microbot.status = "WITHDRAWING FOOD";
            if (!withdrawFood()) {
                // The single notification is owned by the MAX_BANK_FAILURES stop in
                // doAction(); alerting here would repeat the same verdict every cycle.
                log.warn("[GemCrabKiller] Bank trip found no edible food (ids {})", FOOD_IDS);
                AScriptBank.restoreWithdrawMode();
                Rs2Bank.closeBank();
                sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier));
                return false;
            }
        }

        AScriptBank.restoreWithdrawMode();
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier));

        internalState = InternalState.WALKING;
        // Success: food landed (normal mode), or no food is required at all — the Dharok
        // path never withdraws food, so requiring it there would fail every trip.
        return !useFood || isDharok || !Rs2Inventory.getInventoryFood().isEmpty();
    }

    /** Ids that must survive a bank trip: pickaxes, Dharok lowering items, held potions. */
    private List<Integer> bankKeepIds() {
        List<Integer> keep = new ArrayList<>();
        for (Pickaxe pickaxe : Pickaxe.values()) {
            keep.add(pickaxe.getItemId());
        }
        keep.add(ItemID.LOCATOR_ORB);
        keep.add(ItemID.DWARVEN_ROCK_CAKE_7510);
        Rs2Inventory.items()
                .filter(item -> item != null && item.getName() != null)
                .forEach(item -> {
                    if (item.getName().toLowerCase().contains("potion")) {
                        keep.add(item.getId());
                    }
                });
        return keep;
    }

    /**
     * Withdraw an edible food by exact id, up to the free inventory space. Returns true when
     * an edible item is in the inventory afterwards — the same predicate the house
     * {@code getInventoryFood()} uses, so the verdict matches what can actually be eaten.
     */
    private boolean withdrawFood() {
        int target = Math.max(1, AScriptBank.freeSlots());
        for (int foodId : FOOD_IDS) {
            Rs2Bank.withdrawDeficit(foodId, target);
            if (!Rs2Inventory.getInventoryFood().isEmpty()) {
                return true;
            }
        }
        return !Rs2Inventory.getInventoryFood().isEmpty();
    }

    /**
     * True when a pickaxe is available for looting: unnoted in the inventory OR equipped.
     * A wielded pickaxe never appears in the inventory, so an inventory-only check would
     * refuse to loot for every player who wields it.
     */
    private boolean hasUsablePickaxe() {
        if (AScriptBank.hasUnnotedItem(" pickaxe")) return true;
        for (Pickaxe pickaxe : Pickaxe.values()) {
            if (Rs2Equipment.isWearing(pickaxe.getItemId())) return true;
        }
        return false;
    }

    /**
     * One tick of the combat loop. Called by AScript's CRAFTING phase dispatch.
     */
    @Override
    public void doAction(AScriptConfig config) {
        if (exitRequested) return;
        if (!Microbot.isLoggedIn()) return;

        // Ensure fresh weather data (cached for 30 min, safe to call every tick)
        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        try {
            switch (internalState) {
                case WALKING:
                    handleWalking();
                    break;
                case FIGHTING:
                    handlePotions(config);
                    handleSafety(config);
                    handleFighting(config);
                    break;
                case BANKING:
                    // The module banks itself, so the house bank counter never sees these
                    // trips: consume doBank()'s verdict here and stop after three failures
                    // with a single notification. A lamp yield is not a failure.
                    if (doBank(config)) {
                        bankFailureCount = 0;
                    } else if (!bankYieldedToLamp) {
                        bankFailureCount++;
                        if (bankFailureCount >= MAX_BANK_FAILURES) {
                            exitRequested = true;
                            Microbot.status = "STOPPED — bank has no food";
                            AScriptNotify.notify("Gem Crab Killer Stopped",
                                    "Bank trip failed " + MAX_BANK_FAILURES
                                            + " times — no edible food in the bank");
                            Microbot.getConfigManager().setConfiguration(
                                    AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
                        }
                    }
                    break;
                case WAITING:
                    handleWaiting();
                    break;
            }

            // Track consecutive failures — stop if stuck
            Rs2NpcModel npc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest();
            Rs2NpcModel deadNpc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_DEAD_ID).nearest();
            if (npc == null && deadNpc == null && internalState == InternalState.WALKING) {
                consecutiveFightFailures++;
                if (consecutiveFightFailures >= MAX_CONSECUTIVE_FIGHT_FAILURES) {
                    exitRequested = true;
                    Microbot.status = "STOPPED — no crabs found";
                    AScriptNotify.notify("Gem Crab Killer Stopped",
                            "No crabs found after " + MAX_CONSECUTIVE_FIGHT_FAILURES + " attempts");
                    Microbot.getConfigManager().setConfiguration(
                            AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
                }
            } else {
                consecutiveFightFailures = 0;
            }
        } catch (Exception ex) {
            log.error("[GemCrabKiller] Error in fight loop", ex);
        }
    }

    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFightFailures = 0;
        bankFailureCount = 0;
        bankYieldedToLamp = false;
        lastCountedCorpseIndex = -1;
        lootAttempts = 0;
        waitingDeadlineMs = 0L;
        hasLooted = false;
        waitingTimeStart = null;
        totalCrabKills = 0;
        sessionStartTime = null;
        lastAfkTime = 0L;
        postKillBreakDone = false;
        internalState = InternalState.WALKING;
    }

    // ── Internal handlers ──────────────────────────────────────

    private void handleWalking() {
        if (sessionStartTime == null) sessionStartTime = Instant.now();

        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier));
        }

        // Check for nearby crab
        Rs2NpcModel npc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest();
        if (npc != null) {
            internalState = InternalState.FIGHTING;
            consecutiveFightFailures = 0;
            return;
        }

        // Check for cave entrance
        Rs2TileObjectModel caveEntrance = Microbot.getRs2TileObjectCache()
                .query().withId(CAVE_ENTRANCE_ID).nearest();
        if (caveEntrance != null) {
            var composition = caveEntrance.getObjectComposition();
            // getActions() is null for an object with no options — an unguarded stream() NPEs
            // inside doAction()'s try and silently kills the tick.
            String[] actions = composition == null ? null : composition.getActions();
            if (actions != null
                    && java.util.Arrays.stream(actions).anyMatch("Crawl-through"::equals)) {
                // Client-mouse object recipe (AScriptClick — the aScript mouse refactor):
                // camera first, then the entrance's real clickbox through the client mouse;
                // the canvas-sized fallback of an entrance that has not rendered is never
                // clicked. "Crawl-through" is the entrance's left-click default — all a
                // physical click dispatches; the crab lookup below is the effect
                // verification (unchanged).
                AScriptClick.clickObject(caveEntrance);
                sleepUntil(
                        () -> Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest() != null,
                        Rs2Random.logNormalBounded(4000, 7000, weatherMultiplier));
                if (Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest() != null) {
                    internalState = InternalState.FIGHTING;
                    consecutiveFightFailures = 0;
                }
                return;
            }
        }

        // Walk to known crab location
        Microbot.status = "WALKING TO CRABS";
        Rs2Walker.walkTo(CLOSEST_CRAB_LOCATION_TO_BANK);
    }

    private void handleFighting(AScriptConfig config) {
        Rs2NpcModel npc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest();

        // A live crab wins over a corpse. The old order checked the corpse first, so with
        // both in scene every tick took the exit-the-cave path and the fight was skipped.
        if (npc != null) {
            hasLooted = false;
            // A new fight is under way — its kill earns the next post-kill break.
            postKillBreakDone = false;

            if (!Rs2Player.isInCombat()) {
                Microbot.status = "FIGHTING";
                // Client-mouse actor recipe: "Attack" is the crab's left-click default — all
                // a physical click dispatches; combat itself is the verification.
                AScriptClick.clickActor(npc.getNpc());
            } else {
                waitingTimeStart = null;
            }
            return;
        }

        Rs2NpcModel deadNpc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_DEAD_ID).nearest();
        if (deadNpc == null) {
            internalState = InternalState.WALKING;
            return;
        }

        // Count the kill on the dead-crab EDGE, once per corpse: the old code incremented
        // on every tick a corpse stayed in scene, so a slow exit inflated the counter.
        int corpseIndex = deadNpc.getIndex();
        if (corpseIndex != lastCountedCorpseIndex) {
            lastCountedCorpseIndex = corpseIndex;
            lootAttempts = 0;
            totalCrabKills++;
        }

        if (config.gemCrabLootCrab()
                && hasUsablePickaxe()
                && !hasLooted
                && lootAttempts < MAX_LOOT_ATTEMPTS) {
            Microbot.status = "MINING LOOT";
            lootAttempts++;
            int emptyBefore = Rs2Inventory.emptySlotCount();
            // Client-mouse actor recipe (AScriptClick — the Ammonite attackCrab shape):
            // camera first, then the dead crab's real clickbox through the client mouse.
            AScriptClick.clickActor(deadNpc.getNpc());
            boolean changed = Rs2Inventory.waitForInventoryChanges(
                    Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier));
            // Loot is only marked when something actually landed: the old code slept and
            // set hasLooted unconditionally, so a failed click ended the loot for that kill.
            hasLooted = changed || Rs2Inventory.emptySlotCount() < emptyBefore;
            sleep(Rs2Random.logNormalBounded(3000, 5000, weatherMultiplier));
        }

        if (Rs2Inventory.isFull()) {
            // The internal bank trip runs on a later tick (needsBank is constant false, so
            // the house yield cannot take this tick): take the post-kill break HERE, with
            // the bank still closed — cannonball placement.
            postKillBreak(config);
            internalState = InternalState.BANKING;
            return;
        }

        // Post-kill break — the unit (kill + optional loot) just completed: the
        // unconditional short pause plus the toggle-gated random AFK, once per kill,
        // where the kill happened and before the module moves on.
        postKillBreak(config);

        // Exit cave — same recipe as the way in (see handleWalking): the WAITING state
        // below is what follows the crawl.
        AScriptClick.clickObject(Microbot.getRs2TileObjectCache()
                .query().withId(CAVE_ENTRANCE_ID).nearest());
        internalState = InternalState.WAITING;
        waitingTimeStart = Instant.now();
        waitingDeadlineMs = System.currentTimeMillis()
                + Rs2Random.logNormalBounded(12000, 18000, weatherMultiplier);
    }

    /**
     * Post-kill break — the Jewelry/Crafting randomness pattern: a short randomized pause
     * after every completed kill, then the optional random AFK (log-normal 3–120 s,
     * weather-modulated, interruptible so blocking events still fire), 5 s min-gap and
     * status {@code AFK (Ns)}. Runs once per kill (see {@link #postKillBreakDone}) with the
     * bank still closed — Gem Crab Killer banks through its own state machine, so the house
     * {@code needsBank} yield cannot take this tick; the internal BANKING transition is
     * what follows a full inventory.
     */
    private void postKillBreak(AScriptConfig config) {
        if (postKillBreakDone) return; // one break per kill — a corpse still in the scene never re-runs it
        postKillBreakDone = true;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.gemCrabKillerAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3_000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    private void handleWaiting() {
        if (waitingTimeStart == null) {
            waitingTimeStart = Instant.now();
        }
        // Roll the deadline once per stay: the old code re-rolled it every tick, so the
        // wait never expired while the roll kept coming out longer than the elapsed time.
        if (waitingDeadlineMs == 0L) {
            waitingDeadlineMs = System.currentTimeMillis()
                    + Rs2Random.logNormalBounded(12000, 18000, weatherMultiplier);
        }

        // New crab appeared
        if (Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest() != null) {
            internalState = InternalState.FIGHTING;
            waitingTimeStart = null;
            waitingDeadlineMs = 0L;
            return;
        }

        // Timeout — go find next crab
        if (System.currentTimeMillis() >= waitingDeadlineMs) {
            waitingTimeStart = null;
            waitingDeadlineMs = 0L;
            internalState = InternalState.WALKING;
        }
    }

    private void handleSafety(AScriptConfig config) {
        boolean useFood = config.gemCrabUseFood();
        boolean isDharok = config.gemCrabDharokMode();

        if (isDharok) {
            int currentHP = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);

            // Lower HP with locator orb or rock cake. The slot is drawn through AScriptPick
            // (the aScript click contract) — Rs2Inventory.interact(id, ...) always takes the
            // lowest matching slot, which is a fixed-pixel tell across a whole session.
            if (currentHP > DHAROK_LOW_HP) {
                Rs2ItemModel orb = AScriptPick.pick(ItemID.LOCATOR_ORB);
                if (orb != null) {
                    Rs2Inventory.interact(orb, "feel");
                } else {
                    Rs2ItemModel cake = AScriptPick.pick(ItemID.DWARVEN_ROCK_CAKE_7510);
                    if (cake != null) {
                        Rs2Inventory.interact(cake, "guzzle");
                    }
                }
            }

            // Emergency eat (only if useFood enabled — saves from death at 1 HP)
            if (useFood && currentHP <= EAT_EMERGENCY_HP) {
                eatBestFood();
            }

            // Rapid heal prayer flick
            int prayerLevel = Rs2Player.getRealSkillLevel(Skill.PRAYER);
            if (prayerLevel >= 25) {
                if (Rs2Random.diceFractional(0.98)) {
                    Rs2Prayer.toggle(Rs2PrayerEnum.RAPID_HEAL, true);
                    AScriptSleep.sleepInterruptibly((int) (300 * weatherMultiplier));
                    Rs2Prayer.toggle(Rs2PrayerEnum.RAPID_HEAL, false);
                }
            }
        } else if (useFood) {
            // Normal mode with food — eat at the threshold, compared by levels (the same
            // NaN-safe shape as the bank check below).
            int hp = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);
            int maxHp = Rs2Player.getRealSkillLevel(Skill.HITPOINTS);
            if (maxHp > 0 && hp * 100 < maxHp * EAT_NORMAL_HP) {
                eatBestFood();
            }
        }
        // useFood disabled, non-Dharok: no eating at all

        // Check if out of food and low HP — need to bank (only when useFood enabled and the
        // trip would actually withdraw food: in Dharok mode doBank() never withdraws any,
        // so banking here would loop walk -> open -> close without ever fixing anything).
        if (useFood && !isDharok) {
            boolean hasFood = !Rs2Inventory.getInventoryFood().isEmpty();
            int hp = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);
            int maxHp = Rs2Player.getRealSkillLevel(Skill.HITPOINTS);
            // Level comparison, not getHealthPercentage(): that helper divides by the real
            // level, which reads 0 before skill data loads (NaN), and NaN compares false.
            boolean lowHp = maxHp > 0 && hp * 100 < maxHp * BANK_FOOD_HP_THRESHOLD;
            if (!hasFood && lowHp) {
                internalState = InternalState.BANKING;
                return; // state changed — the rest of this tick belongs to the bank trip
            }
        }
    }

    /**
     * Eat the best food the inventory holds. The slot is drawn by {@link AScriptPick#pickFood}
     * (top-heal group, randomized instance) — the house eating path, instead of
     * {@code Rs2Player.eatAt}, which always eats the first food in slot order.
     */
    private boolean eatBestFood() {
        return AScriptFood.dispatch(AScriptFood.bestFood());
    }

    private void handlePotions(AScriptConfig config) {
        if (!config.gemCrabUseOffensivePotions()) return;
        if (!Rs2Combat.inCombat()) return;

        if (Rs2Player.drinkCombatPotionAt(Skill.RANGED, false)) {
            Rs2Player.waitForAnimation();
        }
        if (Rs2Player.drinkCombatPotionAt(Skill.MAGIC, false)) {
            Rs2Player.waitForAnimation();
        }
        if (Rs2Player.drinkCombatPotionAt(Skill.STRENGTH)) {
            Rs2Player.waitForAnimation();
        }
        if (Rs2Player.drinkCombatPotionAt(Skill.ATTACK)) {
            Rs2Player.waitForAnimation();
        }
        if (Rs2Player.drinkCombatPotionAt(Skill.DEFENCE)) {
            Rs2Player.waitForAnimation();
        }
    }

    @Override
    public boolean isActive() { return currentPhase != Phase.NONE; }
}
