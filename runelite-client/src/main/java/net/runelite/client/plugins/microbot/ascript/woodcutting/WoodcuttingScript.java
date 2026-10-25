package net.runelite.client.plugins.microbot.ascript.woodcutting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptDrop;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.LootingParameters;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2LogBasket;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static net.runelite.api.gameval.AnimationID.*;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Woodcutting sub-script — chops a configured tree type around the activation spot,
 * with Drop (powercut) or Bank log handling. Migration of the hub AutoWoodcutting
 * plugin, mapped onto the AModule contract: banking collapses into
 * {@link #needsBank}/{@link #doBank} (the orchestrator opens the bank; the module
 * walks only when no bank is in sight), and the chop loop is tick-driven — the
 * woodcutting animation is the in-flight signal and log-count increases are the
 * batch tracker, so pause/resume, tree depletion and restarts are self-healing.
 */
@Slf4j
public class WoodcuttingScript implements AModule {

    /** Failed attempts in a row before the module stops and notifies. */
    private static final int MAX_ACTION_FAILURES = 3;
    /** Render-wait steps for the tree click (clickObject's bounded hull poll). */
    private static final int TREE_RENDER_WAIT_STEPS = 2;
    /** No choppable tree in range for this long → stop loudly (trees respawn fast). */
    private static final long NO_TREE_STOP_MS = 90_000L;
    /** No position change while travelling for this long → stop loudly (cannonball watchdog). */
    private static final long TRAVEL_STAGNATION_MS = 120_000L;
    /** Bird-nest loot radius around the player (hub parity). */
    private static final int LOOT_RADIUS = 15;
    /** Axe spec: bounded activation attempts per energy cycle (BVF harpoon-spec shape). */
    private static final int AXE_SPEC_MAX_ATTEMPTS = 3;

    /**
     * Every woodcutting animation in gameval (all axe tiers and variants, incl. league /
     * 3a / gilded / blessed / trailblazer / demonic-pact / gauntlet). The in-flight
     * signal — a slow tree is normal, the animation never is not.
     */
    private static final Set<Integer> WOODCUTTING_ANIMATION_IDS = Set.of(
            HUMAN_WOODCUTTING_BRONZE_AXE,
            HUMAN_WOODCUTTING_IRON_AXE,
            HUMAN_WOODCUTTING_STEEL_AXE,
            HUMAN_WOODCUTTING_BLACK_AXE,
            HUMAN_WOODCUTTING_MITHRIL_AXE,
            HUMAN_WOODCUTTING_ADAMANT_AXE,
            HUMAN_WOODCUTTING_RUNE_AXE,
            HUMAN_WOODCUTTING_DRAGON_AXE,
            HUMAN_WOODCUTTING_INFERNAL_AXE,
            HUMAN_WOODCUTTING_CRYSTAL_AXE,
            HUMAN_WOODCUTTING_GILDED_AXE,
            HUMAN_WOODCUTTING_3A_AXE,
            HUMAN_WOODCUTTING_BLESSED_AXE,
            HUMAN_WOODCUTTING_TRAILBLAZER_AXE,
            HUMAN_WOODCUTTING_TRAILBLAZER_RELOADED_AXE,
            HUMAN_WOODCUTTING_TRAILBLAZER_AXE_NO_INFERNAL,
            HUMAN_WOODCUTTING_LEAGUE_AXE,
            HUMAN_WOODCUTTING_DEMONIC_PACT,
            HUMAN_WOODCUTTING_GAUNTLET_AXE_HM,
            MISC_HUMAN_WOODCUTTING_STEEL_AXE);

