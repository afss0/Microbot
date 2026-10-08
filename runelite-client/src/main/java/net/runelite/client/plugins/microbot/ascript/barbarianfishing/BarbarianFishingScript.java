package net.runelite.client.plugins.microbot.ascript.barbarianfishing;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.FishingSpot;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.awt.Rectangle;
import java.util.List;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Barbarian Fishing module — rod fishing for leaping fish at Otto's Grotto.
 * <p>
 * Migrated from the Microbot-Hub {@code barbarianfishing} plugin onto the aScript
 * module contract: Otto's Grotto powerfishing (drop leaping fish plus the roe/caviar
 * byproducts; keep the barbarian rod + feathers as locked inventory anchors), an
 * optional dragon harpoon special attack whose activation is VERIFIED before
 * latching (the hub version latched on an unconfirmed dispatch), walk-gated banking
 * at the Barbarian Outpost chest when the rod or feathers run out, and client-mouse
 * clicks for every action (no menu injection). The post-batch randomness layer
 * (short pause + optional random AFK, weather-modulated) matches the house pattern.
 */
@Slf4j
public class BarbarianFishingScript implements AModule {

    private static final int MAX_ACTION_FAILURES = 5;
    private static final int SPOT_CLICK_RETRIES = 5;

    /** Fishing 48 catches leaping trout — the module's minimum capability guard. */
    private static final int MIN_FISHING_LEVEL = 48;

    // Otto's Grotto: the barbarian fishing spots (worldmap-verified) span x2500-2520, y3495-3518.
    private static final WorldPoint FISHING_ANCHOR = new WorldPoint(2504, 3506, 0);
    /** Tiles around the anchor that count as "at the fishing area". */
    private static final int FISH_AREA_DISTANCE = 15;
    /** Spot search radius around the anchor (keeps stray barb spots elsewhere from matching). */
    private static final int SPOT_QUERY_RADIUS = 25;
    /** Arrival threshold for the walk to the fishing area. */
    private static final int WALK_TO_SPOT_DISTANCE = 10;
    /** Bank approach threshold — needsBank() only fires within this range (fisher pattern). */
    private static final int BANK_CLOSE_DISTANCE = 10;

    private static final int FISHING_ANIMATION_DEFAULT_MS = 600;
    /** Droppables clicked per drop-slice tick (rolled per slice). */
    private static final int DROP_SLICE_MIN = 5;
    private static final int DROP_SLICE_MAX = 9;
    private static final int POST_DROP_PAUSE_MIN_MS = 800;
    private static final int POST_DROP_PAUSE_MAX_MS = 1600;
    private static final int AFK_MIN_MS = 3_000;
    private static final int AFK_MAX_MS = 120_000;
    private static final long AFK_MIN_GAP_MS = 5_000;

    private static final int HARPOON_SPEC_MAX_ATTEMPTS = 3;
    private static final int[] HARPOON_IDS = {
            ItemID.DRAGON_HARPOON, ItemID.DRAGON_HARPOON_OR, ItemID.DRAGON_HARPOON_OR_30349
    };

    /** Everything the module drops: the leaping fish plus the roe/caviar byproducts, by id. */
    private static final int[] DROPPABLE_IDS = {
            ItemID.LEAPING_TROUT, ItemID.LEAPING_SALMON, ItemID.LEAPING_STURGEON,
            ItemID.ROE, ItemID.CAVIAR
    };

    public enum Phase { NONE, FISHING, DROPPING, WALK_TO_SPOT, WALK_TO_BANK }

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private int consecutiveFailures = 0;
    private double weatherMultiplier = 1.0;

    /** Randomized window defining "still fishing" between catch animations (hub parity). */
    private int animationWindowMs = FISHING_ANIMATION_DEFAULT_MS;
    /** Transient retry counter for spot clicks that produced no catch. */
    private int spotClickRetries = 0;

    /** A drop batch (inventory full -> all droppables gone) is in progress. */
    private boolean dropSessionActive = false;
    /** True once the session actually dropped something — gates the post-batch break. */
    private boolean droppedInSession = false;
    /** Min-gap reference for the random-AFK layer. */
    private long lastAfkTime = 0L;

