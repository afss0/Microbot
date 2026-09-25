package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.awt.*;

@Slf4j
public class AScriptOverlay extends OverlayPanel {

    private final AScriptPlugin plugin;

    @Inject
    AScriptOverlay(AScriptPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            AScript script = plugin.getScript();
            AScriptConfig config = plugin.getConfig();

            panelComponent.setPreferredSize(new Dimension(275, 0));

            // Title
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("aScript AIO")
                    .color(config != null && config.enabled() ? Color.GREEN : Color.RED)
                    .build());

            if (config == null || !config.enabled()) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Status:")
                        .right("Disabled")
                        .build());
                return super.render(graphics);
            }

            // Script selection
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Script:")
                    .right(config.scriptSelection().getName())
                    .build());

            // Phase
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Phase:")
                    .right(String.valueOf(script.getCurrentPhase()))
                    .build());

            // Microbot status
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right(Microbot.status == null ? "—" : Microbot.status)
                    .build());

        } catch (Exception ex) {
            log.warn("[AScript] Overlay render error", ex);
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("aScript — error")
                    .color(Color.RED)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Internal error — check logs")
                    .build());
        }
        return super.render(graphics);
    }
}
