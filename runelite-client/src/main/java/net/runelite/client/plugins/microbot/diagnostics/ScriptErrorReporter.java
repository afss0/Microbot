package net.runelite.client.plugins.microbot.diagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ClientSessionManager;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.MicrobotApi;
import net.runelite.client.plugins.microbot.alerts.ClientAlertReporter;
import net.runelite.client.plugins.microbot.externalplugins.MicrobotPluginManager;
import net.runelite.client.util.ResetResistantAttachment;

@Slf4j
@Singleton
public class ScriptErrorReporter extends UnsynchronizedAppenderBase<ILoggingEvent>
{
	static final int MAX_FINGERPRINTS = 100;
	private static final int MAX_FRAMES = 20;
	private static final int FINGERPRINT_FRAMES = 3;
	private static final long FLUSH_MINUTES = 5;
	static final String CLIENT_THREAD_STALL_MESSAGE = "Client thread did not run task within";
	static final String CLIENT_THREAD_STALL_REASON = "client-thread-stall";
	static final long LOG_UPLOAD_DELAY_SECONDS = 5;
	static final long LOG_UPLOAD_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
	static final int MAX_LOG_BYTES = 256 * 1024;
	private static final Pattern IP_PATTERN = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
	private final AtomicLong lastLogUpload = new AtomicLong();

	private final MicrobotApi microbotApi;
	private final MicrobotPluginManager microbotPluginManager;
	private final ScheduledExecutorService executor;
	private final boolean disableTelemetry;
	private final ClientSessionManager clientSessionManager;
	private final ClientAlertReporter clientAlertReporter;
	private final Map<String, JsonObject> pending = new LinkedHashMap<>();
	private ScheduledFuture<?> flushTask;
	private volatile String playerName;

	@Inject
	ScriptErrorReporter(MicrobotApi microbotApi, MicrobotPluginManager microbotPluginManager, ScheduledExecutorService executor,
		ClientSessionManager clientSessionManager, ClientAlertReporter clientAlertReporter, @Named("disableTelemetry") boolean disableTelemetry)
	{
		this.microbotApi = microbotApi;
		this.microbotPluginManager = microbotPluginManager;
		this.executor = executor;
		this.disableTelemetry = disableTelemetry;
		this.clientSessionManager = clientSessionManager;
		this.clientAlertReporter = clientAlertReporter;
		setName("SCRIPT_ERROR_REPORTER");
	}

	public ResetResistantAttachment install(LoggerContext context)
	{
		setContext(context);
		start();
		return ResetResistantAttachment.attach(context, this, this::start);
	}

	@Override
	public void start()
	{
		if (isStarted())
		{
			return;
		}
		super.start();
		flushTask = executor.scheduleWithFixedDelay(this::flush, FLUSH_MINUTES, FLUSH_MINUTES, TimeUnit.MINUTES);
	}

	@Override
	public void stop()
	{
		if (flushTask != null)
		{
			flushTask.cancel(false);
		}
		super.stop();
		flush();
	}

	@Override
	protected void append(ILoggingEvent event)
	{
		if (event.getLevel().equals(Level.WARN) && event.getFormattedMessage().startsWith(CLIENT_THREAD_STALL_MESSAGE))
		{
			if (!isDisabled())
			{
				String name = playerName;
				executor.schedule(() -> uploadClientLog(name), LOG_UPLOAD_DELAY_SECONDS, TimeUnit.SECONDS);
			}
			return;
		}
		if (!event.getLevel().isGreaterOrEqual(Level.ERROR)
			|| event.getLoggerName().startsWith(ScriptErrorReporter.class.getPackageName())
			|| event.getLoggerName().equals(MicrobotApi.class.getName())
			|| isDisabled())
		{
			return;
		}

		IThrowableProxy root = rootCause(event.getThrowableProxy());
		if (root != null && InterruptedException.class.getName().equals(root.getClassName()))
		{
			return;
		}
		List<String> frames = frames(root);
		if (clientAlertReporter.isActive())
		{
			clientAlertReporter.onScriptError(event.getLoggerName(), frames, alertMessage(event, root));
		}
		String fingerprint = root == null
			? event.getLoggerName() + "|" + scrub(event.getMessage()).replaceAll("\\d+", "#")
			: root.getClassName() + "|" + frames.stream()
				.filter(frame -> !frame.startsWith("java.") && !frame.startsWith("javax.") && !frame.startsWith("jdk.") && !frame.startsWith("sun."))
				.limit(FINGERPRINT_FRAMES)
				.collect(Collectors.joining("|"));

		synchronized (pending)
		{
			JsonObject error = pending.get(fingerprint);
			if (error != null)
			{
				error.addProperty("count", error.get("count").getAsInt() + 1);
				return;
			}
			if (pending.size() >= MAX_FINGERPRINTS)
			{
				return;
			}
			pending.put(fingerprint, describe(event, root, frames, fingerprint));
		}
	}

