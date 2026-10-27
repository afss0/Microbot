package net.runelite.client.plugins.microbot.ascript;

/**
 * Asynchronous confirmation state for the auto-eat QOL.
 * <p>
 * The tick dispatches an Eat/Drink and never waits for the bite: {@link #resolve} is polled
 * on the following ticks and reports whether the food count dropped or hitpoints rose. This
 * keeps the 600 ms script loop free, and turns human takeovers, pauses, lag and late bites
 * into ordinary observations instead of false failures.
 * <p>
 * There is deliberately no strike counter and no suspension. A {@link Outcome#TIMED_OUT}
 * attempt is cleared and the caller schedules a randomized retry through
 * {@link #scheduleRetry}.
 */
final class EatTracker {

    enum Outcome {
        /** No attempt is in flight. */
        NONE,
        /** An attempt was dispatched but neither signal is visible yet. */
        IN_FLIGHT,
        /** The bite is observable: food dropped or hitpoints rose. */
        SUCCESS,
        /** The confirmation window elapsed with no observable bite. */
        TIMED_OUT
    }

    private long attemptStartedAt;
    private long confirmDeadline;
    private int foodBefore;
    private int hpBefore;
    private long nextAttemptAt;

    /**
     * Resolve the in-flight attempt against the current observations. On {@link Outcome#SUCCESS}
     * and {@link Outcome#TIMED_OUT} the attempt is cleared; the caller decides whether to roll a
     * new threshold (success) or schedule a retry (timeout).
     *
     * @param now       wall-clock time in milliseconds
     * @param foodNow   current unnoted food count
     * @param hpNow     current boosted hitpoints
     */
    Outcome resolve(long now, int foodNow, int hpNow) {
        if (attemptStartedAt == 0) {
            return Outcome.NONE;
        }
        if (foodNow < foodBefore || hpNow > hpBefore) {
            clearAttempt();
            return Outcome.SUCCESS;
        }
        if (now < confirmDeadline) {
            return Outcome.IN_FLIGHT;
        }
        clearAttempt();
        return Outcome.TIMED_OUT;
    }

    /**
     * Record a dispatched attempt. The baselines must be captured <b>before</b> the dispatch:
     * a fast bite that lands before the baseline is read would otherwise look like no change.
     */
    void begin(long now, int foodBefore, int hpBefore, long confirmWindowMs) {
        this.attemptStartedAt = now;
        this.foodBefore = foodBefore;
        this.hpBefore = hpBefore;
        this.confirmDeadline = now + Math.max(0, confirmWindowMs);
        this.nextAttemptAt = 0;
    }

    /** Gate the next dispatch behind a randomized delay after an unconfirmed attempt. */
    void scheduleRetry(long now, long delayMs) {
        this.nextAttemptAt = now + Math.max(0, delayMs);
    }

    /** True when no attempt is in flight and the retry delay has elapsed. */
    boolean ready(long now) {
        return attemptStartedAt == 0 && now >= nextAttemptAt;
    }

    /** True while a dispatched attempt is still awaiting its confirmation window. */
    boolean inFlight() {
        return attemptStartedAt != 0;
    }

    /** Drop the attempt and the retry gate — feature disabled or lifecycle boundary. */
    void reset() {
        clearAttempt();
        nextAttemptAt = 0;
    }

    private void clearAttempt() {
        attemptStartedAt = 0;
        confirmDeadline = 0;
        foodBefore = 0;
        hpBefore = 0;
    }
}
