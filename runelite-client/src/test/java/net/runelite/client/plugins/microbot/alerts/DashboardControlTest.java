package net.runelite.client.plugins.microbot.alerts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.MicrobotApi;
import net.runelite.client.plugins.microbot.MicrobotConfig;
import org.junit.Before;
import org.junit.Test;

public class DashboardControlTest
{
	private static final String TOKEN = "token";
	private static final String COMMAND = "6f1c7a8e-1a2b-4c3d-9e8f-0123456789ab";

	private MicrobotApi api;
	private MicrobotConfig config;
	private PluginManager pluginManager;
	private DashboardControl control;

	@Before
	public void setUp()
	{
		api = mock(MicrobotApi.class);
		config = mock(MicrobotConfig.class);
		pluginManager = mock(PluginManager.class);
		control = new DashboardControl(api, config, pluginManager, mock(Client.class), mock(ClientThread.class));
	}

	@Test
	public void onlyStopScriptsAndLogoutAreHandled()
	{
		assertTrue(DashboardControl.handles("stop_scripts"));
		assertTrue(DashboardControl.handles("logout"));
		assertFalse(DashboardControl.handles("screenshot"));
		assertFalse(DashboardControl.handles(null));
	}

	@Test
	public void commandsAreRefusedWhenControlIsTurnedOff()
	{
		when(config.allowDashboardControl()).thenReturn(false);

		control.run(TOKEN, COMMAND, "stop_scripts");

		verify(api).reportCommandResult(TOKEN, COMMAND, false, "disabled");
		verify(pluginManager, never()).getPlugins();
	}

	@Test
	public void unknownCommandsAreReportedAsUnsupported()
	{
		when(config.allowDashboardControl()).thenReturn(true);

		control.run(TOKEN, COMMAND, "self_destruct");

		verify(api).reportCommandResult(TOKEN, COMMAND, false, "unsupported");
	}
}
