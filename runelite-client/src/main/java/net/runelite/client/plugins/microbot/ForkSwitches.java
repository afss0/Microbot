package net.runelite.client.plugins.microbot;

/**
 * Fork switches for the upstream features this fork keeps inert.
 *
 * <p>They live in a fork-owned file so the upstream classes only need one guarded
 * line ({@code if (!ForkSwitches.<fn>()) return;}) at the top of {@code start()}:
 * the classes stay injectable and their public API keeps working, so other
 * plugins and future fork work can use them, but their lifecycle does not run.
 *
 * <ul>
 *   <li>dashboard: chsami's dashboard / remote client management ({@code DashboardSync})</li>
 *   <li>bug reports: bug report status tracking ({@code BugReportTracker})</li>
 *   <li>flight recorder: per-tick recorder attached to bug reports ({@code FlightRecorder})</li>
 * </ul>
 *
 * <p>Off by default. {@code -Dmicrobot.fork.telemetry=true} turns all three back
 * on for a run — the unit tests set it so they exercise upstream's behaviour
 * (see {@code runelite-client/build.gradle.kts}).
 *
 * <p>Merge note: {@code microbot-fork-switches.py} re-applies the guarded lines
 * after every sync and aborts when an anchor is missing, so an upstream
 * refactor of these classes shows up as a named failure instead of silently
 * turning the feature back on.
 */
public final class ForkSwitches
{
	/** System property that re-enables the gated features for a run. */
	public static final String TELEMETRY_PROPERTY = "microbot.fork.telemetry";

	private ForkSwitches()
	{
	}

	/** chsami's dashboard / remote client management ({@code DashboardSync}). */
	public static boolean dashboardSyncEnabled()
	{
		return telemetryEnabled();
	}

	/** Bug report status tracking ({@code BugReportTracker}). */
	public static boolean bugReportsEnabled()
	{
		return telemetryEnabled();
	}

	/** Per-tick flight recorder attached to bug reports ({@code FlightRecorder}). */
	public static boolean flightRecorderEnabled()
	{
		return telemetryEnabled();
	}

	private static boolean telemetryEnabled()
	{
		return Boolean.getBoolean(TELEMETRY_PROPERTY);
	}
}
