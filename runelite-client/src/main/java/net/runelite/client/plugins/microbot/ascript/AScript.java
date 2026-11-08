package net.runelite.client.plugins.microbot.ascript;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.ascript.ammonitecrabs.AmmoniteCrabsScript;
import net.runelite.client.plugins.microbot.ascript.barbarianfishing.BarbarianFishingScript;
import net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher.BarbarianVillageFisherScript;
import net.runelite.client.plugins.microbot.ascript.boltenchant.BoltEnchantScript;
import net.runelite.client.plugins.microbot.ascript.cannonballsmelter.CannonballSmelterScript;
import net.runelite.client.plugins.microbot.ascript.cannoning.CannoningScript;
import net.runelite.client.plugins.microbot.ascript.crafting.CraftingScript;
import net.runelite.client.plugins.microbot.ascript.eventdismiss.LampUtility;
import net.runelite.client.plugins.microbot.ascript.firemaking.FiremakingScript;
import net.runelite.client.plugins.microbot.ascript.fletching.FletchingScript;
import net.runelite.client.plugins.microbot.ascript.gemcrabkiller.GemCrabKillerScript;
import net.runelite.client.plugins.microbot.ascript.jewellenchant.JewelEnchantScript;
import net.runelite.client.plugins.microbot.ascript.mining.MiningScript;
import net.runelite.client.plugins.microbot.ascript.motherloadmine.MotherloadMineScript;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptFood;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.woodcutting.WoodcuttingScript;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.security.LoginLockout;
import net.runelite.client.plugins.microbot.util.security.LoginManager;
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop;

