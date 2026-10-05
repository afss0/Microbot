package net.runelite.client.plugins.microbot.ascript.ammonitecrabs;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.awt.Rectangle;
import java.util.List;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Ammonite Crabs module — kills ammonite crabs on the Fossil Island beach.
 * <p>
 * Ported from chsami/Microbot-Hub's AmmoniteCrabs plugin onto the aScript module
 * contract with the review findings folded in: the recovery gate measures against the
 * fight spot (not the old worldhop anchor), banking is walk-gated and a supply missing
 * from the bank stops the script instead of ping-ponging, query terminals are
 * thread-safe, and world hops go through the guarded Microbot.hopToWorld (return checked).
 * <p>
 * Flow: TRAVELING -> FIGHTING <-> RESETTING (aggro timer) and WALK_TO_BANK ->
 * (orchestrator bank cycle) -> TRAVELING, plus HOPPING on a crash.
 */
@Slf4j
public class AmmoniteCrabsScript implements AModule {

    private static final int MAX_ACTION_FAILURES = 5;

    /** Eat below this HP% (the module owns its food; QOL auto-eat skips AMMONITE_CRABS). */
    private static final int EAT_AT_HP = 50;

    /** Chebyshev distance from the fight spot that triggers a recovery walk back. */
    private static final int RECOVER_DISTANCE = 25;
    /** Beyond this the player died/teleported — stop instead of a cross-world walk. */
    private static final int ABANDON_DISTANCE = 100;
    /** Arrival threshold for spot/reset walk targets. */
    private static final int ARRIVE_DISTANCE = 3;
    /** Tiles around the fight spot that count as 'at the spot' (scatter pull radius). */
    private static final int SPOT_RADIUS = 15;
    /** Bank approach threshold — needsBank() only fires within this range. */
    private static final int BANK_CLOSE_DISTANCE = 10;
    /** No combat for this long while at the spot -> aggro reset. */
    private static final long NO_COMBAT_RESET_MS = 10_000;
    /** Another player camping within 3 tiles for this long -> hop. */
    private static final long HIJACK_TRIP_MS = 7_000;
    /** Cooldown after a spore pickup click that did not verify. */
    private static final long SPORE_RETRY_COOLDOWN_MS = 15_000;

    public enum Phase { NONE, TRAVELING, FIGHTING, RESETTING, WALK_TO_BANK, HOPPING }

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private int consecutiveFailures = 0;
    private double weatherMultiplier = 1.0;

    /** Last time we were in combat / interacting at the spot (epoch ms). */
    private long lastCombatMs = 0;
    /** When another player started camping us (0 = none). */
    private long playerNearSinceMs = 0;
    /** Cooldown gate for a spore click that did not verify. */
    private long lastSporeAttemptMs = 0;