	public void flush()
	{
		try
		{
			send();
		}
		catch (RuntimeException e)
		{
			log.debug("Error telemetry flush failed", e);
		}
	}

	private void send()
	{
		UUID sessionId = clientSessionManager.getMicrobotSessionId();
		if (sessionId == null)
		{
			return;
		}
		JsonArray errors = new JsonArray();
		synchronized (pending)
		{
			pending.values().forEach(errors::add);
			pending.clear();
		}
		if (errors.size() == 0 || isDisabled())
		{
			return;
		}
		errors.forEach(error -> attribute(error.getAsJsonObject()));

		JsonObject payload = new JsonObject();
		payload.addProperty("sessionId", sessionId.toString());
		payload.addProperty("microbotVersion", RuneLiteProperties.getMicrobotVersion());
		payload.addProperty("microbotCommit", RuneLiteProperties.getMicrobotCommit());
		payload.addProperty("buildChannel", RuneLiteProperties.getMicrobotBuildChannel());
		payload.addProperty("javaVersion", System.getProperty("java.version"));
		payload.addProperty("osName", System.getProperty("os.name"));
		payload.addProperty("osArch", System.getProperty("os.arch"));
		payload.add("errors", errors);
		try
		{
			microbotApi.submitErrors(payload, () -> requeue(errors));
		}
		catch (RuntimeException e)
		{
			requeue(errors);
			throw e;
		}
	}

	public void rememberPlayerName(String name)
	{
		if (name != null && !name.isEmpty())
		{
			playerName = name;
		}
	}

	private void requeue(JsonArray errors)
	{
		synchronized (pending)
		{
			for (JsonElement element : errors)
			{
				JsonObject error = element.getAsJsonObject();
				String fingerprint = error.get("fingerprint").getAsString();
				JsonObject existing = pending.get(fingerprint);
				if (existing != null)
				{
					existing.addProperty("count", existing.get("count").getAsInt() + error.get("count").getAsInt());
				}
				else if (pending.size() < MAX_FINGERPRINTS)
				{
					pending.put(fingerprint, error);
				}
			}
		}
	}

	int pendingCount()
	{
		synchronized (pending)
		{
			return pending.size();
		}
	}

	private JsonObject describe(ILoggingEvent event, IThrowableProxy root, List<String> frames, String fingerprint)
	{
		JsonObject error = new JsonObject();
		error.addProperty("fingerprint", fingerprint);
		error.addProperty("count", 1);
		error.addProperty("firstSeen", event.getTimeStamp());
		error.addProperty("logger", event.getLoggerName());
		error.addProperty("thread", scrub(event.getThreadName()));
		error.addProperty("message", scrub(event.getMessage()));
		if (root != null)
		{
			error.addProperty("exception", root.getClassName());
			error.addProperty("exceptionMessage", scrub(root.getMessage()));
		}
		JsonArray stack = new JsonArray();
		frames.forEach(stack::add);
		error.add("stack", stack);
		return error;
	}

	private String alertMessage(ILoggingEvent event, IThrowableProxy root)
	{
		String message = scrub(event.getFormattedMessage());
		if (root == null)
		{
			return message;
		}
		String exception = root.getClassName().substring(root.getClassName().lastIndexOf('.') + 1);
		String detail = scrub(root.getMessage());
		String cause = detail.isEmpty() ? exception : exception + ": " + detail;
		return message.isEmpty() ? cause : message + " (" + cause + ")";
	}

	private boolean isDisabled()
	{
		return disableTelemetry || Microbot.isTelemetryDisabled();
	}

