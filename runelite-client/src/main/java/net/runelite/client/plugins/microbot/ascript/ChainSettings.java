package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Range;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates and applies per-step chain overrides against {@link AScriptConfig}.
 * <p>
 * A chain step is a module plus a small map of text values. The names are the RuneLite config
 * keys (the {@code keyName} of an annotated {@link ConfigItem}); values are converted using the
 * method's return type, so enums, booleans, ints and strings all round-trip through the normal
 * config store. Validation is intentionally separate from setting: an invalid step must not
 * partially change the active profile and then start a module with half of its old settings.
 */
final class ChainSettings {

    private static final Set<String> RESERVED = Set.of(
            "enabled",
            "scriptSelection",
            "chainEnabled",
            "chainModules",
            "chainLoop",
            ChainPresetStore.CONFIG_KEY
    );

    private static final Map<String, Setting> SETTINGS = buildSettings();

    private ChainSettings() {
    }

    /** Validate every override in a parsed chain; {@code null} means all are usable. */
    static String validate(ModuleChain chain) {
        if (chain == null) {
            return null;
        }
        for (ModuleChain.Step step : chain.entries()) {
            String error = validate(step);
            if (error != null) {
                return step.getType().name() + ": " + error;
            }
        }
        return null;
    }

    static String validate(ModuleChain.Step step) {
        if (step == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : step.getSettings().entrySet()) {
            Setting setting = find(entry.getKey());
            if (setting == null) {
                return "unknown setting '" + entry.getKey() + "'";
            }
            if (RESERVED.contains(setting.key)) {
                return "setting '" + setting.key + "' is managed by the chain";
            }
            try {
                setting.parse(entry.getValue());
            } catch (IllegalArgumentException ex) {
                return setting.key + ": " + ex.getMessage();
            }
        }
        return null;
    }

    /**
     * Apply a step's overrides. Returns {@code null} on success; otherwise a human-readable
     * validation error and no values are written.
     */
    static String apply(ConfigManager configManager, ModuleChain.Step step) {
        if (step == null || step.getSettings().isEmpty()) {
            return null;
        }
        String error = validate(step);
        if (error != null) {
            return error;
        }
        Map<Setting, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : step.getSettings().entrySet()) {
            Setting setting = find(entry.getKey());
            values.put(setting, setting.parse(entry.getValue()));
        }
        if (configManager == null) {
            return "config manager unavailable";
        }
        for (Map.Entry<Setting, Object> entry : values.entrySet()) {
            configManager.setConfiguration(AScriptConfig.GROUP, entry.getKey().key,
                    entry.getValue());
        }
        return null;
    }

    private static Setting find(String key) {
        if (key == null) {
            return null;
        }
        for (Map.Entry<String, Setting> entry : SETTINGS.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Setting> buildSettings() {
        Map<String, Setting> settings = new LinkedHashMap<>();
        for (Method method : AScriptConfig.class.getMethods()) {
            ConfigItem item = method.getAnnotation(ConfigItem.class);
            if (item == null) {
                continue;
            }
            settings.put(item.keyName(), new Setting(item.keyName(), method.getReturnType(),
                    method.getAnnotation(Range.class)));
        }
        return Collections.unmodifiableMap(settings);
    }

    private static final class Setting {
        private final String key;
        private final Class<?> type;
        private final Range range;

        private Setting(String key, Class<?> type, Range range) {
            this.key = key;
            this.type = type;
            this.range = range;
        }

        private Object parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (type == boolean.class || type == Boolean.class) {
                if ("true".equalsIgnoreCase(value)) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(value)) {
                    return Boolean.FALSE;
                }
                throw new IllegalArgumentException("expected true or false");
            }
            if (type == int.class || type == Integer.class) {
                int parsed;
                try {
                    parsed = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("expected an integer");
                }
                if (range != null && (parsed < range.min() || parsed > range.max())) {
                    throw new IllegalArgumentException("expected " + range.min() + ".." + range.max());
                }
                return parsed;
            }
            if (type == long.class || type == Long.class) {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("expected an integer");
                }
            }
            if (type == double.class || type == Double.class) {
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("expected a number");
                }
            }
            if (type == String.class) {
                return value;
            }
            if (type.isEnum()) {
                return parseEnum(value);
            }
            throw new IllegalArgumentException("unsupported config type " + type.getSimpleName());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object parseEnum(String value) {
            String normalized = normalize(value);
            for (Object constant : type.getEnumConstants()) {
                Enum<?> enumValue = (Enum<?>) constant;
                if (enumValue.name().equalsIgnoreCase(value)
                        || normalize(enumValue.name()).equals(normalized)
                        || normalize(String.valueOf(enumValue)).equals(normalized)) {
                    return enumValue;
                }
            }
            throw new IllegalArgumentException("unknown value '" + value + "'");
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9]+", "_");
        }
    }
}
