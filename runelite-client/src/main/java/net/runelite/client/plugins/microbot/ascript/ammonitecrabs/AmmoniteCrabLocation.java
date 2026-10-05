package net.runelite.client.plugins.microbot.ascript.ammonitecrabs;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * Ammonite crab spots on the Fossil Island beach.
 * <p>
 * {@code fightLocation} is the tile the module keeps returning to; {@code resetLocation}
 * is the detour tile used to reset the aggro timer. Distances are Chebyshev
 * (WorldPoint.distanceTo takes max(|dx|,|dy|)).
 */
@Getter
public enum AmmoniteCrabLocation {
    NONE("None", null, null),
    NORTH_WEST("North West (3 spot)", new WorldPoint(3657, 3875, 0), new WorldPoint(3689, 3897, 0)),
    NORTH_EAST("North East (2 spot)", new WorldPoint(3718, 3881, 0), new WorldPoint(3690, 3874, 0)),
    SOUTH_WEST("South West (2 spot)", new WorldPoint(3717, 3846, 0), new WorldPoint(3680, 3844, 0)),
    SOUTH_EAST("South East (2 spot)", new WorldPoint(3733, 3846, 0), new WorldPoint(3736, 3818, 0));

    private final String name;
    private final WorldPoint fightLocation;
    private final WorldPoint resetLocation;

    AmmoniteCrabLocation(String name, WorldPoint fightLocation, WorldPoint resetLocation) {
        this.name = name;
        this.fightLocation = fightLocation;
        this.resetLocation = resetLocation;
    }

    @Override
    public String toString() {
        return name;
    }
}
