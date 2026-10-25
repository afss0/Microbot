package net.runelite.client.plugins.microbot.ascript.ammonitecrabs;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

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
    /**
     * Minimum gap between two random AFKs. The house value is 5 s, but a 1-HP crab dies every
     * couple of seconds here, so a 5 s gap would AFK after nearly every kill and dominate the
     * run — raised so breaks land every few kills instead.
     */
    private static final long AFK_MIN_GAP_MS = 30_000;

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
    /** Timestamp of the last random AFK — {@link #AFK_MIN_GAP_MS} between AFKs. */
    private long lastAfkTime = 0L;
    /**
     * Combat state of the previous fighting tick. The kill is read off the falling edge
     * (was in combat, now not) — the single fire of the edge IS the once-per-kill guard.
     */
    private boolean wasInCombat = false;

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
        lastAfkTime = 0L;
        wasInCombat = false;
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
            if (!AScriptBank.bankableAtLeast(foodId, 1)) return true;
        }
        if (config.ammoniteCrabUsePotions()) {
            int potionId = config.ammoniteCrabPotion().getId();
            // Any dose in the inventory counts (the old name-based rule), and a noted potion
            // is bankable. The bank side checks the configured 4-dose id.
            if (Rs2Inventory.getFilteredPotionItemsInInventory(
                    config.ammoniteCrabPotion().getPotionName()).isEmpty()
                    && !AScriptBank.hasNotedItem(config.ammoniteCrabPotion().getPotionName())
                    && !Rs2Bank.hasBankItem(potionId, 1)) return true;
        }
        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        StringBuilder sb = new StringBuilder();
        if (config.ammoniteCrabUseFood()) {
            int foodId = config.ammoniteCrabFood().getId();
            if (!AScriptBank.bankableAtLeast(foodId, 1))
                sb.append(config.ammoniteCrabFood().getName());
        }
        if (config.ammoniteCrabUsePotions()) {
            int potionId = config.ammoniteCrabPotion().getId();
            if (Rs2Inventory.getFilteredPotionItemsInInventory(
                    config.ammoniteCrabPotion().getPotionName()).isEmpty()
                    && !AScriptBank.hasNotedItem(config.ammoniteCrabPotion().getPotionName())
                    && !Rs2Bank.hasBankItem(potionId, 1)) {
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
                return false;
            }
        }
        if (config.ammoniteCrabUseFood() && Rs2Inventory.getInventoryFood().isEmpty()) {
            if (!AScriptBank.withdrawVerified(String.valueOf(config.ammoniteCrabFood().getId()))) {
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
        // The kill, as this module can observe it: the fight is passive (auto-retaliate), so
        // no module action marks a crab dying — the falling edge of combat does. Consumed
        // here unconditionally so an early return below never leaves a stale edge armed.
        boolean killEdge = !inCombat && wasInCombat;
        wasInCombat = inCombat;
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

        // Post-kill break, taken where the module would otherwise move on. lastCombatMs is
        // then restarted: the break just spent real time out of combat, and without this the
        // stale clock would read as aggro decay and send the next tick to RESETTING.
        if (killEdge) {
            postKillBreak(config);
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

    /**
     * Post-kill break — the house randomness pattern (Jewelry/Crafting shape, as shipped in
     * Gem Crab Killer): a short randomized pause at every completed kill, then the
     * toggle-gated random AFK (log-normal 3-120 s, weather-modulated, interruptible so
     * blocking events still fire), a min-gap since the last AFK, status {@code AFK (Ns)}.
     *
     * The break fires once per kill because the caller only invokes it on the combat falling
     * edge. An AFK here does NOT stop combat — the crabs keep attacking and the player keeps
     * retaliating — what it removes is the module's own interventions (re-planting walk, spore
     * looting, potion sips), which is the profile of a human standing AFK at the spot.
     */
    private void postKillBreak(AScriptConfig config) {
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.ammoniteCrabAfk() && System.currentTimeMillis() - lastAfkTime > AFK_MIN_GAP_MS) {
            int afkMs = Rs2Random.logNormalBounded(3_000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
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
        // The shared house actor recipe (AScriptClick): camera first (the turn runs off
        // the client thread — setAngle's key-hold wait no-ops on it), then the crab's
        // real clickbox through the client mouse; the canvas-sized fallback of an
        // unrendered actor is never clicked.
        AScriptClick.Target target = AScriptClick.clickActor(crab.getNpc());
        if (!target.clicked()) return false;
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

        // The shared ground-item recipe (AScriptClick): camera first, the tile's real
        // clickbox (canvas fallback rejected), then the "Take" row verified in the hover
        // (plain click) or the open menu (row press). The hand-rolled click this replaces
        // pressed the tile rectangle without checking what ranked on top of the hover.
        Microbot.status = "LOOTING SPORES";
        int before = Rs2Inventory.count("Seaweed spore");
        AScriptClick.Target target = AScriptClick.clickGroundItem(spore.getTileItem(), spore.getTile());
        if (target.cameraTurned()) {
            return; // the turn is the whole action — retry next tick without spending the cooldown
        }
        if (!target.clicked()) {
            lastSporeAttemptMs = System.currentTimeMillis();
            return;
        }
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
