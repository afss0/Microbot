package net.runelite.client.plugins.microbot.ascript.jewellenchant;

import lombok.Getter;
import net.runelite.api.ItemID;

/**
 * Enchantable jewellery items, grouped by enchant level.
 * <p>
 * Each entry maps an unenchanted item to its enchant level and enchanted variant:
 * the enchanting flow uses the unenchanted item's ID for banking and inventory
 * checks, the enchant level for spell selection, and the enchanted IDs for the
 * landed-cast check (an enchanted count increase is the only proof a cast
 * converted a piece rather than, say, a misclick wearing it).
 * <p>
 * Items whose enchant product has charge variants list EVERY variant: a fresh
 * cast lands on a specific variant (e.g. a games necklace always comes out at
 * (8)), but the check only needs "the enchanted side grew", and listing the
 * whole family keeps it correct regardless of which variant the game produces.
 * Ornamented / imbued copies are deliberately excluded — the enchant never
 * produces them.
 */
@Getter
public enum JewelEnchantItem {
    NONE(" ", 0, JewelEnchantLevel.LVL_1),

    // ── Lvl-1 Enchant (7 Magic) ──────────────────────────
    OPAL_RING("Opal ring", ItemID.OPAL_RING, JewelEnchantLevel.LVL_1,
            ItemID.RING_OF_PURSUIT),
    OPAL_NECKLACE("Opal necklace", ItemID.OPAL_NECKLACE, JewelEnchantLevel.LVL_1,
            ItemID.DODGY_NECKLACE),
    OPAL_BRACELET("Opal bracelet", ItemID.OPAL_BRACELET, JewelEnchantLevel.LVL_1,
            ItemID.EXPEDITIOUS_BRACELET),
    OPAL_AMULET("Opal amulet", ItemID.OPAL_AMULET, JewelEnchantLevel.LVL_1,
            ItemID.AMULET_OF_BOUNTY),
    SAPPHIRE_RING("Sapphire ring", ItemID.SAPPHIRE_RING, JewelEnchantLevel.LVL_1,
            ItemID.RING_OF_RECOIL),
    SAPPHIRE_NECKLACE("Sapphire necklace", ItemID.SAPPHIRE_NECKLACE, JewelEnchantLevel.LVL_1,
            ItemID.GAMES_NECKLACE8, ItemID.GAMES_NECKLACE7, ItemID.GAMES_NECKLACE6,
            ItemID.GAMES_NECKLACE5, ItemID.GAMES_NECKLACE4, ItemID.GAMES_NECKLACE3,
            ItemID.GAMES_NECKLACE2, ItemID.GAMES_NECKLACE1),
    // 11072 = JEWL_SAPPHIRE_BRACELET (the real item). ItemID.SAPPHIRE_BRACELET (11071) is the
    // cache's dummy entry — it never exists in an inventory or bank.
    SAPPHIRE_BRACELET("Sapphire bracelet", ItemID.SAPPHIRE_BRACELET_11072, JewelEnchantLevel.LVL_1,
            ItemID.BRACELET_OF_CLAY),
    SAPPHIRE_AMULET("Sapphire amulet", ItemID.SAPPHIRE_AMULET, JewelEnchantLevel.LVL_1,
            ItemID.AMULET_OF_MAGIC),