	private void attribute(JsonObject error)
	{
		List<Plugin> plugins = microbotPluginManager.getInstalledPlugins();
		for (JsonElement frame : error.getAsJsonArray("stack"))
		{
			for (Plugin plugin : plugins)
			{
				String pkg = plugin.getClass().getPackageName();
				if (!pkg.equals(Microbot.class.getPackageName()) && frame.getAsString().startsWith(pkg + "."))
				{
					String internalName = plugin.getClass().getSimpleName();
					error.addProperty("plugin", internalName);
					error.addProperty("pluginVersion", microbotPluginManager.getInstalledPluginVersion(internalName)
						.orElse(plugin.getClass().getAnnotation(PluginDescriptor.class).version()));
					return;
				}
			}
		}
	}

	private static IThrowableProxy rootCause(IThrowableProxy ex)
	{
		while (ex != null && ex.getCause() != null && ex.getCause() != ex)
		{
			ex = ex.getCause();
		}
		return ex;
	}

	private static List<String> frames(IThrowableProxy ex)
	{
		List<String> frames = new ArrayList<>();
		if (ex == null)
		{
			return frames;
		}
		for (StackTraceElementProxy proxy : ex.getStackTraceElementProxyArray())
		{
			if (frames.size() >= MAX_FRAMES)
			{
				break;
			}
			StackTraceElement element = proxy.getStackTraceElement();
			frames.add(element.getClassName() + "." + element.getMethodName() + ":" + element.getLineNumber());
		}
		return frames;
	}

	private void uploadClientLog(String name)
	{
		UUID sessionId = clientSessionManager.getMicrobotSessionId();
		if (sessionId == null || isDisabled())
		{
			return;
		}
		long now = System.currentTimeMillis();
		long last = lastLogUpload.get();
		if (last != 0 && now - last < LOG_UPLOAD_INTERVAL_MILLIS || !lastLogUpload.compareAndSet(last, now))
		{
			return;
		}
		String text;
		try
		{
			text = scrubLog(tail(new File(RuneLite.LOGS_DIR, "client.log"), MAX_LOG_BYTES), name);
		}
		catch (IOException e)
		{
			log.debug("Could not read client log for upload", e);
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("sessionId", sessionId.toString());
		payload.addProperty("reason", CLIENT_THREAD_STALL_REASON);
		payload.addProperty("microbotVersion", RuneLiteProperties.getMicrobotVersion());
		payload.addProperty("log", text);
		microbotApi.submitClientLog(payload);
	}

	static String tail(File file, int maxBytes) throws IOException
	{
		try (RandomAccessFile raf = new RandomAccessFile(file, "r"))
		{
			long length = raf.length();
			long start = Math.max(0, length - maxBytes);
			long readFrom = start > 0 ? start - 1 : 0;
			byte[] bytes = new byte[(int) (length - readFrom)];
			raf.seek(readFrom);
			raf.readFully(bytes);
			String text = new String(bytes, StandardCharsets.UTF_8);
			if (start > 0)
			{
				if (bytes[0] == '\n')
				{
					text = text.substring(1);
				}
				else
				{
					int newline = text.indexOf('\n');
					text = newline < 0 ? "" : text.substring(newline + 1);
				}
			}
			return text;
		}
	}

	String scrubLog(String raw, String name)
	{
		String text = raw;
		if (name != null && !name.isEmpty())
		{
			text = text.replace(name, "[player]")
				.replace(name.replace(' ', ' '), "[player]")
				.replace(name.replace(' ', ' '), "[player]");
		}
		String user = System.getProperty("user.name");
		if (user != null && user.length() >= 3)
		{
			text = text.replace(user, "[user]");
		}
		StringBuilder out = new StringBuilder();
		for (String line : text.split("\n", -1))
		{
			out.append(IP_PATTERN.matcher(DiagnosticReport.redact(line)).replaceAll("[ip]")).append('\n');
		}
		return out.toString();
	}

	private String scrub(String raw)
	{
		String name = playerName;
		if (raw != null && name != null && !name.isEmpty())
		{
			raw = raw.replace(name, "[player]").replace(name.replace('\u00A0', ' '), "[player]");
		}
		return DiagnosticReport.clean(raw);
	}
}
