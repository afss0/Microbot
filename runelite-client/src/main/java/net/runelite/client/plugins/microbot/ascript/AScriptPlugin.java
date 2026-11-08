package net.runelite.client.plugins.microbot.ascript;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.eventdismiss.DismissNpcEvent;
import net.runelite.client.plugins.microbot.ascript.eventdismiss.LampUtility;
import net.runelite.client.plugins.microbot.ascript.eventdismiss.UseLampEvent;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;

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

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ConfigManager configManager;

    private DismissNpcEvent dismissNpcEvent;
    private UseLampEvent useLampEvent;
    private AScriptChainPanel chainPanel;
    private NavigationButton chainNavButton;

    /**
     * Menu Entry Swapper mechanics for the inventory recipes: while a swap is pending, reorder
     * the real menu entries so the item's {@code Drop} or {@code Use} row is the left click.
     */
    @Subscribe
    public void onPostMenuSort(PostMenuSort event) {
        AScriptClick.onPostMenuSort();
    }

    @Subscribe
    public void onProfileChanged(ProfileChanged event) {
        if (chainPanel != null) {
            chainPanel.reload();
        }
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
        chainPanel = new AScriptChainPanel(config, configManager);
        chainNavButton = NavigationButton.builder()
                .tooltip("aScript Module Chain")
                .icon(createChainIcon())
                .priority(10)
                .panel(chainPanel)
                .build();
        clientToolbar.addNavigation(chainNavButton);
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
        if (chainNavButton != null) {
            clientToolbar.removeNavigation(chainNavButton);
            chainNavButton = null;
        }
        chainPanel = null;
    }

    /**
     * A small chain-link icon for the sidebar button; kept inline so the plugin does not need a
     * new image resource just for the panel entry.
     */
    private static BufferedImage createChainIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(120, 180, 80));
            graphics.setStroke(new BasicStroke(2.0f));
            graphics.drawRoundRect(1, 3, 7, 10, 4, 4);
            graphics.drawRoundRect(8, 3, 7, 10, 4, 4);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
