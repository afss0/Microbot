package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * AutoFletching activity groups. Each maps to one game action family and one type dropdown.
 * Existing constant names (DARTS, BOLTS, ARROWS, BOWS) are preserved so stored configs still
 * resolve; only the display labels change/shorten.
 */
@Getter
@RequiredArgsConstructor
public enum FletchingActivity {
    NONE(" "),
    DARTS("Darts"),
    BOLTS("Bolts"),
    BOLT_TIPS("Gem-tipped Bolts"),
    ARROWS("Arrows"),
    BOWS("String Bows"),
    UNSTRUNG_BOWS("Carve Bows (u)"),
    ARROW_SHAFTS("Arrow Shafts"),
    STOCKS("Crossbow Stocks"),
    SHIELDS("Shields");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
