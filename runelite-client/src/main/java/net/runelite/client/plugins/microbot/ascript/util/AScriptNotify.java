package net.runelite.client.plugins.microbot.ascript.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.discord.Rs2Discord;

import java.awt.Color;

/**
 * Shared Discord notification helper for aScript modules.
 * <p>
 * Mirrors the three duplicated {@code notifyDiscord(...)} methods that lived in
 * AScript, CraftingScript, and FletchingScript. Sends an ORANGE embed tagged
 * with the player name and a {@code "aScript"} source.
 */
@Slf4j
public final class AScriptNotify {

    private AScriptNotify() {
        // static utility
    }

    /**
     * Send a custom notification if a webhook is configured. Failures are logged
     * at WARN and swallowed — notification errors must never break the loop.
     */
    public static void notify(String title, String message) {
        try {
            String playerName = "Unknown";
            if (Microbot.getClient() != null && Microbot.getClient().getLocalPlayer() != null) {
                playerName = Microbot.getClient().getLocalPlayer().getName();
            }
            Rs2Discord.sendCustomNotification(
                    title,
                    message,
                    Rs2Discord.convertColorToInt(Color.ORANGE),
                    playerName,
                    "aScript"
            );
        } catch (Exception e) {
            log.warn("Failed to send Discord notification: {}", e.getMessage());
        }
    }
}
