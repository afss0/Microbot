package net.runelite.client.plugins.microbot.ascript.boltenchant;

import lombok.Getter;
import net.runelite.api.ItemID;
import net.runelite.client.plugins.microbot.util.magic.Runes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gem-tipped crossbow bolts enchantable with the single "Enchant Crossbow Bolt" spell on the
 * modern spellbook. Levels, runes and per-cast costs are the OSRS wiki table (Enchant
 * Crossbow Bolt, fetched 2026-10-28); dragon bolts share the spell and the cost of their
 * regular twin and are a separate item pair per gem.
 * <p>
 * Ids are the legacy {@code net.runelite.api.ItemID} constants. The gameval {@code ItemID}
 * class also carries {@code ..._ENCHANTED_2.._5} / {@code DRAGON_BOLTS_ENCHANTED_*} duplicates
 * (e.g. 9246-9249, 21933) which are NOT the tradeable item — using one would silently break
 * every count check. {@code BoltEnchantDataTest} pins the table against that trap.
 */
@Getter
public enum BoltEnchantType {
    NONE("None", 0, 0, 0, Collections.emptyMap()),

    // ── Regular gem-tipped bolts ─────────────────────────────
    OPAL("Opal bolts", ItemID.OPAL_BOLTS, ItemID.OPAL_BOLTS_E, 4,
            runes(Runes.AIR, 2, Runes.COSMIC, 1)),
    SAPPHIRE("Sapphire bolts", ItemID.SAPPHIRE_BOLTS, ItemID.SAPPHIRE_BOLTS_E, 7,
            runes(Runes.WATER, 1, Runes.COSMIC, 1, Runes.MIND, 1)),
    JADE("Jade bolts", ItemID.JADE_BOLTS, ItemID.JADE_BOLTS_E, 14,
            runes(Runes.EARTH, 2, Runes.COSMIC, 1)),
    PEARL("Pearl bolts", ItemID.PEARL_BOLTS, ItemID.PEARL_BOLTS_E, 24,
            runes(Runes.WATER, 2, Runes.COSMIC, 1)),
    EMERALD("Emerald bolts", ItemID.EMERALD_BOLTS, ItemID.EMERALD_BOLTS_E, 27,
            runes(Runes.AIR, 3, Runes.COSMIC, 1, Runes.NATURE, 1)),
    TOPAZ("Red topaz bolts", ItemID.TOPAZ_BOLTS, ItemID.TOPAZ_BOLTS_E, 29,
            runes(Runes.FIRE, 2, Runes.COSMIC, 1)),
    RUBY("Ruby bolts", ItemID.RUBY_BOLTS, ItemID.RUBY_BOLTS_E, 49,
            runes(Runes.FIRE, 5, Runes.BLOOD, 1, Runes.COSMIC, 1)),
    DIAMOND("Diamond bolts", ItemID.DIAMOND_BOLTS, ItemID.DIAMOND_BOLTS_E, 57,
            runes(Runes.EARTH, 10, Runes.COSMIC, 1, Runes.LAW, 2)),
    DRAGONSTONE("Dragonstone bolts", ItemID.DRAGONSTONE_BOLTS, ItemID.DRAGONSTONE_BOLTS_E, 68,
            runes(Runes.EARTH, 15, Runes.COSMIC, 1, Runes.SOUL, 1)),
    ONYX("Onyx bolts", ItemID.ONYX_BOLTS, ItemID.ONYX_BOLTS_E, 87,
            runes(Runes.FIRE, 20, Runes.COSMIC, 1, Runes.DEATH, 1)),