    /** Spec axes (hub parity) — the special attack boosts Woodcutting. Derived from {@link Axe}. */
    private static final Set<Integer> SPEC_AXE_IDS = Axe.specialAttackAxeIds();

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private int consecutiveFailures = 0;
    private double weatherMultiplier = 1.0;
    /** Where the module started cutting: the search centre and the return point after bank trips. */
    private WorldPoint anchor = null;
    /** Target-log count observed last tick (-1 = not tracked yet). */
    private int lastLogCount = -1;
    /** DROP mode: a drop phase is running (entered when full; ends when the count hits 0). */
    private boolean dropping = false;
    /** When the first tick saw no choppable tree (0 = a tree is available). */
    private long noTreeSinceMs = 0L;
    /** Timestamp of the last random AFK — minimum 5 s between AFKs. */
    private long lastAfkTime = 0L;
    /** Set once the batch-end break ran; cleared when logs are back in hand. */
    private boolean postBatchBreakDone = false;
    /** True once a batch has been worked since the last bank cycle (break gating). */
    private boolean batchRanSinceBank = false;
    /** Travel watchdog: last observed position and when it last changed. */
    private WorldPoint lastTravelPos = null;
    private long lastTravelMoveAt = 0L;
    /** Axe spec: one activation per full-energy cycle (BVF harpoon-spec shape). */
    private boolean axeSpecDone = false;
    private int axeSpecAttempts = 0;
    private long axeSpecDeadlineMs = 0L;

    public enum Phase { NONE, WOODCUTTING }

