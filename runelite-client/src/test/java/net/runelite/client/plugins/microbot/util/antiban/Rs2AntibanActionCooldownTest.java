package net.runelite.client.plugins.microbot.util.antiban;

import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.antiban.enums.PlayStyle;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import org.mockito.MockedStatic;

/**
 * Upstream test (chsami e8453bf2a4) reconciled with the fork's two deliberate antiban
 * customizations — production behavior is untouched, only the expectations are aligned:
 *
 * <ul>
 *   <li><b>Activity intensity lock</b> (fork 8928605036): {@code activityIntensity} is pinned to
 *       {@link ActivityIntensity#VERY_LOW} and {@link Rs2Antiban#setActivityIntensity} is a no-op,
 *       so a cooldown that starts without a play style defaults to VERY_LOW's style instead of
 *       upstream's EXTREME. Guardrail: {@link Rs2AntibanIntensityLockTest}.</li>
 *   <li><b>Weather-scaled TIMEOUT</b> (fork 45f18e8db1, CUSTOM_FEATURES.md → Antiban →
 *       Weather-Based Modulation): {@code Rs2Antiban.performActionCooldown()} multiplies TIMEOUT by
 *       {@link WeatherModulation#combinedSpeedFactor()}, which is fed by live Open-Meteo data.
 *       Upstream has no scaling, so its raw-interval assertions only hold once that factor is
 *       pinned — see {@link #pinWeather}.</li>
 * </ul>
 *
 * The real reason for each divergence is spelled out on the test that documents it; neither is a
 * bug to paper over, and neither production file may change without the user's explicit go-ahead.
 */
public class Rs2AntibanActionCooldownTest {

	@Before
	public void setUp() {
		Rs2Antiban.resetAntibanSettings(true);
		Rs2AntibanSettings.usePlayStyle = true;
		Rs2AntibanSettings.actionCooldownChance = 1.0;
		Rs2Antiban.setTIMEOUT(0);
		// Fork divergence (45f18e8db1): TIMEOUT is scaled by live weather, and the upstream
		// assertions below expect the raw tick interval. Pin the weather cache so the factor is a
		// known constant instead of floating run-to-run with real Open-Meteo data. 45 °C clamps
		// speedFactor() to exactly 1.0, 0 km/h gusts and clear sky (WMO code 0) are already 1.0,
		// so combinedSpeedFactor() is exactly 1.0 and the raw-interval assertions apply unchanged.
		pinWeather(weatherJson(45.0));
		assertEquals("neutral weather pin must yield combinedSpeedFactor() == 1.0",
				1.0, WeatherModulation.combinedSpeedFactor(), 0.0);
	}

	@After
	public void tearDown() {
		Rs2Antiban.resetAntibanSettings(true);
		Rs2Antiban.setTIMEOUT(0);
	}

	/**
	 * Fork divergence (8928605036): upstream e8453bf2a4 defaults the missing play style from the
	 * current activity intensity and expects EXTREME after a reset. In this fork the intensity is
	 * locked to VERY_LOW and never reassigned, because scripts, hub plugins, the dynamic-intensity
	 * feature and the UI slider kept churning it (it was constantly flipping Very Low ↔ Low);
	 * mouse speed is forced independently in {@code NaturalMouse.getFactory()}, so the lock only
	 * stabilizes the panels and the per-activity playStyle seeding. The resolution itself is
	 * unchanged — it runs off whatever the pinned intensity is, i.e. VERY_LOW → PASSIVE.
	 * Guardrail: {@link Rs2AntibanIntensityLockTest}.
	 */
	@Test
	public void actionCooldownWithoutActivityDefaultsToIntensityPlayStyle() {
		Rs2Antiban.actionCooldown();

		PlayStyle expected = ActivityIntensity.VERY_LOW.getPlayStyle();
		assertSame(expected, Rs2Antiban.getPlayStyle());
		assertEquals(expected.getPrimaryTickInterval(), Rs2Antiban.getTIMEOUT());
		assertTrue(Rs2AntibanSettings.actionCooldownActive);
	}