    // ── Lvl-2 Enchant (27 Magic) ─────────────────────────
    JADE_RING("Jade ring", ItemID.JADE_RING, JewelEnchantLevel.LVL_2,
            ItemID.RING_OF_RETURNING5, ItemID.RING_OF_RETURNING4, ItemID.RING_OF_RETURNING3,
            ItemID.RING_OF_RETURNING2, ItemID.RING_OF_RETURNING1),
    JADE_NECKLACE("Jade necklace", ItemID.JADE_NECKLACE, JewelEnchantLevel.LVL_2,
            ItemID.NECKLACE_OF_PASSAGE5, ItemID.NECKLACE_OF_PASSAGE4, ItemID.NECKLACE_OF_PASSAGE3,
            ItemID.NECKLACE_OF_PASSAGE2, ItemID.NECKLACE_OF_PASSAGE1),
    JADE_BRACELET("Jade bracelet", ItemID.JADE_BRACELET, JewelEnchantLevel.LVL_2,
            ItemID.FLAMTAER_BRACELET),
    JADE_AMULET("Jade amulet", ItemID.JADE_AMULET, JewelEnchantLevel.LVL_2,
            ItemID.AMULET_OF_CHEMISTRY),
    EMERALD_RING("Emerald ring", ItemID.EMERALD_RING, JewelEnchantLevel.LVL_2,
            ItemID.RING_OF_DUELING8, ItemID.RING_OF_DUELING7, ItemID.RING_OF_DUELING6,
            ItemID.RING_OF_DUELING5, ItemID.RING_OF_DUELING4, ItemID.RING_OF_DUELING3,
            ItemID.RING_OF_DUELING2, ItemID.RING_OF_DUELING1),
    EMERALD_NECKLACE("Emerald necklace", ItemID.EMERALD_NECKLACE, JewelEnchantLevel.LVL_2,
            ItemID.BINDING_NECKLACE),
    EMERALD_BRACELET("Emerald bracelet", ItemID.EMERALD_BRACELET, JewelEnchantLevel.LVL_2,
            ItemID.CASTLE_WARS_BRACELET3, ItemID.CASTLE_WARS_BRACELET2, ItemID.CASTLE_WARS_BRACELET1),
    EMERALD_AMULET("Emerald amulet", ItemID.EMERALD_AMULET, JewelEnchantLevel.LVL_2,
            ItemID.AMULET_OF_DEFENCE),

    // ── Lvl-3 Enchant (49 Magic) ─────────────────────────
    TOPAZ_RING("Topaz ring", ItemID.TOPAZ_RING, JewelEnchantLevel.LVL_3,
            ItemID.EFARITAYS_AID),
    TOPAZ_NECKLACE("Topaz necklace", ItemID.TOPAZ_NECKLACE, JewelEnchantLevel.LVL_3,
            ItemID.NECKLACE_OF_FAITH),
    TOPAZ_BRACELET("Topaz bracelet", ItemID.TOPAZ_BRACELET, JewelEnchantLevel.LVL_3,
            ItemID.BRACELET_OF_SLAUGHTER),
    TOPAZ_AMULET("Topaz amulet", ItemID.TOPAZ_AMULET, JewelEnchantLevel.LVL_3,
            ItemID.BURNING_AMULET5, ItemID.BURNING_AMULET4, ItemID.BURNING_AMULET3,
            ItemID.BURNING_AMULET2, ItemID.BURNING_AMULET1),
    RUBY_RING("Ruby ring", ItemID.RUBY_RING, JewelEnchantLevel.LVL_3,
            ItemID.RING_OF_FORGING),
    RUBY_NECKLACE("Ruby necklace", ItemID.RUBY_NECKLACE, JewelEnchantLevel.LVL_3,
            ItemID.DIGSITE_PENDANT_5, ItemID.DIGSITE_PENDANT_4, ItemID.DIGSITE_PENDANT_3,
            ItemID.DIGSITE_PENDANT_2, ItemID.DIGSITE_PENDANT_1),
    RUBY_BRACELET("Ruby bracelet", ItemID.RUBY_BRACELET, JewelEnchantLevel.LVL_3,
            ItemID.INOCULATION_BRACELET),
    RUBY_AMULET("Ruby amulet", ItemID.RUBY_AMULET, JewelEnchantLevel.LVL_3,
            ItemID.AMULET_OF_STRENGTH),

    // ── Lvl-4 Enchant (57 Magic) ─────────────────────────
    DIAMOND_RING("Diamond ring", ItemID.DIAMOND_RING, JewelEnchantLevel.LVL_4,
            ItemID.RING_OF_LIFE),
    DIAMOND_NECKLACE("Diamond necklace", ItemID.DIAMOND_NECKLACE, JewelEnchantLevel.LVL_4,
            ItemID.PHOENIX_NECKLACE),
    DIAMOND_BRACELET("Diamond bracelet", ItemID.DIAMOND_BRACELET, JewelEnchantLevel.LVL_4,
            ItemID.ABYSSAL_BRACELET5, ItemID.ABYSSAL_BRACELET4, ItemID.ABYSSAL_BRACELET3,
            ItemID.ABYSSAL_BRACELET2, ItemID.ABYSSAL_BRACELET1),
    DIAMOND_AMULET("Diamond amulet", ItemID.DIAMOND_AMULET, JewelEnchantLevel.LVL_4,
            ItemID.AMULET_OF_POWER),

