package net.runelite.client.plugins.microbot.ascript.cannonballsmelter;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.Rectangle;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Cannonball smelting sub-script — stateless helper called by AScript.
 * <p>
 * Smelts steel bars into cannonballs at a fixed furnace with the ammo mould
 * (or double ammo mould) held as a LOCKED inventory slot (the mould pattern,
 * {@link AScriptBank#ensureToolLocked(String)}), so the banking deposit never
 * removes it. One smelt click runs the whole inventory (~6 s per bar); the
 * batch is tracked by bar-count drops and the smithing animation, and a stall
 * window re-issues the furnace click if the batch stops early.
 * <p>
 * Between batches it runs the Jewelry/Crafting randomness layer: a short
 * randomized pause after every batch plus the optional random AFK
 * ({@code cannonballAfk}) — both log-normal, weather-modulated and
 * interruptible, with {@code needsBank()} yielding one tick so the pause
 * runs with the bank still closed.
 * <p>
 * Adapted from Microbot Hub CannonballSmelterPlugin (Girdy/Engin).
 */
@Slf4j
public class CannonballSmelterScript implements AModule {

    /** Smelt interface (group 270): child 13 marks it open, child 14 is the cannonball option. */
    private static final int SMELT_INTERFACE_WIDGET = 17694733;
    private static final int SMELT_CANNONBALL_BUTTON = 17694734;
    /** Smithing level required to smelt steel bars into cannonballs. */
    private static final int CANNONBALL_SMITHING_LEVEL = 35;
    /** Failed attempts in a row before the module stops and notifies. */
    private static final int MAX_ACTION_FAILURES = 3;
    /** No bar consumed and no animation for this long = the batch is not running anymore. */
    private static final long STALL_WINDOW_MS = 12_000L;
    /**
     * Walk target radius for a bank that is not loaded at all. Readiness is judged on the
     * bank object being in the scene ({@link #readyForBank}), so the walk hands off to the
     * orchestrator the moment the bank loads — long before this radius is reached.
     */
    private static final int BANK_TRAVEL_RADIUS = 4;
    /** No movement at all for this long while travelling = the site is unreachable. */
    private static final long TRAVEL_STAGNATION_MS = 120_000L;

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private int consecutiveFailures = 0;
    private double weatherMultiplier = 1.0;
    private int lastBarCount = 0;
    private long lastProgressAt = 0L;
    /** Timestamp of the last random AFK — minimum 5 s between AFKs. */
    private long lastAfkTime = 0L;
    /** Set while the post-batch break is pending/done — see {@link #needsBank(AScriptConfig)}. */
    private boolean postBatchBreakDone = false;
    /** Travel watchdog: the last position seen while walking to the site. */
    private int lastTravelX = Integer.MIN_VALUE;
    private int lastTravelY = Integer.MIN_VALUE;
    private int lastTravelPlane = Integer.MIN_VALUE;
    private long lastTravelMoveAt = 0L;

    public enum Phase {
        NONE, WALK_TO_BANK, WALK_TO_FURNACE, SMELTING
    }

    // ── Lifecycle ─────────────────────────────────────────

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFailures = 0;
        lastBarCount = 0;
        lastProgressAt = 0L;
        lastAfkTime = 0L;
        postBatchBreakDone = false;
        resetTravelWatchdog();
    }

    // ── Phase resolution ──────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.CANNONBALL_SMELTER
                || config.cannonballFurnace() == null) {
            currentPhase = Phase.NONE;
            return;
        }
        currentPhase = Phase.SMELTING;
    }

    @Override
    public boolean isActive() {
        return currentPhase != Phase.NONE;
    }

    // ── Selection validation ──────────────────────────────

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.CANNONBALL_SMELTER) {
            return "no furnace selected";
        }
        if (currentPhase != Phase.NONE) {
            // Capability guard: a sub-35 Smithing account cannot smelt steel bars. The
            // level reads 0 while skill data is still loading — treat that as "retry",
            // not as a stop.
            int smithing = Rs2Player.getRealSkillLevel(Skill.SMITHING);
            if (smithing > 0 && smithing < CANNONBALL_SMITHING_LEVEL) {
                return "need level " + CANNONBALL_SMITHING_LEVEL + " Smithing to smelt cannonballs (current: " + smithing + ")";
            }
        }
        return null;
    }

    // ── Banking needs ─────────────────────────────────────

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!hasBars()) {
            // Batch finished (or nothing to smelt): yield one tick so the post-batch
            // break runs with the bank still closed (Jewelry/Crafting placement).
            return postBatchBreakDone;
        }
        return !hasMould();            // mould lost mid-run
    }

    /**
     * The bank cycle is allowed as soon as a bank the orchestrator can actually open is in
     * the scene and on camera — the same candidates {@code Rs2Bank.openBank()} uses. A
     * visible bank is used directly: clicking it is the human pattern, and the game walks
     * the last few tiles as part of the interaction. Only a bank that is not loaded at all
     * makes this false, and the tick then falls through to {@link #doAction(AScriptConfig)},
     * which walks to the site anchor (the fisher/ammonite rule, made explicit).
     */
    @Override
    public boolean readyForBank(AScriptConfig config) {
        TileObject bank = nearestBankObject();
        return bank != null && Rs2Camera.isTileOnScreen(bank);
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        // Missing from both inventory AND bank
        if (!Rs2Inventory.hasItem(ItemID.STEEL_BAR) && !Rs2Bank.hasBankItem(ItemID.STEEL_BAR, 1)) {
            return true;
        }
        return !hasMould() && !bankHasMould();
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        StringBuilder sb = new StringBuilder("cannonball smelter: ");
        if (!Rs2Inventory.hasItem(ItemID.STEEL_BAR) && !Rs2Bank.hasBankItem(ItemID.STEEL_BAR, 1)) {
            sb.append("no steel bars (not in bank), ");
        }
        if (!hasMould() && !bankHasMould()) {
            sb.append("no ammo mould or double ammo mould (not in bank), ");
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2); // trailing ", "
        return sb.toString();
    }

    // ── Banking ───────────────────────────────────────────

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING — Cannonballs";

        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            return false;
        }

        // 1. Mould FIRST (locked tool — the blanket deposit must not remove it).
        //    Keep whichever mould is already held; when withdrawing fresh, prefer
        //    the double ammo mould (hub parity).
        String mouldName;
        if (Rs2Inventory.hasItem(ItemID.DOUBLE_AMMO_MOULD)) {
            mouldName = "double ammo mould";
        } else if (Rs2Inventory.hasItem(ItemID.AMMO_MOULD)) {
            mouldName = "ammo mould";
        } else {
            mouldName = Rs2Bank.hasItem("double ammo mould") ? "double ammo mould" : "ammo mould";
        }
        if (!AScriptBank.ensureToolLocked(mouldName)) {
            AScriptNotify.notify("Banking Failed", "No ammo mould or double ammo mould in bank");
            return false;
        }

        // 2. Deposit the produced cannonballs (and anything else) via the toolbar button.
        if (!AScriptBank.depositAndWaitEmpty()) {
            log.warn("[CannonballSmelter] deposit failed");
            return false;
        }

        // 3. Withdraw steel bars (fill the inventory).
        if (!AScriptBank.withdrawVerified("steel bar")) {
            AScriptNotify.notify("Banking Failed", "No steel bars in bank");
            return false;
        }

        lastBarCount = 0; // fresh batch — doAction resolves the real count
        return true;
    }

    // ── Action (smelt) ────────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;

        CannonballSmelterFurnace site = config.cannonballFurnace();
        if (site == null) return;

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        int bars = barCount();
        if (bars <= 0) {
            // Batch end. The randomness break runs ONCE per batch — never once per tick
            // while walking away with no bars. The orchestrator takes the bank cycle as
            // soon as readyForBank() allows it; until then, walk to the site.
            if (!postBatchBreakDone) {
                postBatchBreak(config);
            }
            if (!readyForBank(config)) {
                approachBank(site);
            }
            return;
        }
        postBatchBreakDone = false; // bars present again — the next empty state is a new batch end

        // Bars in hand, but the module cannot work from where it is: a lost mould means a
        // bank trip, and a furnace outside the loaded scene means we are not at the site.
        if (!hasMould()) {
            if (!readyForBank(config)) {
                approachBank(site);
            }
            return;
        }
        if (!isFurnaceResolvable(site)) {
            walkToSite(site, "FURNACE");
            return;
        }
        resetTravelWatchdog();

        long now = System.currentTimeMillis();
        boolean progressed = bars < lastBarCount;
        if (progressed) {
            lastBarCount = bars;
            lastProgressAt = now;
        }

        // In flight: a bar was consumed since the last tick, the player is animating,
        // or the batch started less than the stall window ago (between two actions).
        if (progressed || Rs2Player.isAnimating() || now - lastProgressAt < STALL_WINDOW_MS) {
            Microbot.status = "SMELTING — " + bars + " bars left";
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
            return;
        }

        startSmelt(site);
    }

    // ── Internal helpers ──────────────────────────────────

    /**
     * Post-batch break — the Jewelry/Crafting randomness pattern: a short
     * randomized pause after every batch, then the optional random AFK
     * (log-normal, weather-modulated, interruptible so blocking events still
     * fire). Runs while {@link #needsBank(AScriptConfig)} yields, i.e. with the
     * bank still closed.
     */
    private void postBatchBreak(AScriptConfig config) {
        postBatchBreakDone = true;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.cannonballAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    /**
     * Start (or resume) a smelt batch: open the smelt interface if needed, click
     * the cannonball option, park the mouse off-canvas and confirm the batch
     * actually started (animation or a bar consumed).
     */
    private void startSmelt(CannonballSmelterFurnace choice) {
        // The interface may already be open from a previous attempt whose wait timed
        // out — finish that sequence instead of re-clicking the furnace.
        if (Rs2Widget.getWidget(SMELT_INTERFACE_WIDGET) == null) {
            Rs2TileObjectModel furnace = findFurnace(choice);
            if (furnace == null) {
                fail("furnace not found (" + choice + ")");
                return;
            }
            if (!Rs2Camera.isTileOnScreen(furnace.getLocalLocation())) {
                Rs2Camera.turnTo(furnace.getLocalLocation());
                return; // retry next tick — not a failure
            }
            // A canvas-sized clickbox means getObjectClickbox fell back because the object
            // has no hull yet (not rendered in the last frame) — give the render a bounded
            // moment to catch up so the mouse lands on the furnace, then click either way:
            // the entity click goes through the menu-entry override, which fires the right
            // action regardless of where the pixel lands (jewelry/clickObject mechanism).
            Rectangle clickbox = Rs2UiHelper.getObjectClickbox(furnace);
            for (int i = 0; i < 5 && isCanvasFallback(clickbox); i++) {
                AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(150, 300, weatherMultiplier));
                clickbox = Rs2UiHelper.getObjectClickbox(furnace);
            }
            Microbot.status = "SMELTING — approaching furnace";
            furnace.click("Smelt");

            if (!sleepUntil(() -> Rs2Widget.getWidget(SMELT_INTERFACE_WIDGET) != null,
                    Rs2Random.logNormalBounded(5000, 8000, weatherMultiplier))) {
                fail("smelt interface did not open");
                return;
            }
        }

        Microbot.status = "SMELTING — Cannonballs";
        if (!Rs2Widget.clickWidget(SMELT_CANNONBALL_BUTTON)) {
            fail("could not click the cannonball option");
            return;
        }

        moveMouseOffCanvas();

        // Confirm the batch started: the player animates or a bar is consumed.
        int before = barCount();
        if (!sleepUntil(() -> Rs2Player.isAnimating() || barCount() < before,
                Rs2Random.logNormalBounded(1500, 3000, weatherMultiplier))) {
            fail("smelt did not start");
            return;
        }

        lastBarCount = barCount();
        lastProgressAt = System.currentTimeMillis();
        consecutiveFailures = 0;
    }

    /**
     * The configured furnace object, nearest to the player — via the tile-object
     * query API ({@code Rs2GameObject} is deprecated for removal), always with the
     * {@code ...OnClientThread()} variant: client-state reads must run on the
     * client thread. Shilo Village uses a name lookup — its gameval id
     * (ZQFURNACE_LIT) does not resolve reliably there (hub precedent).
     * <p>
     * Returns the query-API entity: its {@code click("Smelt")} is the sanctioned
     * entry-click (NewMenuEntry + {@code targetMenu} override), so the action fires
     * even when the pixel does not land on the hull.
     */
    private Rs2TileObjectModel findFurnace(CannonballSmelterFurnace choice) {
        if (choice == CannonballSmelterFurnace.SHILO_VILLAGE) {
            return Microbot.getRs2TileObjectCache().query().withName("Furnace").nearestOnClientThread();
        }
        return Microbot.getRs2TileObjectCache().query().withId(choice.getObjectId()).nearestOnClientThread();
    }

    /**
     * The canvas-sized rectangle {@link Rs2UiHelper#getObjectClickbox} returns when the
     * object has no hull yet (not rendered in the last frame). A real furnace clickbox
     * is a small fraction of the canvas — never this wide.
     */
    private boolean isCanvasFallback(Rectangle rect) {
        return rect == null || rect.getWidth() >= Microbot.getClient().getCanvasWidth() - 2;
    }

    /**
     * The nearest bank object the orchestrator could open from where the player stands —
     * the same query {@code Rs2Bank.openBank()} runs (a bank booth/chest or a Grand
     * Exchange booth, both searched within 20 tiles). Null means no bank is loaded in the
     * scene at all, which is the only case that justifies walking.
     */
    private TileObject nearestBankObject() {
        TileObject bank = Rs2GameObject.findBank();
        return bank != null ? bank : Rs2GameObject.findGrandExchangeBooth();
    }

    /**
     * Gets the player into a position to bank without inventing movement: a bank already in
     * the scene but off camera needs one glance ({@link Rs2Camera#turnTo}) — the
     * orchestrator then clicks it and the game walks the last few tiles as part of the
     * interaction, exactly as a human does. Only a bank that is not loaded at all falls
     * through to the walker.
     */
    private void approachBank(CannonballSmelterFurnace site) {
        currentPhase = Phase.WALK_TO_BANK;
        TileObject bank = nearestBankObject();
        if (bank != null) {
            Microbot.status = "BANKING — " + site.getName();
            Rs2Camera.turnTo(bank);
            return;
        }
        walkToSite(site, "BANK");
    }

    /** Whether the configured furnace object is in the loaded scene right now. */
    private boolean isFurnaceResolvable(CannonballSmelterFurnace site) {
        return findFurnace(site) != null;
    }

    /**
     * Walks to the site's bank anchor — the module's single travel primitive, because the
     * bank and the furnace share a region at every supported site. The walker handles
     * teleports and transports, so progress is measured as "the player moved", never as
     * "the distance shrank": a teleport may legitimately move you farther away first.
     * A player who has not moved at all for {@link #TRAVEL_STAGNATION_MS} stops the module
     * loudly — an unreachable site must never idle silently.
     *
     * @param what status label for the trip ("BANK" or "FURNACE")
     */
    private void walkToSite(CannonballSmelterFurnace site, String what) {
        Microbot.status = "WALKING TO " + what + " — " + site.getName();
        currentPhase = "BANK".equals(what) ? Phase.WALK_TO_BANK : Phase.WALK_TO_FURNACE;

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
            stopWithMessage("could not reach " + site.getName() + " — no movement for "
                    + (TRAVEL_STAGNATION_MS / 1000) + "s at " + here.getX() + "," + here.getY());
            return;
        }
        if (!Rs2Player.isMoving()) {
            Rs2Walker.walkTo(site.getBankLocation().getWorldPoint(), BANK_TRAVEL_RADIUS);
        }
    }

    /** Re-arms the travel watchdog; the next travel tick counts as movement. */
    private void resetTravelWatchdog() {
        lastTravelX = Integer.MIN_VALUE;
        lastTravelY = Integer.MIN_VALUE;
        lastTravelPlane = Integer.MIN_VALUE;
    }

    /**
     * Park the mouse off-canvas while the multi-minute batch runs (hub parity:
     * a human looking away mid-batch). All coordinates are randomized.
     */
    private void moveMouseOffCanvas() {
        int width = Microbot.getClient().getCanvasWidth();
        int height = Microbot.getClient().getCanvasHeight();
        int offX = Rs2Random.diceFractional(0.5) ? -1 : width + 1;
        int offY = Rs2Random.diceFractional(0.5) ? -1 : height + 1;
        if (Rs2Random.diceFractional(0.5)) {
            Microbot.naturalMouse.moveTo(offX, Rs2Random.between(0, height + 1));
        } else {
            Microbot.naturalMouse.moveTo(Rs2Random.between(0, width + 1), offY);
        }
    }

    private boolean hasBars() {
        return Rs2Inventory.hasItem(ItemID.STEEL_BAR);
    }

    private int barCount() {
        return Rs2Inventory.count(ItemID.STEEL_BAR);
    }

    private boolean hasMould() {
        return Rs2Inventory.hasItem(ItemID.AMMO_MOULD) || Rs2Inventory.hasItem(ItemID.DOUBLE_AMMO_MOULD);
    }

    private boolean bankHasMould() {
        return Rs2Bank.hasItem("double ammo mould") || Rs2Bank.hasItem("ammo mould");
    }

    /**
     * Register a failed attempt: log it, back off, and stop the module (with a
     * Discord notification) after {@link #MAX_ACTION_FAILURES} in a row — a missed
     * click or a missing furnace must never spin the loop forever.
     */
    private void fail(String reason) {
        consecutiveFailures++;
        log.warn("[CannonballSmelter] Attempt failed ({}/{}): {}",
                consecutiveFailures, MAX_ACTION_FAILURES, reason);
        if (consecutiveFailures >= MAX_ACTION_FAILURES) {
            exitRequested = true;
            stopWithMessage(reason);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }

    /**
     * Stop the module: status, log, Discord notification and config reset. Every
     * stop path goes through here so a self-stop is never silent.
     */
    private void stopWithMessage(String message) {
        Microbot.status = "STOPPED — " + message;
        log.warn("[CannonballSmelter] Stopping: {}", message);
        AScriptNotify.notify("aScript Stopped — Cannonball Smelter", message);
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
    }
}
