/*
 * Copyright (c) 2026, Microbot
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 */
package net.runelite.client.plugins.microbot.ascript.util;

import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract of the drop click-gap mixture: a fast band most of the time, a slow band on a
 * weather-scaled roll, both bands sampled with the shared
 * {@link Rs2Random#logNormalBounded(int, int, double)}. The chance curve is pure; the sampled
 * rates use tolerances wide enough to be stable in practice.
 */
public class AScriptDropGapTest
{
	private static final int MIN = 50;
	private static final int MAX = 320;
	/** Band boundary for the default bounds: 50 + round(270 / 3). */
	private static final int SPLIT = 140;

	@Test
	public void slowChanceStartsAtFifteenPercentAndRisesWithWeather()
	{
		assertEquals("neutral weather keeps the 15% baseline", 0.15,
				AScriptDrop.slowGapChance(1.0), 1e-9);
		assertEquals("weather below 1x cannot lower the baseline", 0.15,
				AScriptDrop.slowGapChance(0.7), 1e-9);
		assertEquals("1.2x weather raises the chance proportionally", 0.18,
				AScriptDrop.slowGapChance(1.2), 1e-9);
		assertEquals("2x weather doubles it", 0.30, AScriptDrop.slowGapChance(2.0), 1e-9);
		assertEquals("the ceiling holds in the worst weather", 0.55,
				AScriptDrop.slowGapChance(4.0), 1e-9);
		assertTrue("the chance must never reach certainty", AScriptDrop.slowGapChance(9.0) < 1.0);
	}

	@Test
	public void gapsStayInsideTheRequestedBounds()
	{
		for (int i = 0; i < 2000; i++)
		{
			int gap = AScriptDrop.dropGapMs(MIN, MAX, 1.0);
			assertTrue("gap " + gap + " below floor", gap >= MIN);
			assertTrue("gap " + gap + " above ceiling", gap <= MAX);
		}
	}

	@Test
	public void mostGapsComeFromTheFastBand()
	{
		int slow = 0;
		int draws = 4000;
		for (int i = 0; i < draws; i++)
		{
			if (AScriptDrop.dropGapMs(MIN, MAX, 1.0) > SPLIT)
			{
				slow++;
			}
		}
		double rate = slow / (double) draws;
		// 15% expected; the window only has to absorb sampling noise.
		assertTrue("slow-band rate " + rate + " should be near 15%", rate > 0.10 && rate < 0.20);
	}

	@Test
	public void worseWeatherShiftsDrawsUpwards()
	{
		int calmSlow = 0;
		int stormSlow = 0;
		int draws = 4000;
		for (int i = 0; i < draws; i++)
		{
			if (AScriptDrop.dropGapMs(MIN, MAX, 1.0) > SPLIT)
			{
				calmSlow++;
			}
			if (AScriptDrop.dropGapMs(MIN, MAX, 2.0) > SPLIT)
			{
				stormSlow++;
			}
		}
		assertTrue("storm (" + stormSlow + ") must out-weigh calm (" + calmSlow + ") above the split",
				stormSlow > calmSlow);
	}

	@Test
	public void customBoundsAreHonouredAndCollapseSafely()
	{
		List<Integer> gaps = new ArrayList<>();
		for (int i = 0; i < 1000; i++)
		{
			gaps.add(AScriptDrop.dropGapMs(100, 600, 1.0));
		}
		assertTrue("custom floor respected", gaps.stream().allMatch(g -> g >= 100));
		assertTrue("custom ceiling respected", gaps.stream().allMatch(g -> g <= 600));

		for (int i = 0; i < 50; i++)
		{
			assertEquals("an inverted range collapses to the floor", 200,
					AScriptDrop.dropGapMs(200, 100, 1.0));
			assertEquals("a non-positive floor is lifted to 1 ms", 1,
					AScriptDrop.dropGapMs(0, 1, 1.0));
		}
	}
}