import java.awt.event.KeyEvent;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * aScript — AIO orchestrator (standard Script, no state machine).
 * <p>
 * Three independent layers control what runs:
 * <ol>
 *   <li>the RuneLite plugin toggle — the master switch: the loop, the overlay and the
 *       random-event handlers exist only while it is on;</li>
 *   <li>{@code AScriptConfig#enabled()} — <b>automation</b> permission: may a module
 *       dispatch at all;</li>
 *   <li>{@code AScriptConfig#scriptSelection()} — <b>which</b> module; {@link ScriptType#IDLE}
 *       means "none selected".</li>
 * </ol>
 * QOL features are not automation: they run on every tick regardless of the automation
 * switch and the selection, each gated only by its own config toggle. The random-event
 * handler is registered at plugin start-up and is already independent of {@code enabled}.
 * <p>
 * Each tick: QOL → automation gate → resolve phase → bank if needed → dispatch.
 * Sub-modules implement {@link AModule}; the orchestrator dispatches generically.
 * <p>
 * Adding a module = implement AModule + add to {@link #MODULES}. No tick() changes needed.
 */
@Slf4j
public class AScript extends Script {

    /**
     * High-level <b>automation</b> phase exposed to the overlay. QOL state is not represented
     * here — it runs independently and the overlay reports it on its own line.
     */
    public enum Phase { DISABLED, IDLE, WAITING, RECOVERING, BANKING, RUNNING, ERROR }

    // ── Module registry ─────────────────────────────────────
    /** All modules, in dispatch priority order. First active module wins. */
    private static final List<AModule> MODULES = List.of(
            new CraftingScript(),
            new FletchingScript(),
            new MotherloadMineScript(),
            new GemCrabKillerScript(),
            new BarbarianVillageFisherScript(),
            new JewelEnchantScript(),
            new BoltEnchantScript(),
            new CannonballSmelterScript(),
            new CannoningScript(),
            new AmmoniteCrabsScript(),
            new FiremakingScript(),
            new BarbarianFishingScript(),
            new WoodcuttingScript(),
            new MiningScript()
    );

    /** Previous tick's active state per module — for IDLE → active transition detection. */
    private final Map<AModule, Boolean> wasActive = new IdentityHashMap<>();
    /** Previous tick's sub-selection key per module — a change starts a new run scope. */
    private final Map<AModule, String> runKeys = new IdentityHashMap<>();

    // ── State ───────────────────────────────────────────────
    private AScriptConfig config;

    @Getter
    private Phase currentPhase = Phase.DISABLED;

    private boolean stopRequested;
    private long lastZoomOutTime;
    /** Current auto-eat HP% threshold (0 = needs a roll; re-rolled after every confirmed bite). */
    private int eatThreshold;
    /**
     * QOL auto-eat confirmation state. The bite is confirmed asynchronously over the following
     * ticks (food count drop or HP rise) — the tick never blocks waiting for it, so human
     * takeovers, pauses and lag cannot masquerade as a failed bite.
     */
    private final EatTracker eatTracker = new EatTracker();
    /** Rate limit for the auto-eat miss WARN. Diagnostic only — there is no strike system. */
    private long lastEatMissLogAt;
    /** Cooldown after an interface-close attempt, so a stuck interface is not hammered. */
    private long nextInterfaceCloseAt;
    /**
     * Reason from the last orchestrator stop, shown by the overlay while automation is
     * disabled. QOL actions overwrite {@code Microbot.status} constantly, so the stop reason
     * needs its own field.
     */
    @Getter
    private String lastStopMessage;
    /** True once the "no script selected" idle hold has been logged for this configuration. */
    private boolean idleHoldAnnounced;
    /** Consecutive doBank failures — stops script after threshold. */
    private int consecutiveBankFailures = 0;
    private static final int MAX_BANK_FAILURES = 3;
    /**
     * Consecutive failed bank opens. The bank phase is only entered for a module that
     * reports {@link AModule#readyForBank}, so a run of failures means the bank is not
     * actually reachable — stop instead of returning forever.
     */
    private int consecutiveBankOpenFailures = 0;
    private static final int MAX_BANK_OPEN_FAILURES = 10;
    /**
     * Consecutive bank closes that did not land on the "no module needs bank" path.
     * An open bank interface blocks production interfaces (make-X etc.) for ANY module,
     * so dispatch must not proceed while one is open — the tick retries the close and
     * stops loudly after the bound instead of letting the module act against the bank.
     * The close itself falls back between ESC and the widget click inside
     * {@link Rs2Bank#closeBank()}, so a run of failures means both mechanisms failed
     * (e.g. keyboard emission suppressed by human takeover *and* the click not landing).
     */
    private int consecutiveBankCloseFailures = 0;
    private static final int MAX_BANK_CLOSE_FAILURES = 5;
    /**
     * Consecutive failures to put the bank into "Withdraw as item" mode. Modules cannot use
     * banknotes, so the cycle must not proceed in note mode; a run of failures stops loudly.
     */
    private int consecutiveWithdrawModeFailures = 0;
    private static final int MAX_WITHDRAW_MODE_FAILURES = 5;
    /**
     * Consecutive "materials missing" verdicts. A missing verdict ends the run and is read
     * from the cached bank mirror (refreshed only by bank container events), so it is
     * confirmed across ticks and trusted only on a live mirror — see the bank step in
     * {@link #tick()}.
     */
    private int consecutiveMissingVerdicts = 0;
    /** Consecutive missing verdicts required before the run stops. */
    private static final int MISSING_CONFIRMATION_TICKS = 3;
    /** Hard bound on the confirmation wait — an unreadable mirror must not hold the tick. */
    private static final int MISSING_MAX_TICKS = 12;
    /** Consecutive unexpected exceptions in the scheduled loop before the run stops. */
    private int consecutiveTickErrors = 0;
    private static final int MAX_TICK_ERRORS = 5;
    /** Selection the run counters belong to — a switch must start them from zero. */
    private ScriptType lastSelection = ScriptType.IDLE;
    /** Last "waiting for configuration" message logged — the hold logs once per change. */
    private String lastSelectionError = null;
    /** Configured chain and its session cursor; {@code null} until the first enabled tick. */
    private ModuleChain moduleChain;
    /** Last chainModules text parsed — a config edit starts a fresh chain session. */
    private String chainSpecCache;
    /** Whether the current invalid chain text has already been logged. */
    private boolean chainParseErrorLogged;
    /** Human-readable chain position for the overlay, or {@code null} when no chain is active. */
    @Getter
    private String chainStatus;
    /**
     * True while the last tick actually ran automation (config enabled + a module selected).
     * The idle branch uses it to close only a bank THIS run may have left open — and clears
     * it on that close, so the cleanup is a one-shot on the running → idle transition.
     * <p>
     * Never infer that from {@link #lastSelection}: the selection persists across ticks even
     * while automation is disabled, so {@code selection != IDLE} stays true with the
     * automation switch off and the cleanup closed every bank the player opened, every
     * 600 ms (live 2026-10-26).
     */
    private boolean runWasActive = false;
    /** Track if the player was logged in at least once — triggers the logout watcher. */
    private boolean playerWasLoggedIn = false;
    /**
     * Re-login recovery for an unexpected logout. Tick-driven: the logout branch samples the
     * waits and dispatches one attempt per deadline, so the loop keeps running and the overlay
     * keeps counting down while the client sits on the login screen. Only an exhausted budget
     * stops the automation — and that stop is the flow's single Discord notification.
     */
    private final ReloginRecovery reloginRecovery = new ReloginRecovery();
    /** Random wait before the first re-login attempt, before weather scaling. */
    private static final int RELOGIN_INITIAL_WAIT_MIN_MS = 5_000;
    private static final int RELOGIN_INITIAL_WAIT_MAX_MS = 20_000;
    /** How long a dispatched login gets to report LOGGED_IN before it counts as failed. */
    private static final int RELOGIN_SETTLE_TIMEOUT_MS = 30_000;
    /** Log-normal jitter around the nominal backoff step (15 s / 30 s / 60 s / 120 s). */
    private static final double RELOGIN_BACKOFF_JITTER_MIN = 0.75;
    private static final double RELOGIN_BACKOFF_JITTER_MAX = 1.5;
    /** Login-lockout cooldown (~10 min) before the next attempt, weather-modulated. */
    private static final int RELOGIN_LOCKOUT_WAIT_MIN_MS = 9 * 60_000;
    private static final int RELOGIN_LOCKOUT_WAIT_MAX_MS = 11 * 60_000;
    /**
     * Game-readiness gate. Every tick samples the client before anything acts; a module must never
     * dispatch (and QOL must never click) on a client that is still building the world or whose
     * game loop has stopped. Liveness comes from the client game cycle — it advances every 20 ms
     * with the client loop, unlike the server tick counter, which pauses during lag and region
     * loads while the client is healthy.
     */
    private final GameReadinessMonitor gameReadiness = new GameReadinessMonitor();
    /** Reason of the current hold, {@code null} when the game is ready. Logs once per change. */
    private GameReadinessMonitor.Readiness gameHoldReason;
    /** When this hold started; {@code 0} while ready. */
    private long gameHoldSince;
    /**
     * How long automation may wait for a ready game before stopping loudly. Covers a slow region
     * load plus the monitor's own {@link GameReadinessMonitor#STALL_MS} window.
     */
    private static final long GAME_READY_TIMEOUT_MS = 60_000;

    // ── Main loop ───────────────────────────────────────────

    public boolean run(AScriptConfig config) {
        this.config = config;
        this.playerWasLoggedIn = false;
        this.reloginRecovery.reset();
        this.runWasActive = false;
        this.lastStopMessage = null;
        this.idleHoldAnnounced = false;
        this.moduleChain = null;
        this.chainSpecCache = null;
        this.chainParseErrorLogged = false;
        this.chainStatus = null;
        resetGameHold();
        resetQolState();
        // A previous run must not leave a slot-lock capability failure or a menu swap armed.
        AScriptBank.resetSessionState();
        AScriptClick.reset();
        log.info("[AScript] Starting AIO script");

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                // Unexpected logout: recover while automation is on instead of stopping at the
                // first login screen. Only the exhausted attempt budget stops the automation.
                if (!Microbot.isLoggedIn()) {
                    handleUnexpectedLogout();
                    return;
                }

                // Any successful login — ours or one another actor performed (AutoLogin, the
                // break handler) — ends the recovery, without a notification.
                if (reloginRecovery.isActive()) {
                    resumeAfterRelogin();
                } else if (reloginRecovery.isExhausted()) {
                    // Back in after the budget ran out (manual login or another plugin). The
                    // automation is already off; the next run starts from a clean machine.
                    reloginRecovery.reset();
                }
                playerWasLoggedIn = true;

                if (!super.run()) return; // heartbeat / pause guard

                // Nothing touches the client until the game is actually running: not the modules
                // and not QOL. A logged-in client can still be building the scene, and a dead
                // client keeps reporting LOGGED_IN with a stale player (see GameReadinessMonitor).
                if (!awaitGameReady()) return;

                tick();
                consecutiveTickErrors = 0;
            } catch (Exception ex) {
                log.error("[AScript] Unexpected error in scheduled loop", ex);
                // A loop that throws every tick automates nothing: stop loudly after a bound
                // instead of logging forever with the config still enabled.
                try {
                    if (++consecutiveTickErrors >= MAX_TICK_ERRORS) {
                        consecutiveTickErrors = 0;
                        stopWithMessage("aScript Stopped — Loop Errors",
                                MAX_TICK_ERRORS + " consecutive exceptions in the script loop — see the client log");
                    }
                } catch (Exception stopEx) {
                    log.error("[AScript] Could not stop the script after repeated loop errors", stopEx);
                }
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    // ── Unexpected-logout recovery ──────────────────────────

    /**
     * Handles one tick on the login screen. Starts the recovery on the first detection, then
     * either dispatches the attempt that is due or keeps the countdown in the status line.
     * <p>
     * <b>Deliberately silent to Discord</b>: the single notification of this flow is the stop in
     * {@link #stopWithMessage(String, String)} after the budget is exhausted. A logout the
     * automation did not cause — automation off, nothing selected, the feature switched off, a
     * break-handler pause, or a plugin run that never logged in — is left alone instead of
     * being "stopped" and notified about.
     */
    private void handleUnexpectedLogout() {
        if (config == null || !config.enabled() || config.scriptSelection() == ScriptType.IDLE
                || !config.recoverUnexpectedLogout()) {
            reloginRecovery.reset();
            return;
        }

        if (Microbot.pauseAllScripts.get()) {
            // A break handler (or another pause) owns this logout — logging back in here would
            // fight the break. A recovery that was already waiting is abandoned; it restarts
            // only if the pause clears while the client is still on the login screen.
            if (reloginRecovery.isActive()) {
                log.info("[AScript] Re-login recovery abandoned — scripts were paused");
            }
            reloginRecovery.reset();
            return;
        }

        if (!reloginRecovery.isActive()) {
            if (reloginRecovery.isExhausted()) {
                return; // the stop already landed; the cleared switch ends this flow next tick
            }
            if (!playerWasLoggedIn) {
                return; // the plugin started before the first login: the user's own login
            }
            GameState gameState = Microbot.getClient() != null
                    ? Microbot.getClient().getGameState() : GameState.UNKNOWN;
            if (gameState != GameState.LOGIN_SCREEN
                    && gameState != GameState.LOGIN_SCREEN_AUTHENTICATOR) {
                return; // connection lost / hopping: not a logout screen yet
            }
            beginRelogin();
            return;
        }

        long now = System.currentTimeMillis();
        if (reloginRecovery.isSettling()) {
            if (reloginRecovery.settleExpired(now)) {
                scheduleReloginRetry(now);
            } else {
                Microbot.status = "RECONNECTING — attempt " + reloginRecovery.attempts()
                        + "/" + ReloginRecovery.MAX_ATTEMPTS + " (waiting for login)";
            }
            return;
        }
        if (reloginRecovery.attemptDue(now)) {
            dispatchReloginAttempt(now);
            return;
        }
        Microbot.status = "RECONNECTING — attempt " + (reloginRecovery.attempts() + 1)
                + "/" + ReloginRecovery.MAX_ATTEMPTS + " in "
                + secondsUntil(reloginRecovery.nextAttemptAt(), now) + " s";
    }

    /** Opens the recovery: one weather-modulated random wait before the first attempt. */
    private void beginRelogin() {
        long now = System.currentTimeMillis();
        int waitMs = sampleReloginWait(RELOGIN_INITIAL_WAIT_MIN_MS, RELOGIN_INITIAL_WAIT_MAX_MS);
        reloginRecovery.begin(now, waitMs);
        currentPhase = Phase.RECOVERING;
        Microbot.status = "RECONNECTING — attempt 1/" + ReloginRecovery.MAX_ATTEMPTS + " in "
                + secondsUntil(reloginRecovery.nextAttemptAt(), now) + " s";
        log.warn("[AScript] Unexpected logout — re-login attempt 1/{} in {} ms",
                ReloginRecovery.MAX_ATTEMPTS, waitMs);
    }

    /**
     * Dispatches one re-login. The attempt is counted before the call: a login API that refuses
     * (no active profile, throttle) must still spend a slot, or a broken login would retry
     * forever. A lockout is counted as a failed attempt and answered with the lockout wait —
     * retrying its screen is exactly what extends the cooldown.
     */
    private void dispatchReloginAttempt(long now) {
        int attempt = reloginRecovery.attempts() + 1;
        reloginRecovery.attemptStarted(now, RELOGIN_SETTLE_TIMEOUT_MS);
        log.info("[AScript] Re-login attempt {}/{}", attempt, ReloginRecovery.MAX_ATTEMPTS);

        boolean lockoutScreen = LoginManager.isLoginLockoutScreen();
        LoginLockout lockout = LoginLockout.getInstance();
        if (lockoutScreen || lockout.isActive()) {
            // A lockout is a failed attempt, not a terminal state. The shared tracker records the
            // cooldown and dismisses the "too many attempts" screen once — retrying that screen is
            // what extends the cooldown. The next attempt waits ~10 min (weather-modulated), and
            // longer when the tracker's own cooldown has not expired, so a retry is never swallowed
            // by the login guard. The attempt counter is preserved, so the backoff sequence keeps
            // counting from this failure instead of restarting.
            LoginManager.checkLoginLockout();
            int waitMs = sampleReloginWait(RELOGIN_LOCKOUT_WAIT_MIN_MS, RELOGIN_LOCKOUT_WAIT_MAX_MS);
            waitMs = (int) Math.max(waitMs, Math.min(lockout.remainingMillis(), Integer.MAX_VALUE));
            log.warn("[AScript] Login lockout — attempt {}/{} failed, next in {} ms",
                    attempt, ReloginRecovery.MAX_ATTEMPTS, waitMs);
            scheduleReloginRetry(now, waitMs, true);
            return;
        }
        if (LoginManager.getActiveProfile() == null) {
            log.warn("[AScript] Re-login attempt {}/{} — no active profile", attempt,
                    ReloginRecovery.MAX_ATTEMPTS);
            scheduleReloginRetry(now);
            return;
        }

        boolean dispatched;
        try {
            dispatched = LoginManager.login();
        } catch (Exception ex) {
            // A login failure must spend an attempt, never the loop-error budget: five throws
            // here would otherwise stop the automation with the misleading "Loop Errors" title.
            log.warn("[AScript] Re-login attempt {}/{} threw", attempt,
                    ReloginRecovery.MAX_ATTEMPTS, ex);
            dispatched = false;
        }
        if (!dispatched) {
            log.warn("[AScript] Re-login attempt {}/{} was not dispatched", attempt,
                    ReloginRecovery.MAX_ATTEMPTS);
            scheduleReloginRetry(now);
        }
    }

    /**
     * Ends a failed attempt: schedules the next one on the exponential backoff, or stops the
     * automation once the budget is spent. The wait is weather-modulated exactly like every
     * other house delay (one three-argument sample — the two-argument overload already applies
     * weather on its own).
     */
    private void scheduleReloginRetry(long now) {
        int step = ReloginRecovery.backoffStepMs(reloginRecovery.attempts());
        int waitMs = sampleReloginWait(
                (int) Math.round(step * RELOGIN_BACKOFF_JITTER_MIN),
                (int) Math.round(step * RELOGIN_BACKOFF_JITTER_MAX));
        scheduleReloginRetry(now, waitMs, false);
    }

    /**
     * Schedules the next attempt with an explicit wait, or stops the automation when the
     * attempt budget is spent. The lockout wait goes through here so it spends the same slot
     * and the backoff sequence keeps counting from it.
     *
     * @param now    wall clock of the failure
     * @param waitMs sampled wait before the next attempt
     * @param lockout true when the wait is the login-lockout window (minutes, not seconds)
     */
    private void scheduleReloginRetry(long now, int waitMs, boolean lockout) {
        int failed = reloginRecovery.attempts();
        if (failed >= ReloginRecovery.MAX_ATTEMPTS) {
            reloginRecovery.scheduleRetry(now, 0);
            stopWithMessage("aScript Stopped — Re-login Failed",
                    ReloginRecovery.MAX_ATTEMPTS + " re-login attempts failed after an unexpected"
                            + " logout — check the active profile's credentials and the login screen.");
            return;
        }
        reloginRecovery.scheduleRetry(now, waitMs);
        long seconds = secondsUntil(reloginRecovery.nextAttemptAt(), now);
        Microbot.status = lockout
                ? "RECONNECTING — login lockout, attempt " + (failed + 1) + "/"
                        + ReloginRecovery.MAX_ATTEMPTS + " in " + ((seconds + 59) / 60) + " min"
                : "RECONNECTING — attempt " + (failed + 1) + "/"
                        + ReloginRecovery.MAX_ATTEMPTS + " in " + seconds + " s";
        log.warn("[AScript] Re-login attempt {}/{} failed — next in {} ms",
                failed, ReloginRecovery.MAX_ATTEMPTS, waitMs);
    }

    /**
     * Samples a wait with the house weather modulation. {@code logNormalBounded(min, max,
     * multiplier)} applies ONLY the given multiplier, so the weather factor is computed once
     * here and the two-argument overload is never stacked on top of it.
     */
    private static int sampleReloginWait(int minMs, int maxMs) {
        WeatherModulation.ensureFresh();
        double weatherMultiplier = 1.0 / Math.max(0.1, WeatherModulation.combinedSpeedFactor());
        return Rs2Random.logNormalBounded(minMs, maxMs, weatherMultiplier);
    }

    /** Whole seconds until {@code deadlineMs}, rounded up so a countdown never shows 0 s early. */
    private static long secondsUntil(long deadlineMs, long nowMs) {
        return Math.max(0, (deadlineMs - nowMs + 999) / 1000);
    }

    /**
     * The player is logged in again — by our attempt or by another actor (AutoLogin, the break
     * handler). Resets recovery, the readiness gate and the run counters: modules re-derive
     * their state from the live inventory instead of carrying a half-finished batch across the
     * logout. No Discord notification — only the exhausted budget notifies.
     */
    private void resumeAfterRelogin() {
        int attempts = reloginRecovery.attempts();
        reloginRecovery.reset();
        resetGameHold();
        resetRunCounters();
        AScriptBank.resetSessionState();
        AScriptClick.reset();
        for (AModule mod : MODULES) {
            wasActive.put(mod, false);
            runKeys.remove(mod);
            mod.resetExitFlag();
        }
        runWasActive = false;
        Microbot.status = "Re-login succeeded — resuming";
        log.info("[AScript] Re-login succeeded after {} attempt(s) — automation resuming", attempts);
    }

    /**
     * Samples the client and either lets the tick proceed or holds it.
     * <p>
     * <b>Why no client-thread hop:</b> a readiness probe must be cheap and must still answer when
     * the client thread is the thing that died. {@code runOnClientThreadOptional} would block for
     * its full timeout on every 600 ms tick against a crashed client — turning the probe into the
     * hang — so this reads a few plain client fields directly. The probe must not depend on the
     * thread it is probing.
     *
     * @return {@code true} when the game is ready and the tick may run
     */
    private boolean awaitGameReady() {
        long now = System.currentTimeMillis();
        GameReadinessMonitor.Readiness readiness = gameReadiness.update(sampleGame(), now);

        if (readiness == GameReadinessMonitor.Readiness.READY) {
            if (gameHoldReason != null) {
                log.info("[AScript] Game ready after {} ms — resuming", now - gameHoldSince);
                gameHoldReason = null;
                gameHoldSince = 0;
            }
            return true;
        }

        // Only an automation request turns a hold into a timed stop. With the switch off — or no
        // module selected — the player may sit on a loading screen for as long as they like; the
        // hold still blocks the tick (QOL has nothing to act on while the world is loading).
        boolean automationRequested = config != null && config.enabled()
                && config.scriptSelection() != ScriptType.IDLE;

        if (gameHoldReason != readiness) {
            gameHoldReason = readiness;
            // Nothing else writes Microbot.status while the tick is held, so the overlay's status
            // line can carry the reason without racing the QOL block.
            Microbot.status = "WAITING FOR GAME — " + readiness.description();
            log.info("[AScript] Holding the tick — game not ready: {}", readiness.description());
        }
        if (gameHoldSince == 0) {
            gameHoldSince = now;
        }

        if (automationRequested) {
            currentPhase = Phase.WAITING;
            if (now - gameHoldSince >= GAME_READY_TIMEOUT_MS) {
                stopWithMessage("aScript Stopped — Game Not Ready",
                        "The game was not ready for " + (GAME_READY_TIMEOUT_MS / 1000) + " s ("
                                + readiness.description() + ") — the client may still be loading,"
                                + " or it crashed");
            }
        }
        return false;
    }

    /**
     * One cheap snapshot of the client for {@link #awaitGameReady()}.
     * <p>
     * Only built-in primitives are read: {@code Microbot.isLoggedIn()} (the RuneLite
     * {@code GameState.LOGGED_IN} check), the local-player reference, the top-level scene base and
     * the client game cycle. The cycle is the liveness signal — it advances every 20 ms with the
     * client loop, while the server tick counter pauses during lag and region loads.
     */
    private static GameReadinessMonitor.Sample sampleGame() {
        var client = Microbot.getClient();
        if (client == null || !Microbot.isLoggedIn()) {
            return new GameReadinessMonitor.Sample(false, false, false, false, -1);
        }
        var worldView = client.getTopLevelWorldView();
        boolean regionLoaded = worldView != null && worldView.getBaseX() > 0;
        return new GameReadinessMonitor.Sample(
                true, client.getLocalPlayer() != null, regionLoaded, welcomeScreenOpen(client),
                client.getGameCycle());
    }

    /**
     * The welcome ("click here to play") screen is still up.
     * <p>
     * Read straight from the client's widget array instead of {@code Rs2Widget.isWidgetVisible},
     * which hops to the client thread: the readiness probe must answer even when that thread is the
     * thing that died, or the probe becomes the hang. Same read as
     * {@code WelcomeScreenEvent.validate()}, without the hop.
     */
    private static boolean welcomeScreenOpen(Client client) {
        try {
            var play = client.getWidget(InterfaceID.WelcomeScreen.PLAY);
            return play != null && !play.isHidden();
        } catch (RuntimeException clientNotReady) {
            return false;
        }
    }

    /** Drops the hold state — the next sample starts the gate from scratch. */
    private void resetGameHold() {
        gameReadiness.reset();
        gameHoldReason = null;
        gameHoldSince = 0;
    }

    /**
     * Apply the configured module chain to the selection read from config.
     * <p>
     * The chain owns only the transition rule, not module internals: a self-stop writes
     * {@link ScriptType#IDLE} (the existing module contract), and this method turns that
     * IDLE into the next configured step. A hard orchestrator stop disables automation and
     * never reaches this method; the only orchestrator stop that is chainable is the
     * "No Materials" verdict, handled in {@link #stopWithMessage(String, String, boolean)}.
     */
    private ScriptType applyConfiguredChain(ScriptType selection) {
        if (!config.chainEnabled()) {
            resetChainSession();
            return selection;
        }

        String spec = config.chainModules() == null ? "" : config.chainModules().trim();
        if (!spec.equals(chainSpecCache)) {
            chainSpecCache = spec;
            chainParseErrorLogged = false;
            chainStatus = null;
            try {
                moduleChain = ModuleChain.parse(spec);
                String settingsError = ChainSettings.validate(moduleChain);
                if (settingsError != null) {
                    moduleChain = null;
                    throw new IllegalArgumentException(settingsError);
                }
                if (!moduleChain.isEmpty()) {
                    log.info("[AScript] Module chain configured: {}", describeChain(moduleChain));
                }
            } catch (IllegalArgumentException ex) {
                moduleChain = null;
                if (!chainParseErrorLogged) {
                    chainParseErrorLogged = true;
                    log.warn("[AScript] Invalid module chain '{}': {}", spec, ex.getMessage());
                }
            }
        }

        if (moduleChain == null || moduleChain.isEmpty()) {
            chainStatus = null;
            return selection;
        }
        if (moduleChain.isFinished()) {
            // A finished chain does not auto-start on the next IDLE tick, but a manual
            // selection of one of its steps is a deliberate restart.
            if (selection != ScriptType.IDLE && moduleChain.contains(selection)) {
                ModuleChain.Step seeded = moduleChain.startAtStep(selection);
                if (seeded != null && applyStepSettings(seeded)) {
                    resetChainStepState();
                    updateChainStatus();
                    log.info("[AScript] Chain restarted at manual selection: {}", chainStatus);
                } else {
                    rejectChainStep();
                    return ScriptType.IDLE;
                }
            } else {
                chainStatus = null;
            }
            return selection;
        }
        if (!config.enabled()) {
            // An IDLE selection while automation is off is not a module stop: do not let a
            // stale cursor turn it into an advance when the switch is turned back on.
            if (selection == ScriptType.IDLE && moduleChain != null) {
                moduleChain.reset();
                chainStatus = null;
            }
            return selection;
        }

        if (!moduleChain.isStarted()) {
            if (selection == ScriptType.IDLE) {
                ModuleChain.Step first = moduleChain.startStep();
                if (first != null && applyStepSettings(first)) {
                    resetChainStepState();
                    setScriptSelection(first.getType());
                    updateChainStatus();
                    log.info("[AScript] Chain started: {}", chainStatus);
                    return first.getType();
                }
                if (first != null) {
                    rejectChainStep();
                    return ScriptType.IDLE;
                }
            } else if (moduleChain.contains(selection)) {
                // A manual selection of a configured module seeds the chain at that step.
                ModuleChain.Step seeded = moduleChain.startAtStep(selection);
                if (seeded != null && applyStepSettings(seeded)) {
                    resetChainStepState();
                    updateChainStatus();
                } else {
                    rejectChainStep();
                    return ScriptType.IDLE;
                }
            }
            return selection;
        }

        if (selection == moduleChain.current()) {
            updateChainStatus();
            return selection;
        }
        if (selection == ScriptType.IDLE) {
            return advanceChain("module stopped");
        }
        if (moduleChain.contains(selection)) {
            ModuleChain.Step seeded = moduleChain.startAtStep(selection);
            if (seeded != null && applyStepSettings(seeded)) {
                resetChainStepState();
                updateChainStatus();
                log.info("[AScript] Chain restarted at manual selection: {}", chainStatus);
                return selection;
            }
            rejectChainStep();
            return ScriptType.IDLE;
        }

        // The user picked a module outside the configured chain: honour the manual choice
        // and end this chain session instead of fighting the dropdown.
        log.info("[AScript] Chain stopped: manual selection {} is outside the chain",
                selection.getName());
        moduleChain.finish();
        chainStatus = null;
        return selection;
    }

    /**
     * Move to the next chain step. Returns {@link ScriptType#IDLE} when the chain ends without
     * looping. The next selection is written to config here; the caller only has to carry the
     * returned value through the current tick.
     */
    private ScriptType advanceChain(String reason) {
        if (moduleChain == null || !moduleChain.isStarted()) {
            return ScriptType.IDLE;
        }
        ModuleChain.Step previous = moduleChain.currentStep();
        ModuleChain.Step next = moduleChain.advanceStep(config.chainLoop());
        if (next == null) {
            chainStatus = null;
            setScriptSelection(ScriptType.IDLE);
            Microbot.status = "CHAIN — finished";
            log.info("[AScript] Chain finished after {}: {} ({} step(s))",
                    reason, previous == null ? null : previous.getType(), moduleChain.size());
            return ScriptType.IDLE;
        }
        if (!applyStepSettings(next)) {
            rejectChainStep();
            return ScriptType.IDLE;
        }
        resetChainStepState();
        setScriptSelection(next.getType());
        updateChainStatus();
        Microbot.status = "CHAIN — starting " + next.getType().getName();
        log.info("[AScript] Chain advanced after {}: {} -> {}", reason,
                previous == null ? null : previous.getType(), next.getType());
        return next.getType();
    }

    /**
     * Chainable orchestrator stop: if a next step exists, select it and keep automation
     * enabled. Hard failures never call this path, so a broken bank/login/config still stops
     * the whole run instead of marching through the chain.
     */
    private boolean advanceChainAfterStop(String reason) {
        if (config == null || !config.chainEnabled()
                || moduleChain == null || !moduleChain.isStarted()) {
            return false;
        }
        ModuleChain.Step previous = moduleChain.currentStep();
        ModuleChain.Step next = moduleChain.advanceStep(config.chainLoop());
        if (next == null) {
            chainStatus = null;
            setScriptSelection(ScriptType.IDLE);
            log.info("[AScript] Chain finished after {}: {}",
                    reason, previous == null ? null : previous.getType());
            return false;
        }
        if (!applyStepSettings(next)) {
            rejectChainStep();
            return false;
        }
        resetChainStepState();
        setScriptSelection(next.getType());
        updateChainStatus();
        stopRequested = false;
        lastStopMessage = null;
        currentPhase = Phase.IDLE;
        Microbot.status = "CHAIN — starting " + next.getType().getName();
        log.warn("[AScript] Chain advanced after {}: {} -> {}", reason,
                previous == null ? null : previous.getType(), next.getType());
        AScriptNotify.notify("aScript chain", reason + " — starting "
                + next.getType().getName());
        return true;
    }

    /** Apply a step's config overrides before its module is selected. */
    private boolean applyStepSettings(ModuleChain.Step step) {
        if (step == null || step.getSettings().isEmpty()) {
            return true;
        }
        String error = ChainSettings.apply(Microbot.getConfigManager(), step);
        if (error == null) {
            log.info("[AScript] Chain step {} applied {} config override(s)",
                    step.getType(), step.getSettings().size());
            return true;
        }
        log.warn("[AScript] Chain step {} has invalid config: {}", step.getType(), error);
        Microbot.status = "CHAIN — config error: " + error;
        return false;
    }

    /** The selected step could not be configured: do not run it with stale settings. */
    private void rejectChainStep() {
        if (moduleChain != null) {
            moduleChain.finish();
        }
        chainStatus = null;
        setScriptSelection(ScriptType.IDLE);
    }

    /** Start a repeated module step from a clean run scope, even when its type did not change. */
    private void resetChainStepState() {
        for (AModule mod : MODULES) {
            wasActive.put(mod, false);
        }
        runKeys.clear();
        AScriptBank.resetSessionState();
        resetRunCounters();
    }

    private void updateChainStatus() {
        if (moduleChain == null || !moduleChain.isStarted()) {
            chainStatus = null;
            return;
        }
        ModuleChain.Step step = moduleChain.currentStep();
        String label = step == null ? "?" : step.getType().getName();
        if (step != null && !step.getSettings().isEmpty()) {
            label += " [" + step.getSettings().size() + " override(s)]";
        }
        chainStatus = label + " (" + moduleChain.position() + "/" + moduleChain.size() + ")";
    }

    private static String describeChain(ModuleChain chain) {
        StringBuilder text = new StringBuilder();
        for (ModuleChain.Step step : chain.entries()) {
            if (text.length() > 0) {
                text.append(" -> ");
            }
            text.append(step.toConfigText());
        }
        return text.toString();
    }

    private void setScriptSelection(ScriptType selection) {
        if (Microbot.getConfigManager() != null) {
            Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP,
                    "scriptSelection", selection);
        }
    }

    /** Drop the chain session; the configured text and its parsed steps are re-read next tick. */
    private void resetChainSession() {
        moduleChain = null;
        chainSpecCache = null;
        chainParseErrorLogged = false;
        chainStatus = null;
    }

    private void tick() {
        ScriptType selection = config == null ? ScriptType.IDLE : config.scriptSelection();
        if (config != null) {
            selection = applyConfiguredChain(selection);
        }
        if (selection != lastSelection) {
            // A different module starts from zero: a bank/missing streak earned by the
            // previous selection must never decide the new one's fate. QOL state is
            // independent of the selection and is deliberately left alone.
            lastSelection = selection;
            lastSelectionError = null;
            lastStopMessage = null;
            AScriptBank.resetSessionState();
            resetRunCounters();
        }

        if (config == null) {
            return;
        }

        // 1. QOL — not automation: runs whenever the plugin is on, regardless of the
        //    automation switch and the selected script. Each feature owns its own toggle.
        runQol(config);

        // 2. Automation gate. The switch grants permission to run a module; IDLE means the
        //    permission is granted but no module is selected. Both are distinct from the
        //    plugin being off, where this loop does not run at all.
        if (!config.enabled()) {
            currentPhase = Phase.DISABLED;
            stopRequested = false;
            idleHoldAnnounced = false;
            enterAutomationIdle();
            return;
        }
        // Re-enabling automation clears the previous stop banner, even before a script is
        // picked (the banner belongs to the stopped run, not to the idle selection).
        lastStopMessage = null;
        if (selection == ScriptType.IDLE) {
            currentPhase = Phase.IDLE;
            stopRequested = false;
            if (!idleHoldAnnounced) {
                idleHoldAnnounced = true;
                log.info("[AScript] Automation idle — no script selected");
            }
            enterAutomationIdle();
            return;
        }
        idleHoldAnnounced = false;

        // From here on this tick is automation. `runWasActive` is armed only after the
        // selection validates (below): a configuration hold never opens a bank, so it must not
        // make the idle branch close one the player opened.

        // 3. Resolve all module phases + detect IDLE → active and sub-selection transitions
        for (AModule mod : MODULES) {
            boolean prevActive = wasActive.getOrDefault(mod, false);
            mod.resolvePhase(config);
            boolean active = mod.isActive();
            if (active) {
                String runKey = mod.runKey(config);
                String previousKey = runKeys.put(mod, runKey);
                if (!prevActive) {
                    mod.resetExitFlag();
                } else if (previousKey != null && !previousKey.equals(runKey)) {
                    // The same module but a different activity/item/location: the previous
                    // batch latch, anchor and failure streak belong to the old scope.
                    mod.resetExitFlag();
                }
            } else {
                runKeys.remove(mod);
            }
            wasActive.put(mod, active);
        }

        // 4. Validate the selection (first error wins). Every module is asked, not only the
        //    active ones: a module whose activity is unset resolves to NONE, and that is
        //    precisely the state its validateSelection reports on (the activity enums default
        //    to NONE, so this is the first-run state of a freshly selected module).
        String selectionError = null;
        AModule invalidModule = null;
        for (AModule mod : MODULES) {
            String error = mod.validateSelection(config);
            if (error != null) {
                selectionError = error;
                invalidModule = mod;
                break;
            }
        }
        if (selectionError != null) {
            if (invalidModule != null && !invalidModule.isActive()) {
                // Selected but not configured yet. This is a config hold, not a failure: the
                // run starts as soon as the user picks the activity, so the plugin must not
                // disable itself in the middle of the config panel.
                if (!selectionError.equals(lastSelectionError)) {
                    lastSelectionError = selectionError;
                    log.info("[AScript] Waiting for configuration: {}", selectionError);
                }
                Microbot.status = "CONFIGURE — " + selectionError;
                currentPhase = Phase.IDLE;
                return;
            }
            stopWithMessage("aScript Stopped", selectionError);
            return;
        }
        lastSelectionError = null;

        // The selection is valid, so this tick may reach the bank/dispatch steps: a later
        // disable/self-stop is the running → idle transition that owns the one-shot bank
        // cleanup in enterAutomationIdle().
        runWasActive = true;

        // 5. Find the active module that needs the bank (at most one). A module may
        //    report that it is not ready for the bank cycle yet (readyForBank): it still
        //    has preparation to do, e.g. walk to the bank area. The tick then falls
        //    through to its doAction() so the module can prepare, and the open/cache
        //    cycle runs on a later tick, once the module reports ready.
        AModule bankModule = null;
        for (AModule mod : MODULES) {
            if (mod.isActive() && mod.needsBank(config) && mod.readyForBank(config)) {
                bankModule = mod;
                break;
            }
        }

        if (bankModule != null) {
            // Lamp-vs-bank gate: an XP lamp in the inventory blocks banking — lamps are
            // never deposited and must be used first. Yielding here (before the bank can
            // open) lets the lamp-use blocking event fire; banking resumes without it.
            if (LampUtility.yieldBankingToLamp(config.eventDismissLampSkill())) {
                currentPhase = Phase.BANKING;
                return;
            }

            // Open bank to check stock. Retried next tick — but not forever: without a
            // bound, a bank the module cannot reach would freeze the tick before its
            // doAction() is ever dispatched again (the silent hang). Stop loudly.
            if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
                currentPhase = Phase.BANKING;
                consecutiveBankOpenFailures++;
                log.debug("[AScript] Bank did not open ({}/{})",
                        consecutiveBankOpenFailures, MAX_BANK_OPEN_FAILURES);
                if (consecutiveBankOpenFailures >= MAX_BANK_OPEN_FAILURES) {
                    stopWithMessage("aScript Stopped — Bank Unreachable",
                            "the bank did not open " + consecutiveBankOpenFailures
                                    + " times in a row — check that it is reachable");
                }
                return;
            }
            consecutiveBankOpenFailures = 0;

            // aScript modules can only use base items, never banknotes: put the bank into
            // "Withdraw as item" mode before any verdict/withdraw. The player's "Withdraw as
            // note" setting is restored when the bank phase ends.
            if (!AScriptBank.ensureItemWithdrawMode()) {
                currentPhase = Phase.BANKING;
                consecutiveWithdrawModeFailures++;
                log.warn("[AScript] Bank would not switch to 'Withdraw as item' ({}/{})",
                        consecutiveWithdrawModeFailures, MAX_WITHDRAW_MODE_FAILURES);
                if (consecutiveWithdrawModeFailures >= MAX_WITHDRAW_MODE_FAILURES) {
                    stopWithMessage("aScript Stopped — Bank Withdraw Mode",
                            "the bank would not switch to 'Withdraw as item' after "
                                    + consecutiveWithdrawModeFailures + " attempts — set it manually");
                }
                return;
            }
            consecutiveWithdrawModeFailures = 0;

            if (bankModule.isBankMissingMaterials(config)) {
                // A "missing" verdict ends the run, and it is read from the cached bank
                // mirror (refreshed only by bank container events). Confirm it before
                // stopping: the verdict must hold MISSING_CONFIRMATION_TICKS times in a row
                // on a LIVE mirror — the retrying hasBankItem reads inside the modules get
                // that window to correct a stale read, and a restored (non-live) snapshot is
                // never trusted as "empty". Bounded by MISSING_MAX_TICKS so an unreadable
                // mirror cannot hold the tick forever.
                consecutiveMissingVerdicts++;
                int epoch = Rs2Bank.getBankLiveEpoch();
                boolean mirrorLive = epoch > 0;
                log.debug("[AScript] Missing-materials verdict {}/{} from {} (bank epoch {}, live={}): {}",
                        consecutiveMissingVerdicts, MISSING_CONFIRMATION_TICKS,
                        bankModule.getClass().getSimpleName(), epoch, mirrorLive,
                        bankModule.describeMissing(config));
                if (consecutiveMissingVerdicts >= MISSING_MAX_TICKS) {
                    stopWithMessage("aScript Stopped — Bank State Unreadable ("
                                    + bankModule.getClass().getSimpleName() + ")",
                            bankModule.describeMissing(config)
                                    + " — the bank mirror never went live, so it cannot be trusted");
                } else if (mirrorLive && consecutiveMissingVerdicts >= MISSING_CONFIRMATION_TICKS) {
                    stopWithMessage("aScript Stopped — No Materials ("
                                    + bankModule.getClass().getSimpleName() + ")",
                            bankModule.describeMissing(config), true);
                }
                return;
            }
            consecutiveMissingVerdicts = 0;
            // Bank HAS materials — fall through to BANKING
        } else {
            // No module needs bank — restore the player's withdraw mode, then close the bank.
            // The close is a HARD precondition for dispatch: an open bank interface blocks
            // production interfaces (make-X, smelt) for any module, so dispatching doAction
            // with the bank open stalls the module ("production interface did not open"). The
            // close itself falls back between ESC and the widget click inside closeBank(); if
            // the bank is STILL open after that, retry next tick — and stop loudly after the
            // bound instead of looping forever against an open bank.
            AScriptBank.restoreWithdrawMode();
            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                if (Rs2Bank.isOpen()) {
                    consecutiveBankCloseFailures++;
                    log.warn("[AScript] Bank still open after closeBank() ({}/{}); not dispatching doAction",
                            consecutiveBankCloseFailures, MAX_BANK_CLOSE_FAILURES);
                    if (consecutiveBankCloseFailures >= MAX_BANK_CLOSE_FAILURES) {
                        stopWithMessage("aScript Stopped — Bank Will Not Close",
                                "the bank did not close after " + consecutiveBankCloseFailures
                                        + " attempts — close it manually (ESC or the bank X button)");
                    }
                    return;
                }
            }
            consecutiveBankCloseFailures = 0;
        }

        // 6. Dispatch
        if (bankModule != null) {
            // A bank cycle that needs a locked anchor (tool, mould, rune stack, keeper set) cannot
            // run without bank slot locking: AScriptBank refuses those cycles and records why, so
            // report it here once with a dedicated message instead of burning the generic
            // "doBank failed" retries on a capability gap the user has to fix in the bank settings.
            String capabilityFailure = AScriptBank.takeBankCapabilityFailure();
            if (capabilityFailure != null) {
                stopWithMessage("aScript Stopped — Bank Slot Locking Disabled", capabilityFailure);
                return;
            }
            currentPhase = Phase.BANKING;
            boolean banked = bankModule.doBank(config);
            if (!banked) {
                // A helper refuses a locking-dependent cycle and records why (AScriptBank); report
                // it on THIS tick with the dedicated message instead of spending the generic
                // doBank-failure retries on a capability gap the user has to fix in bank settings.
                String cycleCapabilityFailure = AScriptBank.takeBankCapabilityFailure();
                if (cycleCapabilityFailure != null) {
                    stopWithMessage("aScript Stopped — Bank Slot Locking Disabled", cycleCapabilityFailure);
                    return;
                }
            }
            if (banked) {
                consecutiveBankFailures = 0;
                consecutiveBankOpenFailures = 0;
            } else {
                consecutiveBankFailures++;
                if (consecutiveBankFailures >= MAX_BANK_FAILURES) {
                    // Deliberately distinct from the "No Materials" verdict: here the bank
                    // opened, the module believes it has materials, and the deposit/withdraw
                    // still did not land. The module's own WARN lines name the item and step.
                    stopWithMessage("aScript Stopped — Bank Cycle Failed ("
                                    + bankModule.getClass().getSimpleName() + ")",
                            "doBank failed " + consecutiveBankFailures
                                    + " times in a row — the bank opened but the deposit/withdraw did not land"
                                    + " (see the AScriptBank WARN lines for the item)");
                }
            }
        } else {
            // Assume no module runs this tick; only an actual dispatch promotes the phase. This
            // also clears a sticky RUNNING left behind by a module that just became inactive.
            currentPhase = Phase.IDLE;
            for (AModule mod : MODULES) {
                if (mod.isActive()) {
                    currentPhase = Phase.RUNNING;
                    mod.doAction(config);
                    break;
                }
            }
        }
    }

    /**
     * Leave the automation dispatch path. Clears the IDLE → active edge state, restores the
     * player's bank settings, and closes a bank THIS run may have left open.
     * <p>
     * The close is gated on {@link #runWasActive} and clears it: with automation off no tick
     * enters the dispatch path, so a bank the player opens must stay open. Never infer "a run
     * left the bank open" from the selection — it persists across the automation switch.
     * <p>
     * QOL state is intentionally untouched: QOL keeps running while automation is off.
     */
    private void enterAutomationIdle() {
        // A self-stopped module leaves wasActive == true; clearing it here arms the user's
        // reselect to run the IDLE → active edge that calls resetExitFlag().
        for (AModule mod : MODULES) {
            wasActive.put(mod, false);
        }
        runKeys.clear();
        AScriptBank.restoreWithdrawMode();
        AScriptBank.resetSessionState();
        if (runWasActive) {
            runWasActive = false;
            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
            }
        }
        resetRunCounters();
    }

    /**
     * Zeroes every <b>automation</b> run counter — on a selection change, on entering the idle
     * state, and in {@link #shutdown()}. QOL state (the eat threshold and tracker) is owned by
     * {@link #resetQolState()} and deliberately survives automation stops.
     */
    private void resetRunCounters() {
        consecutiveBankFailures = 0;
        consecutiveBankOpenFailures = 0;
        consecutiveBankCloseFailures = 0;
        consecutiveWithdrawModeFailures = 0;
        consecutiveMissingVerdicts = 0;
    }

    // ── QOL: auto-eat ───────────────────────────────────────

    /**
     * Rolls a new auto-eat threshold: normal-distributed between the config
     * bounds, shifted down when real-world weather is bad.
     */
    private void rollEatThreshold() {
        int min = Math.min(config.autoEatMinHpPercent(), config.autoEatMaxHpPercent());
        int max = Math.max(config.autoEatMinHpPercent(), config.autoEatMaxHpPercent());
        int roll = Rs2Random.fancyNormalSample(min, max);

        WeatherModulation.ensureFresh();
        int weatherOffset = (int) Math.round((WeatherModulation.combinedSpeedFactor() - 1.0) * 15);
        eatThreshold = Math.max(Math.max(1, min - 5), Math.min(max, roll + weatherOffset));
    }

    /**
     * True when hitpoints are at or below the rolled auto-eat threshold.
     * <p>
     * Compares the hitpoint LEVELS instead of {@link Rs2Player#getHealthPercentage()}, which
     * divides by the real level: while that level still reads 0 (skill data not loaded yet —
     * the client-thread read also falls back to 0 on timeout) the ratio is NaN (0/0) or
     * Infinity, every comparison against it is false, and the auto-eat silently does nothing.
     * An unloaded level returns false here and the next tick retries — which also keeps
     * {@link Rs2Player#eatAt(int)}'s own division away from the zero denominator.
     * <p>
     * Same "at or below" semantics as the ratio it replaces.
     */
    private boolean belowEatThreshold() {
        int real = Rs2Player.getRealSkillLevel(Skill.HITPOINTS);
        if (real <= 0) return false; // hitpoint level not loaded — retry next tick
        return (double) (Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS) * 100) / real <= eatThreshold;
    }

    /**
     * QOL features are intentionally outside the automation gate: enabling the plugin is the
     * only master switch, and each feature is gated by its own config toggle. The random-event
     * handler is plugin-scoped already (registered in {@code AScriptPlugin.startUp()}); this
     * covers the tick-driven QOL features.
     */
    private void runQol(AScriptConfig config) {
        if (config.autoZoomOut()
                && System.currentTimeMillis() - lastZoomOutTime > 60_000) {
            lastZoomOutTime = System.currentTimeMillis();
            Rs2Camera.zoomOutFully();
        }
        runAutoEat(config);
    }

    /**
     * Auto-eat QOL, asynchronous and automation-independent.
     * <p>
     * A dispatched Eat/Drink is confirmed over the following ticks by the {@link EatTracker}:
     * the food count dropped or hitpoints rose. Nothing blocks this 600 ms loop, so a human
     * takeover, a pause, lag or a late bite cannot be mistaken for a failed bite.
     * <p>
     * There is no strike system and no suspension: an unconfirmed attempt only schedules a
     * log-normal retry. The feature is skipped while a food-owning combat module is running
     * (see {@link #autoEatEnabled}) and on ticks where no bite is due.
     */
    private void runAutoEat(AScriptConfig config) {
        if (!autoEatEnabled(config)) {
            resetQolState();
            return;
        }

        WeatherModulation.ensureFresh();
        double weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();
        long now = System.currentTimeMillis();

        EatTracker.Outcome outcome = eatTracker.resolve(now,
                AScriptFood.edibleCount(),
                Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS));
        if (outcome == EatTracker.Outcome.IN_FLIGHT) {
            return; // one attempt at a time; never double-dispatch while unconfirmed
        }
        if (outcome == EatTracker.Outcome.SUCCESS) {
            rollEatThreshold();
            // Fall through: a second bite may be due immediately if HP is still low.
        } else if (outcome == EatTracker.Outcome.TIMED_OUT) {
            long retry = Rs2Random.logNormalBounded(600, 2000, weatherMultiplier);
            eatTracker.scheduleRetry(now, retry);
            logEatMiss(now, retry);
        }

        if (eatThreshold <= 0) {
            rollEatThreshold();
        }
        if (!eatTracker.ready(now)) return;
        // Hitpoint check FIRST: eatReady() has a side effect (it clicks to cancel a pending
        // selection) and must not run on ticks where no bite is due.
        if (!belowEatThreshold()) return;
        if (!eatReady()) return;

        int foodBefore = AScriptFood.edibleCount();
        if (foodBefore <= 0) return;
        int hpBefore = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);
        if (hpBefore <= 0) return; // skill data not loaded — retry, never baseline on 0
        Rs2ItemModel food = AScriptFood.bestFood();
        if (food == null) return;

        AScriptFood.Dispatch dispatch = AScriptFood.dispatch(food);
        if (dispatch == AScriptFood.Dispatch.ISSUED) {
            // Baselines were captured BEFORE the dispatch; the confirmation window starts
            // after the press so hover travel does not eat into it.
            eatTracker.begin(System.currentTimeMillis(), foodBefore, hpBefore,
                    Rs2Random.logNormalBounded(1200, 2500, weatherMultiplier));
            return;
        }
        if (dispatch == AScriptFood.Dispatch.NO_ACTION) {
            // The dispatch may have spent time hovering; the close cooldown starts after it.
            handleBlockedEatInterface(System.currentTimeMillis(), weatherMultiplier);
        } else if (dispatch == AScriptFood.Dispatch.BLOCKED) {
            // A menu/selection/pointer state prevented the press. Do not hammer every tick;
            // the retry gate is short and is not a strike.
            eatTracker.scheduleRetry(System.currentTimeMillis(),
                    Rs2Random.logNormalBounded(600, 1500, weatherMultiplier));
        }
        // NO_FOOD: nothing to dispatch; retry next tick.
    }

    /**
     * The food is in the inventory, but the visible container exposed no Eat/Drink row.
     * Close the interface and eat from the normal inventory on a later tick. The bank and
     * the deposit box are supported by the game (9 Jan 2020, "Eating in the Bank"), so they
     * are never closed here — a missing row there is a dispatch bug, not an interface.
     * A close/retry is not an eat attempt: no tracker attempt is opened. Only a short retry
     * gate stops a stuck interface from being hammered every 600 ms.
     */
    private void handleBlockedEatInterface(long now, double weatherMultiplier) {
        if (Rs2Bank.isBankPinWidgetVisible()) {
            log.debug("[AScript] Auto-eat skipped: the bank PIN interface is open");
            eatTracker.scheduleRetry(now, Rs2Random.logNormalBounded(1000, 3000, weatherMultiplier));
            return;
        }
        if (Rs2Bank.isOpen()) {
            log.debug("[AScript] Auto-eat: the bank inventory exposed no Eat row");
            eatTracker.scheduleRetry(now, Rs2Random.logNormalBounded(1000, 3000, weatherMultiplier));
            return;
        }
        if (Rs2DepositBox.isOpen()) {
            log.debug("[AScript] Auto-eat: the deposit-box inventory exposed no Eat row");
            eatTracker.scheduleRetry(now, Rs2Random.logNormalBounded(1000, 3000, weatherMultiplier));
            return;
        }
        if (now < nextInterfaceCloseAt) {
            return; // a close was just issued — let it render before judging it
        }
        if (Rs2GrandExchange.isOpen()) {
            log.debug("[AScript] Auto-eat: closing the Grand Exchange to eat");
            nextInterfaceCloseAt = now + 1000;
            Rs2GrandExchange.closeExchange();
            return;
        }
        if (Rs2Shop.isOpen()) {
            log.debug("[AScript] Auto-eat: closing the shop to eat");
            nextInterfaceCloseAt = now + 1000;
            Rs2Shop.closeShop();
            return;
        }

        Widget inventory = Rs2Inventory.getInventory();
        if (inventory == null) {
            // The inventory tab is closed and no interface is open; the next tick retries
            // after the recipe's tab switch. There is nothing to close.
            long retry = Rs2Random.logNormalBounded(600, 1200, weatherMultiplier);
            eatTracker.scheduleRetry(now, retry);
            return;
        }
        if (inventory.getId() == ComponentID.INVENTORY_CONTAINER) {
            // Nothing to close: this food genuinely exposes no Eat/Drink action.
            long retry = Rs2Random.logNormalBounded(2000, 5000, weatherMultiplier);
            eatTracker.scheduleRetry(now, retry);
            log.debug("[AScript] Auto-eat: the selected food exposes no consume action; retrying in {} ms", retry);
            return;
        }

        // Some other interface is showing its own inventory container.
        if (Rs2Settings.isEscCloseInterfaceSettingEnabled()) {
            log.debug("[AScript] Auto-eat: closing an interface with ESC to eat");
            nextInterfaceCloseAt = now + 1000;
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
        } else {
            nextInterfaceCloseAt = now + 3000;
            log.debug("[AScript] Auto-eat: an interface blocks the inventory and ESC-close is disabled");
        }
    }

    /** Rate-limited miss log: diagnostic only, the attempt is always retried. */
    private void logEatMiss(long now, long retryMs) {
        if (now - lastEatMissLogAt >= 30_000) {
            lastEatMissLogAt = now;
            log.warn("[AScript] Auto-eat: no bite observed within the confirmation window — retrying in {} ms", retryMs);
        } else {
            log.debug("[AScript] Auto-eat: no bite observed — retrying in {} ms", retryMs);
        }
    }

    /** Clear every QOL-owned state. Called when the feature is off and at lifecycle boundaries. */
    private void resetQolState() {
        eatThreshold = 0;
        lastEatMissLogAt = 0;
        nextInterfaceCloseAt = 0;
        eatTracker.reset();
    }

    /**
     * True when the auto-eat QOL may run for the current selection.
     * <p>
     * QOL is not automation: it runs while the automation switch is off, even when a
     * food-owning module is selected — that module is not ticking and owns nothing then.
     * While automation is on, Gem Crab Killer and Ammonite Crabs own their HP management
     * (GCK: 50% normal eat, 2% emergency, Dharok mode holding 10 HP; Ammonite: 50% eat and
     * bank restock), so the global eat stays out to avoid double-eating or healing a
     * deliberately low-HP setup. With automation off the user's QOL toggle decides.
     */
    private boolean autoEatEnabled(AScriptConfig config) {
        if (!config.autoEat()) return false;
        if (!config.enabled()) return true;
        return config.scriptSelection() != ScriptType.GEM_CRAB_KILLER
                && config.scriptSelection() != ScriptType.AMMONITE_CRABS;
    }

    /**
     * True when an Eat op can actually land: no spell/item is left selected on the cursor.
     * <p>
     * With a spell/item selected the next inventory op becomes "use the selected thing on
     * the food" instead of Eat/Drink. The pending selection is cancelled through
     * {@link AScriptClick#clearSelection()} (a click on the selected widget/item slot, not
     * at the current cursor position), then confirmed gone. When it cannot be cancelled the
     * attempt is skipped rather than dispatched as a wrong action — there is no strike
     * system to burn.
     * <p>
     * Note: {@code Rs2Inventory.deselect()} is NOT usable here — it re-uses the selected
     * widget's item id, which is -1 for a spell, so it no-ops.
     *
     * @return true when it is safe to dispatch the Eat
     */
    private boolean eatReady() {
        // The shared selection canceller clicks the selected widget (item slot or spell icon)
        // rather than wherever the cursor happens to be.
        if (AScriptClick.clearSelection()) {
            return true;
        }
        log.debug("[AScript] Auto-eat skipped: a spell/item is still selected on the cursor");
        return false;
    }

    // ── Lifecycle ───────────────────────────────────────────

    /**
     * Stop <b>automation</b>: notify Discord, clear the automation switch, and close a bank
     * this run left open. QOL features keep running — they are not automation. A module
     * self-stop is different: it writes {@link ScriptType#IDLE} to the selection and leaves
     * this switch alone.
     */
    private void stopWithMessage(String title, String message) {
        stopWithMessage(title, message, false);
    }

    /**
     * @param chainable {@code true} only for a module-completion stop (today: "No Materials").
     *                  When the chain has a next step, automation stays enabled and the next
     *                  module is selected; hard infrastructure failures pass {@code false}.
     */
    private void stopWithMessage(String title, String message, boolean chainable) {
        if (stopRequested) return; // already stopping
        if (chainable && advanceChainAfterStop(title + ": " + message)) {
            enterAutomationIdle();
            return;
        }
        stopRequested = true;
        consecutiveBankFailures = 0;
        consecutiveBankOpenFailures = 0;
        consecutiveBankCloseFailures = 0;
        lastStopMessage = message;
        resetGameHold();
        Microbot.status = "STOPPED — " + message;
        log.warn("[AScript] {}: {}", title, message);
        AScriptNotify.notify(title, message);
        AScriptBank.restoreWithdrawMode();
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
        }
        for (AModule mod : MODULES) {
            if (mod.isActive()) mod.resetExitFlag();
        }
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "enabled", false);
        currentPhase = Phase.ERROR;
    }

    @Override
    public void shutdown() {
        stopRequested = false;
        resetRunCounters();
        resetQolState();
        consecutiveTickErrors = 0;
        lastSelection = ScriptType.IDLE;
        lastSelectionError = null;
        lastStopMessage = null;
        moduleChain = null;
        chainSpecCache = null;
        chainParseErrorLogged = false;
        chainStatus = null;
        idleHoldAnnounced = false;
        runWasActive = false;
        resetGameHold();
        playerWasLoggedIn = false;
        reloginRecovery.reset();
        for (AModule mod : MODULES) {
            wasActive.put(mod, false);
            runKeys.remove(mod);
            mod.resetExitFlag();
        }
        // Restore the player's bank setting while the window is still open (the request stays
        // pending if it is closed), then drop the rest of the session state.
        AScriptBank.restoreWithdrawMode();
        AScriptBank.resetSessionState();
        AScriptClick.reset();
        currentPhase = Phase.DISABLED;
        super.shutdown();
    }
}
