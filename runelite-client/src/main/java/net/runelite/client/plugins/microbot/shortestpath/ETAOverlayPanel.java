package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.Constants;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.awt.*;
import java.util.stream.IntStream;

public class ETAOverlayPanel extends OverlayPanel {
    
    private final ShortestPathPlugin plugin;

    @Inject
    ETAOverlayPanel(ShortestPathPlugin plugin) {
        this.plugin = plugin;
        setPosition(OverlayPosition.CANVAS_TOP_RIGHT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setBackgroundColor(new Color(0, 0, 0, 0));
            panelComponent.setPreferredSize(new Dimension(160, 100));

            if (ShortestPathPlugin.getPathfinder() != null && ShortestPathPlugin.getPathfinder().isDone()) {
                List<WorldPoint> path = ShortestPathPlugin.getPathfinder().getPath();
                WorldPoint playerLocation = Rs2Player.getWorldLocation();

                int progressIndex = findClosestPointIndex(playerLocation, path);

                int remainingPathLength = path.size() - progressIndex;

                String remainingTime = calculateTravelTime(remainingPathLength, plugin.getConfig().showInSeconds());

                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Est. Time till Arrival:")
                        .right(remainingTime)
                        .build());
            } else {
                if (!panelComponent.getChildren().isEmpty()) {
                    panelComponent.getChildren().clear();
                }
            }
        } catch (Exception ex) {
            System.out.println("Error in render: " + ex.getMessage());
        }
        return super.render(graphics);
    }

    private int findClosestPointIndex(WorldPoint playerLocation, List<WorldPoint> path) {
        return IntStream.range(0, path.size())
                .boxed()
                .min(Comparator.comparingInt(i -> playerLocation.distanceTo(path.get(i))))
                .orElse(0); 
    }

    /**
     * Simplified travel time calculation (replaces removed RunEnergyPlugin.calculateTravelTime).
     * Estimates based on running/walking speed with energy check.
     */
    private static String calculateTravelTime(int pathLength, boolean inSeconds) {
        final double tickDurationInSeconds = Constants.GAME_TICK_LENGTH / 1000.0;
        final int tilesPerTickRunning = 2;
        final int tilesPerTickWalking = 1;

        double currentEnergy = Microbot.getClient().getEnergy();
        int weight = Math.min(Math.max(Microbot.getClient().getWeight(), 0), 64);
        int agilityLevel = Microbot.getClient().getBoostedSkillLevel(Skill.AGILITY);

        // Drain rate per tick
        double drainRate = (60 + (67 * weight / 64.0)) * (1 - (agilityLevel / 300.0));

        // Recovery rate (energy per second)
        double recoveryRate = (agilityLevel / 10.0) + 15.0;

        int remainingPath = pathLength;
        int totalTicks = 0;

        // Simulate running/walking
        while (remainingPath > 0) {
            if (currentEnergy > 0) {
                // Running
                double runningDistance = Math.min(currentEnergy / drainRate, (double) remainingPath / tilesPerTickRunning) * tilesPerTickRunning;
                int runningTicks = (int) Math.ceil(runningDistance / tilesPerTickRunning);
                totalTicks += runningTicks;
                remainingPath -= runningDistance;
                currentEnergy -= runningTicks * drainRate;
            } else {
                // Walking + recovering
                double tickDuration = tickDurationInSeconds;
                double recoveredEnergy = tickDuration * recoveryRate;
                currentEnergy = Math.min(10000, currentEnergy + recoveredEnergy);
                totalTicks++;
                remainingPath -= tilesPerTickWalking;
            }
        }

        double totalTimeInSeconds = totalTicks * tickDurationInSeconds;

        if (inSeconds) {
            return (int) Math.floor(totalTimeInSeconds) + "s";
        } else {
            final int minutes = (int) Math.floor(totalTimeInSeconds / 60.0);
            final int seconds = (int) Math.floor(totalTimeInSeconds - (minutes * 60.0));
            return minutes + ":" + String.format("%02d", seconds);
        }
    }
}
