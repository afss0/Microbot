package net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.FishingSpot;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptDrop;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptPick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;

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

    /**
     * Landed wait for a fishing click. Deliberately long: a lagging client can delay the
     * first animation/interaction by many seconds, and a shorter window re-clicks the spot
     * while the first click is still in flight.
     */
    private static final int FISHING_LANDED_MIN_MS = 8_000;
    private static final int FISHING_LANDED_MAX_MS = 30_000;
    /** Sleep while the character is already fishing. */
    private static final int FISHING_ACTIVE_MIN_MS = 3_500;
    private static final int FISHING_ACTIVE_MAX_MS = 10_000;
    /**
     * XP window for the active cooking batch. Several fish can pass between XP reads, and a
     * lag spike must not read as a dead dispatch (which re-opened the make-X box).
     */
    private static final int COOKING_XP_MIN_MS = 8_000;
    private static final int COOKING_XP_MAX_MS = 15_000;
    /** Extra window before a timed-out XP wait releases the latch for a re-dispatch. */
    private static final int COOKING_XP_GRACE_MIN_MS = 4_000;
    private static final int COOKING_XP_GRACE_MAX_MS = 8_000;
    /** Make-X dialog appearance window. */
    private static final int COOKING_DIALOG_MIN_MS = 8_000;
    private static final int COOKING_DIALOG_MAX_MS = 16_000;

    private static final String COOK_MAKE_X_PROMPT = "How many would you like to cook?";

    /**
     * The raw ids a cook dispatch may select. Membership is owned by {@link BarbarianFish}
     * (raw {@code ItemID} ids, cooked ids validated against {@code CookingAction}); cooked and
     * burnt ids are drop/bank-only and must never appear here.
     */
    private static final int[] RAW_FISH = BarbarianFish.rawIds();
    /**
     * Rod and bait are resolved by ID, never by name: every relevant name is a substring
     * of a larger family. "Fishing rod" is contained in "Fly fishing rod", and "Feather"
     * is contained in each coloured feather ("Red feather", "Stripy feather", ...), so a
     * name lookup can withdraw/lock/drop the wrong item — or count the wrong item as bait
     * and fish with an empty inventory.
     */
    private static final int FISHING_ROD_ID = ItemID.FISHING_ROD;
    private static final int FLY_FISHING_ROD_ID = ItemID.FLY_FISHING_ROD;
    private static final int FISHING_BAIT_ID = ItemID.FISHING_BAIT;
    private static final int FEATHER_ID = ItemID.FEATHER;
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
    /** Min-gap reference for the random-AFK layer. */
    private long lastAfkTime = 0L;
    /** Set when a full cycle ended in banking — the post-cycle break runs bank-closed next tick. */
    private boolean postCycleBreakPending = false;
    /**
     * DROP_RAW / COOK_AND_DROP: a drop phase is running. Entered when the inventory fills and
     * held until the filter is empty — a slice frees slots, so without the latch the phase
     * resolver would resume fishing with the module's fish still in the bag (the
     * Woodcutting/Mining latch finding).
     */
    private boolean dropping = false;
    /** When the BANKING phase found no loaded bank object (bounded wait). */
    private long bankTurnSinceMs = 0L;
    private static final long BANK_APPROACH_STOP_MS = 20_000;

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
                || !Rs2Inventory.hasItem(getRodId(config))
                || !Rs2Inventory.hasItem(getBaitId(config))) {
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
        lastAfkTime = 0L;
        postCycleBreakPending = false;
        dropping = false;
        bankTurnSinceMs = 0L;
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
        int rodId = getRodId(config);
        int baitId = getBaitId(config);
        // Need bank if we don't have rod or bait
        if (!Rs2Inventory.hasItem(rodId) || !Rs2Inventory.hasItem(baitId)) return true;
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

    /**
     * The bank cycle is allowed only once a bank the orchestrator can click is in the scene
     * and on camera ({@link AScriptBank#bankVisible()}). Until then the tick falls through
     * to {@link #doAction}, and the module's own WALK_TO_BANK phase walks there — the
     * Village's bank is Edgeville, well outside the loaded scene from the river, so that is
     * the normal path for a restock. Judged on the object, never on a distance to the
     * anchor: a visible bank is clicked directly and the game walks the last few tiles.
     */
    @Override
    public boolean readyForBank(AScriptConfig config) {
        return AScriptBank.bankVisible();
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        int rodId = getRodId(config);
        int baitId = getBaitId(config);
        // Missing only when neither unnoted/noted inventory nor bank can supply it. A noted
        // rod is bankable; bait is stackable and therefore cannot be noted at all.
        if (!AScriptBank.bankableAtLeast(rodId, 1)) return true;
        if (!AScriptBank.bankableAtLeast(baitId, 1)) return true;
        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        int rodId = getRodId(config);
        int baitId = getBaitId(config);
        StringBuilder sb = new StringBuilder();
        if (!AScriptBank.bankableAtLeast(rodId, 1))
            sb.append(getRodLabel(config));
        if (!AScriptBank.bankableAtLeast(baitId, 1)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(getBaitLabel(config));
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
        int rodId = getRodId(config);
        int baitId = getBaitId(config);
        BarbarianFishingFunctions func = config.barbarianVillageFisherFunction();

        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) {
                consecutiveBankFailures++;
                return false;
            }
            if (!sleepUntil(Rs2Bank::isOpen, Rs2Random.logNormalBounded(5000, 22000))) {
                consecutiveBankFailures++;
                return false;
            }
        }

        // Lock the rod AND the bait stack so the blanket toolbar deposit never
        // removes them — banking re-withdraws otherwise (churn each cycle).
        // Bait is a stackable consumable, so use ensureStackLocked (whole-stack
        // withdraw) — locking one bait would be pointless.
        // Both ride in one keep-list: without it the second ensure treats the lock
        // the first just set as another module's stray and unlocks + deposits it
        // (the rod deposit/re-withdraw ping-pong, observed live 2026-10-10).
        List<String> keepLocked = Arrays.asList(String.valueOf(rodId), String.valueOf(baitId));
        if (!AScriptBank.ensureToolLocked(String.valueOf(rodId), keepLocked)
                || !AScriptBank.ensureStackLocked(String.valueOf(baitId), keepLocked)) {
            consecutiveBankFailures++;
            return false;
        }

        // Deposit via toolbar button
        if (!AScriptBank.depositAndWaitEmpty()) {
            consecutiveBankFailures++;
            return false;
        }

        // Withdraw rod if missing — via ensure so it comes back LOCKED, never bare:
        // a bare re-withdraw left it unlocked in the inventory for the whole cycle.
        if (!Rs2Inventory.hasItem(rodId)) {
            if (!AScriptBank.ensureToolLocked(String.valueOf(rodId), keepLocked)) {
                consecutiveBankFailures++;
                return false;
            }
        }

        // No bait left: locked stack consumed entirely — withdraw a fresh full
        // stack ("withdraw all" drains the bank pile in one interaction).
        if (!Rs2Inventory.hasItem(baitId)
                && !AScriptBank.ensureStackLocked(String.valueOf(baitId), keepLocked)) {
            consecutiveBankFailures++;
            return false;
        }

        consecutiveBankFailures = 0;
        dropping = false; // a fresh trip — the next fill starts a new drop phase
        // Run the post-cycle break bank-closed on the next tick: the orchestrator closes
        // the bank once needsBank flips false (cannonball/crafting placement).
        postCycleBreakPending = true;
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

        // Post-cycle break: a banking cycle completed on an earlier tick — run the
        // randomized pause + AFK now, with the bank already closed again (the
        // orchestrator closes it once needsBank flips back to false).
        if (postCycleBreakPending) {
            postCycleBreakPending = false;
            postCycleBreak(config);
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
                // resolveDynamicPhase() puts the module here on distance alone; the bank
                // object itself may be loaded but off camera, and readyForBank() then blocks
                // openBank — this case used to do nothing and the module idled forever.
                if (!approachBankObject()) {
                    fail("bank did not become clickable");
                }
                break;
            default:
                break;
        }
    }

    // ── Dynamic phase resolution ─────────────────────────────

    private void resolveDynamicPhase(AScriptConfig config) {
        int rodId = getRodId(config);
        int baitId = getBaitId(config);
        BarbarianFishingFunctions func = config.barbarianVillageFisherFunction();

        // Missing materials → bank
        if (!Rs2Inventory.hasItem(rodId) || !Rs2Inventory.hasItem(baitId)) {
            if (isCloseTo(BankLocation.EDGEVILLE.getWorldPoint())) {
                currentPhase = Phase.BANKING;
            } else {
                currentPhase = Phase.WALK_TO_BANK;
            }
            return;
        }

        // A running drop phase owns the tick regardless of fullness: a slice frees slots, and
        // without the latch the module would resume fishing with the module's fish still in
        // the bag (the Woodcutting/Mining latch finding). Cleared when the filter is empty.
        if (dropping) {
            currentPhase = Phase.DROPPING;
            return;
        }

        // Inventory full
        if (Rs2Inventory.isFull()) {
            switch (func) {
                case DROP_RAW:
                    dropping = true;
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
                        dropping = true;
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
            AScriptSleep.sleepInterruptibly(
                    Rs2Random.logNormalBounded(FISHING_ACTIVE_MIN_MS, FISHING_ACTIVE_MAX_MS,
                            weatherMultiplier));
            return;
        }

        Rs2NpcModel fishingSpot = Microbot.getRs2NpcCache().query()
                .withIds(FishingSpot.SALMON.getIds())
                .within(FISHING_SPOT, 15)
                .nearestOnClientThread();

        if (fishingSpot == null) {
            fail("no fishing spot near Barbarian Village");
            return;
        }

        if (!Rs2Camera.isTileOnScreen(fishingSpot.getLocalLocation())) {
            // A loaded spot that is merely off camera is TURNED TO, not walked at: walking
            // moved the player off the river bank for a click the recipe would only have
            // turned toward anyway. Far spots are already handled by WALK_TO_FISHING_SPOT.
            Rs2Camera.turnTo(fishingSpot.getNpc());
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(180, 540, weatherMultiplier));
            return;
        }

        // "Lure" is the spot's default op — a plain left-click on the clickbox is what a
        // human does, so the recipe issues it directly. "Bait" is NOT the default: a
        // left-click can only ever dispatch the default, so the recipe presses our row out
        // of the open menu instead. Both paths are dispatch-verified by AScriptClick (the
        // hover must rank our row top, or the frozen menu must expose it); the effect is
        // still judged by the gate below, unchanged.
        AScriptClick.Target spotClick =
                config.barbarianVillageFisherType() == BarbarianFishingType.BAIT_FISHING
                        ? AScriptClick.clickActor(fishingSpot.getNpc(), "Bait")
                        : AScriptClick.clickActor(fishingSpot.getNpc());
        if (spotClick.cameraTurned()) {
            // The recipe turned the camera toward the spot — a retry on a later tick,
            // not a failure, so it must not spend a click retry or a failure life.
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(180, 540, weatherMultiplier));
            return;
        }
        if (spotClick.clicked()) {
            boolean landed = sleepUntil(
                    () -> Rs2Player.isAnimating() || Rs2Player.isInteracting(),
                    (int) Rs2Random.logNormalBounded(FISHING_LANDED_MIN_MS, FISHING_LANDED_MAX_MS,
                            weatherMultiplier));
            if (!landed) {
                // Transient click failure — bounded retry with backoff (Ponto 2)
                spotClickRetries++;
                if (spotClickRetries >= SPOT_CLICK_RETRIES) {
                    fail("fishing spot click never landed after " + SPOT_CLICK_RETRIES + " retries");
                    return;
                }
                AScriptSleep.sleepInterruptibly(
                        Rs2Random.logNormalBounded(1000 + spotClickRetries * 400,
                                2000 + spotClickRetries * 800, weatherMultiplier));
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

    /**
     * One sliced drop pass over the module's fish ({@link AScriptDrop}): 5–9 slots per tick,
     * paced and verified, instead of clicking a full bag inside a single tick. The phase
     * resolver keeps the module in DROPPING until the filter matches nothing; a clean bag
     * (remaining 0) ends the cycle and runs the bank-free post-cycle break.
     */
    private void doDropping(AScriptConfig config) {
        AScriptDrop.Result result = AScriptDrop.drop(
                BarbarianVillageFisherScript::isDroppable, "DROPPING", weatherMultiplier);
        if (result.getOutcome() == AScriptDrop.Outcome.NO_PROGRESS) {
            fail("drop did not land");
            return;
        }
        if (result.getOutcome() == AScriptDrop.Outcome.NOTHING_TO_DROP) {
            dropping = false;
            if (Rs2Inventory.isFull()) {
                // Full with nothing the module owns is a stuck state, not a silent spin.
                fail("inventory full but nothing droppable (unexpected items?)");
            } else {
                // The latch can survive one tick past the last slice; an empty filter is the
                // normal end of the phase, not a failure.
                consecutiveFailures = 0;
                postCycleBreak(config);
            }
            return;
        }
        consecutiveFailures = 0;
        if (result.getRemaining() == 0) {
            dropping = false;
            postCycleBreak(config);
        }
    }

    private void doCooking(AScriptConfig config) {
        Microbot.status = "COOKING";

        if (!hasRawFish()) {
            clearCookingState();
            return;
        }

        // Wait for XP drop if we previously started cooking (confirmed dispatch)
        if (expectingXPDrop && currentlyCookingID != 0
                && Rs2Inventory.count(currentlyCookingID) != 0) {
            if (waitForCookingXp(COOKING_XP_MIN_MS, COOKING_XP_MAX_MS)) {
                onCookingProgress();
                return;
            }

            // XP not received: is the cooking action still in flight (slow batch /
            // animation without XP in this window)?
            if (cookingConfirmed && isCookingInFlight()) {
                // Action is in progress — do NOT count as failure, do NOT reset
                // the latch, do NOT re-dispatch. Just re-wait next tick.
                return;
            }

            // One grace window before releasing the latch: under lag the XP read can arrive
            // late with no animation in flight, and a re-dispatch in that gap is what used
            // to select the freshly burnt fish left in the raw stack's slot.
            if (waitForCookingXp(COOKING_XP_GRACE_MIN_MS, COOKING_XP_GRACE_MAX_MS)) {
                onCookingProgress();
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

        // A stale make-X box means an earlier SPACE never landed. Dismiss it so the dispatch
        // below starts a fresh, tracked batch — selecting a second fish on the fire while a
        // box is open races the selection.
        if (isCookPromptOpen()) {
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(180, 540, weatherMultiplier));
            return;
        }

        // Still cooking the previous batch (animation/interaction): never dispatch a second
        // use-on-fire in the gaps between fish.
        if (isCookingInFlight()) {
            return;
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
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        }
    }

    /** The make-X prompt this module confirms with SPACE. */
    private boolean isCookPromptOpen() {
        return Rs2Widget.findWidget(COOK_MAKE_X_PROMPT, null, false) != null;
    }

    /** One XP window for the active cooking batch, weather-modulated and log-normal. */
    private boolean waitForCookingXp(int minMs, int maxMs) {
        return Rs2Player.waitForXpDrop(Skill.COOKING,
                (int) Rs2Random.logNormalBounded(minMs, maxMs, weatherMultiplier));
    }

    /** Success block for one XP drop: reset counters/latches and extend the batch deadline. */
    private void onCookingProgress() {
        Microbot.status = "COOKING — got XP";
        consecutiveFailures = 0;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(235, 798, weatherMultiplier));
        expectingXPDrop = false;
        cookingConfirmed = false;
        // Progress made — reset stagnation deadline (Ponto 3)
        cookingDeadlineMs = System.currentTimeMillis() + 60_000;
        cookingRedispaches = 0;
    }

    /** Clear every cooking latch/state (batch finished or nothing raw left). */
    private void clearCookingState() {
        expectingXPDrop = false;
        currentlyCookingID = 0;
        cookingConfirmed = false;
        cookingDeadlineMs = 0;
        cookingRedispaches = 0;
    }

    /**
     * Post-cycle break — the house randomness layer: a short randomized pause after every
     * completed cycle, then the optional random AFK (log-normal, weather-modulated,
     * interruptible so blocking events still fire). Always runs with the bank closed.
     */
    private void postCycleBreak(AScriptConfig config) {
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.barbarianVillageFisherAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3_000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    private void walkToBank() {
        Microbot.status = "WALKING TO BANK";
        if (Rs2Player.isMoving()) return;
        // The walk radius must stay BELOW isCloseTo()'s 10-tile switch: the old 20-tile
        // radius stopped the run 11-20 tiles out, isCloseTo() stayed false and the phase
        // never advanced.
        if (!Rs2Walker.walkTo(BankLocation.EDGEVILLE.getWorldPoint(), 8)) {
            log.debug("[BarbarianVillageFisher] walk to bank did not start");
        }
    }

    /**
     * Progress toward a bank that can actually be clicked. When the bank is visible the
     * orchestrator owns the next step; a loaded-but-off-camera bank is TURNED TO; with no
     * bank object in the scene the module walks, bounded by {@link #BANK_APPROACH_STOP_MS}.
     *
     * @return false when the bounded attempt expired without a clickable bank
     */
    private boolean approachBankObject() {
        if (AScriptBank.bankVisible()) {
            bankTurnSinceMs = 0L;
            return true; // the bank cycle takes it from here
        }
        var bankObject = AScriptBank.findBankObject();
        if (bankObject != null) {
            Microbot.status = "TURNING TO BANK";
            Rs2Camera.turnTo(bankObject);
            bankTurnSinceMs = 0L;
            return true;
        }
        if (bankTurnSinceMs == 0L) bankTurnSinceMs = System.currentTimeMillis();
        if (System.currentTimeMillis() - bankTurnSinceMs > BANK_APPROACH_STOP_MS) {
            bankTurnSinceMs = 0L;
            return false;
        }
        walkToBank();
        return true;
    }

    private void walkToFishingSpot() {
        Microbot.status = "WALKING TO SPOT";
        if (Rs2Player.isMoving()) return;
        if (!Rs2Walker.walkTo(FISHING_SPOT, 9)) {
            log.debug("[BarbarianVillageFisher] walk to fishing spot did not start");
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    /** Rod id for the configured fishing type (ids, never names — see the constants above). */
    private int getRodId(AScriptConfig config) {
        return config.barbarianVillageFisherType() == BarbarianFishingType.BAIT_FISHING
                ? FISHING_ROD_ID : FLY_FISHING_ROD_ID;
    }

    /** Bait id for the configured fishing type. */
    private int getBaitId(AScriptConfig config) {
        return config.barbarianVillageFisherType() == BarbarianFishingType.BAIT_FISHING
                ? FISHING_BAIT_ID : FEATHER_ID;
    }

    /** User-facing rod name (status/notify only — never used to resolve an item). */
    private String getRodLabel(AScriptConfig config) {
        return config.barbarianVillageFisherType() == BarbarianFishingType.BAIT_FISHING
                ? "fishing rod" : "fly fishing rod";
    }

    /** User-facing bait name (status/notify only — never used to resolve an item). */
    private String getBaitLabel(AScriptConfig config) {
        return config.barbarianVillageFisherType() == BarbarianFishingType.BAIT_FISHING
                ? "fishing bait" : "feather";
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

    /** True when the item is a fish this module produced and may drop. */
    private static boolean isDroppable(Rs2ItemModel item) {
        return item != null
                && (BarbarianFish.isRawOrCooked(item.getId()) || BarbarianFish.isBurnt(item));
    }

    private boolean isCloseTo(WorldPoint location) {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        return playerLoc != null && playerLoc.distanceTo(location) <= 10;
    }

    private boolean isGameObjectOnTile(WorldPoint location, int id) {
        var result = Microbot.getRs2TileObjectCache().query()
                .withId(id)
                .within(location, 0)
                .first();
        return result != null;
    }

    /**
     * The scenery fire this module cooks on — id + the exact tile the caller verified with
     * {@link #isGameObjectOnTile}, resolved on the client thread (client-state reads, see
     * the Firemaking note). The object is resolved immediately before the dispatch: the
     * recipe switches panels and turns the camera, so a model resolved earlier in the tick
     * could be stale by the time it is clicked.
     */
    private Rs2TileObjectModel findCookingFire() {
        return Microbot.getRs2TileObjectCache().query()
                .withId(BARBARIAN_VILLAGE_FIRE_ID)
                .within(BARBARIAN_VILLAGE_FIRE_POINT, 0)
                .firstOnClientThread();
    }

    /**
     * Dispatches a cooking action: select raw fish → use on fire → wait for
     * make-X dialog → confirm with SPACE → mark cooking state.
     *
     * @return true if the make-X dialog appeared and was confirmed; false if
     *         the dispatch did not engage (click silently failed).
     *
     * <p>The fish→fire dispatch is a {@link AScriptClick} client-mouse item-on-X recipe
     * (the Firemaking pilot of the aScript mouse refactor): a physical click on the raw
     * fish's inventory slot selects it, the selection is verified, and a physical click on
     * the fire's real clickbox applies it — no {@code doInvoke}/{@code NewMenuEntry} menu
     * injection. The Barbarian Village scenery fire (id 43475) is not a Firemaking target
     * (that module never converts scenery), but it is this module's cook target; it is
     * resolved by id on the same tile the caller just verified, mirroring what
     * {@code Rs2Inventory.useItemOnObject(fishId, fireId)} resolved for the old injection.
     * Confirming the make-X box below stays this method's verification, as before.</p>
     */
    private boolean doCookingDispatch() {
        for (BarbarianFish fish : BarbarianFish.values()) {
            final int fishId = fish.getRawId();
            if (Rs2Inventory.count(fishId) == 0) {
                continue;
            }
            // Raw fish's left-click default is "Use" — what the recipe's physical slot
            // click selects with. The slot is drawn from the batch (AScriptPick), never
            // pinned to the first raw fish.
            Rs2ItemModel fishItem = AScriptPick.pick(fishId);
            // Re-check the MODEL at the click site: a stack that finished cooking leaves the
            // cooked/burnt product in its slot, and the recipe's selected-id check then aborts
            // the dispatch instead of using the wrong item on the fire.
            if (fishItem == null || fishItem.getId() != fishId || fishItem.isNoted()) {
                log.debug("[BarbarianFishing] raw fish {} changed before the dispatch — skipping", fishId);
                continue;
            }
            if (!AScriptClick.useItemOnObject(fishItem, findCookingFire())) {
                return false;
            }
            // Wait for the make-X dialog to appear (confirm the click landed)
            boolean dialogOpened = sleepUntil(() -> !Rs2Player.isMoving()
                            && isCookPromptOpen(),
                    (int) Rs2Random.logNormalBounded(COOKING_DIALOG_MIN_MS, COOKING_DIALOG_MAX_MS,
                            weatherMultiplier));
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