	@Test
	public void actionCooldownWithoutActivityUsesRandomPlayStyleWhenRandomIntervalsEnabled() {
		Rs2AntibanSettings.randomIntervals = true;
		Rs2AntibanSettings.behavioralVariability = true;
		Rs2AntibanSettings.nonLinearIntervals = true;

		Rs2Antiban.actionCooldown();

		assertSame(PlayStyle.RANDOM, Rs2Antiban.getPlayStyle());
		assertTrue(Rs2Antiban.getTIMEOUT() >= 1);
		assertTrue(Rs2AntibanSettings.actionCooldownActive);
	}

	/**
	 * Fork divergence (45f18e8db1): {@code performActionCooldown()} scales TIMEOUT by
	 * {@link WeatherModulation#combinedSpeedFactor()} — live weather data, so without pinning this
	 * assertion floated run-to-run (17 vs 15 vs 14 observed). {@link #setUp()} pins the factor to
	 * exactly 1.0, keeping the upstream raw CAUTIOUS-interval assertion deterministic without
	 * hardcoding a scaled interval. The scaling itself is locked by
	 * {@link #actionCooldownScalesTimeoutByPinnedWeatherFactor}.
	 */
	@Test
	public void actionCooldownKeepsExistingPlayStyle() {
		PlayStyle.CAUTIOUS.resetPlayStyle();
		Rs2Antiban.setPlayStyle(PlayStyle.CAUTIOUS);

		Rs2Antiban.actionCooldown();

		assertSame(PlayStyle.CAUTIOUS, Rs2Antiban.getPlayStyle());
		assertEquals(PlayStyle.CAUTIOUS.getPrimaryTickInterval(), Rs2Antiban.getTIMEOUT());
	}

	/**
	 * Guardrail for the fork's weather-scaled TIMEOUT (45f18e8db1): with a non-neutral pinned
	 * factor the timeout must be the play style's primary interval scaled by
	 * {@link WeatherModulation#combinedSpeedFactor()}, mirroring the formula in
	 * {@code Rs2Antiban.performActionCooldown()}. Deterministic because the factor is pinned here —
	 * the live Open-Meteo feed is never read, and no raw interval is hardcoded.
	 */
	@Test
	public void actionCooldownScalesTimeoutByPinnedWeatherFactor() {
		// 0 °C → speedFactor() = 0.875 + 0.005 * (0 − 15) = 0.8, no gusts, clear sky.
		pinWeather(weatherJson(0.0));

		PlayStyle.CAUTIOUS.resetPlayStyle();
		Rs2Antiban.setPlayStyle(PlayStyle.CAUTIOUS);

		Rs2Antiban.actionCooldown();

		double factor = WeatherModulation.combinedSpeedFactor();
		assertTrue("pinned factor must be far enough below 1 that scaling is observable", factor <= 0.85);
		int scaled = Math.max(1, (int) Math.round(PlayStyle.CAUTIOUS.getPrimaryTickInterval() * factor));
		assertEquals("TIMEOUT must be the primary interval scaled by the pinned weather factor",
				scaled, Rs2Antiban.getTIMEOUT());
	}

	/**
	 * Pins the weather cache so {@link WeatherModulation#ensureFresh()} — called inside
	 * {@code Rs2Antiban.performActionCooldown()} — skips the live Open-Meteo fetch: the synthetic
	 * payload is fed through the production parser, then the private cache timestamp is refreshed
	 * reflectively so the 30-minute TTL reads as fresh. This makes the weather factor a test
	 * constant (hermetic unit test, no network) instead of live data.
	 */
	private static void pinWeather(String weatherJson) {
		WeatherModulation.parseWeatherResponse(weatherJson);
		try {
			Field lastFetchTime = WeatherModulation.class.getDeclaredField("lastFetchTime");
			lastFetchTime.setAccessible(true);
			lastFetchTime.setLong(null, System.currentTimeMillis());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("WeatherModulation.lastFetchTime changed shape — update pinWeather()", e);
		}
	}

