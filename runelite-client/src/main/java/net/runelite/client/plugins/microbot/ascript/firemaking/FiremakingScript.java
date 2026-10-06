package net.runelite.client.plugins.microbot.ascript.firemaking;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
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
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.util.Set;

import static net.runelite.api.gameval.AnimationID.*;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Firemaking sub-script — burns logs on fires / forester's campfires (GE corners, Castle Wars).
 * <p>
 * Robustness model (deliberately different from the hub GEFiremaker's blocking burn loop):
 * there is NO persistent "burning" flag. Every tick re-derives the state from the log count
 * plus two timestamps, so pause/resume, fire death, blocked spots and script restarts are all
 * self-healing — no code path can wait forever:
 * <ul>
 *   <li>a count drop is progress; a drop-less {@link #BURN_STALL_WINDOW_MS} means the burn is
 *       not actually running → re-engage (find fire → tend, or light a new one);</li>
 *   <li>a fresh/relit fire resets the trackers so the next tick engages immediately;</li>
 *   <li>a fire is only ever looked up by OBJECT ID ({@link #CAMPFIRE_ID} then {@link #FIRE_ID}):
 *       scenery fires that merely look like fires (e.g. the Barbarian Village fire, id 43475)
 *       are not convertible and must never be targeted;</li>
 *   <li>using logs on a lit fire is also what CREATES a forester's campfire — with no fire in
 *       range the module lights its own at the configured spot first (tinderbox required).</li>
 * </ul>
 * Fire interaction follows the hub AutoWoodcutting campfire flow (Sep 2026): {@code
 * useItemOnObject} → brief make-X probe → SPACE if it shows (post-12-Aug-2026 it only appears
 * with 2+ log types; a single log type auto-burns) → confirm via tending animation or the first
 * log consumed. Banking/AFK/failure handling follow CannonballSmelter.
 */
@Slf4j
public class FiremakingScript implements AModule {

    /** Forester's campfire — the converted (tendable, depleting) state of a fire. */
    private static final int CAMPFIRE_ID = 49927;
    /** Plain fire (player-made or a convertible permanent fire) — the pre-campfire state. */
    private static final int FIRE_ID = 26185;
    /** Search radius for a nearby fire, around the player (hub parity). */
    private static final int FIRE_SEARCH_RADIUS = 12;
    /** Make-X prompt for adding logs to a fire (only shows with 2+ log types in the inventory). */
    private static final String BURN_DIALOG_TEXT = "How many would you like to burn?";
    /** A log burns every ~9 ticks (~5.4 s). No count drop for this long = the burn is not running. */
    private static final long BURN_STALL_WINDOW_MS = 20_000L;
    /** Failed attempts in a row before the module stops and notifies. */
    private static final int MAX_ACTION_FAILURES = 3;

    /**
     * Campfire-tending and fire-lighting animations (gameval AnimationID, verified against
     * runelite-api 2026-10-06). Hub AutoWoodcutting's list, extended to every current log tier.
     */
    private static final Set<Integer> BURNING_ANIMATION_IDS = Set.of(
            FORESTRY_CAMPFIRE_BURNING_GENERIC,
            FORESTRY_CAMPFIRE_BURNING_ACHEY_TREE_LOGS,
            FORESTRY_CAMPFIRE_BURNING_ARCTIC_PINE_LOG,
            FORESTRY_CAMPFIRE_BURNING_BLISTERWOOD_LOGS,
            FORESTRY_CAMPFIRE_BURNING_LOGS,
            FORESTRY_CAMPFIRE_BURNING_MAGIC_LOGS,
            FORESTRY_CAMPFIRE_BURNING_MAHOGANY_LOGS,
            FORESTRY_CAMPFIRE_BURNING_MAPLE_LOGS,
            FORESTRY_CAMPFIRE_BURNING_OAK_LOGS,
            FORESTRY_CAMPFIRE_BURNING_REDWOOD_LOGS,
            FORESTRY_CAMPFIRE_BURNING_TEAK_LOGS,
            FORESTRY_CAMPFIRE_BURNING_WILLOW_LOGS,
            FORESTRY_CAMPFIRE_BURNING_YEW_LOGS,
            FORESTRY_CAMPFIRE_BURNING_JATOBA_LOGS,
            FORESTRY_CAMPFIRE_BURNING_CAMPHOR_LOGS,
            FORESTRY_CAMPFIRE_BURNING_IRONWOOD_LOGS,
            FORESTRY_CAMPFIRE_BURNING_ROSEWOOD_LOGS,
            HUMAN_CREATEFIRE);

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private int consecutiveFailures = 0;
    private double weatherMultiplier = 1.0;
    /** Log count observed last tick (-1 = no batch tracked yet / fresh start). */
    private int lastLogCount = -1;
    /** Last time a log was consumed (or a burn/light was initiated). */
    private long lastProgressAt = 0L;
    /** Timestamp of the last random AFK — minimum 5 s between AFKs. */
    private long lastAfkTime = 0L;
    /** Set while the post-batch break is pending/done — see {@link #needsBank(AScriptConfig)}. */
    private boolean postBatchBreakDone = false;

    public enum Phase { NONE, FIREMAKING }

    // ── Lifecycle ─────────────────────────────────────────

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFailures = 0;
        lastLogCount = -1;
        lastProgressAt = 0L;
        lastAfkTime = 0L;
        postBatchBreakDone = false;
    }

    // ── Phase resolution ──────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.FIREMAKING
                || config.firemakingLog() == null
                || config.firemakingLog() == FiremakingLog.NONE) {
            currentPhase = Phase.NONE;
            return;
        }
        currentPhase = Phase.FIREMAKING;
    }

    @Override
    public boolean isActive() {
        return currentPhase != Phase.NONE;
    }

    // ── Selection validation ──────────────────────────────

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.FIREMAKING) {
            return "no log type selected";
        }
        if (currentPhase == Phase.FIREMAKING) {
            // Capability guard: burning a tier below its level would fail every attempt. The
            // level reads 0 while skill data is still loading — treat that as "retry", not a stop.
            FiremakingLog choice = config.firemakingLog();
            int level = Rs2Player.getRealSkillLevel(Skill.FIREMAKING);
            if (level > 0 && level < choice.getLevelRequired()) {
                return "need level " + choice.getLevelRequired() + " Firemaking for "
                        + choice.getName() + " (current: " + level + ")";
            }
        }
        return null;
    }

    // ── Banking needs ─────────────────────────────────────

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        FiremakingLog choice = config.firemakingLog();
        if (choice == null || choice == FiremakingLog.NONE) return false;
        if (!Rs2Inventory.hasItem(choice.getItemId())) {
            // Batch finished (or nothing to burn): yield one tick so the post-batch break runs
            // with the bank still closed (Jewelry/Crafting placement).
            return postBatchBreakDone;
        }
        return !Rs2Inventory.hasItem(ItemID.TINDERBOX); // tinderbox lost mid-run
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        FiremakingLog choice = config.firemakingLog();
        if (choice == null || choice == FiremakingLog.NONE) return true; // defensive
        // Missing from both inventory AND bank
        if (!Rs2Inventory.hasItem(choice.getItemId()) && !Rs2Bank.hasBankItem(choice.getItemId(), 1)) {
            return true;
        }
        return !Rs2Inventory.hasItem(ItemID.TINDERBOX) && !Rs2Bank.hasBankItem(ItemID.TINDERBOX, 1);
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        FiremakingLog choice = config.firemakingLog();
        StringBuilder sb = new StringBuilder("firemaking: ");
        if (choice != null && choice != FiremakingLog.NONE
                && !Rs2Inventory.hasItem(choice.getItemId())
                && !Rs2Bank.hasBankItem(choice.getItemId(), 1)) {
            sb.append("no ").append(choice.getName().toLowerCase()).append(" (not in bank), ");
        }
        if (!Rs2Inventory.hasItem(ItemID.TINDERBOX) && !Rs2Bank.hasBankItem(ItemID.TINDERBOX, 1)) {
            sb.append("no tinderbox (not in bank), ");
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2); // trailing ", "
        return sb.toString();
    }

    // ── Banking ───────────────────────────────────────────

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING — Logs";

        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            return false;
        }

        // 1. Tinderbox FIRST (locked tool slot — the blanket deposit must not remove it).
        //    Numeric id on purpose: a "tinderbox" name match could pull a Damp/Fever tinderbox.
        if (!AScriptBank.ensureToolLocked(String.valueOf(ItemID.TINDERBOX))) {
            AScriptNotify.notify("Banking Failed", "No tinderbox in bank");
            return false;
        }

        // 2. Deposit anything left via the toolbar button (locked slots survive).
        if (!AScriptBank.depositAndWaitEmpty()) {
            log.warn("[Firemaking] deposit failed");
            return false;
        }

        // 3. Withdraw logs (fill the inventory). Numeric id on purpose: the name "logs" is a
        //    substring of every log type ("oak logs", "magic logs", ...) and would over-withdraw.
        FiremakingLog choice = config.firemakingLog();
        if (!AScriptBank.withdrawVerified(String.valueOf(choice.getItemId()))) {
            AScriptNotify.notify("Banking Failed", "No " + choice.getName().toLowerCase() + " in bank");
            return false;
        }

        lastLogCount = -1; // fresh batch — doAction resolves the real count
        lastProgressAt = 0L;
        return true;
    }

    // ── Action (burn) ─────────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;
        FiremakingLog logChoice = config.firemakingLog();
        if (logChoice == null || logChoice == FiremakingLog.NONE) return;
        int logId = logChoice.getItemId();

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // Level-up and other continue dialogues — click through, the burn keeps running.
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return;
        }

        int count = Rs2Inventory.count(logId);
        if (count <= 0) {
            postBatchBreak(config);
            return; // batch finished — the orchestrator banks on the next tick
        }
        postBatchBreakDone = false; // logs present again — the next empty state is a new batch end

        long now = System.currentTimeMillis();
        if (lastLogCount >= 0 && count < lastLogCount) {
            lastLogCount = count;
            lastProgressAt = now;
        }

        // In flight: a log was consumed since the last tick, or the burn started less than the
        // stall window ago (between two logs). No state flag — everything derives from the count,
        // so pause/resume, fire death and script restarts are all self-healing.
        if (lastLogCount >= 0 && now - lastProgressAt < BURN_STALL_WINDOW_MS) {
            Microbot.status = "FIREMAKING — " + count + " logs left";
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
            return;
        }

        engage(config, logId);
    }

    @Override
    public boolean isPrecisionModule() {
        return false; // one item-on-object click per multi-minute batch; imprecision is tolerable
    }

    // ── Internal helpers ──────────────────────────────────

    /**
     * (Re)start burning: find the nearest fire, walk to it, and use a log on it. With no fire in
     * range, light one at the configured spot instead — that is also what CREATES a forester's
     * campfire (using logs on a lit fire converts/extends it).
     */
    private void engage(AScriptConfig config, int logId) {
        Rs2TileObjectModel fire = findFire();
        if (fire == null) {
            lightFire(config, logId);
            return;
        }
        if (Rs2Player.distanceTo(fire.getWorldLocation()) > 3) {
            Microbot.status = "FIREMAKING — approaching fire";
            Rs2Walker.walkTo(fire.getWorldLocation(), 2);
            return; // retry next tick
        }

        Microbot.status = "FIREMAKING — adding logs to fire";
        // Hub AutoWoodcutter campfire precedent (documented injection exception — the aScript
        // client-mouse refactor for item-on-object is pending, see AGENTS.md / Fisher notes).
        Rs2Inventory.useItemOnObject(logId, fire.getId());

        // Post-12-Aug-2026 the make-X prompt only appears with 2+ log types in the inventory
        // (a single log type auto-burns). Probe briefly; confirm with SPACE if it shows.
        if (sleepUntil(() -> Rs2Widget.findWidget(BURN_DIALOG_TEXT, null, false) != null,
                Rs2Random.logNormalBounded(2000, 3000, weatherMultiplier))) {
            Rs2Random.waitEx(400, 200);
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        }

        // Confirm the burn actually started: a campfire-tending animation, or the first log
        // consumed (the first log burns ~9 ticks after the dispatch).
        int before = Rs2Inventory.count(logId);
        if (!sleepUntil(() -> isBurning() || Rs2Inventory.count(logId) < before,
                Rs2Random.logNormalBounded(5000, 7000, weatherMultiplier))) {
            fail("logs-on-fire did not start burning");
            return;
        }

        lastLogCount = Rs2Inventory.count(logId);
        lastProgressAt = System.currentTimeMillis();
        consecutiveFailures = 0;
    }

    /**
     * Light a fire at the configured spot (tinderbox on a log), then reset the trackers so the
     * next tick finds the fresh fire and starts tending it — the tend converts it into a
     * forester's campfire.
     */
    private void lightFire(AScriptConfig config, int logId) {
        if (!Rs2Inventory.hasItem(ItemID.TINDERBOX)) {
            return; // needsBank() pulls one next tick
        }
        WorldPoint spot = config.firemakingLocation().getWorldPoint();
        if (Rs2Player.distanceTo(spot) > 1) {
            Microbot.status = "FIREMAKING — walking to the fire spot";
            Rs2Walker.walkTo(spot, 1);
            return; // retry next tick
        }
        if (Rs2Player.isStandingOnGameObject()) {
            stepOffGameObject();
            return; // retry next tick
        }

        Microbot.status = "FIREMAKING — lighting fire";
        Rs2Inventory.use(ItemID.TINDERBOX);
        if (!sleepUntil(Rs2Inventory::isItemSelected,
                Rs2Random.logNormalBounded(1500, 2500, weatherMultiplier))) {
            fail("tinderbox did not select");
            return;
        }
        Rs2Inventory.useLast(logId);

        // Fire lighting gives a Firemaking XP drop; no drop in the window = did not light
        // (blocked tile / occupied spot / missed dispatch).
        if (!Rs2Player.waitForXpDrop(Skill.FIREMAKING,
                Rs2Random.logNormalBounded(5000, 7000, weatherMultiplier))) {
            fail("fire did not light at the configured spot");
            return;
        }
        lastLogCount = -1;  // fresh fire — engage immediately on the next tick
        lastProgressAt = 0L;
        consecutiveFailures = 0;
    }

    /**
     * The nearest tendable fire: a forester's campfire first, then a plain fire — always with the
     * {@code ...OnClientThread()} query variant (client-state reads must run on the client thread).
     * <p>
     * ID-only on purpose: scenery fires that merely look like fires (e.g. the Barbarian Village
     * fire, id 43475) are NOT convertible — only player-made fires (26185) and the game's flagged
     * permanent fires turn into campfires. A name-based "fire" search would happily target scenery.
     */
    private Rs2TileObjectModel findFire() {
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return null;
        Rs2TileObjectModel fire = Microbot.getRs2TileObjectCache().query()
                .withId(CAMPFIRE_ID)
                .nearestOnClientThread(player, FIRE_SEARCH_RADIUS);
        if (fire != null) return fire;
        return Microbot.getRs2TileObjectCache().query()
                .withId(FIRE_ID)
                .nearestOnClientThread(player, FIRE_SEARCH_RADIUS);
    }

    /**
     * The lighting spot must not have a game object on the player's own tile (e.g. leftover
     * ashes); the game refuses to light there. Walk to a neighbouring walkable tile and let the
     * next tick retry the light.
     */
    private void stepOffGameObject() {
        WorldPoint current = Rs2Player.getWorldLocation();
        if (current == null) return;
        for (WorldPoint tile : Rs2Tile.getWalkableTilesAroundPlayer(1)) {
            if (!tile.equals(current)) {
                Rs2Walker.walkFastCanvas(tile);
                return;
            }
        }
    }

    /** True while the player plays a campfire-tending (or fire-lighting) animation. */
    private boolean isBurning() {
        return Rs2Player.isAnimating(1800) && BURNING_ANIMATION_IDS.contains(Rs2Player.getLastAnimationID());
    }

    /**
     * Post-batch break — the Jewelry/Crafting randomness pattern: a short randomized pause after
     * every batch, then the optional random AFK (log-normal, weather-modulated, interruptible so
     * blocking events still fire). Runs while {@link #needsBank(AScriptConfig)} yields, i.e. with
     * the bank still closed.
     */
    private void postBatchBreak(AScriptConfig config) {
        postBatchBreakDone = true;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.firemakingAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    /**
     * Register a failed attempt: log it, back off, and stop the module (with a Discord
     * notification) after {@link #MAX_ACTION_FAILURES} in a row — a missed click or a blocked
     * spot must never spin the loop forever.
     */
    private void fail(String reason) {
        consecutiveFailures++;
        log.warn("[Firemaking] Attempt failed ({}/{}): {}",
                consecutiveFailures, MAX_ACTION_FAILURES, reason);
        if (consecutiveFailures >= MAX_ACTION_FAILURES) {
            exitRequested = true;
            stopWithMessage(reason);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }

    /**
     * Stop the module: status, log, Discord notification and config reset. Every stop path goes
     * through here so a self-stop is never silent.
     */
    private void stopWithMessage(String message) {
        Microbot.status = "STOPPED — " + message;
        log.warn("[Firemaking] Stopping: {}", message);
        AScriptNotify.notify("aScript Stopped — Firemaking", message);
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
    }
}
