package net.runelite.client.plugins.microbot.ascript.jewellenchant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;
import net.runelite.client.plugins.microbot.util.magic.Runes;

import java.util.Arrays;

/**
 * Elemental staves that provide infinite runes of one or more elements.
 * Used by {@link JewelEnchantScript} to skip withdrawing elemental runes
 * when the player has the correct staff equipped.
 */
@Getter
@RequiredArgsConstructor
public enum ElementalStaff {
    // Single Element Staves
    STAFF_OF_AIR(ItemID.STAFF_OF_AIR, new Runes[]{Runes.AIR}),
    AIR_BATTLESTAFF(ItemID.AIR_BATTLESTAFF, new Runes[]{Runes.AIR}),
    MYSTIC_AIR_STAFF(ItemID.MYSTIC_AIR_STAFF, new Runes[]{Runes.AIR}),
    STAFF_OF_WATER(ItemID.STAFF_OF_WATER, new Runes[]{Runes.WATER}),
    WATER_BATTLESTAFF(ItemID.WATER_BATTLESTAFF, new Runes[]{Runes.WATER}),
    MYSTIC_WATER_STAFF(ItemID.MYSTIC_WATER_STAFF, new Runes[]{Runes.WATER}),
    STAFF_OF_EARTH(ItemID.STAFF_OF_EARTH, new Runes[]{Runes.EARTH}),
    EARTH_BATTLESTAFF(ItemID.EARTH_BATTLESTAFF, new Runes[]{Runes.EARTH}),
    MYSTIC_EARTH_STAFF(ItemID.MYSTIC_EARTH_STAFF, new Runes[]{Runes.EARTH}),
    STAFF_OF_FIRE(ItemID.STAFF_OF_FIRE, new Runes[]{Runes.FIRE}),
    FIRE_BATTLESTAFF(ItemID.FIRE_BATTLESTAFF, new Runes[]{Runes.FIRE}),
    MYSTIC_FIRE_STAFF(ItemID.MYSTIC_FIRE_STAFF, new Runes[]{Runes.FIRE}),

    // Combination Staves
    MUD_BATTLESTAFF(ItemID.MUD_BATTLESTAFF, new Runes[]{Runes.WATER, Runes.EARTH}),
    MYSTIC_MUD_STAFF(ItemID.MYSTIC_MUD_STAFF, new Runes[]{Runes.WATER, Runes.EARTH}),
    LAVA_BATTLESTAFF(ItemID.LAVA_BATTLESTAFF, new Runes[]{Runes.FIRE, Runes.EARTH}),
    MYSTIC_LAVA_STAFF(ItemID.MYSTIC_LAVA_STAFF, new Runes[]{Runes.FIRE, Runes.EARTH}),
    STEAM_BATTLESTAFF(ItemID.STEAM_BATTLESTAFF, new Runes[]{Runes.WATER, Runes.FIRE}),
    MYSTIC_STEAM_STAFF(ItemID.MYSTIC_STEAM_STAFF, new Runes[]{Runes.WATER, Runes.FIRE}),
    SMOKE_BATTLESTAFF(ItemID.SMOKE_BATTLESTAFF, new Runes[]{Runes.AIR, Runes.FIRE}),
    MYSTIC_SMOKE_STAFF(ItemID.MYSTIC_SMOKE_STAFF, new Runes[]{Runes.AIR, Runes.FIRE});

    private final int itemId;
    private final Runes[] providedRunes;

    public boolean providesRune(Runes rune) {
        return Arrays.stream(providedRunes).anyMatch(r -> r == rune);
    }

    /**
     * True when at least one staff in this enum supplies {@code rune}.
     * <p>
     * Used to decide whether an elemental staff can substitute for a rune at
     * all: blood and soul (Lvl-7 Enchant) are supplied by no staff, so runes
     * which no staff provides must always be withdrawn.
     */
    public static boolean anyProvides(Runes rune) {
        return Arrays.stream(values()).anyMatch(staff -> staff.providesRune(rune));
    }
}