    // ── Lvl-5 Enchant (68 Magic) ─────────────────────────
    DRAGONSTONE_RING("Dragonstone ring", ItemID.DRAGONSTONE_RING, JewelEnchantLevel.LVL_5,
            ItemID.RING_OF_WEALTH, ItemID.RING_OF_WEALTH_5, ItemID.RING_OF_WEALTH_4,
            ItemID.RING_OF_WEALTH_3, ItemID.RING_OF_WEALTH_2, ItemID.RING_OF_WEALTH_1),
    DRAGON_NECKLACE("Dragon necklace", ItemID.DRAGON_NECKLACE, JewelEnchantLevel.LVL_5,
            ItemID.SKILLS_NECKLACE, ItemID.SKILLS_NECKLACE6, ItemID.SKILLS_NECKLACE5,
            ItemID.SKILLS_NECKLACE4, ItemID.SKILLS_NECKLACE3, ItemID.SKILLS_NECKLACE2,
            ItemID.SKILLS_NECKLACE1),
    DRAGONSTONE_BRACELET("Dragonstone bracelet", ItemID.DRAGONSTONE_BRACELET, JewelEnchantLevel.LVL_5,
            ItemID.COMBAT_BRACELET, ItemID.COMBAT_BRACELET6, ItemID.COMBAT_BRACELET5,
            ItemID.COMBAT_BRACELET4, ItemID.COMBAT_BRACELET3, ItemID.COMBAT_BRACELET2,
            ItemID.COMBAT_BRACELET1),
    DRAGONSTONE_AMULET("Dragonstone amulet", ItemID.DRAGONSTONE_AMULET, JewelEnchantLevel.LVL_5,
            ItemID.AMULET_OF_GLORY, ItemID.AMULET_OF_GLORY1, ItemID.AMULET_OF_GLORY2,
            ItemID.AMULET_OF_GLORY3, ItemID.AMULET_OF_GLORY4, ItemID.AMULET_OF_GLORY5,
            ItemID.AMULET_OF_GLORY6),

    // ── Lvl-6 Enchant (87 Magic) ─────────────────────────
    ONYX_RING("Onyx ring", ItemID.ONYX_RING, JewelEnchantLevel.LVL_6,
            ItemID.RING_OF_STONE),
    ONYX_NECKLACE("Onyx necklace", ItemID.ONYX_NECKLACE, JewelEnchantLevel.LVL_6,
            ItemID.BERSERKER_NECKLACE),
    ONYX_BRACELET("Onyx bracelet", ItemID.ONYX_BRACELET, JewelEnchantLevel.LVL_6,
            ItemID.REGEN_BRACELET),
    ONYX_AMULET("Onyx amulet", ItemID.ONYX_AMULET, JewelEnchantLevel.LVL_6,
            ItemID.AMULET_OF_FURY),

    // ── Lvl-7 Enchant (93 Magic) ─────────────────────────
    ZENYTE_RING("Zenyte ring", ItemID.ZENYTE_RING, JewelEnchantLevel.LVL_7,
            ItemID.RING_OF_SUFFERING),
    ZENYTE_NECKLACE("Zenyte necklace", ItemID.ZENYTE_NECKLACE, JewelEnchantLevel.LVL_7,
            ItemID.NECKLACE_OF_ANGUISH),
    ZENYTE_BRACELET("Zenyte bracelet", ItemID.ZENYTE_BRACELET, JewelEnchantLevel.LVL_7,
            ItemID.TORMENTED_BRACELET),
    ZENYTE_AMULET("Zenyte amulet", ItemID.ZENYTE_AMULET, JewelEnchantLevel.LVL_7,
            ItemID.AMULET_OF_TORTURE);

    JewelEnchantItem(String name, int unenchantedId, JewelEnchantLevel enchantLevel,
            int... enchantedIds) {
        this.name = name;
        this.unenchantedId = unenchantedId;
        this.enchantLevel = enchantLevel;
        this.enchantedIds = enchantedIds;
    }

    private final String name;
    private final int unenchantedId;
    private final JewelEnchantLevel enchantLevel;
    /** Every item id a landed cast can produce for this piece (charge variants included). */
    private final int[] enchantedIds;

    @Override
    public String toString() {
        return name;
    }
}
