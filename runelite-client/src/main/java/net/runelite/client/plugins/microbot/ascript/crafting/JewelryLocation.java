package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * Furnace sites supported by the jewelry module.
 * <p>
 * Each site carries the scenery object id(s) of its own furnace — the furnace is not one shared
 * object id across Gielinor, so a lookup hardcoded to Edgeville's 16469 only ever resolves at
 * Edgeville. Ids verified against the OSRS wiki's Furnace infobox (versioned per location) and the
 * Volcanic Furnace page:
 * <ul>
 *   <li>Edgeville — 16469</li>
 *   <li>Falador / Port Phasmatys — 24009 (the general furnace scenery)</li>
 *   <li>Mount Karuulm — 34342 (volcanic furnace)</li>
 *   <li>Zanaris — 12100</li>
 *   <li>Shilo Village — 30157 (unlit) / 30158 (lit)</li>
 * </ul>
 */
@Getter
public enum JewelryLocation {
    NONE(" ", null),
    EDGEVILLE("Edgeville", new WorldPoint(3109, 3499, 0), 16469),
    PORT_PHASMATYS("Port Phasmatys", new WorldPoint(3687, 3479, 0), 24009),
    MOUNT_KARUULM("Mount Karuulm", new WorldPoint(1324, 3808, 0), 34342),
    ZANARIS("Zanaris", new WorldPoint(2401, 4473, 0), 12100),
    FALADOR("Falador", new WorldPoint(2975, 3369, 0), 24009),
    SHILO_VILLAGE("Shilo Village", new WorldPoint(2856, 2967, 0), 30157, 30158);

    private final String label;
    private final WorldPoint furnaceLocation;
    /** Scenery object id(s) of this site's furnace, tried in order. */
    private final int[] furnaceObjectIds;

    JewelryLocation(String label, WorldPoint furnaceLocation, int... furnaceObjectIds) {
        this.label = label;
        this.furnaceLocation = furnaceLocation;
        this.furnaceObjectIds = furnaceObjectIds;
    }

    /** The site's furnace ids as a slash-separated string, for stop messages. */
    public String getFurnaceObjectIdsText() {
        StringBuilder sb = new StringBuilder();
        for (int id : furnaceObjectIds) {
            if (sb.length() > 0) sb.append('/');
            sb.append(id);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return label;
    }
}
