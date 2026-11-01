package net.runelite.client.plugins.microbot.ascript;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Client-free tests for {@link ReloginRecovery}: the schedule, the attempt budget and the
 * settle window. Every transition is driven by an explicit {@code nowMs}, so the test never
 * sleeps.
 */
public class ReloginRecoveryTest {

    @Test
    public void firstAttemptWaitsForTheInitialDelay() {
        ReloginRecovery recovery = new ReloginRecovery();
        recovery.begin(1_000, 5_000);

        assertTrue(recovery.isActive());
        assertEquals(0, recovery.attempts());
        assertFalse(recovery.attemptDue(5_999));
        assertTrue(recovery.attemptDue(6_000));
    }

    @Test
    public void attemptOpensASettleWindow() {
        ReloginRecovery recovery = new ReloginRecovery();
        recovery.begin(0, 0);

        recovery.attemptStarted(100, 30_000);

        assertEquals(1, recovery.attempts());
        assertTrue(recovery.isSettling());
        assertFalse(recovery.settleExpired(30_099));
        assertTrue(recovery.settleExpired(30_100));
    }

    @Test
    public void failureSchedulesTheExponentialBackoff() {
        int[] expected = {15_000, 30_000, 60_000, 120_000};
        for (int attempt = 1; attempt <= expected.length; attempt++) {
            assertEquals(expected[attempt - 1], ReloginRecovery.backoffStepMs(attempt));
        }
        // The budget never dispatches a sixth attempt, so the sequence is clamped.
        assertEquals(120_000, ReloginRecovery.backoffStepMs(5));
        assertEquals(0, ReloginRecovery.backoffStepMs(0));
    }

    @Test
    public void fiveFailedAttemptsExhaustTheBudget() {
        ReloginRecovery recovery = new ReloginRecovery();
        long now = 0;
        recovery.begin(now, 0);

        for (int attempt = 1; attempt <= ReloginRecovery.MAX_ATTEMPTS; attempt++) {
            assertTrue("attempt " + attempt + " must be due", recovery.attemptDue(now));
            recovery.attemptStarted(now, 0);
            recovery.scheduleRetry(now, ReloginRecovery.backoffStepMs(attempt));
            now += 200_000;
        }

        assertTrue(recovery.isExhausted());
        assertFalse(recovery.isActive());
        assertEquals(ReloginRecovery.MAX_ATTEMPTS, recovery.attempts());
        assertFalse("an exhausted machine must not dispatch again", recovery.attemptDue(now));
    }

    @Test
    public void retryWaitsTheSampledBackoff() {
        ReloginRecovery recovery = new ReloginRecovery();
        recovery.begin(0, 0);
        recovery.attemptStarted(0, 10_000);
        recovery.scheduleRetry(0, 15_000);

        assertFalse(recovery.attemptDue(14_999));
        assertTrue(recovery.attemptDue(15_000));
    }

    @Test
    public void resetClearsEverything() {
        ReloginRecovery recovery = new ReloginRecovery();
        recovery.begin(0, 0);
        recovery.attemptStarted(0, 0);
        recovery.scheduleRetry(0, 0);
        recovery.reset();

        assertFalse(recovery.isActive());
        assertFalse(recovery.isExhausted());
        assertFalse(recovery.isSettling());
        assertEquals(0, recovery.attempts());
        assertFalse(recovery.attemptDue(Long.MAX_VALUE));
    }
}
