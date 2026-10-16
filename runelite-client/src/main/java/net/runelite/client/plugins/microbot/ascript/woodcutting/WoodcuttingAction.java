package net.runelite.client.plugins.microbot.ascript.woodcutting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** What happens when the inventory fills up. */
@Getter
@RequiredArgsConstructor
public enum WoodcuttingAction {
    DROP("Drop (powercut)"),
    BANK("Bank");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