	/**
	 * Minimal Open-Meteo payload for the given temperature: no precipitation, no gusts, clear sky
	 * (WMO code 0) — a single hourly entry, which {@code parseWeatherResponse} falls back to when
	 * it does not match the current UTC hour.
	 */
	private static String weatherJson(double temperatureCelsius) {
		return "{\"hourly\":{\"time\":[\"2026-01-01T00:00\"],"
				+ "\"temperature_2m\":[" + temperatureCelsius + "],"
				+ "\"precipitation\":[0.0],"
				+ "\"wind_gusts_10m\":[0.0],"
				+ "\"weather_code\":[0]}}";
	}

	@Test
	public void actionCooldownWithoutActivityCountsDownAndEnds() {
		Rs2Antiban.actionCooldown();
		assertNull(Rs2Antiban.getCategory());
		assertTrue(Rs2AntibanSettings.actionCooldownActive);

		int ticks = Rs2Antiban.getTIMEOUT();
		for (int i = 0; i < ticks; i++) {
			AntibanPlugin.performActionBreak();
		}
		assertEquals(0, Rs2Antiban.getTIMEOUT());
		assertTrue(Rs2AntibanSettings.actionCooldownActive);

		AntibanPlugin.performActionBreak();
		assertFalse(Rs2AntibanSettings.actionCooldownActive);
	}

	@Test
	public void overlayRendersWithoutActivity() {
		Rs2Antiban.actionCooldown();
		PanelComponent panel = new PanelComponent();

		Rs2Antiban.renderAntibanOverlayComponents(panel);

		assertFalse(panel.getChildren().isEmpty());
	}


	@Test
	public void actionCooldownIsSkippedWhenAntibanDisabled() {
		Rs2AntibanSettings.antibanEnabled = false;

		Rs2Antiban.actionCooldown();

		assertFalse(Rs2AntibanSettings.actionCooldownActive);
		assertEquals(0, Rs2Antiban.getTIMEOUT());
	}

	@Test
	public void cooldownStillCountsDownAfterAntibanDisabled() {
		Rs2Antiban.actionCooldown();
		assertTrue(Rs2AntibanSettings.actionCooldownActive);
		Rs2AntibanSettings.antibanEnabled = false;

		AntibanPlugin plugin = new AntibanPlugin();
		int ticks = Rs2Antiban.getTIMEOUT();
		for (int i = 0; i <= ticks; i++) {
			plugin.onGameTick(new GameTick());
		}

		assertEquals(0, Rs2Antiban.getTIMEOUT());
		assertFalse(Rs2AntibanSettings.actionCooldownActive);
	}

	@Test
	public void universalCooldownPauseIsReleasedWhenNoBreakHandlerConsumesMicroBreak() {
		Rs2AntibanSettings.universalAntiban = true;
		Rs2AntibanSettings.takeMicroBreaks = true;
		AntibanPlugin plugin = new AntibanPlugin();

		try (MockedStatic<Microbot> microbot = mockStatic(Microbot.class)) {
			Microbot.pauseAllScripts.set(false);

			for (int round = 0; round < 3; round++) {
				Rs2Antiban.actionCooldown();
				Rs2AntibanSettings.microBreakActive = true;
				assertTrue(Microbot.pauseAllScripts.get());

				plugin.handleMicroBreakIntegration();
				assertFalse(Rs2AntibanSettings.microBreakActive);
				assertTrue(Microbot.pauseAllScripts.get());

				int ticks = Rs2Antiban.getTIMEOUT();
				for (int i = 0; i < ticks; i++) {
					plugin.handleMicroBreakIntegration();
					AntibanPlugin.performActionBreak();
					assertTrue(Microbot.pauseAllScripts.get());
				}

				plugin.handleMicroBreakIntegration();
				AntibanPlugin.performActionBreak();
				assertFalse(Rs2AntibanSettings.actionCooldownActive);
				assertFalse(Microbot.pauseAllScripts.get());
			}

			microbot.verify(() -> Microbot.showMessage(anyString()), times(1));
		} finally {
			Microbot.pauseAllScripts.set(false);
		}
	}

