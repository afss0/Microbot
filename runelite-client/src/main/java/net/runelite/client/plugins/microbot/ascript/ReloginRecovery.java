package net.runelite.client.plugins.microbot.ascript;

/**
 * Re-login state machine for an unexpected logout while automation is on.
 * <p>
 * Tick-driven on purpose: the orchestrator loop runs every 600 ms and is also the overlay's
 * clock, so a multi-minute {@code sleep} inside it would freeze the countdown, the status line
 * and every other check. The machine only stores deadlines; the caller samples the delays
 * (weather-modulated log-normal) and polls it once per tick.
 * <p>
 * The shape is the project's backoff sequence: five attempts, waits of 15 s / 30 s / 60 s /
 * 120 s between them, each wait drawn by the caller so weather can stretch it. Every attempt
 * gets a settle window: {@code LoginManager.login()} only <i>dispatches</i> the login, so the
 * attempt is judged by {@code LOGGED_IN} arriving before the deadline, not by the call's
 * return value.
 * <p>
 * Pure and client-free: {@link #begin(long, int)} and the transitions take {@code nowMs} so the
 * whole truth table is unit-tested without a live client or wall clock.
 */
public final class ReloginRecovery {

    /** Hard bound on re-login dispatches before the automation is stopped. */
    public static final int MAX_ATTEMPTS = 5;

    /** Nominal wait after the first failed attempt; each later step doubles it. */
    private static final int BACKOFF_BASE_MS = 15_000;

    private enum State {
        /** Not recovering. */
        IDLE,
        /** Between attempts: the next dispatch is scheduled at {@link #nextAttemptAt}. */
        WAITING,
        /** An attempt was dispatched; waiting for the game to report LOGGED_IN. */
        SETTLING,
        /** Every attempt failed — the caller stops the automation. */
        EXHAUSTED
    }

    private State state = State.IDLE;
    private int attempts;
    private long nextAttemptAt;
    private long settleDeadline;

    /**
     * Starts a recovery: the first attempt is due after {@code firstWaitMs}.
     *
     * @param nowMs      wall clock of the logout detection
     * @param firstWaitMs sampled initial wait (weather-modulated by the caller)
     */
    public void begin(long nowMs, int firstWaitMs) {
        state = State.WAITING;
        attempts = 0;
        nextAttemptAt = nowMs + Math.max(0, firstWaitMs);
        settleDeadline = 0;
    }

    /** True while the machine is between attempts or waiting for a dispatched login. */
    public boolean isActive() {
        return state == State.WAITING || state == State.SETTLING;
    }

    /** True while an attempt is dispatched and the game has not reported LOGGED_IN yet. */
    public boolean isSettling() {
        return state == State.SETTLING;
    }

    /** True once every attempt failed. The caller stops the automation exactly once. */
    public boolean isExhausted() {
        return state == State.EXHAUSTED;
    }

    /** Attempts dispatched so far. */
    public int attempts() {
        return attempts;
    }

    /** Wall clock of the next dispatch, meaningful only in {@link State#WAITING}. */
    public long nextAttemptAt() {
        return nextAttemptAt;
    }

    /** True when the scheduled attempt may be dispatched now. */
    public boolean attemptDue(long nowMs) {
        return state == State.WAITING && nowMs >= nextAttemptAt;
    }

    /** True when the settle window of the dispatched attempt has elapsed. */
    public boolean settleExpired(long nowMs) {
        return state == State.SETTLING && nowMs >= settleDeadline;
    }

    /**
     * Records that an attempt is being dispatched and opens its settle window. Every dispatch
     * counts, including one the login API refuses (no profile, lockout, throttle): the bound
     * exists so a broken login cannot retry forever.
     *
     * @param nowMs           wall clock of the dispatch
     * @param settleTimeoutMs how long the game gets to report LOGGED_IN
     */
    public void attemptStarted(long nowMs, long settleTimeoutMs) {
        if (state != State.WAITING) {
            return;
        }
        attempts++;
        state = State.SETTLING;
        settleDeadline = nowMs + Math.max(0, settleTimeoutMs);
    }

    /**
     * Schedules the next attempt after a failed one, or marks the machine exhausted when the
     * attempt budget is spent. Called for a dispatch that never completed and for one that was
     * refused outright.
     *
     * @param nowMs  wall clock of the failure
     * @param waitMs sampled backoff before the next attempt (ignored when exhausting)
     */
    public void scheduleRetry(long nowMs, int waitMs) {
        if (attempts >= MAX_ATTEMPTS) {
            state = State.EXHAUSTED;
            nextAttemptAt = 0;
            settleDeadline = 0;
            return;
        }
        state = State.WAITING;
        nextAttemptAt = nowMs + Math.max(0, waitMs);
        settleDeadline = 0;
    }

    /** Clears the machine — a successful login, a shutdown or automation turned off. */
    public void reset() {
        state = State.IDLE;
        attempts = 0;
        nextAttemptAt = 0;
        settleDeadline = 0;
    }

    /**
     * Nominal exponential backoff after {@code failedAttempts} failures: 15 s, 30 s, 60 s,
     * 120 s. The caller applies the weather multiplier and the log-normal jitter; this is the
     * schedule itself, kept pure so the sequence is testable.
     *
     * @param failedAttempts number of failed attempts so far (1-based)
     * @return nominal wait in milliseconds, 0 when no attempt has failed yet
     */
    public static int backoffStepMs(int failedAttempts) {
        if (failedAttempts < 1) {
            return 0;
        }
        int doublings = Math.min(failedAttempts - 1, 3);
        return BACKOFF_BASE_MS << doublings;
    }
}
