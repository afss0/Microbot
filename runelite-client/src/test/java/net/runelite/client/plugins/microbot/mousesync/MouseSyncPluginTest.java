package net.runelite.client.plugins.microbot.mousesync;

import net.runelite.api.Client;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.ClientUI;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JPanel;
import java.awt.Canvas;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The grace countdown is a debounce, not a lockout: a new gesture during
 * GRACE_PERIOD must restart it. This pins the other half of that contract —
 * re-entering grace must not mistake MouseSync's own disabled client for the
 * user's "Disable Input" choice and leave input off forever.
 */
public class MouseSyncPluginTest
{
	private static final int GRACE_MS = 300;

	private MouseSyncPlugin plugin;
	private ExecutorService executor;
	private JPanel clientPanel;
	private Object previousClient;
	private Object previousClientUI;
	private Object previousInputSelector;

	@Before
	public void setUp() throws Exception
	{
		Canvas canvas = new Canvas();

		Client client = mock(Client.class);
		when(client.getCanvas()).thenReturn(canvas);

		MouseSyncConfig config = mock(MouseSyncConfig.class);
		when(config.enabled()).thenReturn(true);
		when(config.skipWhenUnfocused()).thenReturn(false);
		when(config.gracePeriodMs()).thenReturn(GRACE_MS);

		clientPanel = new JPanel();
		clientPanel.setEnabled(true);

		previousClient = swap(Microbot.class, "client", client);
		previousClientUI = swap(ClientUI.class, "client", clientPanel);
		previousInputSelector = swap(Microbot.class, "inputSelector", null);

		executor = Executors.newSingleThreadScheduledExecutor();
		plugin = new MouseSyncPlugin();
		set(plugin, "config", config);
		set(plugin, "executor", executor);
	}

	@After
	public void tearDown() throws Exception
	{
		executor.shutdownNow();
		executor.awaitTermination(1, TimeUnit.SECONDS);
		swap(Microbot.class, "client", previousClient);
		swap(ClientUI.class, "client", previousClientUI);
		swap(Microbot.class, "inputSelector", previousInputSelector);
	}

	@Test
	public void aGestureDuringGraceRestartsTheCountdownAndStillRestoresInput() throws Exception
	{
		plugin.onBotInteractionStart();
		assertFalse("MouseSync owns input during an interaction", clientPanel.isEnabled());

		plugin.onBotInteractionEnd();
		sleep(100);

		// Second gesture inside the first grace window.
		assertEquals(MouseSyncPlugin.State.GRACE_PERIOD, plugin.getState());
		plugin.onBotInteractionStart();
		assertEquals(MouseSyncPlugin.State.BOT_ACTIVE, plugin.getState());
		plugin.onBotInteractionEnd();

		// Past the FIRST countdown, still inside the restarted one.
		sleep(100);
		assertEquals("the new gesture must restart the grace countdown",
			MouseSyncPlugin.State.GRACE_PERIOD, plugin.getState());

		// Past the restarted countdown.
		sleep(GRACE_MS + 150);
		assertEquals(MouseSyncPlugin.State.IDLE, plugin.getState());
		assertTrue("re-entering grace must not mark input as user-disabled",
			clientPanel.isEnabled());
	}

	private static void sleep(long millis) throws InterruptedException
	{
		Thread.sleep(millis);
	}

	private static Object swap(Class<?> owner, String name, Object value) throws Exception
	{
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		Object previous = field.get(null);
		field.set(null, value);
		return previous;
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
