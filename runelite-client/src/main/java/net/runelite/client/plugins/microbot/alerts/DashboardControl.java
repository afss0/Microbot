package net.runelite.client.plugins.microbot.alerts;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.MicrobotApi;
import net.runelite.client.plugins.microbot.MicrobotConfig;
import net.runelite.client.plugins.microbot.accountselector.AutoLoginSuppressionRequest;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.util.RunnableExceptionLogger;

@Slf4j
@Singleton
public class DashboardControl
{
	static final String STOP_SCRIPTS = "stop_scripts";
	static final String LOGOUT = "logout";
	static final String STOP_NOTICE = "The Microbot dashboard stopped your scripts.";
	static final String LOGOUT_NOTICE = "The Microbot dashboard is logging this account out.";

	private final MicrobotApi microbotApi;
	private final MicrobotConfig config;
	private final PluginManager pluginManager;
	private final Client client;
	private final ClientThread clientThread;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
	{
		Thread thread = new Thread(runnable, "microbot-dashboard-control");
		thread.setDaemon(true);
		return thread;
	});

	@Inject
	DashboardControl(MicrobotApi microbotApi, MicrobotConfig config, PluginManager pluginManager, Client client, ClientThread clientThread)
	{
		this.microbotApi = microbotApi;
		this.config = config;
		this.pluginManager = pluginManager;
		this.client = client;
		this.clientThread = clientThread;
	}

	static boolean handles(String type)
	{
		return STOP_SCRIPTS.equals(type) || LOGOUT.equals(type);
	}

	void run(String token, String commandId, String type)
	{
		if (!handles(type))
		{
			microbotApi.reportCommandResult(token, commandId, false, "unsupported");
			return;
		}
		if (!config.allowDashboardControl())
		{
			microbotApi.reportCommandResult(token, commandId, false, "disabled");
			return;
		}
		worker.execute(RunnableExceptionLogger.wrap(() -> execute(token, commandId, type)));
	}

	private void execute(String token, String commandId, String type)
	{
		boolean succeeded = run(LOGOUT.equals(type) ? this::stopScriptsAndLogout : this::stopScripts);
		microbotApi.reportCommandResult(token, commandId, succeeded, succeeded ? null : "failed");
		if (succeeded && STOP_SCRIPTS.equals(type))
		{
			notifyPlayer(STOP_NOTICE);
		}
	}

	private static boolean run(BooleanSupplier action)
	{
		try
		{
			return action.getAsBoolean();
		}
		catch (RuntimeException e)
		{
			log.warn("A dashboard command failed", e);
			return false;
		}
	}

	private boolean stopScriptsAndLogout()
	{
		if (!stopScripts())
		{
			return false;
		}
		notifyPlayer(LOGOUT_NOTICE);
		if (Rs2Player.logoutWithoutAutoLogin() != Rs2Player.TerminalLogoutState.LOGGED_OUT)
		{
			return false;
		}
		Microbot.getEventBus().post(AutoLoginSuppressionRequest.loggedOut());
		return true;
	}

	private boolean stopScripts()
	{
		boolean[] stopped = {false};
		try
		{
			SwingUtilities.invokeAndWait(() -> stopped[0] = stopRunningScripts());
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
		catch (InvocationTargetException e)
		{
			log.warn("Unable to stop scripts for the dashboard", e.getCause());
			return false;
		}
		return stopped[0];
	}

	private boolean stopRunningScripts()
	{
		for (Plugin plugin : runningScripts())
		{
			Microbot.stopPlugin(plugin);
		}
		return runningScripts().isEmpty();
	}

	private List<Plugin> runningScripts()
	{
		return pluginManager.getPlugins().stream()
			.filter(ClientAlertReporter::isScript)
			.filter(pluginManager::isPluginActive)
			.collect(Collectors.toList());
	}

	private void notifyPlayer(String notice)
	{
		log.info(notice);
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", notice, null);
			}
		});
	}
}
