package net.runelite.client.plugins.microbot;

/**
 * Fork switches for upstream features this fork keeps inert.
 *
 * <p>They live in a fork-owned file so the upstream classes only need one
 * guarded line: the classes stay injectable and their public API keeps working,
 * so other plugins and future fork work can use them, but their lifecycle never
 * starts on this fork.
 *
 * <p>Dashboard sync is chsami's dashboard / remote client management; bug
 * reports and the flight recorder ship player telemetry. Re-enabling any of
 * them is a product decision — flip the corresponding method to {@code true}.
 *
 * <p>Merge note: {@code microbot-fork-switches.py} re-applies the guarded lines
 * after every sync and aborts when an anchor is missing, so an upstream
 * refactor of these classes shows up as a named failure instead of silently
 * turning the feature back on.
 */
public final class ForkSwitches
{
	private ForkSwitches()
	{
	}

	/** chsami's dashboard / remote client management ({@code DashboardSync}). */
	public static boolean dashboardSyncEnabled()
	{
		return false;
	}

	/** Bug report status tracking ({@code BugReportTracker}). */
	public static boolean bugReportsEnabled()
	{
		return false;
	}

	/** Per-tick flight recorder attached to bug reports ({@code FlightRecorder}). */
	public static boolean flightRecorderEnabled()
	{
		return false;
	}
}
