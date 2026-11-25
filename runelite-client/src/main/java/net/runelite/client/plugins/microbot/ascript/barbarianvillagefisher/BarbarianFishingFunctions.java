package net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BarbarianFishingFunctions {
    COOK_AND_BANK("Cook and Bank"),
    BANK_RAW("Bank Raw Fish");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
