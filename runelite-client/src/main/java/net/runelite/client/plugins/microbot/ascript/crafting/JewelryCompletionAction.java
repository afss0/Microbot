package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;

@Getter
public enum JewelryCompletionAction {
    NONE("None"),
    ALCH("High Alch");

    private final String label;

    JewelryCompletionAction(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
