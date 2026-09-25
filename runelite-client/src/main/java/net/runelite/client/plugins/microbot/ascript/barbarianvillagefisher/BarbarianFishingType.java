package net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BarbarianFishingType {
    FLY_FISHING("Fly Fishing"),
    BAIT_FISHING("Bait Fishing");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
