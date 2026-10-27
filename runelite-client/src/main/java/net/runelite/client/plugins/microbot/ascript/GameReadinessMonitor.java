package net.runelite.client.plugins.microbot.ascript;

/**
 * Decides whether the game is ready for automation, from a sample of cheap client state.
 * <p>
 * The inputs are the canonical built-ins rather than a hand-rolled tick counter:
 * {@code GameState.LOGGED_IN} (through {@code Microbot.isLoggedIn()}), a loaded local player,
 * a scene with a real base coordinate ({@code WorldView.getBaseX()}), and the client's own
 * game cycle ({@code Client.getGameCycle()}, incremented every 20 ms).
 * <p>
 * The game cycle is the liveness signal. It is driven by the client loop, not by server ticks:
 * {@code Client.getTickCount()} only advances when RuneLite processes a server tick, so lag or a
 * long region load freezes it while the client is healthy — a probe that watches it reports a
 * stalled loop on a working client. A frozen game cycle means the client loop itself is not
 * running.
 * <p>
 * The monitor is stateful only for that stall window. Pure and client-free on purpose:
 * {@link #update(Sample, long)} takes primitives so the whole truth table is unit-tested without
 * a live client (see {@code GameReadinessMonitorTest}).
 */
public final class GameReadinessMonitor {

    /** Why the game is not ready. {@link #description()} is the user-facing wording. */
    public enum Readiness {
        READY("ready"),
        NOT_LOGGED_IN("not logged in"),
        PLAYER_NOT_LOADED("local player not loaded"),
        REGION_NOT_LOADED("region not loaded"),
        LOOP_STALLED("game loop not advancing");

        private final String description;

        Readiness(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /**
     * One snapshot of the client. Every field is a value type so nothing here can touch the
     * client: the sampler (owned by {@code AScript}) fills it in. Plain class, not a record —
     * this project compiles with {@code options.release = 11}.
     */
    public static final class Sample {
        private final boolean loggedIn;
        private final boolean playerLoaded;
        private final boolean regionLoaded;
        private final int gameCycle;

        /**
         * @param loggedIn     game state is {@code LOGGED_IN} ({@code Microbot.isLoggedIn()})
         * @param playerLoaded the local player model exists
         * @param regionLoaded the top-level scene has a real base coordinate
         * @param gameCycle    the client game cycle ({@code Client.getGameCycle()}, 20 ms unit)
         */
        public Sample(boolean loggedIn, boolean playerLoaded, boolean regionLoaded, int gameCycle) {
            this.loggedIn = loggedIn;
            this.playerLoaded = playerLoaded;
            this.regionLoaded = regionLoaded;
            this.gameCycle = gameCycle;
        }

        public boolean loggedIn() {
            return loggedIn;
        }

        public boolean playerLoaded() {
            return playerLoaded;
        }

        public boolean regionLoaded() {
            return regionLoaded;
        }

        public int gameCycle() {
            return gameCycle;
        }
    }

    /**
     * How long the client game cycle may stay frozen before the client loop is treated as
     * stalled. The cycle advances every 20 ms, so this is ~500 cycles of silence — far longer
     * than a GC pause or a heavy region load, short enough to surface a dead client quickly.
     */
    public static final long STALL_MS = 10_000;

    private int lastCycle = Integer.MIN_VALUE;
    private long lastAdvanceAt;

    /**
     * Folds one sample into the readiness verdict.
     *
     * @param sample current client snapshot
     * @param nowMs  wall clock of the sample, used only for the stall window
     */
    public Readiness update(Sample sample, long nowMs) {
        if (!sample.loggedIn()) {
            reset();
            return Readiness.NOT_LOGGED_IN;
        }
        if (!sample.playerLoaded()) {
            // While the client is logging in / building the world, cycle continuity from before
            // the transition means nothing: carrying it over would report a stall on the first
            // sample after login.
            reset();
            return Readiness.PLAYER_NOT_LOADED;
        }
        if (!sample.regionLoaded()) {
            reset();
            return Readiness.REGION_NOT_LOADED;
        }

        if (sample.gameCycle() != lastCycle) {
            lastCycle = sample.gameCycle();
            lastAdvanceAt = nowMs;
        } else if (lastAdvanceAt != 0 && nowMs - lastAdvanceAt >= STALL_MS) {
            // The client loop has not advanced: nothing will process the next input or tick.
            // Do not reset here — a recovered client advances the cycle and the next update
            // clears the verdict on its own.
            return Readiness.LOOP_STALLED;
        }
        return Readiness.READY;
    }

    /** Clears all cross-tick state, including the stall clock. */
    public void reset() {
        lastCycle = Integer.MIN_VALUE;
        lastAdvanceAt = 0;
    }
}
