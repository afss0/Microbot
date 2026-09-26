package net.runelite.client.plugins.microbot.util.math;

import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class Rs2RandomLogNormalBoundedTest {

	private static final int SAMPLE_SIZE = 40_000;

	/**
	 * The two-argument overload applies the fork's automatic weather modulation: the base max is
	 * scaled by 1/combinedSpeedFactor() (always ≥ 1 — waits can only get longer). Mirror that here
	 * so bound assertions check the effective ceiling rather than the base one.
	 */
	private static int effectiveMax(int baseMax) {
		double multiplier = 1.0 / Math.max(0.1, WeatherModulation.combinedSpeedFactor());
		return (int) (baseMax * multiplier);
	}

	@Test
	public void samplesStayInsideBounds() {
		int ceiling = effectiveMax(200);
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			int v = Rs2Random.logNormalBounded(20, 200);
			assertTrue("sample " + v + " below floor", v >= 20);
			assertTrue("sample " + v + " above effective ceiling " + ceiling, v <= ceiling);
		}
	}

	@Test
	public void medianSitsNearGeometricMean() {
		int[] samples = new int[SAMPLE_SIZE];
		for (int i = 0; i < SAMPLE_SIZE; i++) samples[i] = Rs2Random.logNormalBounded(20, 200);
		Arrays.sort(samples);
		int median = samples[SAMPLE_SIZE / 2];
		double geometricMean = Math.sqrt(20.0 * effectiveMax(200)); // fork: max is weather-scaled
		assertTrue("median " + median + " should be within 20% of geometric mean " + geometricMean,
				Math.abs(median - geometricMean) < geometricMean * 0.20);
	}

	@Test
	public void distributionIsRightSkewed() {
		int[] samples = new int[SAMPLE_SIZE];
		long sum = 0;
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			samples[i] = Rs2Random.logNormalBounded(20, 200);
			sum += samples[i];
		}
		Arrays.sort(samples);
		int median = samples[SAMPLE_SIZE / 2];
		double mean = sum / (double) SAMPLE_SIZE;
		assertTrue("mean " + mean + " should exceed median " + median + " (right-skewed)",
				mean > median);
	}

	@Test
	public void degenerateBoundsDoNotExplode() {
		int v = Rs2Random.logNormalBounded(50, 50);
		assertTrue(v == 50);
	}

	@Test
	public void handlesSwappedBoundsGracefully() {
		int v = Rs2Random.logNormalBounded(100, 50);
		assertTrue(v == 100);
	}

	/**
	 * Guardrail for the fork's weather modulation: the explicit-multiplier overload must NOT
	 * additionally apply the automatic weather factor — callers that weather-scale themselves
	 * (e.g. ascript modules passing 1/combinedSpeedFactor()) would otherwise be scaled twice.
	 */
	@Test
	public void explicitMultiplierOverloadIsNotWeatherScaled() {
		for (int i = 0; i < SAMPLE_SIZE; i++) {
			int v = Rs2Random.logNormalBounded(20, 200, 1.0);
			assertTrue("sample " + v + " above base ceiling — weather applied twice?", v <= 200);
		}
	}
}
