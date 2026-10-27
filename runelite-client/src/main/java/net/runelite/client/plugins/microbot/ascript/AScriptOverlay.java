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

            boolean automationOn = config != null && config.enabled();
            boolean idle = config != null && config.scriptSelection() == ScriptType.IDLE;

            // Colour: red = the last orchestrator stop, orange = automation off,
            // yellow = idle/configuration hold, green = automation running.
            Color titleColor;
            if (script.getLastStopMessage() != null) {
                titleColor = Color.RED;
            } else if (!automationOn) {
                titleColor = new Color(255, 170, 0);
            } else if (idle || script.getCurrentPhase() == AScript.Phase.IDLE
                    || script.getCurrentPhase() == AScript.Phase.WAITING) {
                titleColor = Color.YELLOW;
            } else {
                titleColor = Color.GREEN;
            }
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("aScript AIO")
                    .color(titleColor)
                    .build());

            if (config == null) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Automation:")
                        .right("unavailable")
                        .build());
                return super.render(graphics);
            }

            String automation;
            if (!automationOn) {
                String stop = script.getLastStopMessage();
                automation = stop == null ? "Disabled" : "Stopped — " + stop;
            } else if (idle) {
                automation = "Idle — select a script";
            } else {
                automation = config.scriptSelection().getName()
                        + " (" + script.getCurrentPhase() + ")";
            }
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Automation:")
                    .right(automation)
                    .build());

            // QOL is independent of the automation switch and stays visible while it is off.
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("QOL:")
                    .right(qolSummary(config))
                    .build());

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

    /**
     * QOL features are always-on while the plugin is enabled (random events) or gated by
     * their own toggles (auto-eat, auto-zoom) — never by the automation switch.
     */
    private static String qolSummary(AScriptConfig config) {
        StringBuilder summary = new StringBuilder("events");
        if (config.autoEat()) {
            summary.append(", auto-eat");
        }
        if (config.autoZoomOut()) {
            summary.append(", auto-zoom");
        }
        return summary.toString();
    }
}
