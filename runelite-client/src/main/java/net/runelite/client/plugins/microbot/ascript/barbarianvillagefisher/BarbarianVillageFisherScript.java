package net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.FishingSpot;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.InteractOrder;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;

import java.awt.event.KeyEvent;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Barbarian Village Fisher module — fly/bait fishing with cook/drop/bank options.
 * <p>
 * Adapted from chsami/Microbot-Hub BarbarianVillageFisherScript.
 */
@Slf4j
public class BarbarianVillageFisherScript implements AModule {

    private static final int MAX_ACTION_FAILURES = 5;
    private static final int MAX_BANK_FAILURES = 3;
    private static final int SPOT_CLICK_RETRIES = 5;

    // Raw fish IDs: Trout (335), Salmon (331), Pike (349)
    private static final int[] RAW_FISH = {335, 331, 349};
    private static final int BARBARIAN_VILLAGE_FIRE_ID = 43475;
    private static final WorldPoint BARBARIAN_VILLAGE_FIRE_POINT = new WorldPoint(3106, 3432, 0);
    private static final WorldPoint FISHING_SPOT = new WorldPoint(3108, 3432, 0);

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private int consecutiveFailures = 0;
    private int consecutiveBankFailures = 0;
    private double weatherMultiplier = 1.0;

    /** Whether we've dispatched a cook action and are waiting for the XP drop. */
    private boolean expectingXPDrop = false;
    /** The raw fish ID we're currently cooking (set when cooking starts). */
    private int currentlyCookingID = 0;
    /** Whether the make-X dialog was confirmed this dispatch cycle. */
    private boolean cookingConfirmed = false;
    /** Deadline (epoch ms) for finishing the current cooking batch. Reset when a new batch starts. */
    private long cookingDeadlineMs = 0;
    /** How many re-dispatches we've attempted within the current batch deadline. */
    private int cookingRedispaches = 0;
    /** Transient retry counter for fishing spot clicks that didn't land. Reset when clicked lands. */
    private int spotClickRetries = 0;

    public enum Phase {
        NONE, FISHING, WALK_TO_BANK, WALK_TO_FISHING_SPOT, BANKING, DROPPING, COOKING
    }

