package net.runelite.client.plugins.microbot.ascript;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.eventdismiss.DismissNpcEvent;
import net.runelite.client.plugins.microbot.ascript.eventdismiss.LampUtility;
import net.runelite.client.plugins.microbot.ascript.eventdismiss.UseLampEvent;
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

    private DismissNpcEvent dismissNpcEvent;
    private UseLampEvent useLampEvent;

    /**
     * Menu Entry Swapper mechanics for the inventory recipes: while a swap is pending, reorder
     * the real menu entries so the item's {@code Drop} or {@code Use} row is the left click.
     */
    @Subscribe
    public void onPostMenuSort(PostMenuSort event) {
        AScriptClick.onPostMenuSort();
    }

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
        // Random event handler — always-on QOL while the plugin is enabled (no master
        // toggle): dismiss random events and optionally accept/use Genie & Count Check lamps.
        dismissNpcEvent = new DismissNpcEvent(config);
        useLampEvent = new UseLampEvent(config);
        Microbot.getBlockingEventManager().add(dismissNpcEvent);
        Microbot.getBlockingEventManager().add(useLampEvent);
        LampUtility.reset();
        // No pauseAllScripts.compareAndSet(true, false) here: enabling aScript must not
        // resume scripts that the user (or a break handler) paused. This plugin did not set
        // that pause and must not clear it — scripts start from the overlay/panel action.
        script.run(config);
    }

    @Override
    protected void shutDown() {
        log.info("[AScript] Plugin stopped");
        Microbot.getBlockingEventManager().remove(dismissNpcEvent);
        Microbot.getBlockingEventManager().remove(useLampEvent);
        dismissNpcEvent = null;
        useLampEvent = null;
        script.shutdown();
        if (overlayManager != null) {
            overlayManager.remove(overlay);
        }
    }
}
