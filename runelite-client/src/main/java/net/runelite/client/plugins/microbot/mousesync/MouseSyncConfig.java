package net.runelite.client.plugins.microbot.mousesync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(MouseSyncConfig.GROUP)
public interface MouseSyncConfig extends Config {

    String GROUP = "mouseSync";

    @ConfigItem(
            keyName = "enabled",
            name = "Enable Mouse Sync",
            description = "When enabled, the bot disables user mouse input during interactions, then naturally returns the cursor to the user's position after a grace period.",
            position = 0
    )
    default boolean enabled() {
        return false;
    }

    @ConfigItem(
            keyName = "gracePeriodMs",
            name = "Grace period (ms)",
            description = "How long to wait after the bot finishes interacting before moving the cursor back to the user's position. During this time user mouse input remains disabled.",
            position = 1
    )
    @Range(min = 1000, max = 15000)
    default int gracePeriodMs() {
        return 5000;
    }

    @ConfigItem(
            keyName = "emergencyHotkey",
            name = "Emergency hotkey",
            description = "Press this combination to immediately stop the bot and restore mouse control. Use this if the bot starts doing something wrong.",
            position = 2
    )
    default Keybind emergencyHotkey() {
        return new Keybind(java.awt.event.KeyEvent.VK_X, java.awt.event.InputEvent.CTRL_DOWN_MASK);
    }

    @ConfigItem(
            keyName = "skipWhenUnfocused",
            name = "Skip when unfocused",
            description = "Prevents mouse sync when the client window is not focused. If the window loses focus during a bot interaction, input is released immediately.",
            position = 3
    )
    default boolean skipWhenUnfocused() {
        return true;
    }
}