    // ── Lifecycle ─────────────────────────────────────────

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFailures = 0;
        anchor = null;
        lastLogCount = -1;
        dropping = false;
        noTreeSinceMs = 0L;
        lastAfkTime = 0L;
        postBatchBreakDone = false;
        batchRanSinceBank = false;
        lastTravelPos = null;
        lastTravelMoveAt = 0L;
        axeSpecDone = false;
        axeSpecAttempts = 0;
        axeSpecDeadlineMs = 0L;
    }

    // ── Phase resolution ──────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.WOODCUTTING
                || config.woodcuttingTree() == null
                || config.woodcuttingTree() == WoodcuttingTree.NONE) {
            currentPhase = Phase.NONE;
            return;
        }
        currentPhase = Phase.WOODCUTTING;
    }

    @Override
    public boolean isActive() {
        return currentPhase != Phase.NONE;
    }

    // ── Selection validation ──────────────────────────────

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.WOODCUTTING) {
            return "no tree type selected";
        }
        if (currentPhase == Phase.WOODCUTTING) {
            // Capability guard: the level reads 0 while skill data is still loading —
            // treat that as "retry", not a stop.
            WoodcuttingTree choice = config.woodcuttingTree();
            int level = Rs2Player.getRealSkillLevel(Skill.WOODCUTTING);
            if (level > 0 && level < choice.getLevelRequired()) {
                return "need level " + choice.getLevelRequired() + " Woodcutting for "
                        + choice.getName() + " (current: " + level + ")";
            }
        }
        return null;
    }

    // ── Banking needs ─────────────────────────────────────

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        WoodcuttingTree tree = config.woodcuttingTree();
        if (tree == null || tree == WoodcuttingTree.NONE) return false;

        // No axe anywhere — it may be sitting in the bank.
        if (!hasAxe()) return true;

        if (config.woodcuttingAction() == WoodcuttingAction.BANK && Rs2Inventory.isFull()) {
            // A batch that actually ran earns the bank-closed break first (the yield);
            // a fresh full inventory banks immediately.
            return !batchRanSinceBank || postBatchBreakDone;
        }
        return false;
    }

    /**
     * The bank phase gate (cannonball pattern): readiness is judged on the bank OBJECT
     * being loaded and on camera, never on a distance to a coordinate. While this
     * reports not-ready the orchestrator skips the open/cache cycle and dispatches
     * {@link #doAction}, which walks towards the bank until it loads into view.
     */
    @Override
    public boolean readyForBank(AScriptConfig config) {
        return AScriptBank.bankVisible();
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        // The only material this module needs is an axe — missing from inventory,
        // equipment, banknotes and bank.
        return !hasAxe() && !axeInBank() && !notedAxeInInventory();
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        return "woodcutting: no axe (not worn, not in inventory, not noted, not in bank)";
    }

    // ── Banking ───────────────────────────────────────────

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING — Woodcutting";

        // 0. An axe carried in the inventory is equipped BEFORE the bank opens: the bank
        //    window swallows equip clicks, and a player already holding the tool must never
        //    be sent back for a bank copy. Explicit wear, verified.
        if (!wearingAnyAxe()) {
            Axe held = bestAxeInInventory();
            if (held != null) {
                if (Rs2Bank.isOpen()) {
                    Rs2Bank.closeBank();
                    sleepUntil(() -> !Rs2Bank.isOpen(),
                            Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier));
                }
                Rs2Inventory.wield(held.getItemId());
                if (!sleepUntil(WoodcuttingScript::wearingAnyAxe,
                        Rs2Random.logNormalBounded(2000, 3500, weatherMultiplier))) {
                    log.warn("[Woodcutting] held axe wear did not land");
                    return false;
                }
            }
        }

        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            return false;
        }

        // 1. Log basket (when held): empty its contents into the bank and lock its slot so
        //    the blanket deposit below cannot send the basket itself away. The keep-list
        //    carries every axe id so the lock sweep can never release a locked axe.
        if (Rs2LogBasket.hasLogBasket()) {
            if (!Rs2LogBasket.emptyLogBasketAtBank()) {
                log.warn("[Woodcutting] log basket could not be emptied into the bank");
                return false;
            }
            AScriptBank.ensureToolLocked(String.valueOf(Rs2LogBasket.getLogBasketId()), axeIdStrings());
        }

        // 2. Axe: keep the worn one; otherwise take the best tier in the bank and equip it.
        //    An equip that fails leaves it in the inventory — lock the slot (keep-list =
        //    the basket) so the blanket deposit cannot send it back. ONE bank snapshot for
        //    the pick (never a per-tier scan through the retrying reads).
        //    A usable axe held only as a banknote is un-noted by the toolbar deposit first:
        //    the bank then holds the base axe the pick below withdraws.
        if (!wearingAnyAxe() && bestAxeInInventory() == null && notedAxeInInventory()) {
            if (!AScriptBank.depositAll()) {
                log.warn("[Woodcutting] could not un-note the carried axe");
                return false;
            }
        }
        if (!wearingAnyAxe()) {
            Axe best = bestAxeInBank();
            if (best == null) {
                AScriptNotify.notify("Banking Failed",
                        "No axe in bank usable at Woodcutting " + Rs2Player.getBoostedSkillLevel(Skill.WOODCUTTING));
                return false;
            }
            Rs2Bank.withdrawAndEquip(best.getItemId());
            if (!sleepUntil(WoodcuttingScript::wearingAnyAxe,
                    Rs2Random.logNormalBounded(2000, 3500, weatherMultiplier))) {
                if (Rs2Inventory.hasItem(best.getItemId())) {
                    AScriptBank.ensureToolLocked(String.valueOf(best.getItemId()),
                            List.of(String.valueOf(Rs2LogBasket.getLogBasketId())));
                } else {
                    log.warn("[Woodcutting] axe withdraw/equip did not land");
                    return false;
                }
            }
        }

        // 3. Deposit the logs (and anything else non-locked) via the toolbar button.
        if (!AScriptBank.depositAndWaitEmpty()) {
            log.warn("[Woodcutting] deposit failed");
            return false;
        }

        // The wear must survive the blanket deposit: a released lock would send the axe to
        // the bank and the next chop would find no tool.
        if (!wearingAnyAxe()) {
            log.warn("[Woodcutting] axe not worn after the deposit");
            return false;
        }

        batchRanSinceBank = false;
        postBatchBreakDone = false;
        lastLogCount = -1;
        dropping = false; // a fresh trip — the next fill starts a new drop phase
        return true;
    }

    // ── Action (chop) ─────────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;
        WoodcuttingTree treeChoice = config.woodcuttingTree();
        if (treeChoice == null || treeChoice == WoodcuttingTree.NONE) return;
        int logId = treeChoice.getLogId();

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // Level-up and other continue dialogues — click through; the chop continues.
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return;
        }

        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return;
        if (anchor == null) anchor = player;

        // DROP mode: one drop PHASE per fill — entered when the inventory fills and run
        // until the log count hits 0. The plain "drop while full" shape freed a slot and
        // resumed chopping, so the count never reached 0, the batch-end break never fired
        // and batchRanSinceBank never cleared (Mining's dropping latch, mirrored).
        if (config.woodcuttingAction() == WoodcuttingAction.DROP) {
            if (dropping) {
                if (Rs2Inventory.itemQuantity(logId) == 0) {
                    dropping = false;
                    postBatchBreak(config);
                    return;
                }
                dropSlice(logId);
                return;
            }
            if (Rs2Inventory.isFull()) {
                if (Rs2Inventory.itemQuantity(logId) == 0) {
                    // Full of something else: a drop slice would wait on a count that can never
                    // shrink and stop the module with "log drop did not land" (Mining's guard).
                    fail("inventory is full but holds no " + treeChoice.getLogName());
                    return;
                }
                dropping = true;
                dropSlice(logId);
                return;
            }
        }

        // BANK mode, inventory nearly full and a log basket held: fill the basket first —
        // it buys ~28 more logs before the bank trip (fork util; the "Fill" interaction
        // inside it is a shared-util exception, same family as the bank path).
        if (config.woodcuttingAction() == WoodcuttingAction.BANK
                && Rs2LogBasket.hasLogBasket() && Rs2Inventory.emptySlotCount() <= 3
                && Rs2LogBasket.shouldFillBasket(logId)) {
            if (Rs2LogBasket.fillLogBasket()) consecutiveFailures = 0;
            else fail("log basket fill did not land");
            return;
        }

        // Bank needed but not in sight: walk to it (both modes — the missing-axe trip
        // is mode-independent). This branch MUST run before the full-inventory break
        // below: needsBank stays false while the break is pending (the yield in
        // needsBank above), so the break always runs first, and once the trip is
        // earned this branch walks until the bank loads — readyForBank then hands the
        // cycle to the orchestrator (cannonball pattern). Without it a far bank would
        // deadlock in the isFull branch below, which returns every tick.
        if (needsBank(config) && !AScriptBank.bankVisible()) {
            var bank = Rs2Bank.getNearestBank();
            if (bank == null) {
                fail("no reachable bank found");
                return;
            }
            // A bank object already loaded in the scene is TURNED TO, not walked at: the
            // walker cannot target an off-camera bank reliably, and a camera turn does not
            // move the player (cannonball approachBank pattern).
            TileObject bankObject = AScriptBank.findBankObject();
            if (bankObject != null) {
                Microbot.status = "TURNING TO BANK";
                Rs2Camera.turnTo(bankObject);
                return;
            }
            travelTo(bank.getWorldPoint());
            return;
        }

        // BANK mode, full inventory: take the post-batch break (bank still closed); the
        // orchestrator banks on a later tick via the needsBank yield above.
        if (config.woodcuttingAction() == WoodcuttingAction.BANK && Rs2Inventory.isFull()) {
            if (batchRanSinceBank) postBatchBreak(config);
            else postBatchBreakDone = true;
            return;
        }

        // Drifted away from the cutting spot (bank trip, misclick): walk back first.
        if (player.distanceTo(anchor) > config.woodcuttingRadius()) {
            travelTo(anchor);
            return;
        }
        lastTravelPos = null; // arrived — the next trip gets a fresh watchdog

        // Axe special attack — one activation per full-energy cycle (BVF harpoon shape).
        maybeAxeSpec(config);

        // DROP mode batch end: the logs are gone after a batch ran — bank-free break,
        // once per batch (MLM placement). lastLogCount > 0 demands that logs were held
        // since the last break/reset: the chop confirmation below arms
        // batchRanSinceBank as soon as the animation starts, before the first log
        // arrives, so count == 0 alone would fire the break at startup and again
        // through every 0-log window — an empty inventory is not a batch end.
        if (config.woodcuttingAction() == WoodcuttingAction.DROP
                && Rs2Inventory.itemQuantity(logId) == 0 && lastLogCount > 0
                && batchRanSinceBank) {
            postBatchBreak(config);
            return;
        }

        int count = Rs2Inventory.itemQuantity(logId);
        // Arm only with logs in hand: with the -1 baseline, `count > lastLogCount`
        // alone armed the tracker at 0 logs (empty inventory), resetting
        // postBatchBreakDone so the batch-end break above could fire at startup and
        // re-fire every ~2 ticks while the inventory waited empty.
        if (count > 0 && count > lastLogCount) {
            lastLogCount = count;
            batchRanSinceBank = true;    // a batch is being worked
            postBatchBreakDone = false;  // logs in hand — the next batch end is a new one
        }

        if (isChopping()) {
            Microbot.status = "WOODCUTTING — " + treeChoice.getName()
                    + (count > 0 ? " (" + count + " logs)" : "");
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
            return;
        }

        // Nests on the ground (they drop from trees while chopping): loot from the idle
        // path before re-engaging — never mid-chop (walking would interrupt it).
        if (config.woodcuttingLootNests() && lootNests()) {
            return; // looted this tick — chop resumes next tick
        }

        Rs2TileObjectModel tree = findTree(treeChoice, anchor, config.woodcuttingRadius());
        if (tree == null) {
            if (noTreeSinceMs == 0L) noTreeSinceMs = System.currentTimeMillis();
            if (System.currentTimeMillis() - noTreeSinceMs > NO_TREE_STOP_MS) {
                fail("no choppable " + treeChoice.getName() + " near the starting spot");
                return;
            }
            Microbot.status = "WOODCUTTING — waiting for a tree";
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
            return;
        }
        noTreeSinceMs = 0L;

        // Click the tree with the client-mouse object recipe (camera first; the real
        // clickbox; the canvas-sized fallback is never clicked).
        Microbot.status = "WOODCUTTING — chopping " + treeChoice.getName();
        AScriptClick.Target target = AScriptClick.clickObject(tree, TREE_RENDER_WAIT_STEPS);
        if (target.cameraTurned()) {
            return; // retry next tick — a camera turn is not a failure
        }
        if (!target.clicked()) {
            fail("tree click did not land (not rendered)");
            return;
        }

        // Confirm the chop started: the woodcutting animation, or the first log arriving.
        int before = count;
        if (!sleepUntil(() -> isChopping() || Rs2Inventory.itemQuantity(logId) > before,
                Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier))) {
            fail("click did not start chopping");
            return;
        }
        consecutiveFailures = 0;
        lastLogCount = Math.max(lastLogCount, Rs2Inventory.itemQuantity(logId));
        batchRanSinceBank = true;
    }

    // ── Internal helpers ──────────────────────────────────

    /**
     * One sliced drop pass over the target logs ({@link AScriptDrop}): a rolled 5–9 slots per
     * tick with log-normal pacing and a count verification. The shared helper owns the route,
     * the slice and the verify; this module's {@code dropping} latch owns "keep going until the
     * log count is 0" and the batch-end break.
     */
    private void dropSlice(int logId) {
        AScriptDrop.Result result = AScriptDrop.drop(
                item -> item.getId() == logId, "WOODCUTTING — dropping logs", weatherMultiplier);
        if (result.getOutcome() == AScriptDrop.Outcome.NO_PROGRESS) {
            fail("log drop did not land");
        } else if (result.getOutcome() == AScriptDrop.Outcome.DROPPED) {
            consecutiveFailures = 0;
        }
    }

    /**
     * Loot bird nests on the ground near the player (they drop from trees while chopping;
     * all nest variants share the "nest" name fragment). Hub parity: radius 15, one item
     * per pass. Shared-util interaction (Rs2GroundItem) — same exception family as the
     * bank path.
     */
    private boolean lootNests() {
        return Rs2GroundItem.lootItemsBasedOnNames(new LootingParameters(
                LOOT_RADIUS, 1, 1, 1, false, false, "nest"));
    }

    /** Every axe id as strings — the keep-list for lock sweeps (an axe is never a stray). */
    private static List<String> axeIdStrings() {
        return Arrays.stream(Axe.values()).map(a -> String.valueOf(a.getItemId()))
                .collect(Collectors.toList());
    }

    /**
     * Nearest tree of the configured type within {@code radius} of the anchor. The
     * composition action filter ("Chop down" / "Chop" / "Cut") is the discriminator —
     * it excludes scenery that shares the name and tree variants that cannot be cut
     * (the hub's hardwood-patch trick, generalised) — and the exact-name check keeps
     * "tree" from resolving "oak tree" (wrong log id downstream).
     */
    private Rs2TileObjectModel findTree(WoodcuttingTree tree, WorldPoint center, int radius) {
        return Microbot.getRs2TileObjectCache().query()
                .within(center, radius)
                .where(x -> {
                    var comp = x.getObjectComposition();
                    if (comp == null || !Rs2GameObject.hasAction(comp, tree.getAction())) return false;
                    String name = comp.getName();
                    return name != null && name.equalsIgnoreCase(tree.getName());
                })
                .nearestOnClientThread();
    }

    /**
     * True while the player plays a woodcutting animation (the in-flight signal).
     * Reads the CURRENT animation through {@code getAnimation()} (client-thread
     * wrapped): Rs2Player has no {@code getLastAnimationID()} accessor, and its
     * public {@code lastAnimationID} field never resets to IDLE — it sticks at the
     * last non-idle animation, which would read "chopping" for a stale window.
     */
    private boolean isChopping() {
        return Rs2Player.isAnimating(1800)
                && WOODCUTTING_ANIMATION_IDS.contains(Rs2Player.getAnimation());
    }

    /** Any axe worn or in the inventory that the player can actually chop with. */
    private static boolean hasAxe() {
        int level = Rs2Player.getBoostedSkillLevel(Skill.WOODCUTTING);
        for (Axe axe : Axe.values()) {
            if (axe.isUsableAt(level)
                    && (Rs2Equipment.isWearing(axe.getItemId()) || Rs2Inventory.hasItem(axe.getItemId()))) {
                return true;
            }
        }
        return false;
    }

    /** Any usable axe held only as a banknote — bankable, not choppable. */
    private static boolean notedAxeInInventory() {
        int level = Rs2Player.getBoostedSkillLevel(Skill.WOODCUTTING);
        for (Axe axe : Axe.values()) {
            if (axe.isUsableAt(level) && AScriptBank.hasNotedItem(axe.getItemId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean wearingAnyAxe() {
        for (Axe axe : Axe.values()) {
            if (Rs2Equipment.isWearing(axe.getItemId())) return true;
        }
        return false;
    }

    /** Any axe present in the bank (ONE snapshot pass — never per-tier retrying reads). */
    private static boolean axeInBank() {
        return bestAxeInBank() != null;
    }

    /**
     * Best axe in the bank: the highest tier present, from ONE {@code Rs2Bank.getAll()}
     * snapshot (the JewelEnchant staff-scan lesson: a per-tier scan through the retrying
     * reads pays a 2-tick retry per miss and stalls the step silently).
     * <p>
     * Level-gated like {@link #hasAxe()}: withdrawing an axe above the player's
     * Woodcutting level only trades "no axe" for "an axe that cannot chop".
     */
    private static Axe bestAxeInBank() {
        int level = Rs2Player.getBoostedSkillLevel(Skill.WOODCUTTING);
        Set<Integer> bankIds = Rs2Bank.getAll()
                .map(Rs2ItemModel::getId)
                .collect(Collectors.toSet());
        Axe best = null;
        for (Axe axe : Axe.values()) {
            if (bankIds.contains(axe.getItemId()) && axe.isUsableAt(level)) best = axe;
        }
        return best;
    }

    /**
     * Best axe the player is CARRYING (inventory only). The held-tool path equips this
     * before the bank opens; a worn axe is handled by {@link #wearingAnyAxe()}.
     */
    private static Axe bestAxeInInventory() {
        int level = Rs2Player.getBoostedSkillLevel(Skill.WOODCUTTING);
        Axe best = null;
        for (Axe axe : Axe.values()) {
            if (Rs2Inventory.hasItem(axe.getItemId()) && axe.isUsableAt(level)) {
                best = axe;
            }
        }
        return best;
    }

    /**
     * Walk to {@code target} with a stagnation watchdog: no position change at all for
     * {@link #TRAVEL_STAGNATION_MS} stops the module loudly. Progress = "the player
     * moved" — a teleport can move you farther first, so distance never proves progress.
     */
    private void travelTo(WorldPoint target) {
        Microbot.status = "WOODCUTTING — travelling";
        WorldPoint now = Rs2Player.getWorldLocation();
        if (now == null) return;
        if (lastTravelPos == null || !now.equals(lastTravelPos)) {
            lastTravelPos = now;
            lastTravelMoveAt = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastTravelMoveAt > TRAVEL_STAGNATION_MS) {
            exitRequested = true;
            stopWithMessage("could not reach the destination (no movement for "
                    + (TRAVEL_STAGNATION_MS / 1000) + "s)");
            return;
        }
        Rs2Walker.walkTo(target, 4);
    }

    /** True when a spec axe is worn. */
    private static boolean specAxeWorn() {
        for (int id : SPEC_AXE_IDS) {
            if (Rs2Equipment.isWearing(id)) return true;
        }
        return false;
    }

    /**
     * Axe special attack — one activation per full-energy cycle, mirroring the BVF
     * dragon-harpoon spec block ({@code maybeActivateHarpoonSpec} in
     * BarbarianFishingScript) shape-for-shape: the latch applies only after the spec
     * state VERIFIES as active; an unconfirmed dispatch re-attempts (bounded) instead
     * of burning the cycle, and the latch resets whenever the energy cycle is not
     * full (i.e. right after the spec is consumed).
     */
    private void maybeAxeSpec(AScriptConfig config) {
        if (!config.woodcuttingAxeSpec()) return;

        boolean wearingSpecAxe = specAxeWorn();
        int energy = Rs2Combat.getSpecEnergy();

        if (!wearingSpecAxe || energy < 1000) {
            axeSpecDone = false;
            axeSpecAttempts = 0;
            axeSpecDeadlineMs = 0L;
            return;
        }
        if (axeSpecDone || Rs2Combat.getSpecState()) return;

        long now = System.currentTimeMillis();
        if (axeSpecDeadlineMs == 0L) {
            axeSpecDeadlineMs = now + Rs2Random.logNormalBounded(5_000, 90_000, weatherMultiplier);
            return;
        }
        if (now < axeSpecDeadlineMs) return;

        if (Rs2Combat.setSpecState(true)
                && sleepUntil(Rs2Combat::getSpecState,
                        (int) Rs2Random.logNormalBounded(1500, 2500, weatherMultiplier))) {
            axeSpecDone = true;
            axeSpecDeadlineMs = 0L;
            return;
        }

        axeSpecAttempts++;
        log.debug("[Woodcutting] Axe spec activation not confirmed ({}/{})",
                axeSpecAttempts, AXE_SPEC_MAX_ATTEMPTS);
        if (axeSpecAttempts >= AXE_SPEC_MAX_ATTEMPTS) {
            axeSpecDone = true; // give up until the energy cycle changes (restart re-arms)
        } else {
            axeSpecDeadlineMs = now + Rs2Random.logNormalBounded(20_000, 45_000, weatherMultiplier);
        }
    }

    /**
     * Post-batch break — the house randomness pattern: a short randomized pause after
     * every batch, then the optional random AFK (log-normal 3–120 s, weather-modulated,
     * interruptible so blocking events still fire). Runs once per batch; clears
     * {@code batchRanSinceBank} so the break is only earned by a batch that ran, and
     * resets the log-count baseline — without it the next batch's counts (1..28) would
     * never exceed the previous batch's peak and the batch tracker would never re-arm
     * (the break would fire once per run instead of once per batch in Drop mode).
     */
    private void postBatchBreak(AScriptConfig config) {
        if (postBatchBreakDone) return;
        postBatchBreakDone = true;
        batchRanSinceBank = false;
        lastLogCount = -1;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.woodcuttingAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    /**
     * Register a failed attempt: log it, back off, and stop the module (with a Discord
     * notification) after {@link #MAX_ACTION_FAILURES} in a row.
     */
    private void fail(String reason) {
        consecutiveFailures++;
        log.warn("[Woodcutting] Attempt failed ({}/{}): {}",
                consecutiveFailures, MAX_ACTION_FAILURES, reason);
        if (consecutiveFailures >= MAX_ACTION_FAILURES) {
            exitRequested = true;
            stopWithMessage(reason);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }

    /**
     * Stop the module: status, log, Discord notification and config reset. Every stop
     * path goes through here so a self-stop is never silent.
     */
    private void stopWithMessage(String message) {
        Microbot.status = "STOPPED — " + message;
        log.warn("[Woodcutting] Stopping: {}", message);
        AScriptNotify.notify("aScript Stopped — Woodcutting", message);
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
    }
}