	@Test
	public void universalCooldownLeavesScriptsRunningWhileInCombat() {
		Rs2AntibanSettings.universalAntiban = true;

		try (MockedStatic<Microbot> microbot = mockStatic(Microbot.class);
			 MockedStatic<Rs2Combat> combat = mockStatic(Rs2Combat.class)) {
			Microbot.pauseAllScripts.set(false);
			microbot.when(Microbot::isLoggedIn).thenReturn(true);
			combat.when(Rs2Combat::inCombat).thenReturn(true);
			Rs2Antiban.setActivity(Activity.GENERAL_SLAYER);

			Rs2Antiban.actionCooldown();
			assertTrue(Rs2AntibanSettings.actionCooldownActive);
			assertFalse(Microbot.pauseAllScripts.get());

			int ticks = Rs2Antiban.getTIMEOUT();
			assertTrue(ticks >= 2);
			for (int i = 0; i < 50; i++) {
				AntibanPlugin.performActionBreak();
				assertFalse(Microbot.pauseAllScripts.get());
			}
			assertEquals(ticks, Rs2Antiban.getTIMEOUT());

			combat.when(Rs2Combat::inCombat).thenReturn(false);
			AntibanPlugin.performActionBreak();
			assertTrue(Microbot.pauseAllScripts.get());
			assertEquals(ticks - 1, Rs2Antiban.getTIMEOUT());

			combat.when(Rs2Combat::inCombat).thenReturn(true);
			AntibanPlugin.performActionBreak();
			assertFalse(Microbot.pauseAllScripts.get());
			assertEquals(ticks - 1, Rs2Antiban.getTIMEOUT());

			combat.when(Rs2Combat::inCombat).thenReturn(false);
			for (int i = 0; i < ticks - 1; i++) {
				AntibanPlugin.performActionBreak();
				assertTrue(Microbot.pauseAllScripts.get());
			}
			AntibanPlugin.performActionBreak();
			assertFalse(Rs2AntibanSettings.actionCooldownActive);
			assertFalse(Microbot.pauseAllScripts.get());
		} finally {
			Microbot.pauseAllScripts.set(false);
		}
	}

	@Test
	public void universalCooldownStartedOutOfCombatPausesScripts() {
		Rs2AntibanSettings.universalAntiban = true;

		try (MockedStatic<Microbot> microbot = mockStatic(Microbot.class);
			 MockedStatic<Rs2Combat> combat = mockStatic(Rs2Combat.class)) {
			Microbot.pauseAllScripts.set(false);
			microbot.when(Microbot::isLoggedIn).thenReturn(true);
			combat.when(Rs2Combat::inCombat).thenReturn(false);
			Rs2Antiban.setActivity(Activity.GENERAL_SLAYER);

			Rs2Antiban.actionCooldown();

			assertTrue(Rs2AntibanSettings.actionCooldownActive);
			assertTrue(Microbot.pauseAllScripts.get());
		} finally {
			Microbot.pauseAllScripts.set(false);
		}
	}

	@Test
	public void externalPauseSurvivesPendingUniversalCooldownInCombat() {
		Rs2AntibanSettings.universalAntiban = true;

		try (MockedStatic<Microbot> microbot = mockStatic(Microbot.class);
			 MockedStatic<Rs2Combat> combat = mockStatic(Rs2Combat.class)) {
			Microbot.pauseAllScripts.set(false);
			microbot.when(Microbot::isLoggedIn).thenReturn(true);
			combat.when(Rs2Combat::inCombat).thenReturn(true);
			Rs2Antiban.setActivity(Activity.GENERAL_SLAYER);

			Rs2Antiban.actionCooldown();
			assertTrue(Rs2AntibanSettings.actionCooldownActive);
			assertFalse(Microbot.pauseAllScripts.get());

			Microbot.pauseAllScripts.set(true);
			for (int i = 0; i < 10; i++) {
				AntibanPlugin.performActionBreak();
				assertTrue(Microbot.pauseAllScripts.get());
			}

			combat.when(Rs2Combat::inCombat).thenReturn(false);
			int ticks = Rs2Antiban.getTIMEOUT();
			for (int i = 0; i <= ticks; i++) {
				AntibanPlugin.performActionBreak();
				assertTrue(Microbot.pauseAllScripts.get());
			}
			assertFalse(Rs2AntibanSettings.actionCooldownActive);
			assertTrue(Microbot.pauseAllScripts.get());
		} finally {
			Microbot.pauseAllScripts.set(false);
		}
	}