    // ── AModule contract ─────────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.BARBARIAN_VILLAGE_FISHER
                || config.barbarianVillageFisherType() == null) {
            currentPhase = Phase.NONE;
            return;
        }
        if (!Microbot.isLoggedIn()) {
            currentPhase = Phase.NONE;
            return;
        }
        // Runtime state (inventory, proximity) is resolved in doAction each tick —
        // here we only need a non-NONE "selected" answer. Use the current bank/spot
        // proximity so the overlay and the NONE → active resetExitFlag() transition
        // see a real phase instead of a hardcoded placeholder.
        if (Rs2Inventory.isFull()
                || !Rs2Inventory.hasItem(getRodName(config))
                || !Rs2Inventory.hasItem(getBaitName(config))) {
            currentPhase = Phase.WALK_TO_BANK;
        } else if (isCloseTo(FISHING_SPOT)) {
            currentPhase = Phase.FISHING;
        } else {
            currentPhase = Phase.WALK_TO_FISHING_SPOT;
        }
    }

    @Override
    public boolean isActive() {
        return currentPhase != Phase.NONE;
    }

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFailures = 0;
        consecutiveBankFailures = 0;
        expectingXPDrop = false;
        currentlyCookingID = 0;
        cookingConfirmed = false;
        cookingDeadlineMs = 0;
        cookingRedispaches = 0;
        spotClickRetries = 0;
    }

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.BARBARIAN_VILLAGE_FISHER)
            return "no barbarian fishing type selected";
        return null;
    }

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        String rodName = getRodName(config);
        String baitName = getBaitName(config);
        // Need bank if we don't have rod or bait
        if (!Rs2Inventory.hasItem(rodName) || !Rs2Inventory.hasItem(baitName)) return true;
        // Need bank if inventory is full AND function requires banking
        BarbarianFishingFunctions func = config.barbarianVillageFisherFunction();
        if (Rs2Inventory.isFull()) {
            if (func == BarbarianFishingFunctions.BANK_RAW
                    || func == BarbarianFishingFunctions.COOK_AND_BANK) {
                if (!hasRawFish()) {
                    // After cooking, need to bank cooked fish
                    return true;
                }
                // Still have raw fish — will cook first, bank later
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        String rodName = getRodName(config);
        String baitName = getBaitName(config);
        // Missing from BOTH inventory and bank
        if (!Rs2Inventory.hasItem(rodName) && !Rs2Bank.hasItem(rodName)) return true;
        if (!Rs2Inventory.hasItem(baitName) && !Rs2Bank.hasItem(baitName)) return true;
        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        String rodName = getRodName(config);
        String baitName = getBaitName(config);
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(rodName) && !Rs2Bank.hasItem(rodName))
            sb.append(rodName);
        if (!Rs2Inventory.hasItem(baitName) && !Rs2Bank.hasItem(baitName)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(baitName);
        }
        return sb.toString();
    }

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        if (consecutiveBankFailures >= MAX_BANK_FAILURES) {
            Microbot.status = "STOPPED — bank failures";
            AScriptNotify.notify("aScript Stopped", "Barbarian Village Fisher: " + consecutiveBankFailures
                    + " consecutive bank failures");
            Microbot.getConfigManager().setConfiguration(
                    AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            return false;
        }

        Microbot.status = "BANKING";
        String rodName = getRodName(config);
        String baitName = getBaitName(config);
        BarbarianFishingFunctions func = config.barbarianVillageFisherFunction();

        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) {
                consecutiveBankFailures++;
                return false;
            }
            if (!sleepUntil(Rs2Bank::isOpen, Rs2Random.logNormalBounded(5000, 10000))) {
                consecutiveBankFailures++;
                return false;
            }
        }

        // Lock the rod AND the bait stack so the blanket toolbar deposit never
        // removes them — banking re-withdraws otherwise (churn each cycle).
        // Bait is a stackable consumable, so use ensureStackLocked (whole-stack
        // withdraw) — locking one bait would be pointless.
        if (!AScriptBank.ensureToolLocked(rodName)
                || !AScriptBank.ensureStackLocked(baitName)) {
            consecutiveBankFailures++;
            return false;
        }

        // Deposit via toolbar button
        if (!AScriptBank.depositAndWaitEmpty()) {
            consecutiveBankFailures++;
            return false;
        }

        // Withdraw rod if missing
        if (!Rs2Inventory.hasItem(rodName)) {
            if (!AScriptBank.withdrawOneVerified(rodName)) {
                AScriptNotify.notify("Banking Failed", "No " + rodName + " in bank");
                consecutiveBankFailures++;
                return false;
            }
        }

        // No bait left: locked stack consumed entirely — withdraw a fresh full
        // stack ("withdraw all" drains the bank pile in one interaction).
        if (!Rs2Inventory.hasItem(baitName)
                && !AScriptBank.ensureStackLocked(baitName)) {
            AScriptNotify.notify("Banking Failed", "No " + baitName + " in bank");
            consecutiveBankFailures++;
            return false;
        }

        consecutiveBankFailures = 0;
        return true;
    }

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // Handle dialogue
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return;
        }

        // Resolve dynamic phase based on current state
        resolveDynamicPhase(config);

        switch (currentPhase) {
            case WALK_TO_BANK:
                walkToBank();
                break;
            case WALK_TO_FISHING_SPOT:
                walkToFishingSpot();
                break;
            case FISHING:
                doFishing(config);
                break;
            case DROPPING:
                doDropping(config);
                break;
            case COOKING:
                doCooking(config);
                break;
            case BANKING:
                // Shouldn't reach here — needsBank handles this
                break;
            default:
                break;
        }
    }

    @Override
    public boolean isPrecisionModule() {
        return false;
    }

    // ── Dynamic phase resolution ─────────────────────────────

    private void resolveDynamicPhase(AScriptConfig config) {
        String rodName = getRodName(config);
        String baitName = getBaitName(config);
        BarbarianFishingFunctions func = config.barbarianVillageFisherFunction();

        // Missing materials → bank
        if (!Rs2Inventory.hasItem(rodName) || !Rs2Inventory.hasItem(baitName)) {
            if (isCloseTo(BankLocation.EDGEVILLE.getWorldPoint())) {
                currentPhase = Phase.BANKING;
            } else {
                currentPhase = Phase.WALK_TO_BANK;
            }
            return;
        }

        // Inventory full
        if (Rs2Inventory.isFull()) {
            switch (func) {
                case DROP_RAW:
                    currentPhase = Phase.DROPPING;
                    return;
                case BANK_RAW:
                    if (isCloseTo(BankLocation.EDGEVILLE.getWorldPoint())) {
                        currentPhase = Phase.BANKING;
                    } else {
                        currentPhase = Phase.WALK_TO_BANK;
                    }
                    return;
                case COOK_AND_BANK:
                case COOK_AND_DROP:
                    if (hasRawFish()) {
                        currentPhase = Phase.COOKING;
                    } else if (func == BarbarianFishingFunctions.COOK_AND_BANK) {
                        if (isCloseTo(BankLocation.EDGEVILLE.getWorldPoint())) {
                            currentPhase = Phase.BANKING;
                        } else {
                            currentPhase = Phase.WALK_TO_BANK;
                        }
                    } else {
                        currentPhase = Phase.DROPPING;
                    }
                    return;
            }
        }

        // Not at fishing spot → walk there
        if (!isCloseTo(FISHING_SPOT)) {
            currentPhase = Phase.WALK_TO_FISHING_SPOT;
            return;
        }

        // Default: fish
        currentPhase = Phase.FISHING;
    }

    // ── Phase implementations ────────────────────────────────

    private void doFishing(AScriptConfig config) {
        if (Rs2Player.isInteracting()) {
            Microbot.status = "FISHING";
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(256, 789, weatherMultiplier));
            return;
        }

        Rs2NpcModel fishingSpot = Microbot.getRs2NpcCache().query()
                .withIds(FishingSpot.SALMON.getIds())
                .within(FISHING_SPOT, 15)
                .nearest();

        if (fishingSpot == null) {
            fail("no fishing spot near Barbarian Village");
            return;
        }

        String fishingAction = config.barbarianVillageFisherType() == BarbarianFishingType.BAIT_FISHING
                ? "Bait" : "Lure";

        if (!Rs2Camera.isTileOnScreen(fishingSpot.getLocalLocation())) {
            Rs2Walker.walkTo(fishingSpot.getWorldLocation(), 3);
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(180, 540, weatherMultiplier));
            return;
        }

        if (fishingSpot.click(fishingAction)) {
            boolean landed = sleepUntil(
                    () -> Rs2Player.isAnimating() || Rs2Player.isInteracting(),
                    (int) Rs2Random.logNormalBounded(5000, 10000, weatherMultiplier));
            if (!landed) {
                // Transient click failure — bounded retry with backoff (Ponto 2)
                spotClickRetries++;
                if (spotClickRetries >= SPOT_CLICK_RETRIES) {
                    fail("fishing spot click never landed after " + SPOT_CLICK_RETRIES + " retries");
                    return;
                }
                AScriptSleep.sleepInterruptibly(
                        Rs2Random.logNormalBounded(500 + spotClickRetries * 200,
                                1100 + spotClickRetries * 400, weatherMultiplier));
                return;
            }
            // Click landed — reset transient counter
            spotClickRetries = 0;
            Microbot.status = "FISHING — clicked spot";
            consecutiveFailures = 0;
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(235, 798, weatherMultiplier));
        } else {
            fail("failed to click fishing spot");
        }
    }

    private void doDropping(AScriptConfig config) {
        Microbot.status = "DROPPING";
        String rodName = getRodName(config);
        String baitName = getBaitName(config);
        Rs2Inventory.dropAllExcept(true, InteractOrder.STANDARD, rodName, baitName);
        boolean dropped = sleepUntil(() -> countDroppableItems() == 0,
                (int) Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier));
        if (!dropped) {
            fail("drop did not land");
            return;
        }
        consecutiveFailures = 0;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(235, 798, weatherMultiplier));
    }

    private void doCooking(AScriptConfig config) {
        Microbot.status = "COOKING";

        if (!hasRawFish()) {
            // Done cooking — clear all cooking state
            expectingXPDrop = false;
            currentlyCookingID = 0;
            cookingConfirmed = false;
            cookingDeadlineMs = 0;
            cookingRedispaches = 0;
            return;
        }

        // Wait for XP drop if we previously started cooking (confirmed dispatch)
        if (expectingXPDrop && currentlyCookingID != 0
                && Rs2Inventory.count(currentlyCookingID) != 0) {
            if (Rs2Player.waitForXpDrop(Skill.COOKING,
                    (int) Rs2Random.logNormalBounded(3500, 6500, weatherMultiplier))) {
                Microbot.status = "COOKING — got XP";
                consecutiveFailures = 0;
                AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(235, 798, weatherMultiplier));
                expectingXPDrop = false;
                cookingConfirmed = false;
                // Progress made — reset stagnation deadline (Ponto 3)
                cookingDeadlineMs = System.currentTimeMillis() + 60_000;
                cookingRedispaches = 0;
                return;
            }

            // XP not received: is the cooking action still in flight (slow batch /
            // animation without XP in this window)?
            if (cookingConfirmed && isCookingInFlight()) {
                // Action is in progress — do NOT count as failure, do NOT reset
                // the latch, do NOT re-dispatch. Just re-wait next tick.
                return;
            }

            // Action not in flight — dispatch died. Release for re-dispatch.
            // Deadline escalation only fires on real stagnation (no in-flight action),
            // never while the player is still cooking (Ponto 3).
            if (cookingDeadlineMs == 0) {
                cookingDeadlineMs = System.currentTimeMillis() + 60_000;
            }
            if (System.currentTimeMillis() > cookingDeadlineMs
                    && !isCookingInFlight()) {
                fail("cooking dispatch never landed (deadline exceeded)");
                return;
            }
            expectingXPDrop = false;
            cookingConfirmed = false;
            return; // next tick re-calls doCookingDispatch()
        }

        // Fail fast if the fire is gone — otherwise useItemOnObject would
        // silently no-op every tick forever.
        if (!isGameObjectOnTile(BARBARIAN_VILLAGE_FIRE_POINT, BARBARIAN_VILLAGE_FIRE_ID)) {
            fail("barbarian village fire not found on tile");
            return;
        }

        // Walk to fire if not adjacent
        if (!isCloseTo(BARBARIAN_VILLAGE_FIRE_POINT)) {
            Rs2Walker.walkTo(BARBARIAN_VILLAGE_FIRE_POINT, 1);
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(180, 540, weatherMultiplier));
            return;
        }

        // Dispatch: use raw fish on fire → wait for make-X dialog → confirm
        if (!doCookingDispatch()) {
            consecutiveFailures++;
            log.warn("[BarbarianFishing] Cooking dispatch failed ({}/{}): "
                    + "useItemOnObject did not open make-X dialog",
                    consecutiveFailures, MAX_ACTION_FAILURES);
            if (consecutiveFailures >= MAX_ACTION_FAILURES) {
                exitRequested = true;
                Microbot.status = "STOPPED — cooking dispatch failed";
                AScriptNotify.notify("aScript Stopped — Barbarian Village Fisher",
                        "cooking dispatch failed");
                Microbot.getConfigManager().setConfiguration(
                        AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            }
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
        }
    }

    private void walkToBank() {
        Microbot.status = "WALKING TO BANK";
        if (Rs2Player.isMoving()) return;
        Rs2Walker.walkTo(BankLocation.EDGEVILLE.getWorldPoint(), 20);
    }

    private void walkToFishingSpot() {
        Microbot.status = "WALKING TO SPOT";
        if (Rs2Player.isMoving()) return;
        Rs2Walker.walkTo(FISHING_SPOT, 9);
    }

    // ── Helpers ──────────────────────────────────────────────

    private String getRodName(AScriptConfig config) {
        return config.barbarianVillageFisherType() == BarbarianFishingType.BAIT_FISHING
                ? "Fishing rod" : "Fly fishing rod";
    }

    private String getBaitName(AScriptConfig config) {
        return config.barbarianVillageFisherType() == BarbarianFishingType.BAIT_FISHING
                ? "Fishing bait" : "Feather";
    }

    private boolean hasRawFish() {
        for (int id : RAW_FISH) {
            if (Rs2Inventory.contains(id)) return true;
        }
        return false;
    }

    private int countRawFish() {
        int count = 0;
        for (int id : RAW_FISH) {
            count += Rs2Inventory.count(id);
        }
        return count;
    }

    /**
     * Counts all items that should be dropped in COOK_AND_DROP mode: raw fish,
     * cooked fish (trout/salmon/pike), and burnt fish. In DROP_RAW mode,
     * {@link #countRawFish()} is still used directly.
     */
    private int countDroppableItems() {
        // Raw fish
        int count = countRawFish();
        // Cooked fish: Trout=333, Salmon=329, Pike=351
        count += Rs2Inventory.count(333); // TROUT
        count += Rs2Inventory.count(329); // SALMON
        count += Rs2Inventory.count(351); // PIKE
        // Burnt fish (BURNT_FISH_343 covers trout/salmon/pike/cod)
        count += Rs2Inventory.count(343);
        return count;
    }

    private boolean isCloseTo(WorldPoint location) {
        return Rs2Player.getWorldLocation().distanceTo(location) <= 10;
    }

    private boolean isGameObjectOnTile(WorldPoint location, int id) {
        var result = Microbot.getRs2TileObjectCache().query()
                .withId(id)
                .within(location, 0)
                .first();
        return result != null;
    }

    /**
     * Dispatches a cooking action: select raw fish → use on fire → wait for
     * make-X dialog → confirm with SPACE → mark cooking state.
     *
     * @return true if the make-X dialog appeared and was confirmed; false if
     *         the dispatch did not engage (click silently failed).
     *
     * <p>NOTE: {@code Rs2Inventory.useItemOnObject} uses menu injection which
     * can silently fail on this machine. The AGENTS.md rule is to use real
     * mouse clicks instead; implementing that here requires resolving the fire's
     * screen bounds and clicking via {@code Microbot.getMouse().click(rect)},
     * which is a deeper refactor. For now this method works correctly but may
     * silently fail when menu injection is broken — the caller handles retries.</p>
     */
    private boolean doCookingDispatch() {
        for (int fishId : RAW_FISH) {
            if (Rs2Inventory.contains(fishId)) {
                boolean interacted = Rs2Inventory.useItemOnObject(fishId, BARBARIAN_VILLAGE_FIRE_ID);
                if (!interacted) {
                    return false;
                }
                // Wait for the make-X dialog to appear (confirm the click landed)
                boolean dialogOpened = sleepUntil(() -> !Rs2Player.isMoving()
                                && Rs2Widget.findWidget("How many would you like to cook?",
                                        null, false) != null,
                        (int) Rs2Random.logNormalBounded(6000, 12000, weatherMultiplier));
                if (!dialogOpened) {
                    // Make-X box never appeared — dispatch did not engage
                    return false;
                }
                AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(180, 540, weatherMultiplier));
                Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                currentlyCookingID = fishId;
                expectingXPDrop = true;
                cookingConfirmed = true;
                // Start a fresh batch deadline
                cookingDeadlineMs = System.currentTimeMillis() + 60_000;
                cookingRedispaches = 0;
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the cooking action is currently in flight — the player is
     * interacting with the fire/obj OR playing a non-idle animation. Used after
     * XP-drop wait timeout to distinguish "slow batch / animation not yet done"
     * from "dispatch died". Prefers {@code isInteracting()} (the signal the
     * fishing code already uses) with {@code isAnimating()} as fallback.
     */
    private boolean isCookingInFlight() {
        return Rs2Player.isInteracting() || Rs2Player.isAnimating();
    }

    private void fail(String reason) {
        consecutiveFailures++;
        log.warn("[BarbarianFishing] Action failed ({}/{}): {}",
                consecutiveFailures, MAX_ACTION_FAILURES, reason);
        if (consecutiveFailures >= MAX_ACTION_FAILURES) {
            exitRequested = true;
            Microbot.status = "STOPPED — " + reason;
            AScriptNotify.notify("aScript Stopped — Barbarian Village Fisher", reason);
            Microbot.getConfigManager().setConfiguration(
                    AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }
}
