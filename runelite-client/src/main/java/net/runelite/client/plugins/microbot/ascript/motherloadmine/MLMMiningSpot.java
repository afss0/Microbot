package net.runelite.client.plugins.microbot.ascript.motherloadmine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

import java.util.Arrays;
import java.util.List;

/**
 * Mining spots in the Motherload Mine.
 * Each spot has a list of WorldPoints where veins can spawn and whether it's on the upper floor.
 */
@Getter
@RequiredArgsConstructor
public enum MLMMiningSpot {
    WEST_LOWER(
            "West (Lower)",
            false,
            new WorldPoint(3731, 5659, 0),
            new WorldPoint(3731, 5663, 0)
    ),
    WEST_MID(
            "West Mid (Lower)",
            false,
            new WorldPoint(3730, 5666, 0),
            new WorldPoint(3731, 5669, 0)
    ),
    SOUTH_EAST(
            "South East (Lower)",
            false,
            new WorldPoint(3753, 5650, 0),
            new WorldPoint(3756, 5653, 0)
    ),
    SOUTH_WEST(
            "South West (Lower)",
            false,
            new WorldPoint(3740, 5648, 0)
    ),
    WEST_UPPER(
            "West (Upper)",
            true,
            new WorldPoint(3752, 5683, 0),
            new WorldPoint(3752, 5680, 0)
    ),
    EAST_UPPER(
            "East (Upper)",
            true,
            new WorldPoint(3760, 5673, 0),
            new WorldPoint(3759, 5673, 0)
    );

    private final String label;
    private final boolean upstairs;
    private final List<WorldPoint> worldPoints;

    MLMMiningSpot(String label, boolean upstairs, WorldPoint... worldPoints) {
        this.label = label;
        this.upstairs = upstairs;
        this.worldPoints = Arrays.asList(worldPoints);
    }

    public boolean isDownstairs() {
        return !upstairs;
    }

    @Override
    public String toString() {
        return label;
    }
}
