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
 * Contract of the transient cursor-speed multiplier used for slow-only pacing scopes:
 * the setter hands back the previous value so a caller can restore it, out-of-range
 * requests are clamped instead of applied, the default is inert, and no caller can
 * accelerate past the forced-slow tier.
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
	public void halfSlowdownIsAppliedAndReportsThePreviousValue()
	{
		original = NaturalMouse.getSpeedMultiplier();
		double previous = NaturalMouse.setSpeedMultiplier(0.5);
		assertEquals("setter returns the value it replaced", original, previous, 0.0);
		assertEquals("the slowdown is observable while it is armed", 0.5,
				NaturalMouse.getSpeedMultiplier(), 0.0);
	}

	@Test
	public void restoringThePreviousValueReturnsToBaseline()
	{
		original = NaturalMouse.getSpeedMultiplier();
		double previous = NaturalMouse.setSpeedMultiplier(0.5);
		NaturalMouse.setSpeedMultiplier(previous);
		assertEquals("the action must not leak its slowdown into later actions", original,
				NaturalMouse.getSpeedMultiplier(), 0.0);
	}

	@Test
	public void aboveTheBaselineClampsInsteadOfAccelerating()
	{
		original = NaturalMouse.getSpeedMultiplier();
		NaturalMouse.setSpeedMultiplier(3.0);
		assertEquals("a caller cannot speed up past the forced-slow tier", 1.0,
				NaturalMouse.getSpeedMultiplier(), 0.0);
	}

	@Test
	public void belowTheSlowFloorClamps()
	{
		original = NaturalMouse.getSpeedMultiplier();
		NaturalMouse.setSpeedMultiplier(0.1);
		assertEquals("a runaway slowdown is clamped to the floor", 0.5,
				NaturalMouse.getSpeedMultiplier(), 0.0);
	}
}
