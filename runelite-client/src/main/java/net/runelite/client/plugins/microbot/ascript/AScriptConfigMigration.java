package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.config.ConfigManager;

/**
 * One-time migration for configuration values that used to select an item-drop mode.
 *
 * <p>Mining, Woodcutting and Motherload Mine no longer expose drop settings; their old keys
 * are removed. The Barbarian Village Fisher still has a bank/cook choice, so its old drop
 * values are rewritten to the equivalent bank mode <b>before</b> the enum is read —
 * {@code ConfigManager} deserializes enums with {@code Enum.valueOf}, and the removed
 * constants would otherwise make an existing profile fail to load.</p>
 */
final class AScriptConfigMigration {

    static final String BVF_FUNCTION_KEY = "barbarianVillageFisherFunction";
    static final String OLD_MLM_DROP_GEMS_KEY = "mlmDropGems";
    static final String OLD_MINING_ACTION_KEY = "miningAction";
    static final String OLD_WOODCUTTING_ACTION_KEY = "woodcuttingAction";

    private AScriptConfigMigration() {
    }

    static void migrate(ConfigManager configManager) {
        if (configManager == null) {
            return;
        }
        migrateValue(configManager, BVF_FUNCTION_KEY, "DROP_RAW", "BANK_RAW");
        migrateValue(configManager, BVF_FUNCTION_KEY, "COOK_AND_DROP", "COOK_AND_BANK");
        configManager.unsetConfiguration(AScriptConfig.GROUP, OLD_MLM_DROP_GEMS_KEY);
        configManager.unsetConfiguration(AScriptConfig.GROUP, OLD_MINING_ACTION_KEY);
        configManager.unsetConfiguration(AScriptConfig.GROUP, OLD_WOODCUTTING_ACTION_KEY);
    }

    private static void migrateValue(ConfigManager configManager, String key, String oldValue, String newValue) {
        String current = configManager.getConfiguration(AScriptConfig.GROUP, key);
        if (oldValue.equals(current)) {
            configManager.setConfiguration(AScriptConfig.GROUP, key, newValue);
        }
    }
}
