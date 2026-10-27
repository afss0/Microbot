package net.runelite.client.plugins.microbot.ascript.motherloadmine;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptDrop;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptPick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.api.Perspective;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Motherload Mine sub-script — stateless helper called by AScript's MINING / BANKING states.
 * <p>
 * Adapted from the Hub's MotherloadMineScript.
 * The entire mining loop is a single Phase.MINING activity.
 */
@Slf4j
public class MotherloadMineScript implements AModule {

    private Phase currentPhase = Phase.NONE;

    // ── Constants ──────────────────────────────────────────────

    /** Gem item IDs that may appear while mining (to drop if configured). */
    private static final List<Integer> GEM_IDS = Arrays.asList(
            ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY,
            ItemID.UNCUT_DIAMOND, ItemID.UNCUT_DRAGONSTONE
    );

    /** Ore item IDs that come from the sack (nuggets excluded — they're currency, kept in inventory). */
    private static final Set<Integer> ORE_IDS = new HashSet<>(Arrays.asList(
            ItemID.RUNITE_ORE, ItemID.ADAMANTITE_ORE, ItemID.MITHRIL_ORE,
            ItemID.GOLD_ORE, ItemID.COAL
    ));

    /** Ore vein wall object IDs. */
    private static final Set<Integer> MINE_SPOT_IDS = Set.of(
            ObjectID.MOTHERLODE_ORE_SINGLE, ObjectID.MOTHERLODE_ORE_LEFT,
            ObjectID.MOTHERLODE_ORE_MIDDLE, ObjectID.MOTHERLODE_ORE_RIGHT
    );

    private static final int SACK_NORMAL_SIZE = 108;
    private static final int SACK_UPGRADED_SIZE = 189;
    private static final int UPPER_FLOOR_HEIGHT = -490;

    /** Sack is considered "full" when within this many of capacity. */
    private static final int SACK_FULL_THRESHOLD = 55;

    // ── Mutable state ──────────────────────────────────────────

    private boolean exitRequested = false;
    private int consecutiveMineFailures = 0;
    private static final int MAX_CONSECUTIVE_MINE_FAILURES = 10;
    private MLMStatus currentStatus = MLMStatus.IDLE;
    private boolean shouldEmptySack = false;
    private boolean shouldRepairWaterwheel = false;
    private boolean pickedUpHammer = false;
    /** Idle detection for mining — tracks when animation stopped. */
    private long idleSince = 0;
    /** When the last vein search came up empty (0 = a vein was found); bounded so a dead
     *  spot stops the module instead of logging "No vein found" forever. */
    private long noVeinSinceMs = 0L;
    private static final long NO_VEIN_STOP_MS = 15_000;
    private int idleThreshold = 0;
    /** Set once per doMine() call; used for weather-modulated timing. */
    private double weatherMultiplier = 1.0;
    /** Timestamp of the last random AFK — minimum 5 s between AFKs. */
    private long lastAfkTime = 0L;
    /** Pickaxe-recovery walk: last observed position and when it last changed. */
    private WorldPoint lastBankWalkPos = null;
    private long lastBankWalkAt = 0L;
    private static final long BANK_WALK_STAGNATION_MS = 120_000L;

    // ── Phase enum ─────────────────────────────────────────────

    public enum Phase { NONE, MINING }

