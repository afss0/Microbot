package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AScriptChainPanelTest {

    @Test
    public void loadPublishesCanonicalChainAndSelectsModuleChain() throws Exception {
        AScriptConfig config = mock(AScriptConfig.class);
        when(config.chainModules()).thenReturn("CRAFTING");
        when(config.chainPresets()).thenReturn(ChainPresetStore.serialize(
                Map.of("Fletching run", "mining; fletching(fletchingActivity=BOLTS)")));
        ConfigManager configManager = mock(ConfigManager.class);

        SwingUtilities.invokeAndWait(() -> {
            AScriptChainPanel panel = new AScriptChainPanel(config, configManager);
            panel.loadPreset();
        });

        verify(configManager).setConfiguration(AScriptConfig.GROUP, "chainModules",
                "MINING;FLETCHING(fletchingActivity=BOLTS)");
        verify(configManager).setConfiguration(AScriptConfig.GROUP, "chainEnabled", true);
        verify(configManager).setConfiguration(AScriptConfig.GROUP, "scriptSelection",
                ScriptType.MODULE_CHAIN);
    }

    @Test
    public void invalidSavedChainIsNotPublished() throws Exception {
        AScriptConfig config = mock(AScriptConfig.class);
        when(config.chainPresets()).thenReturn(ChainPresetStore.serialize(
                Map.of("Broken", "MINING;NOT_A_MODULE")));
        ConfigManager configManager = mock(ConfigManager.class);

        SwingUtilities.invokeAndWait(() -> {
            AScriptChainPanel panel = new AScriptChainPanel(config, configManager);
            panel.loadPreset();
        });

        verify(configManager, never()).setConfiguration(anyString(), anyString(), any());
    }
}