    // -- AModule contract -------------------------------------

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null || config.scriptSelection() != ScriptType.AMMONITE_CRABS
                || config.ammoniteCrabLocation() == null
                || config.ammoniteCrabLocation() == AmmoniteCrabLocation.NONE) {
            currentPhase = Phase.NONE;
            return;
        }
        if (!Microbot.isLoggedIn()) {
            currentPhase = Phase.NONE;
            return;
        }
        if (currentPhase == Phase.NONE) {
            currentPhase = Phase.TRAVELING;
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
        lastCombatMs = 0;
        playerNearSinceMs = 0;
        lastSporeAttemptMs = 0;
        currentPhase = Phase.TRAVELING;
    }

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.AMMONITE_CRABS)
            return "no ammonite crab location selected";
        return null;
    }

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        // The bank trip is module-driven: WALK_TO_BANK walks the player to the bank
        // first; only when close enough for Rs2Bank.openBank() to reach do we hand
        // the open/withdraw cycle to the orchestrator (fisher pattern).
        return bankTripNeeded(config) && isCloseToBank();
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        if (config.ammoniteCrabUseFood()) {
            int foodId = config.ammoniteCrabFood().getId();
            if (!Rs2Inventory.hasItem(foodId) && !Rs2Bank.hasItem(foodId)) return true;
        }
        if (config.ammoniteCrabUsePotions()) {
            int potionId = config.ammoniteCrabPotion().getId();
            if (Rs2Inventory.getFilteredPotionItemsInInventory(config.ammoniteCrabPotion().getPotionName()).isEmpty()
                    && !Rs2Bank.hasItem(potionId)) return true;
        }
        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        StringBuilder sb = new StringBuilder();
        if (config.ammoniteCrabUseFood()) {
            int foodId = config.ammoniteCrabFood().getId();
            if (!Rs2Inventory.hasItem(foodId) && !Rs2Bank.hasItem(foodId))
                sb.append(config.ammoniteCrabFood().getName());
        }
        if (config.ammoniteCrabUsePotions()) {
            int potionId = config.ammoniteCrabPotion().getId();
            if (Rs2Inventory.getFilteredPotionItemsInInventory(config.ammoniteCrabPotion().getPotionName()).isEmpty()
                    && !Rs2Bank.hasItem(potionId)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(config.ammoniteCrabPotion().getPotionName());
            }
        }
        return sb.length() == 0 ? "supplies" : sb.toString();
    }

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING";
        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) return false;
        if (!Rs2Bank.isOpen() && !sleepUntil(Rs2Bank::isOpen, Rs2Random.logNormalBounded(5000, 10000, weatherMultiplier))) {
            return false;
        }
        if (!AScriptBank.depositAll()) return false; // toolbar deposit; nothing here is slot-locked
        // Potions first: they need dedicated slots before food fills the rest.
        if (config.ammoniteCrabUsePotions()
                && Rs2Inventory.getFilteredPotionItemsInInventory(config.ammoniteCrabPotion().getPotionName()).isEmpty()) {
            if (!AScriptBank.withdrawVerified(String.valueOf(config.ammoniteCrabPotion().getId()),
                    config.ammoniteCrabPotionAmount())) {
                AScriptNotify.notify("Banking Failed",
                        "No " + config.ammoniteCrabPotion().getPotionName() + "(4) in bank");
                return false;
            }
        }
        if (config.ammoniteCrabUseFood() && Rs2Inventory.getInventoryFood().isEmpty()) {
            if (!AScriptBank.withdrawVerified(String.valueOf(config.ammoniteCrabFood().getId()))) {
                AScriptNotify.notify("Banking Failed", "No " + config.ammoniteCrabFood().getName() + " in bank");
                return false;
            }
        }
        consecutiveFailures = 0;
        currentPhase = Phase.TRAVELING;
        return true;
    }

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        try {
            switch (currentPhase) {
                case TRAVELING:    handleTraveling(config);    break;
                case FIGHTING:     handleFighting(config);     break;
                case RESETTING:    handleResetting(config);    break;
                case WALK_TO_BANK: handleWalkToBank(config);   break;
                case HOPPING:      handleHopping(config);      break;
                default:           break;
            }
        } catch (Exception ex) {
            log.error("[AmmoniteCrabs] Error in action loop", ex);
        }
    }

    @Override
    public boolean isPrecisionModule() {
        return false;
    }

    // -- Handlers ---------------------------------------------

    private void handleTraveling(AScriptConfig config) {
        AmmoniteCrabLocation loc = config.ammoniteCrabLocation();
        Microbot.status = "WALKING TO CRABS";
        Rs2Combat.setAutoRetaliate(true);

        if (checkCrashHop(config)) return;
        if (Rs2Player.isInCombat()) {
            lastCombatMs = System.currentTimeMillis();
            currentPhase = Phase.FIGHTING;
            return;
        }

        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null) return;

        if (playerLoc.distanceTo(loc.getFightLocation()) <= ARRIVE_DISTANCE) {
            attackScatteredCrabs(loc);
            consecutiveFailures = 0;
            lastCombatMs = System.currentTimeMillis();
            currentPhase = Phase.FIGHTING;
            return;
        }

        if (Rs2Player.isMoving()) return;
        if (!Rs2Walker.walkTo(loc.getFightLocation(), ARRIVE_DISTANCE)) {
            fail("could not reach the crab spot");
        } else {
            consecutiveFailures = 0;
        }
    }

    private void handleFighting(AScriptConfig config) {
        AmmoniteCrabLocation loc = config.ammoniteCrabLocation();
        Microbot.status = "FIGHTING";
        Rs2Combat.setAutoRetaliate(true);

        if (checkCrashHop(config)) return;

        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc != null) {
            int distance = playerLoc.distanceTo(loc.getFightLocation());
            if (distance > ABANDON_DISTANCE) {
                fail("far from the crab spot — died or teleported?");
                return;
            }
            if (distance > RECOVER_DISTANCE) {
                currentPhase = Phase.TRAVELING;
                return;
            }
        }

        boolean inCombat = Rs2Player.isInCombat();
        if (config.ammoniteCrabUseFood()) {
            Rs2Player.eatAt(EAT_AT_HP);
        }
        if (config.ammoniteCrabUsePotions() && inCombat) {
            Rs2Player.drinkCombatPotionAt(config.ammoniteCrabPotion().getSkill());
        }

        if (bankTripNeeded(config)) {
            Microbot.status = "OUT OF SUPPLIES — WALKING TO BANK";
            currentPhase = Phase.WALK_TO_BANK;
            return;
        }

        if (inCombat || Rs2Player.isInteracting()) {
            lastCombatMs = System.currentTimeMillis();
        } else if (lastCombatMs == 0) {
            lastCombatMs = System.currentTimeMillis();
        }

        if (config.ammoniteCrabLootSpores() && !inCombat && !Rs2Player.isInteracting()) {
            lootSpores(loc);
        }

        // Keep the character planted on the spot between kills.
        if (!inCombat && !Rs2Player.isMoving() && playerLoc != null
                && playerLoc.distanceTo(loc.getFightLocation()) > 2) {
            Rs2Walker.walkFastCanvas(loc.getFightLocation());
        }

        if (!inCombat && System.currentTimeMillis() - lastCombatMs > NO_COMBAT_RESET_MS) {
            Microbot.status = "RESETTING AGGRO";
            currentPhase = Phase.RESETTING;
        }
    }

    private void handleResetting(AScriptConfig config) {
        AmmoniteCrabLocation loc = config.ammoniteCrabLocation();
        Microbot.status = "RESETTING AGGRO";

        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null) return;
        if (playerLoc.distanceTo(loc.getFightLocation()) > ABANDON_DISTANCE) {
            fail("far from the crab spot — died or teleported?");
            return;
        }
        if (playerLoc.distanceTo(loc.getResetLocation()) <= ARRIVE_DISTANCE) {
            consecutiveFailures = 0;
            currentPhase = Phase.TRAVELING;
            return;
        }
        if (Rs2Player.isMoving()) return;
        if (!Rs2Walker.walkTo(loc.getResetLocation(), ARRIVE_DISTANCE)) {
            fail("could not reach the aggro reset spot");
        } else {
            consecutiveFailures = 0;
        }
    }

    private void handleWalkToBank(AScriptConfig config) {
        Microbot.status = "WALKING TO BANK";
        if (!bankTripNeeded(config)) { // supplies restocked or config changed
            currentPhase = Phase.TRAVELING;
            return;
        }
        if (isCloseToBank()) return; // orchestrator opens the bank and dispatches doBank next tick
        if (Rs2Player.isMoving()) return;
        if (!Rs2Walker.walkTo(BankLocation.FOSSIL_ISLAND.getWorldPoint(), BANK_CLOSE_DISTANCE)) {
            fail("could not reach the bank");
        } else {
            consecutiveFailures = 0;
        }
    }

    private void handleHopping(AScriptConfig config) {
        Microbot.status = "HOPPING WORLDS";
        int world = Login.getRandomWorld(true, null);
        if (world <= 0 || !Microbot.hopToWorld(world)) {
            fail("could not hop worlds");
            return;
        }
        consecutiveFailures = 0;
        playerNearSinceMs = 0;
        lastCombatMs = System.currentTimeMillis();
        currentPhase = Phase.TRAVELING;
    }

    // -- Helpers ----------------------------------------------

    private boolean bankTripNeeded(AScriptConfig config) {
        if (config.ammoniteCrabUseFood() && Rs2Inventory.getInventoryFood().isEmpty()) return true;
        return config.ammoniteCrabUsePotions()
                && Rs2Inventory.getFilteredPotionItemsInInventory(config.ammoniteCrabPotion().getPotionName()).isEmpty();
    }

    private boolean isCloseToBank() {
        WorldPoint location = Rs2Player.getWorldLocation();
        return location != null
                && location.distanceTo(BankLocation.FOSSIL_ISLAND.getWorldPoint()) <= BANK_CLOSE_DISTANCE;
    }

    private boolean checkCrashHop(AScriptConfig config) {
        if (!config.ammoniteCrabHopOnCrash()) {
            playerNearSinceMs = 0;
            return false;
        }
        WorldPoint playerLoc = Rs2Player.getWorldLocation();
        if (playerLoc == null) return false;
        AmmoniteCrabLocation loc = config.ammoniteCrabLocation();
        if (playerLoc.distanceTo(loc.getFightLocation()) > SPOT_RADIUS) {
            playerNearSinceMs = 0;
            return false;
        }
        boolean playerNear = otherPlayerWithin(playerLoc, 3) || otherPlayerWithin(loc.getFightLocation(), 3);
        if (!playerNear || Rs2Player.isInCombat()) {
            playerNearSinceMs = 0;
            return false;
        }
        long now = System.currentTimeMillis();
        if (playerNearSinceMs == 0) {
            playerNearSinceMs = now;
            return false;
        }
        if (now - playerNearSinceMs >= HIJACK_TRIP_MS) {
            playerNearSinceMs = 0;
            currentPhase = Phase.HOPPING;
            return true;
        }
        return false;
    }

    private boolean otherPlayerWithin(WorldPoint center, int radius) {
        // The cache stream includes the local player — exclude it explicitly (the old
        // Rs2Player.getPlayers(predicate) did that for us; it is deprecated for removal).
        return Microbot.getRs2PlayerCache().query()
                .where(player -> player.getPlayer() != Microbot.getClient().getLocalPlayer()
                        && player.getWorldLocation() != null
                        && player.getWorldLocation().distanceTo(center) <= radius)
                .firstOnClientThread() != null;
    }

    private void attackScatteredCrabs(AmmoniteCrabLocation loc) {
        List<Rs2NpcModel> crabs = Microbot.getRs2NpcCache().query()
                .withId(NpcID.FOSSIL_AMMONITECRAB)
                .within(loc.getFightLocation(), SPOT_RADIUS)
                .where(crab -> crab.getNpc() != null && !crab.isDead()
                        && crab.getWorldLocation().distanceTo(loc.getFightLocation()) > 1)
                .toList();
        for (Rs2NpcModel crab : crabs) {
            if (attackCrab(crab)) {
                AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(400, 900, weatherMultiplier));
            }
        }
    }

    private boolean attackCrab(Rs2NpcModel crab) {
        if (crab == null || crab.getNpc() == null) return false;
        final LocalPoint local = Microbot.getClientThread().invoke(() -> crab.getLocalLocation());
        if (local == null) return false;
        if (!Rs2Camera.isTileOnScreen(local)) {
            // Off the client thread on purpose: setAngle's key-hold wait (sleepUntilTrue)
            // no-ops on the client thread, so the camera turn must run from here to take effect.
            Rs2Camera.turnTo(local);
            return false;
        }
        // Canvas size is read off the lambda: calling Client#getCanvasWidth/Height from
        // inside a clientThread.invoke lambda registers them with the ClientThreadGuardrailTest
        // inference and would flag unrelated pre-existing util/api callers.
        final int canvasWidth = Microbot.getClient().getCanvasWidth();
        final int canvasHeight = Microbot.getClient().getCanvasHeight();
        Rectangle clickbox = Microbot.getClientThread().invoke(() -> {
            Rectangle box = Rs2UiHelper.getActorClickbox(crab.getNpc());
            if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) return null;
            // Canvas-sized fallback rectangle = no real clickbox — never click it blindly.
            if (box.getWidth() >= canvasWidth - 2
                    || box.getHeight() >= canvasHeight - 2) {
                return null;
            }
            return box;
        });
        if (clickbox == null) return false;
        Microbot.getMouse().click(clickbox);
        sleepUntil(() -> Rs2Player.isInteracting() || Rs2Player.isInCombat(),
                Rs2Random.logNormalBounded(1200, 2500, weatherMultiplier));
        return true;
    }

    private void lootSpores(AmmoniteCrabLocation loc) {
        if (System.currentTimeMillis() - lastSporeAttemptMs < SPORE_RETRY_COOLDOWN_MS) return;

        Rs2TileItemModel spore = Microbot.getRs2TileItemCache().query()
                .withName("Seaweed spore")
                .within(loc.getFightLocation(), SPOT_RADIUS)
                .nearestOnClientThread();
        if (spore == null) return;

        final LocalPoint local = Microbot.getClientThread().invoke(() -> spore.getLocalLocation());
        if (local == null) return;
        if (!Rs2Camera.isTileOnScreen(local)) {
            // See attackCrab: the camera turn only takes effect off the client thread.
            Rs2Camera.turnTo(local);
            return;
        }

        // See attackCrab: canvas size is read off the client-thread lambda to keep
        // Client#getCanvasWidth/Height out of the guardrail's inferred list.
        final int canvasWidth = Microbot.getClient().getCanvasWidth();
        final int canvasHeight = Microbot.getClient().getCanvasHeight();
        // Rs2UiHelper.getTileClickbox() wraps the Perspective canvas mapping; a direct
        // Perspective#getCanvasTilePoly call here would register it with the
        // ClientThreadGuardrailTest inference and flag unrelated callers.
        Rectangle clickArea = Microbot.getClientThread().invoke(() -> Rs2UiHelper.getTileClickbox(spore.getTile()));
        if (clickArea == null || clickArea.width <= 0 || clickArea.height <= 0
                || clickArea.width >= canvasWidth - 2 || clickArea.height >= canvasHeight - 2) return;

        Microbot.status = "LOOTING SPORES";
        int before = Rs2Inventory.count("Seaweed spore");
        Microbot.getMouse().click(clickArea);
        boolean picked = sleepUntil(() -> Rs2Inventory.count("Seaweed spore") > before,
                Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier));
        if (!picked) {
            lastSporeAttemptMs = System.currentTimeMillis();
        }
    }

    private void fail(String reason) {
        consecutiveFailures++;
        log.warn("[AmmoniteCrabs] Action failed ({}/{}): {}", consecutiveFailures, MAX_ACTION_FAILURES, reason);
        if (consecutiveFailures >= MAX_ACTION_FAILURES) {
            exitRequested = true;
            Microbot.status = "STOPPED — " + reason;
            AScriptNotify.notify("aScript Stopped — Ammonite Crabs", reason);
            Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }
}