    /**
     * Dragon harpoon spec: ONE activation per full-energy cycle. The latch is only
     * applied after the spec state VERIFIES as active — a dispatch that does not
     * register is re-attempted (bounded) instead of silently burning the cycle.
     */
    private boolean harpoonSpecDone = false;
    private int harpoonSpecAttempts = 0;
    private long harpoonSpecDeadlineMs = 0L;

    // ── AModule contract ─────────────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.BARBARIAN_FISHING
                || config.barbarianFishingActivity() == null
                || config.barbarianFishingActivity() == BarbarianFishingActivity.NONE) {
            currentPhase = Phase.NONE;
            return;
        }
        if (!Microbot.isLoggedIn()) {
            currentPhase = Phase.NONE;
            return;
        }
        // Coarse phase for the overlay and the NONE -> active reset transition;
        // runtime state (bank proximity, drop session) is re-resolved in doAction.
        if (bankTripNeeded()) {
            currentPhase = Phase.WALK_TO_BANK;
        } else if (isCloseToArea()) {
            currentPhase = Phase.FISHING;
        } else {
            currentPhase = Phase.WALK_TO_SPOT;
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
        animationWindowMs = FISHING_ANIMATION_DEFAULT_MS;
        spotClickRetries = 0;
        dropSessionActive = false;
        droppedInSession = false;
        lastAfkTime = 0L;
        harpoonSpecDone = false;
        harpoonSpecAttempts = 0;
        harpoonSpecDeadlineMs = 0L;
    }

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.BARBARIAN_FISHING)
            return "no barbarian fishing activity selected";
        if (currentPhase != Phase.NONE) {
            // Capability guard: the level reads 0 while skill data is still loading —
            // treat that as "retry", not a stop.
            int level = Rs2Player.getRealSkillLevel(Skill.FISHING);
            if (level > 0 && level < MIN_FISHING_LEVEL) {
                return "need level " + MIN_FISHING_LEVEL + " Fishing for barbarian fishing (current: " + level + ")";
            }
        }
        return null;
    }

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        // Walk-gated (fisher pattern): the module walks itself to the Barbarian
        // Outpost; only within bank range does the orchestrator take over the
        // open/withdraw cycle (a needsBank far from the bank would freeze doAction).
        return bankTripNeeded() && isCloseToBank();
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        // True only when missing from BOTH inventory and bank. hasBankItem is the
        // retrying variant (the bank mirror can be stale right after the open).
        if (!Rs2Inventory.hasItem(ItemID.BARBARIAN_ROD) && !Rs2Bank.hasBankItem(ItemID.BARBARIAN_ROD, 1)) return true;
        if (!Rs2Inventory.hasItem(ItemID.FEATHER) && !Rs2Bank.hasBankItem(ItemID.FEATHER, 1)) return true;
        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(ItemID.BARBARIAN_ROD) && !Rs2Bank.hasBankItem(ItemID.BARBARIAN_ROD, 1))
            sb.append("no barbarian rod (not in bank)");
        if (!Rs2Inventory.hasItem(ItemID.FEATHER) && !Rs2Bank.hasBankItem(ItemID.FEATHER, 1)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("no feathers (not in bank)");
        }
        return sb.length() == 0 ? "supplies" : sb.toString();
    }

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING";
        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) return false;
        if (!Rs2Bank.isOpen()
                && !sleepUntil(Rs2Bank::isOpen, Rs2Random.logNormalBounded(5000, 10000, weatherMultiplier))) {
            return false;
        }

        // Rod (tool) + feathers (stack) are locked inventory anchors: the blanket
        // toolbar deposit never removes them, so banking keeps whichever is held
        // and withdraws anew only what is missing.
        if (!AScriptBank.ensureToolLocked("Barbarian rod")) return false;
        if (!AScriptBank.ensureStackLocked("Feather")) return false;

        // Deposit everything else (fish caught before the trip) via the toolbar button.
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        // Final state check — a lock/withdraw that silently no-op'd retries next tick.
        if (!Rs2Inventory.hasItem(ItemID.BARBARIAN_ROD) || !Rs2Inventory.hasItem(ItemID.FEATHER)) {
            log.warn("[BarbarianFishing] Bank cycle finished without rod/feathers in the inventory — retrying");
            return false;
        }
        consecutiveFailures = 0;
        return true;
    }

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // Level-up and other continue dialogues — click through, fishing keeps running.
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return;
        }

        resolveDynamicPhase();

        try {
            switch (currentPhase) {
                case WALK_TO_BANK: handleWalkToBank();    break;
                case WALK_TO_SPOT: handleWalkToSpot();    break;
                case FISHING:      handleFishing(config); break;
                case DROPPING:     handleDropping(config); break;
                default:           break;
            }
        } catch (Exception ex) {
            log.error("[BarbarianFishing] Error in action loop", ex);
        }
    }

    // ── Dynamic phase resolution ─────────────────────────────────

    private void resolveDynamicPhase() {
        if (bankTripNeeded()) {
            currentPhase = Phase.WALK_TO_BANK;
            return;
        }
        if (Rs2Inventory.isFull()) {
            dropSessionActive = true;
        }
        if (dropSessionActive) {
            currentPhase = Phase.DROPPING;
            return;
        }
        if (!isCloseToArea()) {
            currentPhase = Phase.WALK_TO_SPOT;
            return;
        }
        currentPhase = Phase.FISHING;
    }

    // ── Phase handlers ───────────────────────────────────────────

    private void handleFishing(AScriptConfig config) {
        // Harpoon spec runs before the in-flight gate so it can fire while actively fishing.
        maybeActivateHarpoonSpec(config);

        if (Rs2Player.isAnimating(animationWindowMs) || Rs2Player.isMoving()) {
            Microbot.status = "FISHING";
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(256, 789, weatherMultiplier));
            return;
        }
        animationWindowMs = (int) Rs2Random.truncatedGauss(600, 1200, 2.0);

        Rs2NpcModel spot = Microbot.getRs2NpcCache().query()
                .withIds(FishingSpot.BARB_FISH.getIds())
                .nearestOnClientThread(FISHING_ANCHOR, SPOT_QUERY_RADIUS);
        if (spot == null) {
            fail("no barbarian fishing spot within " + SPOT_QUERY_RADIUS + " tiles of Otto's Grotto");
            return;
        }

        final LocalPoint local = Microbot.getClientThread().invoke(() -> spot.getLocalLocation());
        if (local == null) return;
        if (!Rs2Camera.isTileOnScreen(local)) {
            // Off the client thread on purpose: setAngle's key-hold wait no-ops on
            // the client thread, so the camera turn must run from here to take effect.
            Rs2Camera.turnTo(local);
            return; // retry next tick — not a failure
        }

        if (!clickFishingSpot(spot)) {
            spotClickRetries++;
            if (spotClickRetries >= SPOT_CLICK_RETRIES) {
                fail("fishing spot click produced no catch after " + SPOT_CLICK_RETRIES + " retries");
                return;
            }
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(
                    500 + spotClickRetries * 200, 1100 + spotClickRetries * 400, weatherMultiplier));
            return;
        }
        spotClickRetries = 0;
        consecutiveFailures = 0;
        Microbot.status = "FISHING — fishing";
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(235, 798, weatherMultiplier));
    }

    /**
     * Clicks the fishing spot through the client mouse ("Use-rod" entry) and waits
     * (bounded) for the session's first catch — a free inventory-slot count change
     * is the pass signal. Do NOT wait on animating/interacting here: the interaction
     * engages (and the spot keeps churning) seconds before any catch, so either
     * signal returns immediately and the caller re-clicks the spot every ~3 s until
     * fishing starts (observed spam clicks). The caller has already ensured the
     * spot is on screen.
     */
    private boolean clickFishingSpot(Rs2NpcModel spot) {
        if (spot == null || spot.getNpc() == null) return false;

        // Canvas size is read off the lambda: calling Client#getCanvasWidth/Height from
        // inside a clientThread.invoke lambda registers them with the ClientThreadGuardrailTest
        // inference and would flag unrelated pre-existing util/api callers (ammonite recipe).
        final int canvasWidth = Microbot.getClient().getCanvasWidth();
        final int canvasHeight = Microbot.getClient().getCanvasHeight();
        Rectangle clickbox = Microbot.getClientThread().invoke(() -> {
            Rectangle box = Rs2UiHelper.getActorClickbox(spot.getNpc());
            if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) return null;
            // Canvas-sized fallback rectangle = no real clickbox — never click it blindly.
            if (box.getWidth() >= canvasWidth - 2 || box.getHeight() >= canvasHeight - 2) return null;
            return box;
        });
        if (clickbox == null) return false;

        final int freeSlotsBefore = Rs2Inventory.emptySlotCount();
        Microbot.getMouse().click(clickbox);
        // The bound covers the whole start: walk to the spot + engage + first catch.
        // A timeout means the click did not produce a live session (caller retries).
        return sleepUntil(() -> Rs2Inventory.emptySlotCount() != freeSlotsBefore,
                (int) Rs2Random.logNormalBounded(16000, 30000, weatherMultiplier));
    }

    private void handleDropping(AScriptConfig config) {
        List<Rs2ItemModel> droppables = Rs2Inventory.items(BarbarianFishingScript::isDroppable)
                .collect(Collectors.toList());

        if (droppables.isEmpty()) {
            dropSessionActive = false;
            if (droppedInSession) {
                droppedInSession = false;
                consecutiveFailures = 0;
                postDropBreak(config);
            } else if (Rs2Inventory.isFull()) {
                // Full inventory with nothing droppable — loud stop instead of a silent spin.
                fail("inventory full but nothing droppable (unexpected items?)");
            }
            return;
        }

        Microbot.status = "DROPPING FISH";
        int before = countDroppables();
        int slice = Rs2Random.fancyNormalSample(DROP_SLICE_MIN, DROP_SLICE_MAX);
        int clicked = 0;
        for (Rs2ItemModel item : droppables) {
            if (clicked >= slice) break;
            Rectangle bounds = Rs2Inventory.itemBounds(item);
            if (bounds == null) continue;
            Microbot.getMouse().click(bounds);
            clicked++;
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(120, 320, weatherMultiplier));
        }

        if (clicked == 0) {
            fail("no clickable fish slots to drop");
            return;
        }
        if (!sleepUntil(() -> countDroppables() < before,
                (int) Rs2Random.logNormalBounded(1500, 3000, weatherMultiplier))) {
            fail("drop clicks did not land");
            return;
        }
        droppedInSession = true;
    }

    private void handleWalkToBank() {
        Microbot.status = "WALKING TO BANK";
        if (!bankTripNeeded()) { // supplies restored or config changed
            currentPhase = Phase.WALK_TO_SPOT;
            return;
        }
        if (isCloseToBank()) return; // orchestrator opens the bank and dispatches doBank next tick
        if (Rs2Player.isMoving()) return;
        if (!Rs2Walker.walkTo(BankLocation.BARBARIAN_OUTPOST.getWorldPoint(), BANK_CLOSE_DISTANCE)) {
            fail("could not reach the Barbarian Outpost bank");
        } else {
            consecutiveFailures = 0;
        }
    }

    private void handleWalkToSpot() {
        Microbot.status = "WALKING TO OTTO'S GROTTO";
        if (bankTripNeeded()) {
            currentPhase = Phase.WALK_TO_BANK;
            return;
        }
        if (isCloseToArea()) return;
        if (Rs2Player.isMoving()) return;
        if (!Rs2Walker.walkTo(FISHING_ANCHOR, WALK_TO_SPOT_DISTANCE)) {
            fail("could not reach the fishing area");
        } else {
            consecutiveFailures = 0;
        }
    }

    // ── Post-batch randomness layer ──────────────────────────────

    /**
     * Post-batch break — the Jewelry/Crafting randomness pattern: a short randomized
     * pause after every dropped batch, then the optional random AFK (log-normal,
     * weather-modulated, interruptible so blocking events still fire).
     */
    private void postDropBreak(AScriptConfig config) {
        AScriptSleep.sleepInterruptibly(
                Rs2Random.logNormalBounded(POST_DROP_PAUSE_MIN_MS, POST_DROP_PAUSE_MAX_MS, weatherMultiplier));
        if (config.barbarianFishingAfk() && System.currentTimeMillis() - lastAfkTime > AFK_MIN_GAP_MS) {
            int afkMs = Rs2Random.logNormalBounded(AFK_MIN_MS, AFK_MAX_MS, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    // ── Dragon harpoon special ───────────────────────────────────

    /**
     * Dragon harpoon special attack: one activation per full-energy cycle. The
     * activation is confirmed via {@link Rs2Combat#getSpecState()} before latching —
     * a dispatch that does not register is re-attempted (bounded) instead of
     * burning the cycle; the state resets once the spec is consumed (energy drops
     * below full) or the harpoon is unequipped.
     */
    private void maybeActivateHarpoonSpec(AScriptConfig config) {
        if (!config.barbarianFishingHarpoonSpec()) return;

        boolean wearingHarpoon = Rs2Equipment.isWearing(HARPOON_IDS);
        int energy = Rs2Combat.getSpecEnergy();

        if (!wearingHarpoon || energy < 1000) {
            harpoonSpecDone = false;
            harpoonSpecAttempts = 0;
            harpoonSpecDeadlineMs = 0L;
            return;
        }
        if (harpoonSpecDone || Rs2Combat.getSpecState()) return;

        long now = System.currentTimeMillis();
        if (harpoonSpecDeadlineMs == 0L) {
            harpoonSpecDeadlineMs = now + Rs2Random.logNormalBounded(5_000, 90_000, weatherMultiplier);
            return;
        }
        if (now < harpoonSpecDeadlineMs) return;

        if (Rs2Combat.setSpecState(true)
                && sleepUntil(Rs2Combat::getSpecState,
                        (int) Rs2Random.logNormalBounded(1500, 2500, weatherMultiplier))) {
            harpoonSpecDone = true;
            harpoonSpecDeadlineMs = 0L;
            return;
        }

        harpoonSpecAttempts++;
        log.debug("[BarbarianFishing] Harpoon spec activation not confirmed ({}/{})",
                harpoonSpecAttempts, HARPOON_SPEC_MAX_ATTEMPTS);
        if (harpoonSpecAttempts >= HARPOON_SPEC_MAX_ATTEMPTS) {
            harpoonSpecDone = true; // give up until the energy cycle changes (restart re-arms)
        } else {
            harpoonSpecDeadlineMs = now + Rs2Random.logNormalBounded(20_000, 45_000, weatherMultiplier);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private boolean bankTripNeeded() {
        return !Rs2Inventory.hasItem(ItemID.BARBARIAN_ROD) || !Rs2Inventory.hasItem(ItemID.FEATHER);
    }

    private boolean isCloseToArea() {
        WorldPoint location = Rs2Player.getWorldLocation();
        return location != null && location.distanceTo(FISHING_ANCHOR) <= FISH_AREA_DISTANCE;
    }

    private boolean isCloseToBank() {
        WorldPoint location = Rs2Player.getWorldLocation();
        return location != null
                && location.distanceTo(BankLocation.BARBARIAN_OUTPOST.getWorldPoint()) <= BANK_CLOSE_DISTANCE;
    }

    private static boolean isDroppable(Rs2ItemModel item) {
        if (item == null) return false;
        for (int id : DROPPABLE_IDS) {
            if (item.getId() == id) return true;
        }
        return false;
    }

    private int countDroppables() {
        int count = 0;
        for (int id : DROPPABLE_IDS) {
            count += Rs2Inventory.count(id);
        }
        return count;
    }

    /**
     * Register a failed attempt: log it, back off, and stop the module (with a Discord
     * notification) after {@link #MAX_ACTION_FAILURES} in a row — a missed click or a
     * blocked spot must never spin the loop forever.
     */
    private void fail(String reason) {
        consecutiveFailures++;
        log.warn("[BarbarianFishing] Action failed ({}/{}): {}", consecutiveFailures, MAX_ACTION_FAILURES, reason);
        if (consecutiveFailures >= MAX_ACTION_FAILURES) {
            exitRequested = true;
            Microbot.status = "STOPPED — " + reason;
            AScriptNotify.notify("aScript Stopped — Barbarian Fishing", reason);
            Microbot.getConfigManager().setConfiguration(
                    AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }
}
