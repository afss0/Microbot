package net.runelite.client.plugins.microbot.ascript.cannoning;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ObjectID;
import net.runelite.api.TileObject;
import net.runelite.api.VarPlayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.awt.Rectangle;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Cannoning — maintains a placed dwarf multicannon: repairs it when it breaks and reloads
 * it when the ammo runs low. Placing, picking up and restocking stay manual: the module
 * never banks, and when the cannonballs run out it stops loudly instead of walking away.
 * <p>
 * The cannon must be placed before the module starts. The module latches onto the player's
 * <em>own</em> cannon from the {@code OWNEDMCANNON} varp — the same source the core Cannon
 * plugin builds its overlay from — so a stranger's cannon at a shared spot is never
 * clicked. The anchor is kept for the whole run and stays readable while the cannon is
 * outside the loaded scene, which is what lets the module walk back if the player drifts.
 * <p>
 * Every action is verified, never assumed: a repair must clear both the broken object id
 * and the {@code MCANNON_DECAYED} varbit, a load must raise {@code CANNON_AMMO}. Bounded
 * waits plus a failure counter stop the module loudly instead of spinning — an empty cannon
 * with too few balls to load is a dead run, not an idle one.
 */
@Slf4j
public class CannoningScript implements AModule {

    /** Consecutive failed actions before the module stops loudly. */
    private static final int MAX_ACTION_FAILURES = 3;
    /** Render-race budget: a cannon that never renders must not spend action lives. */
    private static final int MAX_RENDER_MISSES = 20;
    /** A load needs at least this many balls in the inventory to be worth clicking. */
    private static final int MIN_LOAD_BALLS = 15;
    /** The cannon holds 30 balls; the ammo varp never reads above this. */
    private static final int CANNON_CAPACITY = 30;
    /** Bounded verification windows for the two maintenance actions. */
    private static final int REPAIR_VERIFY_MS = 6_000;
    private static final int LOAD_VERIFY_MS = 5_000;
    /**
     * Hard cap for this module's random AFK. The house AFK runs to 120 s, but a cannon needs
     * attention sooner: the ammo pool drains and the cannon can decay while nobody is
     * looking, so this module never looks away for longer than 50 s.
     */
    private static final int AFK_MAX_MS = 50_000;
    /** Arrival distance for the walker — the cannon's own tile is not walkable. */
    private static final int WALK_ARRIVAL_RADIUS = 4;
    /** Travel watchdog: no movement for this long ends the run loudly. */
    private static final long TRAVEL_STAGNATION_MS = 120_000L;

    /** Placed cannon object ids. */
    private static final int[] CANNON_IDS = {
            ObjectID.DWARF_MULTICANNON,
            ObjectID.DWARF_MULTICANNON_43027
    };
    /** Broken cannon object ids — the base id included (the fork's Rs2Cannon omits it). */
    private static final int[] BROKEN_CANNON_IDS = {
            ObjectID.BROKEN_MULTICANNON,
            ObjectID.BROKEN_MULTICANNON_14916,
            ObjectID.BROKEN_MULTICANNON_43028
    };

    public enum Phase { NONE, MAINTENANCE, WALK_TO_CANNON }

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private int consecutiveFailures = 0;
    private int renderMisses = 0;
    private double weatherMultiplier = 1.0;
    private long lastAfkTime = 0L;

    /** The player's cannon: latched once per run, kept for the whole run. */
    private WorldPoint cannonTile = null;

    private int lastTravelX = Integer.MIN_VALUE;
    private int lastTravelY = Integer.MIN_VALUE;
    private int lastTravelPlane = Integer.MIN_VALUE;
    private long lastTravelMoveAt = 0L;

