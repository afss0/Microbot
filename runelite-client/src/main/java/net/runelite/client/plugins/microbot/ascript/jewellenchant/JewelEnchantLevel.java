package net.runelite.client.plugins.microbot.ascript.jewellenchant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;
import net.runelite.client.plugins.microbot.util.magic.Runes;

import java.util.Map;

/**
 * Enchant spell levels — each level covers a tier of jewellery.
 * <p>
 * Rune requirements per cast (cosmic is always required):
 * <ul>
 *   <li>Lvl-1 (7 Magic): 1 cosmic + 1 water — Opal, Sapphire</li>
 *   <li>Lvl-2 (27 Magic): 1 cosmic + 3 air — Jade, Emerald</li>
 *   <li>Lvl-3 (49 Magic): 1 cosmic + 5 fire — Topaz, Ruby</li>
 *   <li>Lvl-4 (57 Magic): 1 cosmic + 10 earth — Diamond</li>
 *   <li>Lvl-5 (68 Magic): 1 cosmic + 15 water + 15 earth — Dragonstone</li>
 *   <li>Lvl-6 (87 Magic): 1 cosmic + 20 fire + 20 earth — Onyx</li>
 *   <li>Lvl-7 (93 Magic): 1 cosmic + 20 blood + 20 soul — Zenyte</li>
 * </ul>
 * <p>
 * Rune costs are per cast, matching the in-game spells (see the OSRS wiki pages
 * for Lvl-1..Lvl-7 Enchant). Lvl-7 is the odd one out: it consumes blood and
 * soul runes, which no staff supplies — see {@link ElementalStaff}.
 */
@Getter
@RequiredArgsConstructor
public enum JewelEnchantLevel {
    LVL_1("Lvl-1 Enchant", 7, MagicAction.ENCHANT_SAPPHIRE_JEWELLERY, Map.of(Runes.COSMIC, 1, Runes.WATER, 1)),
    LVL_2("Lvl-2 Enchant", 27, MagicAction.ENCHANT_EMERALD_JEWELLERY, Map.of(Runes.COSMIC, 1, Runes.AIR, 3)),
    LVL_3("Lvl-3 Enchant", 49, MagicAction.ENCHANT_RUBY_JEWELLERY, Map.of(Runes.COSMIC, 1, Runes.FIRE, 5)),
    LVL_4("Lvl-4 Enchant", 57, MagicAction.ENCHANT_DIAMOND_JEWELLERY, Map.of(Runes.COSMIC, 1, Runes.EARTH, 10)),
    LVL_5("Lvl-5 Enchant", 68, MagicAction.ENCHANT_DRAGONSTONE_JEWELLERY, Map.of(Runes.COSMIC, 1, Runes.WATER, 15, Runes.EARTH, 15)),
    LVL_6("Lvl-6 Enchant", 87, MagicAction.ENCHANT_ONYX_JEWELLERY, Map.of(Runes.COSMIC, 1, Runes.FIRE, 20, Runes.EARTH, 20)),
    LVL_7("Lvl-7 Enchant", 93, MagicAction.ENCHANT_ZENYTE_JEWELLERY, Map.of(Runes.COSMIC, 1, Runes.BLOOD, 20, Runes.SOUL, 20));

    private final String name;
    private final int requiredLevel;
    private final MagicAction magicAction;
    /** Full rune requirements per cast, including cosmic. */
    private final Map<Runes, Integer> requiredRunes;

    @Override
    public String toString() {
        return name;
    }
}
