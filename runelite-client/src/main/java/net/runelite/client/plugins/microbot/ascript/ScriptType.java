package net.runelite.client.plugins.microbot.ascript;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ScriptType {
    NONE("None"),
    CRAFTING("Crafting"),
    FLETCHING("Fletching"),
    MOTHERLOAD_MINE("Motherload Mine");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
