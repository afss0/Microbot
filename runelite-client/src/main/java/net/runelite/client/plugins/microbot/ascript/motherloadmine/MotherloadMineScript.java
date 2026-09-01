package net.runelite.client.plugins.microbot.ascript.motherloadmine;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.api.Perspective;

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
public class MotherloadMineScript {

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
    private static final int SACK_FULL_THRESHOLD = 10;

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
    private int idleThreshold = 0;
    /** Set once per doMine() call; used for weather-modulated timing. */
    private double weatherMultiplier = 1.0;

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
        idleSince = 0;
        idleThreshold = 0;
    }

    // ── Phase resolution ───────────────────────────────────────

    public Phase resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.MOTHERLOAD_MINE) return Phase.NONE;
        return Phase.MINING;
    }

    // ── Selection validation ───────────────────────────────────

    public String validateSelection(AScriptConfig config, Phase phase) {
        // Motherload Mine selected but not active → visible error
        if (phase == Phase.NONE && config.scriptSelection() == ScriptType.MOTHERLOAD_MINE)
            return "motherload mine selected but inactive";
        return null;
    }

    // ── Bank check ─────────────────────────────────────────────

    public boolean needsBank(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        return !Pickaxe.hasAnyPickaxe();
    }

    // ── Bank-level check (both bank AND inventory missing) ─────

    public boolean isBankMissingMaterials(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config, phase)) return false;
        return !Pickaxe.hasAnyPickaxe() && !Pickaxe.hasAnyPickaxeInBank();
    }

    // ── Missing-materials description ──────────────────────────

    public String describeMissing(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE) return "";
        return "pickaxe (not in bank)";
    }

    // ── Banking actions ────────────────────────────────────────

    public boolean doBank(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING";

        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            if (!Rs2Bank.isOpen()) return false;
        }

        // Deposit all inventory items (except pickaxe if locked)
        if (!Rs2Bank.depositAll()) {
            log.warn("[MotherloadMineScript] depositAll failed");
            return false;
        }

        sleepUntil(() -> Rs2Inventory.isEmpty(), 5000);

        // Withdraw best pickaxe
        Pickaxe best = Pickaxe.getBestPickaxeFromBank();
        if (best == null) {
            AScriptNotify.notify("Banking Failed", "No pickaxe in bank");
            return false;
        }

        if (!Rs2Bank.withdrawItem(best.getItemId())) {
            log.warn("[MotherloadMineScript] Failed to withdraw pickaxe: {}", best.getItemName());
            return false;
        }

        if (!sleepUntil(() -> Rs2Inventory.hasItem(best.getItemId()), 3000)) {
            AScriptNotify.notify("Banking Failed", "Pickaxe withdraw did not land in inventory");
            return false;
        }

        return true;
    }

    // ── Mining actions ────────────────────────────────────────

    public boolean doMine(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn()) return false;
        if (exitRequested) return false;

        // Ensure fresh weather data (cached for 30 min, safe to call every tick)
        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // Ensure we have a pickaxe
        if (!Pickaxe.hasAnyPickaxe()) {
            log.warn("[MotherloadMineScript] No pickaxe, need bank");
            exitRequested = true;
            AScriptNotify.notify("aScript Stopped", "Motherload Mine: no pickaxe");
            Microbot.getConfigManager().setConfiguration(
                    AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            return false;
        }

        boolean success = true;

        // Check for broken waterwheel first
        if (shouldRepairWaterwheel || countBrokenStruts() > 1) {
            shouldRepairWaterwheel = true;
            if (fixWaterwheel()) {
                shouldRepairWaterwheel = false;
            } else {
                success = false;
            }
        }

        // Drop gems if configured
        else if (config.mlmDropGems() && hasGemsInInventory()) {
            dropGems();
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
        }

        // Deposit hopper: if inventory full of paydirt
        else {
            int paydirtCount = Rs2Inventory.count(ItemID.PAYDIRT);
            if (paydirtCount > 0 && Rs2Inventory.isFull()) {
                depositHopper(config);
            }
            // Empty sack: if sack has contents and no ore in inventory
            else if (shouldEmptySack || hasOreInInventory() || getSackCount() > 0) {
                shouldEmptySack = true;
                emptySack(config);
            }
            // Mine veins
            else {
                mineVeins(config);
            }
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
                        AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            }
        }

        return success;
    }

    // ── Mining sub-routines ────────────────────────────────────

    private void mineVeins(AScriptConfig config) {
        Microbot.status = "MINING";

        // Still animating or moving — keep waiting
        if (Rs2Player.isAnimating() || Rs2Player.isMoving()) {
            idleSince = 0;
            return;
        }

        // Just became idle — start idle timer
        if (idleSince == 0) {
            idleSince = System.currentTimeMillis();
            idleThreshold = Math.max(2000, (int) Rs2Random.randomGaussian(3000, 600));
            return;
        }

        // Idle timer hasn't expired yet — wait
        if (System.currentTimeMillis() - idleSince < idleThreshold) return;

        // Truly idle — find next vein
        idleSince = 0;

        TileObject vein = findNearestVein(config);
        if (vein == null) {
            log.debug("[MotherloadMineScript] No vein found, waiting...");
            sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
            return;
        }

        if (!Rs2GameObject.interact(vein, "Mine")) {
            log.debug("[MotherloadMineScript] Failed to interact with vein");
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
            return;
        }

        // Wait for animation to start
        sleepUntil(() -> Rs2Player.isAnimating() || Rs2Inventory.isFull(), 5000);
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

    private void depositHopper(AScriptConfig config) {
        Microbot.status = "DEPOSITING HOPPER";

        // Determine if we should use the upper hopper
        boolean isUpstairs = isUpstairs();
        boolean useUpperHopper = config.mlmUseUpstairsHopper() && isUpstairs;

        if (!useUpperHopper && isUpstairs) {
            // Go downstairs first
            climbDown();
        }

        TileObject hopper = Rs2GameObject.findObjectById(ObjectID.MOTHERLODE_HOPPER);
        if (hopper == null) {
            log.warn("[MotherloadMineScript] Hopper not found");
            sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
            return;
        }

        int paydirtBefore = Rs2Inventory.count(ItemID.PAYDIRT);
        if (!Rs2GameObject.interact(hopper, "Deposit")) {
            log.warn("[MotherloadMineScript] Failed to interact with hopper");
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
            return;
        }

        // Wait for paydirt to be deposited
        sleepUntil(() -> Rs2Inventory.count(ItemID.PAYDIRT) < paydirtBefore, 5000);

        // Check if sack should be emptied soon
        int sackCount = getSackCount();
        int sackCapacity = getSackCapacity();
        if (sackCount >= sackCapacity - SACK_FULL_THRESHOLD) {
            shouldEmptySack = true;
        }

        sleep(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
    }

    // ── Sack empty ─────────────────────────────────────────────

    private void emptySack(AScriptConfig config) {
        Microbot.status = "EMPTYING SACK";

        // Ensure we're on the lower floor
        if (isUpstairs()) {
            climbDown();
        }

        // If we have ore in inventory, deposit it in the deposit box
        if (hasOreInInventory()) {
            depositOreInBox();
            if (Rs2Inventory.isEmpty() || !hasOreInInventory()) {
                shouldEmptySack = false;
            }
            return;
        }

        // If sack has contents, click to collect
        int sackCount = getSackCount();
        if (sackCount > 0) {
            TileObject sack = Rs2GameObject.findObjectById(ObjectID.MOTHERLODE_SACK);
            if (sack == null) {
                log.warn("[MotherloadMineScript] Sack not found");
                sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
                return;
            }

            // Default left-click (matches Hub's query().interact pattern)
            Rs2GameObject.interact(sack);

            // Wait for ore to appear in inventory
            sleepUntil(() -> hasOreInInventory(), 5000);

            sleep(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
            return;
        }

        // Sack is empty and no ore — done
        shouldEmptySack = false;
    }

    private void depositOreInBox() {
        if (!Rs2DepositBox.isOpen()) {
            if (!Rs2DepositBox.openDepositBox()) {
                log.warn("[MotherloadMineScript] Failed to open deposit box");
                sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
                return;
            }
        }

        // Deposit all except tools (pickaxe + hammer)
        depositExceptTools();

        // Only close the deposit box if we actually deposited something.
        // If deposit failed (grid item silently no-op), keep the box open
        // so the next tick retries without open/close/open overhead.
        boolean deposited = sleepUntil(() -> !hasOreInInventory(), 5000);
        if (deposited && Rs2DepositBox.isOpen()) {
            Rs2DepositBox.closeDepositBox();
        }

        sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
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
        if (!Rs2Equipment.isWearing("hammer") && !Rs2Inventory.hasItem("hammer")) {
            // Search crate at 3752,5674
            TileObject crate = Rs2GameObject.findObjectByLocation(new WorldPoint(3752, 5674, 0));
            if (crate != null) {
                Rs2GameObject.interact(crate, "Search");
                sleepUntil(() -> Rs2Inventory.hasItem("hammer"), 5000);
                pickedUpHammer = true;
            }

            if (!Rs2Inventory.hasItem("hammer")) {
                log.warn("[MotherloadMineScript] No hammer found");
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

        if (!Rs2GameObject.interact(strut, "Repair")) {
            log.warn("[MotherloadMineScript] Failed to interact with broken strut");
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
            return false;
        }

        // Wait for smithing XP drop (animation)
        sleepUntil(() -> !Rs2Player.isAnimating(), 10000);

        // Drop hammer if we picked it up from the crate (not if equipped Imcando)
        if (pickedUpHammer && !Rs2Equipment.isWearing("hammer") && Rs2Inventory.hasItem("hammer")) {
            Rs2Inventory.drop("hammer");
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

    private void dropGems() {
        Microbot.status = "DROPPING GEMS";
        Rs2Inventory.dropAll(
                ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD,
                ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND,
                ItemID.UNCUT_DRAGONSTONE
        );
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
        return Rs2Equipment.isWearing("hammer") || Rs2Inventory.hasItem("hammer");
    }

    /** Deposit everything except pickaxe and hammer into the deposit box. */
    private void depositExceptTools() {
        List<Integer> exclude = new ArrayList<>();
        Pickaxe best = Pickaxe.getBestPickaxe();
        if (best != null) exclude.add(best.getItemId());
        if (hasHammer()) exclude.add(ItemID.HAMMER);

        if (!exclude.isEmpty()) {
            Rs2DepositBox.depositAllExcept(exclude.stream().mapToInt(Integer::intValue).boxed().toArray(Integer[]::new));
        } else {
            Rs2DepositBox.depositAll();
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

    private void climbDown() {
        TileObject ladder = Rs2GameObject.findObjectById(ObjectID.MOTHERLODE_LADDER_TOP);
        if (ladder == null) {
            ladder = Rs2GameObject.findObjectById(ObjectID.MOTHERLODE_LADDER_BOTTOM);
        }
        if (ladder != null) {
            Rs2GameObject.interact(ladder, "Climb-down");
            sleepUntil(() -> !isUpstairs(), 10000);
            sleep(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
        }
    }

    private int getSackCount() {
        return Microbot.getVarbitValue(VarbitID.MOTHERLODE_SACK_TRANSMIT);
    }

    private int getSackCapacity() {
        boolean upgraded = Microbot.getVarbitValue(VarbitID.MOTHERLODE_BIGGERSACK) == 1;
        return upgraded ? SACK_UPGRADED_SIZE : SACK_NORMAL_SIZE;
    }
}