    // ── Dragon bolts: same spells and costs, distinct items ──
    OPAL_DRAGON("Opal dragon bolts", ItemID.OPAL_DRAGON_BOLTS, ItemID.OPAL_DRAGON_BOLTS_E, 4,
            runes(Runes.AIR, 2, Runes.COSMIC, 1)),
    SAPPHIRE_DRAGON("Sapphire dragon bolts", ItemID.SAPPHIRE_DRAGON_BOLTS,
            ItemID.SAPPHIRE_DRAGON_BOLTS_E, 7,
            runes(Runes.WATER, 1, Runes.COSMIC, 1, Runes.MIND, 1)),
    JADE_DRAGON("Jade dragon bolts", ItemID.JADE_DRAGON_BOLTS, ItemID.JADE_DRAGON_BOLTS_E, 14,
            runes(Runes.EARTH, 2, Runes.COSMIC, 1)),
    PEARL_DRAGON("Pearl dragon bolts", ItemID.PEARL_DRAGON_BOLTS,
            ItemID.PEARL_DRAGON_BOLTS_E, 24,
            runes(Runes.WATER, 2, Runes.COSMIC, 1)),
    EMERALD_DRAGON("Emerald dragon bolts", ItemID.EMERALD_DRAGON_BOLTS,
            ItemID.EMERALD_DRAGON_BOLTS_E, 27,
            runes(Runes.AIR, 3, Runes.COSMIC, 1, Runes.NATURE, 1)),
    TOPAZ_DRAGON("Red topaz dragon bolts", ItemID.TOPAZ_DRAGON_BOLTS,
            ItemID.TOPAZ_DRAGON_BOLTS_E, 29,
            runes(Runes.FIRE, 2, Runes.COSMIC, 1)),
    RUBY_DRAGON("Ruby dragon bolts", ItemID.RUBY_DRAGON_BOLTS,
            ItemID.RUBY_DRAGON_BOLTS_E, 49,
            runes(Runes.FIRE, 5, Runes.BLOOD, 1, Runes.COSMIC, 1)),
    DIAMOND_DRAGON("Diamond dragon bolts", ItemID.DIAMOND_DRAGON_BOLTS,
            ItemID.DIAMOND_DRAGON_BOLTS_E, 57,
            runes(Runes.EARTH, 10, Runes.COSMIC, 1, Runes.LAW, 2)),
    DRAGONSTONE_DRAGON("Dragonstone dragon bolts", ItemID.DRAGONSTONE_DRAGON_BOLTS,
            ItemID.DRAGONSTONE_DRAGON_BOLTS_E, 68,
            runes(Runes.EARTH, 15, Runes.COSMIC, 1, Runes.SOUL, 1)),
    ONYX_DRAGON("Onyx dragon bolts", ItemID.ONYX_DRAGON_BOLTS, ItemID.ONYX_DRAGON_BOLTS_E, 87,
            runes(Runes.FIRE, 20, Runes.COSMIC, 1, Runes.DEATH, 1));

    /** Bolts consumed and produced by one cast (wiki: "a set of 10 bolts"). */
    public static final int BOLTS_PER_CAST = 10;
    /**
     * Casts the game performs per interface selection. 2026-10-28 (user-confirmed in game):
     * selecting the bolt repeats the spell ten times — a make-X-style batch of up to 100 bolts
     * — then the player stops on their own. Task 1 of the plan re-measures this live.
     */
    public static final int CASTS_PER_BATCH = 10;

    private final String displayName;
    private final int unenchantedId;
    private final int enchantedId;
    private final int magicLevel;
    private final Map<Runes, Integer> runes;

    BoltEnchantType(String displayName, int unenchantedId, int enchantedId,
                    int magicLevel, Map<Runes, Integer> runes) {
        this.displayName = displayName;
        this.unenchantedId = unenchantedId;
        this.enchantedId = enchantedId;
        this.magicLevel = magicLevel;
        this.runes = runes;
    }

    /**
     * Label of the product row in the bolt interface. The interface names the enchantment after
     * its output ("Opal bolts (e)"); the row is matched by item id first, so this is only the
     * fallback when a row exposes no item id.
     */
    public String interfaceLabel() {
        return displayName + " (e)";
    }

    /** Runes of this spell that some catalogue staff can supply for free (never cosmic). */
    public boolean hasStaffCoverableRune() {
        for (Runes rune : runes.keySet()) {
            if (!net.runelite.client.plugins.microbot.util.magic.Rs2Staff
                    .itemIdsProviding(rune).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Ordered rune map. {@link Collections#unmodifiableMap} keeps the declaration order (cosmic
     * first in the source reads better and makes bank logs stable), unlike {@code Map.copyOf}.
     */
    private static Map<Runes, Integer> runes(Object... pairs) {
        Map<Runes, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Runes) pairs[i], (Integer) pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
