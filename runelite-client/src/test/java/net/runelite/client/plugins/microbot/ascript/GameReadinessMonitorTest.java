package net.runelite.client.plugins.microbot.ascript;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Truth table for the game-readiness gate: the orchestrator must not dispatch on a client that is
 * still loading, must let the world SETTLE after a load before the first click (interfaces are laid
 * out a moment after the state says "logged in"), and a frozen client game cycle must surface as
 * its own verdict (a crashed client still reports {@code LOGGED_IN} with a stale player, so server
 * ticks are not a liveness signal).
 */
public class GameReadinessMonitorTest {

    private static final long T0 = 1_000_000;

    private static GameReadinessMonitor.Sample ready(int cycle) {
        return new GameReadinessMonitor.Sample(true, true, true, cycle);
    }

    @Test
    public void notLoggedInIsNotReady() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();

        GameReadinessMonitor.Readiness verdict = monitor.update(
                new GameReadinessMonitor.Sample(false, false, false, -1), T0);

        assertEquals(GameReadinessMonitor.Readiness.NOT_LOGGED_IN, verdict);
    }

    @Test
    public void missingLocalPlayerIsNotReady() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();

        GameReadinessMonitor.Readiness verdict = monitor.update(
                new GameReadinessMonitor.Sample(true, false, true, 10), T0);

        assertEquals(GameReadinessMonitor.Readiness.PLAYER_NOT_LOADED, verdict);
    }

    @Test
    public void missingRegionIsNotReady() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();

        GameReadinessMonitor.Readiness verdict = monitor.update(
                new GameReadinessMonitor.Sample(true, true, false, 10), T0);

        assertEquals(GameReadinessMonitor.Readiness.REGION_NOT_LOADED, verdict);
    }

    @Test
    public void loadedWorldSettlesBeforeItIsReady() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();

        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(10), T0));
        assertEquals(GameReadinessMonitor.Readiness.SETTLING,
                monitor.update(ready(11), T0 + GameReadinessMonitor.SETTLE_MS - 1));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(12), T0 + GameReadinessMonitor.SETTLE_MS));
    }

    @Test
    public void worldDisappearingRestartsTheSettleWindow() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(11), T0 + GameReadinessMonitor.SETTLE_MS));

        // A teleport re-loads the scene: the world is up again at once, but the interfaces are
        // rebuilt, so the settle window has to run again before the next click.
        assertEquals(GameReadinessMonitor.Readiness.REGION_NOT_LOADED,
                monitor.update(new GameReadinessMonitor.Sample(true, true, false, 20),
                        T0 + GameReadinessMonitor.SETTLE_MS + 600));
        long worldBack = T0 + GameReadinessMonitor.SETTLE_MS + 1_200;
        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(21), worldBack));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(22), worldBack + GameReadinessMonitor.SETTLE_MS));
    }

    @Test
    public void frozenCycleBecomesStalledOnlyAfterTheWindow() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);

        // Same cycle repeated: normal between client cycles, still ready inside the stall window.
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(10), T0 + GameReadinessMonitor.STALL_MS - 1));
        assertEquals(GameReadinessMonitor.Readiness.LOOP_STALLED,
                monitor.update(ready(10), T0 + GameReadinessMonitor.STALL_MS));
    }

    @Test
    public void advancingCycleClearsTheStall() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);
        assertEquals(GameReadinessMonitor.Readiness.LOOP_STALLED,
                monitor.update(ready(10), T0 + GameReadinessMonitor.STALL_MS));

        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(11), T0 + GameReadinessMonitor.STALL_MS + 20));
    }

    @Test
    public void loginTransitionRestartsTheStallAndSettleClocks() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);

        // Player model missing during the load resets continuity: the first sample after that
        // must not be judged against a clock from before the transition, and it must settle again
        // before the loop dispatches.
        assertEquals(GameReadinessMonitor.Readiness.PLAYER_NOT_LOADED,
                monitor.update(new GameReadinessMonitor.Sample(true, false, false, 10), T0 + 1_000));
        long afterLogin = T0 + GameReadinessMonitor.STALL_MS + 1_000;
        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(10), afterLogin));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(11), afterLogin + GameReadinessMonitor.SETTLE_MS));
    }

    @Test
    public void resetDropsTheStallAndSettleClocks() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);
        assertEquals(GameReadinessMonitor.Readiness.LOOP_STALLED,
                monitor.update(ready(10), T0 + GameReadinessMonitor.STALL_MS));

        monitor.reset();

        long afterReset = T0 + GameReadinessMonitor.STALL_MS + 20;
        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(10), afterReset));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(11), afterReset + GameReadinessMonitor.SETTLE_MS));
    }
}
