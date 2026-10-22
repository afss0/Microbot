package net.runelite.client.plugins.microbot;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.plugins.microbot.alerts.ClientLinkToken;
import net.runelite.client.plugins.microbot.alerts.DeviceLinkPoll;
import net.runelite.client.plugins.microbot.alerts.DeviceLinkStart;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Class that communicates with the microbot api
 */
@Slf4j
public class MicrobotApi {

    static final long CANCEL_DEVICE_LINK_TIMEOUT_SECONDS = 2;

    private final OkHttpClient client;
    private final Gson gson;
    private final String pluginTelemetryToken;
    private final ClientLinkToken clientLinkToken;

    private final String microbotApiUrl = System.getProperty("microbot.apiUrl", "https://microbot.cloud/api");
    @Inject
    MicrobotApi(OkHttpClient client, Gson gson, ClientLinkToken clientLinkToken) {
        this.client = client;
        this.gson = gson;
        this.clientLinkToken = clientLinkToken;

        this.pluginTelemetryToken = "zeifkdsjqfiedfb15181==";
    }

    /**
     * Opens a new session by sending a request to the microbot API and retrieves the session UUID.
     *
     * Steps:
     * 1. Sends an HTTP GET request to the `/session` endpoint.
     * 2. Reads the response body as a stream and parses it as a `UUID` using Gson.
     * 3. If the response contains invalid JSON or the parsed UUID is invalid, an exception is thrown.
     * 4. Uses a try-with-resources block to ensure the HTTP response and input stream are properly closed.
     *
     * @return the UUID of the newly opened session.
     * @throws IOException if the HTTP request fails, the response body is invalid, or the UUID parsing fails.
     */
    public UUID microbotOpen() throws IOException {
        try (Response response = client.newCall(linked(new Request.Builder().url(microbotApiUrl + "/session")).build()).execute()) {
            ResponseBody body = response.body();

            InputStream in = body.byteStream();
            return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), UUID.class);
        } catch (JsonParseException | IllegalArgumentException ex) // UUID.fromString can throw IllegalArgumentException
        {
            throw new IOException(ex);
        }
    }

    /**
     * Increments the install counter for the given plugin.
     *
     * @param internalName plugin internal name
     * @param displayName display name
     * @param version version installed
     */
    public void increasePluginInstall(String internalName, String displayName, String version)
    {
        if (Strings.isNullOrEmpty(internalName)) {
            return;
        }

        if (Strings.isNullOrEmpty(pluginTelemetryToken)) {
            log.debug("Skipping plugin telemetry for {}: missing token", internalName);
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("pluginName", Strings.nullToEmpty(internalName));
        payload.addProperty("internalName", internalName);
        payload.addProperty("pluginInternalName", internalName);
        if (!Strings.isNullOrEmpty(version)) {
            payload.addProperty("version", version);
            payload.addProperty("pluginVersion", version);
        }

        Request request = new Request.Builder()
                .url(microbotApiUrl + "/plugintelemetry/plugin-install/increase")
                .header("X-Plugin-Telemetry-Token", pluginTelemetryToken)
                .post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(payload)))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.debug("Plugin telemetry call failed for {}: HTTP {}", internalName, response.code());
            }
        } catch (IOException ex) {
            log.debug("Plugin telemetry call failed for {}", internalName, ex);
        }
    }

    public void submitClientLog(JsonObject payload)
    {
        Request request = new Request.Builder()
                .url(microbotApiUrl + "/plugintelemetry/client-logs")
                .header("X-Plugin-Telemetry-Token", pluginTelemetryToken)
                .post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(payload)))
                .build();

        client.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("Client log upload failed", e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                boolean successful = response.isSuccessful();
                int code = response.code();
                response.close();
                if (!successful)
                {
                    log.debug("Client log upload rejected: HTTP {}", code);
                }
            }
        });
    }

    public void submitErrors(JsonObject payload, Runnable onRejected)
    {
        Request request = new Request.Builder()
                .url(microbotApiUrl + "/plugintelemetry/errors")
                .header("X-Plugin-Telemetry-Token", pluginTelemetryToken)
                .post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(payload)))
                .build();

        client.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("Error telemetry upload failed", e);
                onRejected.run();
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                boolean successful = response.isSuccessful();
                int code = response.code();
                response.close();
                if (!successful)
                {
                    log.debug("Error telemetry upload rejected: HTTP {}", code);
                    onRejected.run();
                }
            }
        });
    }

    public void sendAlertEvent(String token, JsonObject payload, Runnable onUnauthorized)
    {
        Request request = new Request.Builder()
                .url(microbotApiUrl + "/alerts/events")
                .header(ClientLinkToken.HEADER, token)
                .post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(payload)))
                .build();

        client.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("Alert event upload failed", e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                int code = response.code();
                response.close();
                if (code == 401)
                {
                    onUnauthorized.run();
                }
                else if (code >= 300)
                {
                    log.debug("Alert event rejected: HTTP {}", code);
                }
            }
        });
    }

    public DeviceLinkStart startDeviceLink(String label) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("label", label);
        try (Response response = client.newCall(deviceLinkRequest("/alerts/device-links", body)).execute())
        {
            return DeviceLinkStart.parse(gson, response.code(), response.body().string());
        }
    }

    public DeviceLinkPoll pollDeviceLink(String deviceCode) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("deviceCode", deviceCode);
        try (Response response = client.newCall(deviceLinkRequest("/alerts/device-links/token", body)).execute())
        {
            return DeviceLinkPoll.parse(gson, response.code(), response.body().string());
        }
    }

    public CompletableFuture<Void> cancelDeviceLink(String deviceCode)
    {
        JsonObject body = new JsonObject();
        body.addProperty("deviceCode", deviceCode);
        CompletableFuture<Void> done = new CompletableFuture<>();
        Call call = client.newCall(deviceLinkRequest("/alerts/device-links/cancel", body));
        call.timeout().timeout(CANCEL_DEVICE_LINK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        call.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("Unable to cancel the pending link for this client", e);
                done.complete(null);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                int code = response.code();
                response.close();
                if (code >= 300)
                {
                    log.debug("Cancelling the pending link for this client was rejected: HTTP {}", code);
                }
                done.complete(null);
            }
        });
        return done;
    }

    private Request deviceLinkRequest(String path, JsonObject body)
    {
        return new Request.Builder()
                .url(microbotApiUrl + path)
                .post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(body)))
                .build();
    }

    private Request.Builder linked(Request.Builder builder)
    {
        clientLinkToken.get().ifPresent(token -> builder.header(ClientLinkToken.HEADER, token));
        return builder;
    }

    /**
     * Sends a ping request to the microbot API to update the session status.
     *
     * Steps:
     * 1. Constructs an HTTP GET request with query parameters:
     *    - `sessionId`: The unique identifier of the session (UUID).
     *    - `isLoggedIn`: A boolean indicating if the user is logged in.
     *    - `version`: The version of the microbot (retrieved from `RuneLiteProperties`).
     * 2. Sends the request using the HTTP client and checks the response.
     * 3. If the response is unsuccessful, an `IOException` is thrown to indicate the failure.
     * 4. Uses a try-with-resources block to automatically close the HTTP response.
     *
     * @param uuid the unique identifier of the session to ping.
     * @param loggedIn the login status to send with the ping.
     * @throws IOException if the HTTP request fails or the response is unsuccessful.
     */
    public void microbotPing(UUID uuid, boolean loggedIn) throws IOException {
        try (Response response = client.newCall(linked(new Request.Builder().url(microbotApiUrl + "/session?sessionId=" + uuid.toString()
                + "&isLoggedIn=" + loggedIn
                + "&version=" + RuneLiteProperties.getMicrobotVersion())).build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unsuccessful ping");
            }
        }
    }

    /**
     * Sends a DELETE request to remove a microbot session by its UUID.
     *
     * Steps:
     * 1. Constructs an HTTP DELETE request to the endpoint with the session ID as a query parameter.
     * 2. Sends the request using the HTTP client and closes the response to free resources.
     *
     * @param uuid the unique identifier of the session to be deleted.
     * @throws IOException if an I/O error occurs during the HTTP request.
     */
    public void microbotDelete(UUID uuid) throws IOException {
        Request request = new Request.Builder()
                .delete()
                .url(microbotApiUrl + "/session?sessionId=" + uuid)
                .build();

        client.newCall(request).execute().close();
    }
}
