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
package net.runelite.client.plugins.microbot.util.mouse.naturalmouse;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Contract of the transient cursor-speed multiplier used by the sliced inventory drop
 * ({@code AScriptDrop}): the setter hands back the previous value so a caller can restore it,
 * out-of-range requests are clamped instead of applied, and the default is inert.
 * <p>
 * Pure static state — no client, no mouse motion — so it runs in the plain unit suite.
 */
public class NaturalMouseSpeedMultiplierTest
{
	private double original;

	@After
	public void restore()
	{
		NaturalMouse.setSpeedMultiplier(original);
	}

	@Test
	public void defaultIsInert()
	{
		original = NaturalMouse.getSpeedMultiplier();
		assertEquals("a fresh multiplier must not change the travel time", 1.0, original, 0.0);
	}

	@Test
	public void tripleIsAppliedAndReportsThePreviousValue()
	{
		original = NaturalMouse.getSpeedMultiplier();
		double previous = NaturalMouse.setSpeedMultiplier(3.0);
		assertEquals("setter returns the value it replaced", original, previous, 0.0);
		assertEquals("the boost is observable while it is armed", 3.0,
				NaturalMouse.getSpeedMultiplier(), 0.0);
	}

	@Test
	public void restoringThePreviousValueReturnsToBaseline()
	{
		original = NaturalMouse.getSpeedMultiplier();
		double previous = NaturalMouse.setSpeedMultiplier(3.0);
		NaturalMouse.setSpeedMultiplier(previous);
		assertEquals("the drop must not leak its speed into later actions", original,
				NaturalMouse.getSpeedMultiplier(), 0.0);
	}

	@Test
	public void aboveTheCeilingClampsInsteadOfTeleporting()
	{
		original = NaturalMouse.getSpeedMultiplier();
		NaturalMouse.setSpeedMultiplier(50.0);
		assertEquals("a runaway multiplier is clamped to the ceiling", 5.0,
				NaturalMouse.getSpeedMultiplier(), 0.0);
	}

	@Test
	public void belowOneIsIgnored()
	{
		original = NaturalMouse.getSpeedMultiplier();
		NaturalMouse.setSpeedMultiplier(0.25);
		assertEquals("slowing the cursor below 1x is not a supported boost", 1.0,
				NaturalMouse.getSpeedMultiplier(), 0.0);
	}
}
