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
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
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

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.MOTHERLOAD_MINE) { this.currentPhase = Phase.NONE; return; }
        this.currentPhase = Phase.MINING;
    }

    // ── Selection validation ───────────────────────────────────

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
        return !Pickaxe.hasAnyPickaxe();
    }

    // ── Bank-level check (both bank AND inventory missing) ─────

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        return !Pickaxe.hasAnyPickaxe() && !Pickaxe.hasAnyPickaxeInBank();
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

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return;
        if (exitRequested) return;

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
            return;
        }

        boolean success = true;

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
            // Mine veins
            else {
                if (!mineVeins(config)) {
                    success = false;
                }
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
    }

    // ── Mining sub-routines ────────────────────────────────────

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
            log.debug("[MotherloadMineScript] No vein found, waiting...");
            sleep(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
            return true; // no vein = wait state, not a failure
        }

        if (!Rs2GameObject.interact(vein, "Mine")) {
            log.debug("[MotherloadMineScript] Failed to interact with vein");
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
            return false;
        }

        // Wait for animation to start
        sleepUntil(() -> Rs2Player.isAnimating() || Rs2Inventory.isFull(), 5000);
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
        if (!Rs2GameObject.interact(hopper, "Deposit")) {
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

            // Default left-click (matches Hub's query().interact pattern)
            if (!Rs2GameObject.interact(sack)) {
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
                Rs2GameObject.interact(crate, "Search");
                sleepUntil(() -> AScriptBank.hasUnnotedItem("hammer"), 5000);
                pickedUpHammer = true;
            }

            if (!AScriptBank.hasUnnotedItem("hammer")) {
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

        // Walk to strut if too far to interact
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc != null && playerLoc.distanceTo(strut.getWorldLocation()) > 10) {
            Rs2Walker.walkTo(strut.getWorldLocation(), 5);
            sleepUntil(() -> Rs2Player.getWorldLocation().distanceTo(strut.getWorldLocation()) <= 10,
                    Rs2Random.logNormalBounded(5000, 10000, weatherMultiplier));
        }

        if (!Rs2GameObject.interact(strut, "Repair")) {
            log.warn("[MotherloadMineScript] Failed to interact with broken strut");
            sleep(Rs2Random.logNormalBounded(400, 800, weatherMultiplier));
            return false;
        }

        // Wait for animation to end, then wait for the strut to actually be fixed
        // (game state update may lag behind animation)
        sleepUntil(() -> !Rs2Player.isAnimating(), 10000);
        sleepUntil(() -> countBrokenStruts() < brokenCount,
                Rs2Random.logNormalBounded(12000, 25000, weatherMultiplier));

        // Drop hammer if we picked it up from the crate (not if equipped Imcando)
        if (pickedUpHammer && !Rs2Equipment.isWearing("hammer") && AScriptBank.hasUnnotedItem("hammer")) {
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

    private boolean dropGems() {
        Microbot.status = "DROPPING GEMS";
        Rs2Inventory.dropAll(
                ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD,
                ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND,
                ItemID.UNCUT_DRAGONSTONE
        );
        return true; // dropAll is fire-and-forget; return true to not block the cycle
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
            if (!Rs2GameObject.interact(ladder, "Climb-down")) {
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

    @Override
    public boolean isPrecisionModule() { return false; }
}