    /** Reset exit flag and failure counter when script is re-enabled. Call from AScript on state enter. */
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveMineFailures = 0;
        currentStatus = MLMStatus.IDLE;
        shouldEmptySack = false;
        shouldRepairWaterwheel = false;
        pickedUpHammer = false;
        lastBankWalkPos = null;
        lastBankWalkAt = 0L;
        idleSince = 0;
        noVeinSinceMs = 0L;
        idleThreshold = 0;
        lastAfkTime = 0L;
    }

    // ── Phase resolution ───────────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.MOTHERLOAD_MINE) { this.currentPhase = Phase.NONE; return; }
        this.currentPhase = Phase.MINING;
    }

    // ── Selection validation ───────────────────────────────────

    @Override
    public String runKey(AScriptConfig config) {
        // A different mining area is a different trip scope.
        return "MLM:" + config.mlmMiningArea();
    }

    @Override
    public String validateSelection(AScriptConfig config) {
        // Motherload Mine selected but not active → visible error
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.MOTHERLOAD_MINE)
            return "motherload mine selected but inactive";
        return null;
    }

    // ── Bank check ─────────────────────────────────────────────

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        // A bank trip is needed whenever no usable pickaxe is held, even if the bank mirror
        // says nothing: the mirror is refreshed only by an open bank, so reading it here can
        // turn a first-run trip into a false "no pickaxe" stop. readyForBank()/doAction() own
        // the walk; the missing-materials verdict owns "the bank really has none".
        return !Pickaxe.hasAnyPickaxe();
    }

    /** The bank phase gate: the bank must be loaded and on camera before openBank runs. */
    @Override
    public boolean readyForBank(AScriptConfig config) {
        return AScriptBank.bankVisible();
    }

    // ── Bank-level check (both bank AND inventory missing) ─────

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        // A noted pickaxe is recoverable by depositing it at the bank; it is not a missing tool.
        return !Pickaxe.hasAnyPickaxe()
                && !Pickaxe.hasAnyPickaxeInBank()
                && !Pickaxe.hasNotedPickaxeInInventory();
    }

    // ── Missing-materials description ──────────────────────────

    @Override
    public String describeMissing(AScriptConfig config) {
        if (currentPhase == Phase.NONE) return "";
        return "pickaxe (not in bank)";
    }

    // ── Banking actions ────────────────────────────────────────

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING";

        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) return false;
            if (!sleepUntil(Rs2Bank::isOpen, Rs2Random.logNormalBounded(5000, 10000))) return false;
        }

        // Deposit all inventory items (except pickaxe if locked)
        if (!AScriptBank.depositAll()) {
            log.warn("[MotherloadMineScript] AScriptBank.depositAll failed");
            return false;
        }

        // Wait for the deposit to land. "Empty" ignores locked slots (pickaxe) and the XP
        // lamp: the lamp cannot be deposited and must not make this wait time out every cycle.
        sleepUntil(AScriptBank::isEmptyExceptLocksAndLamp, 5000);

        // Withdraw best pickaxe. Use the verified amount path, not Rs2Bank.withdrawItem/
        // withdrawOne: the latter maps the bank quantity setting to menu op 1/2 (Withdraw-5
        // for every setting except "1") and was a dead dispatch on at least one live client.
        Pickaxe best = Pickaxe.getBestPickaxeFromBank();
        if (best == null) {
            log.warn("[MotherloadMineScript] no usable pickaxe in the bank snapshot");
            return false;
        }

        if (!AScriptBank.withdrawVerified(String.valueOf(best.getItemId()), 1)) {
            log.warn("[MotherloadMineScript] Failed to withdraw pickaxe: {}", best.getItemName());
            return false;
        }

        return true;
    }

    // ── Mining actions ────────────────────────────────────────

    /**
     * Move toward a bank that can supply a pickaxe. Returns true while the trip is in
     * progress (the module should return and let the next tick continue); false only when no
     * reachable bank exists, so the caller stops with a missing-tool message.
     * <p>
     * A bank object already loaded in the scene is TURNED TO, not walked at; the walker only
     * runs while no bank is loaded. Progress is measured as "the player moved", never as the
     * distance shrinking (a teleport may legitimately move farther away first).
     */
    private boolean approachBankForPickaxe() {
        if (AScriptBank.bankVisible()) {
            return true; // the orchestrator's bank phase opens it on the next tick
        }
        TileObject bankObject = AScriptBank.findBankObject();
        if (bankObject != null) {
            Microbot.status = "MLM — turning to bank";
            Rs2Camera.turnTo(bankObject);
            return true;
        }
        BankLocation nearest = Rs2Bank.getNearestBank();
        if (nearest == null) {
            return false;
        }
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (lastBankWalkPos == null || !here.equals(lastBankWalkPos)) {
            lastBankWalkPos = here;
            lastBankWalkAt = now;
        } else if (now - lastBankWalkAt > BANK_WALK_STAGNATION_MS) {
            log.warn("[MotherloadMineScript] bank walk made no progress for {}s",
                    BANK_WALK_STAGNATION_MS / 1000);
            return false;
        }
        Microbot.status = "MLM — walking to bank";
        if (!Rs2Player.isMoving()) {
            Rs2Walker.walkTo(nearest.getWorldPoint(), 8);
        }
        return true;
    }

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return;
        if (exitRequested) return;

        // Ensure fresh weather data (cached for 30 min, safe to call every tick)
        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // No usable pickaxe: a bank trip must fetch one. readyForBank() gates the orchestrator
        // on a clickable bank, so while the bank is not in reach this method owns the walk —
        // bounded; a stalled recovery stops loudly instead of failing at the mine forever.
        if (!Pickaxe.hasAnyPickaxe()) {
            if (approachBankForPickaxe()) {
                return;
            }
            log.warn("[MotherloadMineScript] No pickaxe and no reachable bank");
            exitRequested = true;
            AScriptNotify.notify("aScript Stopped — Motherload Mine",
                    "no pickaxe (not worn, not in the inventory or noted, not in the bank)");
            Microbot.getConfigManager().setConfiguration(
                    AScriptConfig.GROUP, "scriptSelection", ScriptType.IDLE);
            return;
        }

        boolean success = true;
        boolean wasEmptyingSack = shouldEmptySack;

        // Drop gems if configured
        if (config.mlmDropGems() && hasGemsInInventory()) {
            if (!dropGems()) {
                success = false;
            }
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
        }

        // Deposit hopper: if inventory full of paydirt
        else {
            int paydirtCount = Rs2Inventory.count(ItemID.PAYDIRT);
            if (paydirtCount > 0 && Rs2Inventory.isFull()) {
                // Fix waterwheel before depositing — only when inventory is full
                // and ready to deposit, not mid-mining
                if (shouldRepairWaterwheel || countBrokenStruts() > 1) {
                    shouldRepairWaterwheel = true;
                    if (fixWaterwheel()) {
                        shouldRepairWaterwheel = false;
                    } else {
                        success = false;
                    }
                } else if (!depositHopper(config)) {
                    success = false;
                }
            }
            // Empty sack: if sack has contents and no ore in inventory
            else if (shouldEmptySack || hasOreInInventory() || getSackCount() > 0) {
                shouldEmptySack = true;
                if (!emptySack(config)) {
                    success = false;
                }
            }
            // Full inventory with nothing this module can consume — checked after the gem
            // drop and the paydirt/sack branches: mining cannot start, and mineVeins used
            // to "confirm" instantly because its start check accepted isFull(). Failing
            // here is counted by the tail below and stops after MAX_CONSECUTIVE_MINE_FAILURES.
            else if (Rs2Inventory.isFull()) {
                log.warn("[MotherloadMineScript] inventory is full but holds no paydirt or ore");
                success = false;
            }
            // Mine veins
            else {
                if (!mineVeins(config)) {
                    success = false;
                }
            }
        }

        // A trip ends when the sack-empty cycle completes (shouldEmptySack falls back to
        // false) — run the post-trip randomness layer once, with no bank involved (the
        // trip ends at the deposit box).
        if (wasEmptyingSack && !shouldEmptySack && success) {
            postTripBreak(config);
        }

        // Track consecutive failures — exit after persistent failures
        if (success) {
            consecutiveMineFailures = 0;
        } else {
            consecutiveMineFailures++;
            if (consecutiveMineFailures >= MAX_CONSECUTIVE_MINE_FAILURES) {
                exitRequested = true;
                Microbot.status = "STOPPED — persistent mine failures";
                log.warn("[MotherloadMineScript] {} consecutive failures, stopping", consecutiveMineFailures);
                AScriptNotify.notify("aScript Stopped", "Motherload Mine: " + consecutiveMineFailures
                        + " consecutive failures");
                Microbot.getConfigManager().setConfiguration(
                        AScriptConfig.GROUP, "scriptSelection", ScriptType.IDLE);
            }
        }
    }

    // ── Mining sub-routines ────────────────────────────────────

    /**
     * Post-trip break — the Jewelry/Crafting randomness layer: a short randomized pause
     * after every completed mining trip (sack emptied, ore deposited), then the optional
     * random AFK (log-normal, weather-modulated, interruptible so blocking events still
     * fire). Runs with no bank involved — the trip ends at the deposit box.
     */
    private void postTripBreak(AScriptConfig config) {
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.mlmAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3_000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    private boolean mineVeins(AScriptConfig config) {
        Microbot.status = "MINING";

        // Still animating or moving — keep waiting
        if (Rs2Player.isAnimating() || Rs2Player.isMoving()) {
            idleSince = 0;
            return true;
        }

        // Just became idle — start idle timer
        if (idleSince == 0) {
            idleSince = System.currentTimeMillis();
            idleThreshold = Math.max(2000, (int) Rs2Random.randomGaussian(3000, 600));
            return true;
        }

        // Idle timer hasn't expired yet — wait
        if (System.currentTimeMillis() - idleSince < idleThreshold) return true;

        // Truly idle — find next vein
        idleSince = 0;

        TileObject vein = findNearestVein(config);
        if (vein == null) {
            if (noVeinSinceMs == 0L) noVeinSinceMs = System.currentTimeMillis();
            if (System.currentTimeMillis() - noVeinSinceMs > NO_VEIN_STOP_MS) {
                log.warn("[MotherloadMineScript] no vein found for {} ms", NO_VEIN_STOP_MS);
                noVeinSinceMs = 0L;
                return false; // counted by the caller; stops after MAX consecutive failures
            }
            log.debug("[MotherloadMineScript] No vein found, waiting...");
            sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
            return true; // no vein yet = wait state, bounded by the watchdog above
        }
        noVeinSinceMs = 0L;

        // Client-mouse object recipe (AScriptClick — the aScript mouse refactor): camera
        // first, then the vein's real clickbox through the client mouse; the canvas-sized
        // fallback of a vein that has not rendered is never clicked. The recipe only
        // reports that the click was issued — confirming the mining animation below stays
        // this method's accounting.
        if (!clickObject(vein).clicked()) {
            log.debug("[MotherloadMineScript] Failed to interact with vein");
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
            return false;
        }

        // Wait for the mining animation. isFull() is NOT part of the confirmation: a full
        // inventory makes it true instantly, so a click that never started mining "landed"
        // and the module spun on a full inventory forever.
        if (!sleepUntil(Rs2Player::isAnimating, 5000)) {
            log.warn("[MotherloadMineScript] vein click did not start mining");
            return false;
        }
        return true;
    }

    private TileObject findNearestVein(AScriptConfig config) {
        MLMMiningSpot spot = resolveMiningSpot(config);
        if (spot != null) {
            // Try to find a vein near the configured spot
            for (WorldPoint wp : spot.getWorldPoints()) {
                TileObject vein = findVeinNear(wp);
                if (vein != null) return vein;
            }
        }
        // Fallback: find any nearby vein
        for (int id : MINE_SPOT_IDS) {
            TileObject vein = Rs2GameObject.findObjectById(id);
            if (vein != null) return vein;
        }
        return null;
    }

    private TileObject findVeinNear(WorldPoint target) {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null) return null;
        if (playerLoc.distanceTo(target) > 5) {
            // Walk to the mining area — walker handles rockfall pathfinding
            net.runelite.client.plugins.microbot.util.walker.Rs2Walker.walkTo(target, 10);
        }

        for (int id : MINE_SPOT_IDS) {
            TileObject vein = Rs2GameObject.findObjectById(id);
            if (vein != null && vein.getWorldLocation().distanceTo(target) <= 5) {
                return vein;
            }
        }
        return null;
    }

    private MLMMiningSpot resolveMiningSpot(AScriptConfig config) {
        MLMMiningSpotList selection = config.mlmMiningArea();
        if (selection == null || selection == MLMMiningSpotList.ANY) return null;
        switch (selection) {
            case WEST_LOWER: return MLMMiningSpot.WEST_LOWER;
            case WEST_MID: return MLMMiningSpot.WEST_MID;
            case SOUTH_EAST: return MLMMiningSpot.SOUTH_EAST;
            case SOUTH_WEST: return MLMMiningSpot.SOUTH_WEST;
            case WEST_UPPER: return MLMMiningSpot.WEST_UPPER;
            case EAST_UPPER: return MLMMiningSpot.EAST_UPPER;
            default: return null;
        }
    }

    // ── Hopper deposit ─────────────────────────────────────────

    private boolean depositHopper(AScriptConfig config) {
        Microbot.status = "DEPOSITING HOPPER";

        // Determine if we should use the upper hopper
        boolean isUpstairs = isUpstairs();
        boolean useUpperHopper = config.mlmUseUpstairsHopper() && isUpstairs;

        if (!useUpperHopper && isUpstairs) {
            // Go downstairs first
            if (!climbDown()) {
                return false;
            }
        }

        TileObject hopper = Rs2GameObject.findObjectById(ObjectID.MOTHERLODE_HOPPER);
        if (hopper == null) {
            log.warn("[MotherloadMineScript] Hopper not found");
            sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
            return false;
        }

        int paydirtBefore = Rs2Inventory.count(ItemID.PAYDIRT);
        // See mineVeins: the recipe issues the click, the paydirt-count wait below is the
        // effect verification. The hopper's "Deposit" is its left-click default, which is
        // all a physical click can dispatch.
        if (!clickObject(hopper).clicked()) {
            log.warn("[MotherloadMineScript] Failed to interact with hopper");
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
            return false;
        }

        // Wait for paydirt to be deposited
        boolean deposited = sleepUntil(() -> Rs2Inventory.count(ItemID.PAYDIRT) < paydirtBefore, 5000);

        // Check if sack should be emptied soon
        int sackCount = getSackCount();
        int sackCapacity = getSackCapacity();
        if (sackCount >= sackCapacity - SACK_FULL_THRESHOLD) {
            shouldEmptySack = true;
        }

        sleep(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
        return deposited;
    }

    // ── Sack empty ─────────────────────────────────────────────

    private boolean emptySack(AScriptConfig config) {
        Microbot.status = "EMPTYING SACK";

        // Ensure we're on the lower floor
        if (isUpstairs()) {
            if (!climbDown()) {
                return false;
            }
        }

        // If we have ore in inventory, deposit it in the deposit box
        if (hasOreInInventory()) {
            if (!depositOreInBox()) {
                return false;
            }
            if (Rs2Inventory.isEmpty() || !hasOreInInventory()) {
                shouldEmptySack = false;
            }
            return true;
        }

        // If sack has contents, click to collect
        int sackCount = getSackCount();
        if (sackCount > 0) {
            TileObject sack = Rs2GameObject.findObjectById(ObjectID.MOTHERLODE_SACK);
            if (sack == null) {
                log.warn("[MotherloadMineScript] Sack not found");
                sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
                return false;
            }

            // The sack's own left-click default, as a client-mouse click (see mineVeins)
            if (!clickObject(sack).clicked()) {
                sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
                return false;
            }

            // Wait for ore to appear in inventory
            boolean gotOre = sleepUntil(() -> hasOreInInventory(), 5000);

            sleep(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
            return gotOre;
        }

        // Sack is empty and no ore — done
        shouldEmptySack = false;
        return true;
    }

    private boolean depositOreInBox() {
        if (!Rs2DepositBox.isOpen()) {
            if (!Rs2DepositBox.openDepositBox()) {
                log.warn("[MotherloadMineScript] Failed to open deposit box");
                sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
                return false;
            }
        }

        // Deposit all except tools (pickaxe + hammer)
        if (!depositExceptTools()) {
            log.warn("[MotherloadMineScript] depositExceptTools failed");
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
            return false;
        }

        // Only close the deposit box if we actually deposited something.
        // If deposit failed (grid item silently no-op), keep the box open
        // so the next tick retries without open/close/open overhead.
        boolean deposited = sleepUntil(() -> !hasOreInInventory(), 5000);
        if (deposited && Rs2DepositBox.isOpen()) {
            Rs2DepositBox.closeDepositBox();
        }

        sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
        return deposited;
    }

    // ── Waterwheel repair ──────────────────────────────────────

    private boolean fixWaterwheel() {
        Microbot.status = "FIXING WATERWHEEL";

        int brokenCount = countBrokenStruts();
        if (brokenCount <= 1) {
            shouldRepairWaterwheel = false;
            return true;
        }

        // Find a hammer — check equipped (Imcando), inventory, then search crate
        if (!Rs2Equipment.isWearing("hammer") && !AScriptBank.hasUnnotedItem("hammer")) {
            // Search crate at 3752,5674
            TileObject crate = Rs2GameObject.findObjectByLocation(new WorldPoint(3752, 5674, 0));
            if (crate != null) {
                // As elsewhere: the recipe only reports that the click was issued, so the
                // hammer check below is what verifies the search actually happened.
                if (clickObject(crate).clicked()) {
                    sleepUntil(() -> AScriptBank.hasUnnotedItem("hammer"), 5000);
                    pickedUpHammer = true;
                }
            }

            if (!AScriptBank.hasUnnotedItem("hammer")) {
                if (AScriptBank.hasNotedItem("hammer")) {
                    Microbot.status = "HAMMER IS NOTED — bank it to un-note";
                    log.warn("[MotherloadMineScript] hammer is a banknote — bank it to un-note before repairing");
                } else {
                    log.warn("[MotherloadMineScript] No hammer found");
                }
                sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
                return false;
            }
        }

        // Click broken strut
        TileObject strut = Rs2GameObject.findObjectById(ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN);
        if (strut == null) {
            log.warn("[MotherloadMineScript] No broken strut found");
            shouldRepairWaterwheel = false;
            return true;
        }

        // Walk to strut if too far to interact
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc != null && playerLoc.distanceTo(strut.getWorldLocation()) > 10) {
            Rs2Walker.walkTo(strut.getWorldLocation(), 5);
            sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(strut.getWorldLocation()) <= 10,
                    Rs2Random.logNormalBounded(5000, 10000, weatherMultiplier));
        }

        // "Repair" is the broken strut's left-click default (see mineVeins); the strut
        // count below is what confirms the repair landed.
        if (!clickObject(strut).clicked()) {
            log.warn("[MotherloadMineScript] Failed to interact with broken strut");
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
            return false;
        }

        // Wait for animation to end, then wait for the strut to actually be fixed
        // (game state update may lag behind animation). The result is checked: both waits
        // used to be discarded, so a repair that never landed still returned true.
        sleepUntil(() -> !Rs2Player.isAnimating(), 10000);
        if (!sleepUntil(() -> countBrokenStruts() < brokenCount,
                Rs2Random.logNormalBounded(12000, 25000, weatherMultiplier))) {
            log.warn("[MotherloadMineScript] waterwheel repair did not land");
            return false;
        }

        // Drop hammer if we picked it up from the crate (not if equipped Imcando).
        // dropAtSlot installs the hammer's Drop entry as the left-click entry: a plain click
        // would "Use" it, and Rs2Inventory.drop() is a menu-entry injection.
        if (pickedUpHammer && !Rs2Equipment.isWearing("hammer") && AScriptBank.hasUnnotedItem("hammer")) {
            Rs2ItemModel hammer = AScriptPick.pick("hammer");
            if (hammer != null && !AScriptClick.dropAtSlot(hammer.getSlot(), hammer.getId())) {
                log.warn("[MotherloadMineScript] hammer drop did not land");
            }
            pickedUpHammer = false;
        }

        // Check if more struts are broken
        if (countBrokenStruts() <= 1) {
            shouldRepairWaterwheel = false;
        }

        sleep(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
        return true;
    }

    private int countBrokenStruts() {
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null) return 0;
        return (int) Rs2GameObject.getAll(
                (TileObject obj) -> obj.getId() == ObjectID.MOTHERLODE_WHEEL_STRUT_BROKEN
                        && obj.getWorldLocation().distanceTo(playerLoc) <= 15
        ).stream().count();
    }

    // ── Gem dropping ───────────────────────────────────────────

    private boolean hasGemsInInventory() {
        for (int gemId : GEM_IDS) {
            if (Rs2Inventory.hasItem(gemId)) return true;
        }
        return false;
    }

    private boolean dropGems() {
        // Small set: one full pass through the shared dropper (it still paces every click and
        // verifies the count; the slicer is reserved for bulk inventories).
        AScriptDrop.Result result = AScriptDrop.dropAll(
                item -> GEM_IDS.contains(item.getId()), "DROPPING GEMS", weatherMultiplier);
        if (result.getOutcome() == AScriptDrop.Outcome.NO_PROGRESS) {
            log.warn("[MotherloadMineScript] gem drop did not land");
            return false;
        }
        return true;
    }

    // ── Clicks ────────────────────────────────────────────────

    /**
     * The shared house object recipe ({@link AScriptClick#clickObject}) with the camera turn
     * absorbed inside the call: the recipe turns toward an off-camera target and returns
     * without clicking ("retry on a later tick"), but these boolean sub-routines feed the
     * module's consecutive-failure counter directly — so wait one frame for the turned view
     * to render and resolve the click again here, as the pre-recipe
     * {@code Rs2GameObject.interact} did (turn, then act). What is still not clicked after
     * that is a render race and goes to the caller's own accounting, which is where the
     * recipe leaves it.
     * <p>
     * A physical click dispatches the target's left-click default only — every object this
     * is called on is clicked for its own default (vein "Mine", hopper "Deposit", sack,
     * crate "Search", strut "Repair", ladder "Climb-down").
     *
     * @return the recipe outcome — {@link AScriptClick.Target#clicked()} is the click issued
     */
    private AScriptClick.Target clickObject(TileObject object) {
        AScriptClick.Target target = AScriptClick.clickObject(object);
        if (!target.cameraTurned()) {
            return target;
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(200, 350));
        return AScriptClick.clickObject(object);
    }

    // ── Utility methods ────────────────────────────────────────

    private boolean hasOreInInventory() {
        for (int oreId : ORE_IDS) {
            if (Rs2Inventory.hasItem(oreId)) return true;
        }
        return false;
    }

    /** Check if a hammer is available — equipped (Imcando) or in inventory. */
    private boolean hasHammer() {
        return Rs2Equipment.isWearing("hammer") || AScriptBank.hasUnnotedItem("hammer");
    }

    /** Deposit everything except pickaxe and hammer into the deposit box. */
    private boolean depositExceptTools() {
        List<Integer> exclude = new ArrayList<>();
        Pickaxe best = Pickaxe.getBestPickaxe();
        if (best != null) exclude.add(best.getItemId());
        if (hasHammer()) exclude.add(ItemID.HAMMER);

        if (!exclude.isEmpty()) {
            return Rs2DepositBox.depositAllExcept(exclude.stream().mapToInt(Integer::intValue).boxed().toArray(Integer[]::new));
        } else {
            Rs2DepositBox.depositAll();
            return true; // depositAll is void; assume success
        }
    }

    private boolean isUpstairs() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            WorldPoint playerLoc = Rs2Player.getWorldLocation();
            if (playerLoc == null) return false;
            LocalPoint localPoint = LocalPoint.fromWorld(Microbot.getClient(), playerLoc);
            if (localPoint == null) return false;
            int tileHeight = Perspective.getTileHeight(Microbot.getClient(), localPoint, playerLoc.getPlane());
            return tileHeight < UPPER_FLOOR_HEIGHT;
        }).orElse(false);
    }

    private boolean climbDown() {
        TileObject ladder = Rs2GameObject.findObjectById(ObjectID.MOTHERLODE_LADDER_TOP);
        if (ladder == null) {
            ladder = Rs2GameObject.findObjectById(ObjectID.MOTHERLODE_LADDER_BOTTOM);
        }
        if (ladder != null) {
            // "Climb-down" is the down-ladder's left-click default (see mineVeins); the
            // floor check below is what confirms the descent.
            if (!clickObject(ladder).clicked()) {
                sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
                return false;
            }
            if (!sleepUntil(() -> !isUpstairs(), 10000)) {
                return false;
            }
            sleep(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
            return true;
        }
        log.warn("[MotherloadMineScript] Ladder not found");
        return false;
    }

    private int getSackCount() {
        return Microbot.getVarbitValue(VarbitID.MOTHERLODE_SACK_TRANSMIT);
    }

    private int getSackCapacity() {
        boolean upgraded = Microbot.getVarbitValue(VarbitID.MOTHERLODE_BIGGERSACK) == 1;
        return upgraded ? SACK_UPGRADED_SIZE : SACK_NORMAL_SIZE;
    }

    @Override
    public boolean isActive() { return currentPhase != Phase.NONE; }
}
