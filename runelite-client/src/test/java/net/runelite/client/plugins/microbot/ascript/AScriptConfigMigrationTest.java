package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the removed drop configs to a safe migration. An existing profile must never make
 * {@code ConfigManager} deserialize a now-removed enum constant.
 */
public class AScriptConfigMigrationTest {

    @Test
    public void rewritesRawDropFunctionToBankRaw() {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.BVF_FUNCTION_KEY)).thenReturn("DROP_RAW");

        AScriptConfigMigration.migrate(configManager);

        verify(configManager).setConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.BVF_FUNCTION_KEY, "BANK_RAW");
    }

    @Test
    public void rewritesCookAndDropFunctionToCookAndBank() {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.BVF_FUNCTION_KEY)).thenReturn("COOK_AND_DROP");

        AScriptConfigMigration.migrate(configManager);

        verify(configManager).setConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.BVF_FUNCTION_KEY, "COOK_AND_BANK");
    }

    @Test
    public void keepsAnAlreadySafeFunctionAndRemovesObsoleteKeys() {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.BVF_FUNCTION_KEY)).thenReturn("BANK_RAW");

        AScriptConfigMigration.migrate(configManager);

        verify(configManager, never()).setConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.BVF_FUNCTION_KEY, "BANK_RAW");
        verify(configManager).unsetConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.OLD_MLM_DROP_GEMS_KEY);
        verify(configManager).unsetConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.OLD_MINING_ACTION_KEY);
        verify(configManager).unsetConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.OLD_WOODCUTTING_ACTION_KEY);
    }

    @Test
    public void unknownOrMissingValueIsLeftAlone() {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.BVF_FUNCTION_KEY)).thenReturn("SOMETHING_ELSE");

        AScriptConfigMigration.migrate(configManager);

        verify(configManager, never()).setConfiguration(AScriptConfig.GROUP,
                AScriptConfigMigration.BVF_FUNCTION_KEY, "BANK_RAW");
    }
}
