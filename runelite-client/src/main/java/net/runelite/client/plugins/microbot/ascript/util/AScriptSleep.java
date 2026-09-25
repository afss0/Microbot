package net.runelite.client.plugins.microbot.ascript.util;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;

/**
 * Shared sleep helpers for aScript modules.
 * <p>
 * Long inline delays (random AFKs up to two minutes) block the script loop
 * thread, and blocking events are normally only dispatched between ticks from
 * {@code Script.run()}. A plain {@code Global.sleep(afkMs)} therefore delays
 * blocking-event handling by the full delay. {@link #sleepInterruptibly(int)}
 * polls the blocking-event manager instead: {@code shouldBlockAndProcess()}
 * dispatches any pending event onto its dedicated executor thread (CAS-guarded,
 * safe to call concurrently with other scripts' loops) and reports whether one
 * is running, so this method returns immediately once a blocking event needs
 * attention and the orchestrator's next {@code run()} guard pauses ticks until
 * it has finished.
 */
public final class AScriptSleep {

    /** Poll cadence for the chunked wait — randomized so timing stays non-uniform. */
    private static final int POLL_MIN_MS = 80;
    private static final int POLL_MAX_MS = 280;

    private AScriptSleep() {
        // static utility
    }

    /**
     * Sleep for approximately {@code ms}, aborting early when a blocking event
     * is pending or executing. Shutdown via thread interruption still works —
     * the loop exits on the interrupt flag.
     *
     * @param ms requested sleep duration in milliseconds
     */
    public static void sleepInterruptibly(int ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (!Thread.currentThread().isInterrupted()
                && System.currentTimeMillis() < deadline
                && !Microbot.getBlockingEventManager().shouldBlockAndProcess()) {
            Global.sleep(Rs2Random.between(POLL_MIN_MS, POLL_MAX_MS));
        }
    }
}
