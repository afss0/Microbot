package net.runelite.client.plugins.microbot.util.antiban;

import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import org.junit.Test;

import static org.junit.Assert.assertSame;

/**
 * Guardrail for the fork's activity-intensity lock: the field is pinned to VERY_LOW and
 * {@link Rs2Antiban#setActivityIntensity} is a no-op, so scripts, hub plugins, the dynamic
 * intensity feature and the UI slider cannot churn it.
 */
public class Rs2AntibanIntensityLockTest {

	@Test
	public void setActivityIntensityIsNoOpAndStaysVeryLow() {
		Rs2Antiban.setActivityIntensity(ActivityIntensity.LOW);
		assertSame(ActivityIntensity.VERY_LOW, Rs2Antiban.getActivityIntensity());
		Rs2Antiban.setActivityIntensity(ActivityIntensity.EXTREME);
		assertSame(ActivityIntensity.VERY_LOW, Rs2Antiban.getActivityIntensity());
	}

	@Test
	public void fieldIsPinnedToVeryLowOnClassLoad() {
		assertSame(ActivityIntensity.VERY_LOW, Rs2Antiban.getActivityIntensity());
	}
}
