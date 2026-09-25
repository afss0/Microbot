package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ObjectID;
import net.runelite.api.coords.WorldPoint;

@Getter
@RequiredArgsConstructor
public enum CraftingFlaxLocation {
    NONE(" ", null, 0),
    FALADOR("Falador", new WorldPoint(2982, 3314, 0), ObjectID.SPINNING_WHEEL_14889),
    SEERS_VILLAGE("Seers Village", new WorldPoint(2711, 3471, 1), ObjectID.SPINNING_WHEEL_25824),
    RELLEKKA("Rellekka", new WorldPoint(2617, 3660, 0), ObjectID.SPINNING_WHEEL),
    TREE_GNOME_STRONGHOLD("Tree Gnome Stronghold", new WorldPoint(2488, 3409, 1), ObjectID.SPINNING_WHEEL_14889),
    NEMUS_RETREAT("Nemus Retreat", new WorldPoint(1373, 3313, 0), 55330);

    private final String label;
    private final WorldPoint worldPoint;
    private final int objectID;

    @Override
    public String toString() {
        return label;
    }
}
