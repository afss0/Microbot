package net.runelite.client.plugins.microbot.ascript.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
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
            // Player#getName is the client's actor-name path, which asserts the client thread
            // (the NPC#getName crash AScriptClick.describeActor hit). Callers are module code on
            // the script thread, so the read hops over; an unavailable client keeps "Unknown".
            String playerName = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                if (Microbot.getClient() == null) {
                    return null;
                }
                Player local = Microbot.getClient().getLocalPlayer();
                return local == null ? null : local.getName();
            }).orElse("Unknown");
            if (playerName == null || playerName.isEmpty()) {
                playerName = "Unknown";
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
