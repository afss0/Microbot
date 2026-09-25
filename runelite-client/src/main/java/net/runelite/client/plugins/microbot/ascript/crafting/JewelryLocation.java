package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

@Getter
@RequiredArgsConstructor
public enum JewelryLocation {
    NONE(" ", null),
    EDGEVILLE("Edgeville", new WorldPoint(3109, 3499, 0)),
    PORT_PHASMATYS("Port Phasmatys", new WorldPoint(3687, 3479, 0)),
    MOUNT_KARUULM("Mount Karuulm", new WorldPoint(1324, 3808, 0)),
    ZANARIS("Zanaris", new WorldPoint(2401, 4473, 0)),
    FALADOR("Falador", new WorldPoint(2975, 3369, 0)),
    SHILO_VILLAGE("Shilo Village", new WorldPoint(2856, 2967, 0));

    private final String label;
    private final WorldPoint furnaceLocation;

    @Override
    public String toString() {
        return label;
    }
}
