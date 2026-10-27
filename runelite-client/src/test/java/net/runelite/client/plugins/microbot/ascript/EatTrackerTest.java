package net.runelite.client.plugins.microbot.ascript;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guardrail for the asynchronous auto-eat confirmation: the bite is confirmed by a food-count
 * drop or an HP rise, baselines are pre-dispatch, and a missed attempt only schedules a retry
 * (there is no strike counter and no suspension).
 */
public class EatTrackerTest {

    @Test
    public void noAttemptResolvesToNone() {
        EatTracker tracker = new EatTracker();
        assertEquals(EatTracker.Outcome.NONE, tracker.resolve(1_000, 10, 50));
        assertTrue(tracker.ready(1_000));
        assertFalse(tracker.inFlight());
    }

    @Test
    public void foodDropConfirmsTheBite() {
        EatTracker tracker = new EatTracker();
        tracker.begin(1_000, 10, 50, 1_500);

        assertTrue(tracker.inFlight());
        assertFalse(tracker.ready(1_000));
        assertEquals(EatTracker.Outcome.SUCCESS, tracker.resolve(1_600, 9, 50));
    }

    @Test
    public void hpRiseConfirmsTheBite() {
        EatTracker tracker = new EatTracker();
        tracker.begin(1_000, 10, 50, 1_500);

        assertEquals(EatTracker.Outcome.SUCCESS, tracker.resolve(1_600, 10, 62));
    }

    @Test
    public void unchangedObservationInsideTheWindowIsInFlight() {
        EatTracker tracker = new EatTracker();
        tracker.begin(1_000, 10, 50, 1_500);

        assertEquals(EatTracker.Outcome.IN_FLIGHT, tracker.resolve(1_200, 10, 50));
        assertEquals(EatTracker.Outcome.IN_FLIGHT, tracker.resolve(2_499, 10, 50));
        assertTrue(tracker.inFlight());
    }

    @Test
    public void deadlineWithoutChangeTimesOutAndRespectsTheRetryGate() {
        EatTracker tracker = new EatTracker();
        tracker.begin(1_000, 10, 50, 1_500);

        assertEquals(EatTracker.Outcome.TIMED_OUT, tracker.resolve(2_500, 10, 50));
        assertFalse(tracker.inFlight());
        assertTrue("the attempt is clear; the caller may schedule a retry", tracker.ready(2_500));

        tracker.scheduleRetry(2_500, 800);
        assertFalse(tracker.ready(3_299));
        assertTrue(tracker.ready(3_300));
    }

    @Test
    public void aLateBiteStillConfirmsAfterTheWindowPassed() {
        EatTracker tracker = new EatTracker();
        tracker.begin(1_000, 10, 50, 1_500);

        // The observation is checked before the deadline comparison: a bite that landed late
        // (network lag, a slow frame) is still a success, never a false retry.
        assertEquals(EatTracker.Outcome.SUCCESS, tracker.resolve(4_000, 9, 50));
    }

    @Test
    public void beginClearsTheRetryGate() {
        EatTracker tracker = new EatTracker();
        tracker.scheduleRetry(1_000, 5_000);
        assertFalse(tracker.ready(2_000));

        tracker.begin(2_000, 4, 40, 1_000);
        assertFalse("an attempt in flight is never ready", tracker.ready(2_000));
        assertEquals(EatTracker.Outcome.SUCCESS, tracker.resolve(2_500, 3, 40));
        assertTrue(tracker.ready(2_500));
    }

    @Test
    public void resetClearsAttemptAndRetry() {
        EatTracker tracker = new EatTracker();
        tracker.begin(1_000, 10, 50, 1_500);
        tracker.scheduleRetry(1_000, 5_000);

        tracker.reset();
        assertFalse(tracker.inFlight());
        assertTrue(tracker.ready(0));
        assertEquals(EatTracker.Outcome.NONE, tracker.resolve(0, 10, 50));
    }
}