    // ── Contract ──────────────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.CANNONING
                || config.cannonBallType() == null) {
            currentPhase = Phase.NONE;
            return;
        }
        currentPhase = Phase.MAINTENANCE;
    }

    @Override
    public boolean isActive() {
        return currentPhase != Phase.NONE;
    }

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFailures = 0;
        renderMisses = 0;
        lastAfkTime = 0L;
        cannonTile = null;      // a fresh run re-latches from the varp
        resetTravelWatchdog();
    }

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.CANNONING) {
            return "no cannonball type selected";
        }
        if (currentPhase == Phase.NONE) return null;

        int reloadBelow = config.cannonRefillBelow();
        if (reloadBelow < 1 || reloadBelow >= CANNON_CAPACITY) {
            return "reload threshold must be between 1 and " + (CANNON_CAPACITY - 1)
                    + " (the cannon holds " + CANNON_CAPACITY + ")";
        }
        return null;
    }

    /**
     * This module never banks. Cannonballs are carried to the cannon by the player, and an
     * empty pool stops the module loudly with an actionable message — a restock trip would
     * leave the cannon firing unattended for minutes.
     */
    @Override
    public boolean needsBank(AScriptConfig config) {
        return false;
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        return "";
    }

    @Override
    public boolean doBank(AScriptConfig config) {
        return false;           // unreachable: needsBank() is always false
    }

    // ── Action ────────────────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;
        CannonBallType ball = config.cannonBallType();
        if (ball == null) return;

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // 1. Latch the player's own cannon once per run. The varp stays readable while the
        //    cannon is outside the loaded scene, so the anchor survives the player walking off.
        if (cannonTile == null) {
            latchCannon();
            if (cannonTile == null) return;         // fail() already counted it
        }

        // 2. Find the cannon at the anchor. Absent while standing there means it was picked
        //    up (or the anchor is wrong): stop loudly. Absent while away: walk back to it.
        TileObject cannon = findCannonObject(cannonTile);
        if (cannon == null) {
            WorldPoint here = Rs2Player.getWorldLocation();
            boolean atAnchor = here != null && here.getPlane() == cannonTile.getPlane()
                    && here.distanceTo(cannonTile) <= WALK_ARRIVAL_RADIUS;
            if (atAnchor) {
                fail("no cannon at " + cannonTile.getX() + "," + cannonTile.getY()
                        + " — was it picked up?");
            } else {
                walkTo(cannonTile, "CANNON");
            }
            return;
        }
        resetTravelWatchdog();

        // 3. Repair first: a broken cannon cannot be loaded. The object id is the primary
        //    signal, the MCANNON_DECAYED varbit the independent one, so an id the list does
        //    not cover still gets repaired. Both must clear before the repair counts.
        if (isBroken(cannon) || decayed()) {
            if (!clickObject(cannon)) return;       // camera turn / render wait, not a failure
            Microbot.status = "REPAIRING CANNON";
            boolean landed = sleepUntil(() -> {
                TileObject now = findCannonObject(cannonTile);
                return !decayed() && now != null && !isBroken(now);
            }, REPAIR_VERIFY_MS);
            if (!landed) {
                fail("cannon repair did not land");
                return;
            }
            consecutiveFailures = 0;
            postMaintenanceBreak(config);
            return;
        }

        // 4. Load when the ammo is low and the inventory can cover a load. The varp must
        //    actually rise — a click that did not take is a failure, not a success.
        int ammo = ammo();
        int balls = ballCount(config);
        if (ammo <= config.cannonRefillBelow() && balls >= MIN_LOAD_BALLS) {
            if (!clickObject(cannon)) return;
            Microbot.status = "LOADING CANNON";
            if (!sleepUntil(() -> ammo() > ammo, LOAD_VERIFY_MS)) {
                fail("cannon load did not land");
                return;
            }
            consecutiveFailures = 0;
            postMaintenanceBreak(config);
            return;
        }

        // 5. Nothing to maintain. An empty cannon with too few balls to load is a dead run —
        //    it stops loudly with an actionable message instead of idling forever.
        if (ammo <= config.cannonRefillBelow() && balls < MIN_LOAD_BALLS) {
            fail("out of " + ball.getItemName().toLowerCase() + "s (have " + balls + ", need "
                    + MIN_LOAD_BALLS + " for a load) — bring more from the bank and re-start");
            return;
        }

        Microbot.status = "CANNON OK — " + ammo + " balls";
        AScriptSleep.sleepInterruptibly(
                (int) (Rs2Random.logNormalBounded(600, 1_200) * weatherMultiplier));
    }

    // ── Cannon state ──────────────────────────────────────

    /**
     * Latch onto the player's own cannon. {@code OWNEDMCANNON} is the game's own "where is
     * my cannon" state (the core Cannon plugin builds its overlay from it) and it stays
     * readable while the cannon is outside the loaded scene. A stranger's cannon can never
     * be latched: the varp only ever names the player's own.
     */
    private void latchCannon() {
        int packed = Microbot.getVarbitPlayerValue(VarPlayerID.OWNEDMCANNON);
        if (packed <= 0) {
            fail("no cannon placed — place the cannon first, then start the module next to it");
            return;
        }
        WorldPoint anchor = WorldPoint.fromCoord(packed);
        // The varp packs the south-west corner of the cannon's 3x3 footprint; the object
        // stands on the centre tile (dx/dy +1) — the convention CannonOverlay draws with.
        cannonTile = new WorldPoint(anchor.getX() + 1, anchor.getY() + 1, anchor.getPlane());
        log.info("[Cannoning] latched the owned cannon at {}", cannonTile);
    }

    /** The cannon object at {@code tile}, placed or broken; {@code null} when not loaded. */
    private TileObject findCannonObject(WorldPoint tile) {
        if (tile == null) return null;
        return Microbot.getRs2TileObjectCache().query()
                .withIds(CANNON_IDS[0], CANNON_IDS[1],
                        BROKEN_CANNON_IDS[0], BROKEN_CANNON_IDS[1], BROKEN_CANNON_IDS[2])
                .where(o -> o.getWorldLocation() != null && o.getWorldLocation().equals(tile))
                .firstOnClientThread();
    }

    private boolean isBroken(TileObject object) {
        int id = object.getId();
        for (int brokenId : BROKEN_CANNON_IDS) {
            if (id == brokenId) return true;
        }
        return false;
    }

    /** Balls left in the cannon (0..30). */
    private int ammo() {
        return Microbot.getVarbitPlayerValue(VarPlayer.CANNON_AMMO);
    }

    /** The game's own "the cannon has decayed" flag. */
    private boolean decayed() {
        return Microbot.getVarbitValue(VarbitID.MCANNON_DECAYED) == 1;
    }

    /** Configured ball type in the inventory (stackable: one slot). */
    private int ballCount(AScriptConfig config) {
        CannonBallType ball = config.cannonBallType();
        return ball == null ? 0 : Rs2Inventory.count(ball.getItemId());
    }

    // ── Clicks ────────────────────────────────────────────

    /**
     * House click recipe for a game object: camera first, then the object's real clickbox
     * through the client mouse. The canvas-sized clickbox {@link Rs2UiHelper#getObjectClickbox}
     * returns for an unrendered object is never clicked — it lands on the terrain as
     * "Walk here" — it is waited out behind its own budget so a render race cannot spend
     * the module's action lives.
     *
     * @return true when a click was actually issued
     */
    private boolean clickObject(TileObject object) {
        if (!Rs2Camera.isTileOnScreen(object)) {
            Rs2Camera.turnTo(object);
            return false;                                   // retry next tick, not a failure
        }
        Rectangle clickbox = Rs2UiHelper.getObjectClickbox(object);
        if (clickbox == null || clickbox.getWidth() >= Microbot.getClient().getCanvasWidth() - 2) {
            renderMisses++;
            log.debug("[Cannoning] object not rendered yet (miss {}/{})", renderMisses, MAX_RENDER_MISSES);
            if (renderMisses >= MAX_RENDER_MISSES) {
                fail("cannon never rendered");
            }
            return false;
        }
        renderMisses = 0;
        Microbot.getMouse().click(clickbox);
        return true;
    }

    // ── Travel ────────────────────────────────────────────

    /**
     * Walk to the cannon anchor — the recovery path for a player who drifted away from the
     * cannon (the module itself never leaves it). Progress is measured as "the player
     * moved", never as "the distance shrank": a teleport may legitimately move you farther
     * away first. No movement for {@link #TRAVEL_STAGNATION_MS} stops the module loudly — an
     * unreachable anchor must never idle silently.
     */
    private void walkTo(WorldPoint anchor, String what) {
        Microbot.status = "WALKING TO " + what;
        currentPhase = Phase.WALK_TO_CANNON;

        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return;

        long now = System.currentTimeMillis();
        boolean moved = here.getX() != lastTravelX
                || here.getY() != lastTravelY
                || here.getPlane() != lastTravelPlane;
        if (moved) {
            lastTravelX = here.getX();
            lastTravelY = here.getY();
            lastTravelPlane = here.getPlane();
            lastTravelMoveAt = now;
        } else if (now - lastTravelMoveAt > TRAVEL_STAGNATION_MS) {
            exitRequested = true;
            stopWithMessage("could not reach " + what + " — no movement for "
                    + (TRAVEL_STAGNATION_MS / 1000) + "s at " + here.getX() + "," + here.getY());
            return;
        }

        if (!Rs2Player.isMoving()) {
            Rs2Walker.walkTo(anchor, WALK_ARRIVAL_RADIUS);
        }
    }

    /** Re-arms the travel watchdog; the next travel tick counts as movement. */
    private void resetTravelWatchdog() {
        lastTravelX = Integer.MIN_VALUE;
        lastTravelY = Integer.MIN_VALUE;
        lastTravelPlane = Integer.MIN_VALUE;
    }

    // ── Randomness ────────────────────────────────────────

    /**
     * Post-action break — the house randomness pattern: a short randomized pause after every
     * completed unit of work, then the optional random AFK (log-normal, weather-modulated,
     * interruptible so blocking events still fire), hard-capped at {@link #AFK_MAX_MS}.
     */
    private void postMaintenanceBreak(AScriptConfig config) {
        AScriptSleep.sleepInterruptibly(
                (int) (Rs2Random.logNormalBounded(800, 1_600) * weatherMultiplier));
        if (config.cannonAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            // logNormalBounded stretches its own max by the weather multiplier, so the cap
            // is applied to the sample: this module never looks away for longer than 50 s.
            int afkMs = Math.min(AFK_MAX_MS,
                    Rs2Random.logNormalBounded(3_000, AFK_MAX_MS, weatherMultiplier));
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    // ── Failure handling ──────────────────────────────────

    /**
     * Register a failed attempt: log it, back off, and stop the module (with a Discord
     * notification) after {@link #MAX_ACTION_FAILURES} in a row — a missed click, an
     * unlatched cannon or a dead ammo pool must never spin the loop forever.
     */
    private void fail(String reason) {
        consecutiveFailures++;
        log.warn("[Cannoning] Attempt failed ({}/{}): {}",
                consecutiveFailures, MAX_ACTION_FAILURES, reason);
        if (consecutiveFailures >= MAX_ACTION_FAILURES) {
            exitRequested = true;
            stopWithMessage(reason);
        }
        AScriptSleep.sleepInterruptibly(
                Rs2Random.logNormalBounded(500, 1_100, weatherMultiplier));
    }

    /**
     * Stop the module: status, log, Discord notification and config reset. Every stop path
     * goes through here so a self-stop is never silent.
     */
    private void stopWithMessage(String message) {
        Microbot.status = "STOPPED — " + message;
        log.warn("[Cannoning] Stopping: {}", message);
        AScriptNotify.notify("aScript Stopped — Cannoning", message);
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
    }
}
