package net.runelite.client.plugins.microbot.ascript.firemaking;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/**
 * Where the module lights its own fire when no campfire/fire is within range.
 * <p>
 * All spots are open ground: the Grand Exchange corners are the classic firemaking tiles
 * (hub GEFiremaker parity); Castle Wars has the bank chest right next to the spot. The module
 * only lights here when nothing tendable is nearby — an existing fire is always preferred.
 */
@Getter
@RequiredArgsConstructor
public enum FiremakingLocation {
    GRAND_EXCHANGE_NORTH_EAST("GE North East", new WorldPoint(3168, 3490, 0)),
    GRAND_EXCHANGE_SOUTH_EAST("GE South East", new WorldPoint(3168, 3489, 0)),
    GRAND_EXCHANGE_NORTH_WEST("GE North West", new WorldPoint(3161, 3490, 0)),
    GRAND_EXCHANGE_SOUTH_WEST("GE South West", new WorldPoint(3161, 3489, 0)),
    CASTLE_WARS("Castle Wars", new WorldPoint(2442, 3083, 0));

    private final String name;
    private final WorldPoint worldPoint;

    @Override
    public String toString() {
        return name;
    }
}
