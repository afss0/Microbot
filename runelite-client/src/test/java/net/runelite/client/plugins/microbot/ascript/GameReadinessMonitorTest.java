package net.runelite.client.plugins.microbot.ascript;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Truth table for the game-readiness gate: the orchestrator must not dispatch on a client that is
 * still loading, must let the world SETTLE after a load before the first click, and must wait out
 * the welcome ("click here to play") screen — {@code LOGGED_IN} with a loaded player and scene is
 * already true while that screen is up, and the game only accepts menu input after it closes.
 * A frozen client game cycle must surface as its own verdict (a crashed client still reports
 * {@code LOGGED_IN} with a stale player, so server ticks are not a liveness signal).
 */
public class GameReadinessMonitorTest {

    private static final long T0 = 1_000_000;

    private static GameReadinessMonitor.Sample ready(int cycle) {
        return new GameReadinessMonitor.Sample(true, true, true, false, cycle);
    }

    @Test
    public void notLoggedInIsNotReady() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();

        GameReadinessMonitor.Readiness verdict = monitor.update(
                new GameReadinessMonitor.Sample(false, false, false, false, -1), T0);

        assertEquals(GameReadinessMonitor.Readiness.NOT_LOGGED_IN, verdict);
    }

    @Test
    public void missingLocalPlayerIsNotReady() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();

        GameReadinessMonitor.Readiness verdict = monitor.update(
                new GameReadinessMonitor.Sample(true, false, true, false, 10), T0);

        assertEquals(GameReadinessMonitor.Readiness.PLAYER_NOT_LOADED, verdict);
    }

    @Test
    public void sceneReloadSettlesForTheShortWindow() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();

        assertEquals(GameReadinessMonitor.Readiness.REGION_NOT_LOADED,
                monitor.update(new GameReadinessMonitor.Sample(true, true, false, false, 10), T0));
        long sceneBack = T0 + 600;
        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(11), sceneBack));
        assertEquals(GameReadinessMonitor.Readiness.SETTLING,
                monitor.update(ready(12), sceneBack + GameReadinessMonitor.SETTLE_MS - 1));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(13), sceneBack + GameReadinessMonitor.SETTLE_MS));
    }

    @Test
    public void loginSettlesForTheLongerWindow() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();

        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(10), T0));
        assertEquals(GameReadinessMonitor.Readiness.SETTLING,
                monitor.update(ready(11), T0 + GameReadinessMonitor.LOGIN_SETTLE_MS - 1));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(12), T0 + GameReadinessMonitor.LOGIN_SETTLE_MS));
    }

    @Test
    public void welcomeScreenIsNotReadyAndRestartsTheSettle() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);
        long loggedIn = T0 + GameReadinessMonitor.LOGIN_SETTLE_MS;

        // The play screen is still up: everything the gate normally looks at is already true, but
        // the client is not accepting input yet.
        assertEquals(GameReadinessMonitor.Readiness.WELCOME_SCREEN,
                monitor.update(new GameReadinessMonitor.Sample(true, true, true, true, 11), loggedIn));

        long playClicked = loggedIn + 600;
        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(12), playClicked));
        assertEquals(GameReadinessMonitor.Readiness.SETTLING,
                monitor.update(ready(13), playClicked + GameReadinessMonitor.LOGIN_SETTLE_MS - 1));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(14), playClicked + GameReadinessMonitor.LOGIN_SETTLE_MS));
    }

    @Test
    public void sceneReloadAfterASettledLoginUsesTheShortWindow() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);
        long loggedIn = T0 + GameReadinessMonitor.LOGIN_SETTLE_MS;
        assertEquals(GameReadinessMonitor.Readiness.READY, monitor.update(ready(11), loggedIn));

        // Teleport: the scene is rebuilt but no welcome screen is involved, so the short window
        // applies — a hop must not cost another LOGIN_SETTLE_MS.
        assertEquals(GameReadinessMonitor.Readiness.REGION_NOT_LOADED,
                monitor.update(new GameReadinessMonitor.Sample(true, true, false, false, 20), loggedIn + 600));
        long sceneBack = loggedIn + 1_200;
        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(21), sceneBack));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(22), sceneBack + GameReadinessMonitor.SETTLE_MS));
    }

    @Test
    public void frozenCycleWinsOverTheSettle() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);

        // A dead client is not a settling one: the stall verdict is decided first.
        assertEquals(GameReadinessMonitor.Readiness.LOOP_STALLED,
                monitor.update(ready(10), T0 + GameReadinessMonitor.STALL_MS));
    }

    @Test
    public void advancingCycleClearsTheStall() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);
        assertEquals(GameReadinessMonitor.Readiness.LOOP_STALLED,
                monitor.update(ready(10), T0 + GameReadinessMonitor.STALL_MS));

        // Recovered, but the settle window still has to finish before the loop dispatches.
        assertEquals(GameReadinessMonitor.Readiness.SETTLING,
                monitor.update(ready(11), T0 + GameReadinessMonitor.STALL_MS + 20));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(12), T0 + GameReadinessMonitor.LOGIN_SETTLE_MS));
    }

    @Test
    public void loginTransitionRestartsTheSettleClock() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);
        long loggedIn = T0 + GameReadinessMonitor.LOGIN_SETTLE_MS;
        assertEquals(GameReadinessMonitor.Readiness.READY, monitor.update(ready(11), loggedIn));

        // Player model missing during the load resets continuity: the first sample after that
        // must not be judged against a clock from before the transition, and it must settle again
        // before the loop dispatches.
        assertEquals(GameReadinessMonitor.Readiness.PLAYER_NOT_LOADED,
                monitor.update(new GameReadinessMonitor.Sample(true, false, false, false, 10), loggedIn + 600));
        long afterLogin = loggedIn + 1_200;
        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(10), afterLogin));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(11), afterLogin + GameReadinessMonitor.LOGIN_SETTLE_MS));
    }

    @Test
    public void resetKeepsTheCurrentWindowAndRestartsTheClock() {
        GameReadinessMonitor monitor = new GameReadinessMonitor();
        monitor.update(ready(10), T0);
        long loggedIn = T0 + GameReadinessMonitor.LOGIN_SETTLE_MS;
        assertEquals(GameReadinessMonitor.Readiness.READY, monitor.update(ready(11), loggedIn));

        monitor.reset();

        long firstAfterReset = loggedIn + 20;
        assertEquals(GameReadinessMonitor.Readiness.SETTLING, monitor.update(ready(12), firstAfterReset));
        assertEquals(GameReadinessMonitor.Readiness.READY,
                monitor.update(ready(13), firstAfterReset + GameReadinessMonitor.LOGIN_SETTLE_MS));
    }
}
