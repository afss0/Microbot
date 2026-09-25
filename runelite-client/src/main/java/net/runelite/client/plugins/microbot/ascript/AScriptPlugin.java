package net.runelite.client.plugins.microbot.ascript;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.Default + "aScript",
        description = "AIO script — multi-script orchestrator with QOL features",
        tags = {"ascript", "microbot", "aio"},
        enabledByDefault = false
)
@Slf4j
public class AScriptPlugin extends Plugin {

    @Inject
    @Getter
    private AScript script;

    @Inject
    @Getter
    private AScriptConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private AScriptOverlay overlay;

    @Provides
    AScriptConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AScriptConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        log.info("[AScript] Plugin started");
        if (overlayManager != null) {
            overlayManager.add(overlay);
        }
        Microbot.pauseAllScripts.compareAndSet(true, false);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        log.info("[AScript] Plugin stopped");
        script.shutdown();
        if (overlayManager != null) {
            overlayManager.remove(overlay);
        }
    }
}
