package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CraftingActivity {
    NONE(" "),
    GEM_CUTTING("Cutting Gems"),
    GLASSBLOWING("Glassblowing"),
    STAFF_MAKING("Staff Making"),
    FLAX_SPINNING("Flax Spinning"),
    DRAGON_LEATHER("Dragon Leather"),
    JEWELRY("Jewelry");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
