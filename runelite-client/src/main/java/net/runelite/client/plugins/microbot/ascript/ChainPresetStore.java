package net.runelite.client.plugins.microbot.ascript;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent named chain presets stored in the active RuneLite profile.
 * <p>
 * The active chain remains in {@code chainModules}; this store only keeps the user's
 * library of named chains. JSON is used instead of a custom delimiter format so a
 * preset name or a quoted chain value cannot corrupt the library.
 */
final class ChainPresetStore {

    static final String CONFIG_KEY = "chainPresets";

    private static final Gson GSON = new Gson();
    private static final Type PRESET_TYPE =
            new TypeToken<LinkedHashMap<String, String>>() { }.getType();

    private ChainPresetStore() {
    }

    /**
     * Parse a stored preset map. Blank data is an empty library; malformed data is
     * rejected explicitly so the panel can show an error instead of silently dropping
     * the user's saved chains.
     */
    static LinkedHashMap<String, String> parse(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, String> parsed = GSON.fromJson(encoded, PRESET_TYPE);
            if (parsed == null) {
                return new LinkedHashMap<>();
            }
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                String name = entry.getKey();
                String spec = entry.getValue();
                if (name == null || name.trim().isEmpty() || spec == null) {
                    throw new IllegalArgumentException("saved chains contain an empty name or value");
                }
                result.put(name, spec);
            }
            return result;
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IllegalArgumentException("saved chains are not valid JSON", ex);
        }
    }

    /** Serialize a library; an empty library is stored as an empty string. */
    static String serialize(Map<String, String> presets) {
        if (presets == null || presets.isEmpty()) {
            return "";
        }
        return GSON.toJson(new LinkedHashMap<>(presets), PRESET_TYPE);
    }
}
