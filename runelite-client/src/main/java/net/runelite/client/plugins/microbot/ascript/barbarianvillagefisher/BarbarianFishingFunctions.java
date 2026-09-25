package net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BarbarianFishingFunctions {
    DROP_RAW("Drop Raw Fish"),
    COOK_AND_DROP("Cook and Drop"),
    COOK_AND_BANK("Cook and Bank"),
    BANK_RAW("Bank Raw Fish");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
