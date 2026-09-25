package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
@Getter
@RequiredArgsConstructor
public enum FletchingActivity {
    NONE(" "),
    DARTS("Darts"),
    BOLTS("Bolts"),
    ARROWS("Arrows"),
    BOWS("Bows");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
