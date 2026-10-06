package net.runelite.client.plugins.microbot.ascript.barbarianfishing;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BarbarianFishingActivity {
    NONE("None"),
    FISH("Fish & Drop");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
