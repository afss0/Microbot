package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

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

            // State machine state
            if (script instanceof StateMachineScript) {
                @SuppressWarnings("unchecked")
                StateMachineScript<AScript.State> sm = (StateMachineScript<AScript.State>) script;
                var snapshot = sm.getSnapshot();
                if (snapshot != null) {
                    panelComponent.getChildren().add(LineComponent.builder()
                            .left("State:")
                            .right(String.valueOf(snapshot.currentState()))
                            .build());
                    panelComponent.getChildren().add(LineComponent.builder()
                            .left("Last transition:")
                            .right(snapshot.lastTransitionReason() != null
                                    ? snapshot.lastTransitionReason()
                                    : "—")
                            .build());
                }
            }

            // Microbot status
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right(Microbot.status)
                    .build());

        } catch (Exception ex) {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("aScript — error")
                    .color(Color.RED)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left(ex.getMessage())
                    .build());
        }
        return super.render(graphics);
    }
}
