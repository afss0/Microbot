package net.runelite.client.plugins.microbot.ascript.mining;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** What happens when the inventory fills up. */
@Getter
@RequiredArgsConstructor
public enum MiningAction {
    DROP("Drop (powermine)"),
    BANK("Bank");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
