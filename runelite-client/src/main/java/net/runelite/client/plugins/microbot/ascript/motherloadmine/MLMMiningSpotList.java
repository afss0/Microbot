package net.runelite.client.plugins.microbot.ascript.motherloadmine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Config dropdown for selecting which mining spot to use.
 * ANY allows the script to mine at any available spot.
 */
@Getter
@RequiredArgsConstructor
public enum MLMMiningSpotList {
    ANY("Any"),
    WEST_LOWER(MLMMiningSpot.WEST_LOWER),
    WEST_MID(MLMMiningSpot.WEST_MID),
    SOUTH_EAST(MLMMiningSpot.SOUTH_EAST),
    SOUTH_WEST(MLMMiningSpot.SOUTH_WEST),
    WEST_UPPER(MLMMiningSpot.WEST_UPPER),
    EAST_UPPER(MLMMiningSpot.EAST_UPPER);

    private final String label;

    MLMMiningSpotList(MLMMiningSpot spot) {
        this.label = spot.getLabel();
    }

    @Override
    public String toString() {
        return label;
    }
}
