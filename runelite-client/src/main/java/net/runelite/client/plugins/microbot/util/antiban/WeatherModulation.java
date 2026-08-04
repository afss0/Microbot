package net.runelite.client.plugins.microbot.util.antiban;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Modulates anti-ban parameters based on real-world weather data from a
 * fully random location on land. Each account is assigned a unique,
 * permanent location (persisted in settings) so that behaviour drifts
 * independently across accounts, driven by a chaotic external process
 * (real weather) rather than a static PRNG seed.
 *
 * <h3>Weather variables used</h3>
 * <ul>
 *   <li><b>Temperature</b> — speed, break length, micro-break chance (heat)</li>
 *   <li><b>Precipitation</b> — micro-break chance, break length (rain)</li>
 *   <li><b>Wind gusts</b> — speed (sudden burst drag), micro-break chance,
 *       mistake probability (cursor jitter)</li>
 * </ul>
 *
 * <p>Temperature follows a predictable diurnal cycle, but precipitation and
 * wind are largely chaotic, ensuring the overall modulation fingerprint is
 * aperiodic and location-specific.
 *
 * <p>Weather data: Open-Meteo (https://open-meteo.com) — free, no API key.
 * Reverse geocoding: Nominatim (OpenStreetMap) — free, requires User-Agent
 * identification, max ~1 req/s.
 */
@Slf4j
public final class WeatherModulation {

    private static final String OPEN_METEO_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s" +
                    "&hourly=temperature_2m,precipitation,wind_gusts_10m,weather_code" +
                    "&timezone=auto&forecast_days=1&wind_speed_unit=kmh";

    private static final String NOMINATIM_URL =
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2" +
                    "&lat=%s&lon=%s&zoom=10&accept-language=en";

    /** How long to cache a weather response before re-fetching (ms). */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    private static final double MIN_LAT = -55.0;
    private static final double MAX_LAT = 60.0;
    private static final int MAX_LOCATION_RETRIES = 15;
    /** User-Agent sent to Nominatim (required by their usage policy). */
    private static final String NOMINATIM_UA = "Microbot/1.0 (weather-modulation)";

    private static final String[] NOMINATIM_PLACE_KEYS = {"city", "town", "village", "hamlet",
            "municipality", "county", "state_district"};

    // ---- cached weather state ----

    @Getter
    private static String cityName = "Unknown";
    @Getter
    private static double latitude;
    @Getter
    private static double longitude;
    private static double currentTempCelsius = 15.0;
    private static double currentPrecipitationMm = 0.0;
    private static double currentWindGustKmh = 8.0;
    /** WMO weather code from Open-Meteo (0=clear … 99=thunderstorm+heavy hail). */
    private static int currentWeatherCode = 0;
    private static long lastFetchTime = 0L;
    private static boolean fetchFailed = false;

    // ─────────────────────────────────────────────────────────────────────────
    //  Factor computations (all public for testing)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Temperature modulates action speed. Colder → slower (hands are cold),
     * hotter → slightly faster (impatience).
     * <p>
     * Returns a speed multiplier (≤ 1.0): −10°C → 0.75, 15°C → 0.875, 40°C → 1.00.
     * Clamped at 1.0 so heat never accelerates beyond baseline.
     */
    public static double speedFactor() {
        double factor = 0.875 + 0.005 * (currentTempCelsius - 15.0);
        return Math.max(0.75, Math.min(1.0, factor));
    }

    /**
     * Wind gusts — sudden, short-lived bursts — penalise interaction speed
     * (the player hesitates, readjusts position, or mistypes due to being
     * startled) and contribute to mistake probability.
     * <p>
     * Returns a speed multiplier (≤ 1.0): 0 km/h → 1.000, 75+ km/h → 0.833.
     */
    public static double windGustFactor() {
        double timePenalty = 1.0 + 0.20 * Math.min(1.0, Math.max(0.0, currentWindGustKmh - 15.0) / 60.0);
        return 1.0 / timePenalty;
    }

    /**
     * Returns a human-readable label for the current WMO weather code.
     */
    public static String getWeatherDescription() {
        return describeCode(currentWeatherCode);
    }

    /**
     * WMO weather interpretation codes — qualitative description of the
     * current sky condition, precipitation type and intensity.
     * <p>
     * Used to add a "mood" layer to the modulation: a thunderstorm affects
     * behaviour beyond what temperature + precipitation mm + gusts capture.
     */
    static String describeCode(int code) {
        switch (code) {
            case 0:  return "Clear sky ☀️";
            case 1:  return "Mainly clear 🌤";
            case 2:  return "Partly cloudy ⛅";
            case 3:  return "Overcast ☁️";
            case 45: return "Fog 🌫";
            case 48: return "Rime fog 🌫";
            case 51: return "Light drizzle 🌦";
            case 53: return "Moderate drizzle 🌦";
            case 55: return "Dense drizzle 🌦";
            case 56: return "Freezing drizzle 🌦";
            case 57: return "Dense freezing drizzle 🌦";
            case 61: return "Slight rain 🌧";
            case 63: return "Moderate rain 🌧";
            case 65: return "Heavy rain 🌧";
            case 66: return "Freezing rain 🌧";
            case 67: return "Heavy freezing rain 🌧";
            case 71: return "Slight snow 🌨";
            case 73: return "Moderate snow 🌨";
            case 75: return "Heavy snow ❄️";
            case 77: return "Snow grains ❄️";
            case 80: return "Slight showers 🌦";
            case 81: return "Moderate showers 🌦";
            case 82: return "Violent showers 🌦";
            case 85: return "Slight snow showers 🌨";
            case 86: return "Heavy snow showers 🌨";
            case 95: return "Thunderstorm ⛈";
            case 96: return "Thunderstorm + hail ⛈";
            case 99: return "Thunderstorm + heavy hail ⛈";
            default: return "Unknown";
        }
    }

    /**
     * Qualitative mood multiplier based on the WMO weather code.
     * <p>
     * Returns a speed multiplier (≤ 1.0): Clear → 1.000, Thunderstorm → 0.870.
     */
    public static double weatherMoodFactor() {
        double timePenalty = 1.000;
        int c = currentWeatherCode;
        if (c <= 1) timePenalty = 1.000;
        else if (c <= 48) timePenalty = 1.030;
        else if (c <= 77) timePenalty = 1.060;
        else if (c <= 86) timePenalty = 1.100;
        else timePenalty = 1.150;
        
        return 1.0 / timePenalty;
    }

    /**
     * Micro-break contribution from the WMO weather code (storm anxiety).
     */
    public static double weatherCodeMicroBreakOffset() {
        if (currentWeatherCode >= 95) return 0.08;  // thunderstorm → +8%
        if (currentWeatherCode >= 80) return 0.02;  // showers → +2%
        return 0.0;
    }

    /**
     * Combined speed = temperature × gust × weather mood.
     * All factors are speed multipliers (≤ 1.0), so the product is always ≤ 1.0.
     * Lower values = slower actions; never accelerates beyond baseline.
     * Returns 1.0 (no effect) when weather modulation is disabled.
     */
    public static double combinedSpeedFactor() {
        if (!Rs2AntibanSettings.weatherEnabled) return 1.0;
        return speedFactor() * windGustFactor() * weatherMoodFactor();
    }

    /**
     * Colder weather → longer micro-breaks (staying still in the cold
     * is uncomfortable, so players step away more).
     * <p>
     * Also: rain makes breaks slightly longer (no one enjoys standing
     * in the rain).
     * <p>
     * Returns a break-length multiplier (≥ 1.0): larger values = longer breaks.
     * Temperature contribution: −10°C → 1.10, 15°C → 1.00, 40°C → 0.90.
     * Rain contribution: 0 mm → 1.00, 10 mm → 1.04.
     */
    public static double breakLengthFactor() {
        double tempPart = 1.0 + 0.10 * (15.0 - currentTempCelsius) / 25.0;
        double rainPart = 1.0 + 0.04 * Math.min(1.0, currentPrecipitationMm / 10.0);
        return tempPart * rainPart;
    }

    /**
     * Total micro-break chance offset contributed by all weather variables.
     * <p>
     * Sources:
     * <ul>
     *   <li><b>Heat</b> — +5% when &gt;30°C (distraction, dehydration)</li>
     *   <li><b>Rain</b> — +3% when &gt;2 mm/h (discomfort)</li>
     *   <li><b>Wind gusts</b> — +10% when &gt;40 km/h, scaling to +10% at 90+ km/h
     *       (sudden strong gust startles → player pauses)</li>
     *   <li><b>Weather code</b> — +2% for showers, +8% for thunderstorms</li>
     * </ul>
     */
    public static double microBreakChanceOffset() {
        double heat = currentTempCelsius > 30.0
                ? 0.05 * Math.min(1.0, (currentTempCelsius - 30.0) / 10.0)
                : 0.0;
        double rain = currentPrecipitationMm > 2.0
                ? 0.03 * Math.min(1.0, (currentPrecipitationMm - 2.0) / 8.0)
                : 0.0;
        double gust = currentWindGustKmh > 40.0
                ? 0.10 * Math.min(1.0, (currentWindGustKmh - 40.0) / 50.0)
                : 0.0;
        double storm = weatherCodeMicroBreakOffset();
        return heat + rain + gust + storm;
    }

    /**
     * Combined mistake-probability offset.
     * <p>
     * Rain and wind gusts increase the chance of click errors.
     * <ul>
     *   <li>Rain: up to +3% at 5 mm/h</li>
     *   <li>Wind gusts: up to +6% at 80 km/h (startle misclick)</li>
     * </ul>
     */
    public static double mistakeProbabilityOffset() {
        double rainPart = 0.03 * Math.min(1.0, currentPrecipitationMm / 5.0);
        double gustPart = 0.06 * Math.min(1.0, Math.max(0.0, currentWindGustKmh - 30.0) / 50.0);
        return rainPart + gustPart;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Location assignment
    // ─────────────────────────────────────────────────────────────────────────

    public static void assignRandomLocation() {
        for (int attempt = 0; attempt < MAX_LOCATION_RETRIES; attempt++) {
            double lat = randomLat();
            double lon = randomLon();
            cityName = reverseGeocode(lat, lon);
            if (cityName != null) {
                latitude = lat;
                longitude = lon;
                fetchFailed = false;
                lastFetchTime = 0L;
                log.debug("WeatherModulation: assigned {} ({}, {})", cityName, lat, lon);
                return;
            }
        }
        latitude = randomLat();
        longitude = randomLon();
        cityName = String.format("%.2f, %.2f", latitude, longitude);
        fetchFailed = false;
        lastFetchTime = 0L;
        log.debug("WeatherModulation: fallback coords after {} retries — {}",
                MAX_LOCATION_RETRIES, cityName);
    }

    public static void setLocation(String name, double lat, double lon) {
        cityName = name;
        latitude = lat;
        longitude = lon;
        fetchFailed = false;
        lastFetchTime = 0L;
        log.debug("WeatherModulation: location set to {} ({}, {})", name, lat, lon);
    }

    private static double randomLat() {
        double u = ThreadLocalRandom.current().nextDouble();
        double minRad = Math.toRadians(MIN_LAT);
        double maxRad = Math.toRadians(MAX_LAT);
        double latRad = Math.asin(u * Math.sin(maxRad) + (1.0 - u) * Math.sin(minRad));
        return Math.toDegrees(latRad);
    }

    private static double randomLon() {
        return ThreadLocalRandom.current().nextDouble(-180.0, 180.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Reverse geocoding (Nominatim / OSM)
    // ─────────────────────────────────────────────────────────────────────────

    static String reverseGeocode(double lat, double lon) {
        String urlStr = String.format(NOMINATIM_URL, lat, lon);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", NOMINATIM_UA);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code != 200) {
                log.debug("WeatherModulation: Nominatim HTTP {} for {}, {}", code, lat, lon);
                return null;
            }

            String body;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                body = sb.toString();
            }
            return parseNominatimResponse(body);
        } catch (IOException e) {
            log.debug("WeatherModulation: Nominatim request failed for {}, {}: {}",
                    lat, lon, e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String parseNominatimResponse(String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            if (root.has("error")) return null;

            String type = root.has("type") && !root.get("type").isJsonNull()
                    ? root.get("type").getAsString() : null;

            if (type != null && ("city".equals(type) || "town".equals(type)
                    || "village".equals(type) || "hamlet".equals(type)
                    || "municipality".equals(type) || "borough".equals(type)
                    || "suburb".equals(type) || "neighbourhood".equals(type))) {
                if (root.has("display_name") && !root.get("display_name").isJsonNull()) {
                    String display = root.get("display_name").getAsString();
                    int comma = display.indexOf(',');
                    if (comma > 0) return display.substring(0, comma).trim();
                    return display.trim();
                }
                if (root.has("name") && !root.get("name").isJsonNull()) {
                    return root.get("name").getAsString().trim();
                }
            }

            if (root.has("address") && !root.get("address").isJsonNull()) {
                JsonObject addr = root.getAsJsonObject("address");
                for (String key : NOMINATIM_PLACE_KEYS) {
                    if (addr.has(key) && !addr.get(key).isJsonNull()) {
                        return addr.get(key).getAsString().trim();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("WeatherModulation: failed to parse Nominatim response", e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Weather fetching (Open-Meteo)
    // ─────────────────────────────────────────────────────────────────────────

    public static void refreshWeather() {
        String urlStr = String.format(OPEN_METEO_URL, latitude, longitude);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code != 200) {
                log.warn("WeatherModulation: Open-Meteo returned HTTP {}", code);
                fetchFailed = true;
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                parseWeatherResponse(sb.toString());
            }
            fetchFailed = false;
            lastFetchTime = System.currentTimeMillis();
            log.debug("WeatherModulation: refreshed — {}°C, {}mm, gust {}km/h, code {} {}",
                    currentTempCelsius, currentPrecipitationMm, currentWindGustKmh,
                    currentWeatherCode, describeCode(currentWeatherCode));
        } catch (IOException e) {
            log.warn("WeatherModulation: fetch failed — {}", e.getMessage());
            fetchFailed = true;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static void parseWeatherResponse(String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonObject hourly = root.getAsJsonObject("hourly");
            if (hourly == null) return;

            JsonArray times = hourly.getAsJsonArray("time");
            JsonArray temps = hourly.getAsJsonArray("temperature_2m");
            JsonArray precips = hourly.getAsJsonArray("precipitation");
            JsonArray gusts = hourly.getAsJsonArray("wind_gusts_10m");
            JsonArray codes = hourly.getAsJsonArray("weather_code");

            if (times == null || temps == null) return;

            String nowHour = LocalDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"));
            int idx = -1;
            for (int i = 0; i < times.size(); i++) {
                String t = times.get(i).getAsString();
                if (t.startsWith(nowHour)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0 && times.size() > 0) idx = 0;

            if (idx >= 0) {
                JsonElement te = temps.get(idx);
                if (te != null && !te.isJsonNull()) {
                    currentTempCelsius = te.getAsDouble();
                }
                if (precips != null) {
                    JsonElement pe = precips.get(idx);
                    if (pe != null && !pe.isJsonNull()) {
                        currentPrecipitationMm = pe.getAsDouble();
                    }
                }
                if (gusts != null) {
                    JsonElement ge = gusts.get(idx);
                    if (ge != null && !ge.isJsonNull()) {
                        currentWindGustKmh = ge.getAsDouble();
                    }
                }
                if (codes != null) {
                    JsonElement ce = codes.get(idx);
                    if (ce != null && !ce.isJsonNull()) {
                        currentWeatherCode = ce.getAsInt();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("WeatherModulation: failed to parse response", e);
        }
    }

    public static void ensureFresh() {
        if (!Rs2AntibanSettings.weatherEnabled) return;
        long now = System.currentTimeMillis();
        if (now - lastFetchTime > CACHE_TTL_MS) {
            refreshWeather();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Accessors (UI display)
    // ─────────────────────────────────────────────────────────────────────────

    public static double getCurrentTempCelsius() { return currentTempCelsius; }
    public static double getCurrentPrecipitationMm() { return currentPrecipitationMm; }
    public static double getCurrentWindGustKmh() { return currentWindGustKmh; }
    public static int getCurrentWeatherCode() { return currentWeatherCode; }
    public static boolean isFetchFailed() { return fetchFailed; }
    public static long getCacheAgeMs() {
        if (lastFetchTime == 0L) return Long.MAX_VALUE;
        return System.currentTimeMillis() - lastFetchTime;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Persistence lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    public static void reset() {
        cityName = "Unknown";
        latitude = 0.0;
        longitude = 0.0;
        currentTempCelsius = 15.0;
        currentPrecipitationMm = 0.0;
        currentWindGustKmh = 8.0;
        currentWeatherCode = 0;
        lastFetchTime = 0L;
        fetchFailed = false;
    }

    public static void initFromSettings() {
        if (Rs2AntibanSettings.weatherLat != 0.0 || Rs2AntibanSettings.weatherLon != 0.0) {
            latitude = Rs2AntibanSettings.weatherLat;
            longitude = Rs2AntibanSettings.weatherLon;
            cityName = Rs2AntibanSettings.weatherCityName != null
                    && !Rs2AntibanSettings.weatherCityName.isEmpty()
                    ? Rs2AntibanSettings.weatherCityName
                    : String.format("%.2f, %.2f", latitude, longitude);
        } else if (Rs2AntibanSettings.weatherEnabled) {
            assignRandomLocation();
            persistToSettings();
        }
    }

    public static void persistToSettings() {
        Rs2AntibanSettings.weatherCityName = cityName;
        Rs2AntibanSettings.weatherLat = latitude;
        Rs2AntibanSettings.weatherLon = longitude;
    }
}
