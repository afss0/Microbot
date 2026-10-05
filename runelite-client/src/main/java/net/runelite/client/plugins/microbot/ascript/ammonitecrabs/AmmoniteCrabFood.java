package net.runelite.client.plugins.microbot.ascript.ammonitecrabs;

import lombok.Getter;
import net.runelite.api.gameval.ItemID;

/** Food choices for the Ammonite Crabs module (unnoted item ids). */
@Getter
public enum AmmoniteCrabFood {
    SHARK("Shark", ItemID.SHARK),
    MONKFISH("Monkfish", ItemID.MONKFISH),
    SWORDFISH("Swordfish", ItemID.SWORDFISH),
    LOBSTER("Lobster", ItemID.LOBSTER),
    TUNA("Tuna", ItemID.TUNA);

    private final String name;
    private final int id;

    AmmoniteCrabFood(String name, int id) {
        this.name = name;
        this.id = id;
    }

    @Override
    public String toString() {
        return name;
    }
}