	@Test
	public void cooldownStartedOutOfCombatIsReleasedWhenActivityTurnsToCombat() {
		Rs2AntibanSettings.universalAntiban = true;

		try (MockedStatic<Microbot> microbot = mockStatic(Microbot.class);
			 MockedStatic<Rs2Combat> combat = mockStatic(Rs2Combat.class)) {
			Microbot.pauseAllScripts.set(false);
			microbot.when(Microbot::isLoggedIn).thenReturn(true);
			combat.when(Rs2Combat::inCombat).thenReturn(true);
			Rs2Antiban.setActivity(Activity.GENERAL_SMITHING);

			Rs2Antiban.actionCooldown();
			assertTrue(Rs2AntibanSettings.actionCooldownActive);
			assertTrue(Microbot.pauseAllScripts.get());
			int ticks = Rs2Antiban.getTIMEOUT();

			Rs2Antiban.setActivity(Activity.GENERAL_SLAYER);
			for (int i = 0; i < 50; i++) {
				AntibanPlugin.performActionBreak();
				assertFalse(Microbot.pauseAllScripts.get());
			}
			assertEquals(ticks, Rs2Antiban.getTIMEOUT());
			assertTrue(Rs2AntibanSettings.actionCooldownActive);
		} finally {
			Microbot.pauseAllScripts.set(false);
		}
	}

	@Test
	public void activityChangeDoesNotRestartRunningUniversalCooldown() {
		Rs2AntibanSettings.universalAntiban = true;
		Rs2AntibanSettings.dynamicActivity = true;
		AntibanPlugin plugin = new AntibanPlugin();

		try (MockedStatic<Microbot> microbot = mockStatic(Microbot.class)) {
			Microbot.pauseAllScripts.set(false);
			plugin.onStatChanged(new StatChanged(Skill.ATTACK, 100, 1, 1));
			plugin.onStatChanged(new StatChanged(Skill.SLAYER, 100, 1, 1));
			plugin.onStatChanged(new StatChanged(Skill.ATTACK, 200, 1, 1));
			assertTrue(Rs2AntibanSettings.actionCooldownActive);
			assertSame(Activity.GENERAL_COMBAT, Rs2Antiban.getActivity());

			Rs2Antiban.setTIMEOUT(1);
			plugin.onStatChanged(new StatChanged(Skill.SLAYER, 200, 1, 1));

			assertSame(Activity.GENERAL_SLAYER, Rs2Antiban.getActivity());
			assertEquals(1, Rs2Antiban.getTIMEOUT());
		} finally {
			Microbot.pauseAllScripts.set(false);
		}
	}

	@Test
	public void staleMicroBreakIsCancelledOnEveryTickWithoutBreakHandler() {
		Rs2AntibanSettings.takeMicroBreaks = true;
		AntibanPlugin plugin = new AntibanPlugin();

		try (MockedStatic<Microbot> microbot = mockStatic(Microbot.class)) {
			plugin.handleMicroBreakIntegration();

			Rs2AntibanSettings.microBreakActive = true;
			Microbot.pauseAllScripts.set(true);
			plugin.handleMicroBreakIntegration();

			assertFalse(Rs2AntibanSettings.microBreakActive);
			assertFalse(Microbot.pauseAllScripts.get());
		} finally {
			Microbot.pauseAllScripts.set(false);
		}
	}

}
