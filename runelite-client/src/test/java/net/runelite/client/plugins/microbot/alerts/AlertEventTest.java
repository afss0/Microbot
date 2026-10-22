package net.runelite.client.plugins.microbot.alerts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AlertEventTest
{
	private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000042");

	@Test
	public void heartbeatCarriesStateButNoReasonOrMessage()
	{
		JsonObject json = AlertEvent.builder()
			.type(AlertEvent.HEARTBEAT)
			.reason("error")
			.message("ignored")
			.runningScripts(List.of("Fisher", "Woodcutter"))
			.loggedIn(true)
			.breakActive(false)
			.build()
			.toJson(SESSION);

		assertEquals(SESSION.toString(), json.get("sessionId").getAsString());
		assertEquals("heartbeat", json.get("type").getAsString());
		assertTrue(json.get("reason").isJsonNull());
		assertTrue(json.get("message").isJsonNull());
		assertTrue(json.get("plugin").isJsonNull());
		assertEquals(2, json.getAsJsonArray("runningScripts").size());
		assertTrue(json.get("loggedIn").getAsBoolean());
		assertFalse(json.get("breakActive").getAsBoolean());
	}

	@Test
	public void scriptStoppedKeepsReasonAndMessage()
	{
		JsonObject json = AlertEvent.builder()
			.type(AlertEvent.SCRIPT_STOPPED)
			.plugin("Fisher")
			.pluginVersion("1.4.0")
			.reason("error")
			.message("Bank closed (IllegalStateException)")
			.build()
			.toJson(SESSION);

		assertEquals("Fisher", json.get("plugin").getAsString());
		assertEquals("1.4.0", json.get("pluginVersion").getAsString());
		assertEquals("error", json.get("reason").getAsString());
		assertEquals("Bank closed (IllegalStateException)", json.get("message").getAsString());
		assertEquals(0, json.getAsJsonArray("runningScripts").size());
	}

	@Test
	public void scriptErrorKeepsMessageButNotReason()
	{
		JsonObject json = AlertEvent.builder()
			.type(AlertEvent.SCRIPT_ERROR)
			.reason("error")
			.message("boom")
			.build()
			.toJson(SESSION);

		assertTrue(json.get("reason").isJsonNull());
		assertEquals("boom", json.get("message").getAsString());
	}

	@Test
	public void intendedIsOnlySentForLogouts()
	{
		JsonObject loggedOut = AlertEvent.builder()
			.type(AlertEvent.LOGGED_OUT)
			.intended(true)
			.build()
			.toJson(SESSION);
		JsonObject heartbeat = AlertEvent.builder()
			.type(AlertEvent.HEARTBEAT)
			.intended(true)
			.build()
			.toJson(SESSION);
		JsonObject unintended = AlertEvent.builder()
			.type(AlertEvent.LOGGED_OUT)
			.build()
			.toJson(SESSION);

		assertTrue(loggedOut.get("intended").getAsBoolean());
		assertFalse(heartbeat.get("intended").getAsBoolean());
		assertFalse(unintended.get("intended").getAsBoolean());
	}

	@Test
	public void fieldsAreCutToTheApiLimits()
	{
		List<String> scripts = new ArrayList<>();
		for (int i = 0; i < 25; i++)
		{
			scripts.add("Script " + i);
		}
		scripts.addAll(Arrays.asList("Script 0", " ", null, "x".repeat(150)));

		JsonObject json = AlertEvent.builder()
			.type(AlertEvent.SCRIPT_ERROR)
			.plugin("p".repeat(150))
			.pluginVersion("v".repeat(40))
			.message("m".repeat(800))
			.runningScripts(scripts)
			.build()
			.toJson(SESSION);

		assertEquals(AlertEvent.MAX_PLUGIN_CHARS, json.get("plugin").getAsString().length());
		assertEquals(AlertEvent.MAX_VERSION_CHARS, json.get("pluginVersion").getAsString().length());
		assertEquals(AlertEvent.MAX_MESSAGE_CHARS, json.get("message").getAsString().length());
		JsonArray running = json.getAsJsonArray("runningScripts");
		assertEquals(AlertEvent.MAX_RUNNING_SCRIPTS, running.size());
		assertEquals("Script 0", running.get(0).getAsString());
		assertEquals("Script 19", running.get(19).getAsString());
	}

	@Test
	public void blankValuesBecomeNull()
	{
		JsonObject json = AlertEvent.builder()
			.type(AlertEvent.SCRIPT_STOPPED)
			.plugin("  ")
			.message("")
			.build()
			.toJson(SESSION);

		assertTrue(json.get("plugin").isJsonNull());
		assertTrue(json.get("message").isJsonNull());
	}
}
