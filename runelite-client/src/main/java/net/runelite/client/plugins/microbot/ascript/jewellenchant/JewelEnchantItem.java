package net.runelite.client.plugins.microbot.ascript.jewellenchant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;

/**
 * Enchantable jewellery items, grouped by enchant level.
 * <p>
 * Each entry maps an unenchanted item to its enchant level and enchanted variant.
 * The enchanting flow uses the unenchanted item's ID for banking and inventory
 * checks, and the enchant level for spell selection.
 */
@Getter
@RequiredArgsConstructor
public enum JewelEnchantItem {
    NONE(" ", 0, JewelEnchantLevel.LVL_1),

    // ── Lvl-1 Enchant (7 Magic) ──────────────────────────
    OPAL_RING("Opal ring", ItemID.OPAL_RING, JewelEnchantLevel.LVL_1),
    OPAL_NECKLACE("Opal necklace", ItemID.OPAL_NECKLACE, JewelEnchantLevel.LVL_1),
    OPAL_BRACELET("Opal bracelet", ItemID.OPAL_BRACELET, JewelEnchantLevel.LVL_1),
    OPAL_AMULET("Opal amulet", ItemID.OPAL_AMULET, JewelEnchantLevel.LVL_1),
    SAPPHIRE_RING("Sapphire ring", ItemID.SAPPHIRE_RING, JewelEnchantLevel.LVL_1),
    SAPPHIRE_NECKLACE("Sapphire necklace", ItemID.SAPPHIRE_NECKLACE, JewelEnchantLevel.LVL_1),
    SAPPHIRE_BRACELET("Sapphire bracelet", ItemID.SAPPHIRE_BRACELET, JewelEnchantLevel.LVL_1),
    SAPPHIRE_AMULET("Sapphire amulet", ItemID.SAPPHIRE_AMULET, JewelEnchantLevel.LVL_1),

    // ── Lvl-2 Enchant (27 Magic) ─────────────────────────
    JADE_RING("Jade ring", ItemID.JADE_RING, JewelEnchantLevel.LVL_2),
    JADE_NECKLACE("Jade necklace", ItemID.JADE_NECKLACE, JewelEnchantLevel.LVL_2),
    JADE_BRACELET("Jade bracelet", ItemID.JADE_BRACELET, JewelEnchantLevel.LVL_2),
    JADE_AMULET("Jade amulet", ItemID.JADE_AMULET, JewelEnchantLevel.LVL_2),
    EMERALD_RING("Emerald ring", ItemID.EMERALD_RING, JewelEnchantLevel.LVL_2),
    EMERALD_NECKLACE("Emerald necklace", ItemID.EMERALD_NECKLACE, JewelEnchantLevel.LVL_2),
    EMERALD_BRACELET("Emerald bracelet", ItemID.EMERALD_BRACELET, JewelEnchantLevel.LVL_2),
    EMERALD_AMULET("Emerald amulet", ItemID.EMERALD_AMULET, JewelEnchantLevel.LVL_2),

    // ── Lvl-3 Enchant (49 Magic) ─────────────────────────
    TOPAZ_RING("Topaz ring", ItemID.TOPAZ_RING, JewelEnchantLevel.LVL_3),
    TOPAZ_NECKLACE("Topaz necklace", ItemID.TOPAZ_NECKLACE, JewelEnchantLevel.LVL_3),
    TOPAZ_BRACELET("Topaz bracelet", ItemID.TOPAZ_BRACELET, JewelEnchantLevel.LVL_3),
    TOPAZ_AMULET("Topaz amulet", ItemID.TOPAZ_AMULET, JewelEnchantLevel.LVL_3),
    RUBY_RING("Ruby ring", ItemID.RUBY_RING, JewelEnchantLevel.LVL_3),
    RUBY_NECKLACE("Ruby necklace", ItemID.RUBY_NECKLACE, JewelEnchantLevel.LVL_3),
    RUBY_BRACELET("Ruby bracelet", ItemID.RUBY_BRACELET, JewelEnchantLevel.LVL_3),
    RUBY_AMULET("Ruby amulet", ItemID.RUBY_AMULET, JewelEnchantLevel.LVL_3),

    // ── Lvl-4 Enchant (57 Magic) ─────────────────────────
    DIAMOND_RING("Diamond ring", ItemID.DIAMOND_RING, JewelEnchantLevel.LVL_4),
    DIAMOND_NECKLACE("Diamond necklace", ItemID.DIAMOND_NECKLACE, JewelEnchantLevel.LVL_4),
    DIAMOND_BRACELET("Diamond bracelet", ItemID.DIAMOND_BRACELET, JewelEnchantLevel.LVL_4),
    DIAMOND_AMULET("Diamond amulet", ItemID.DIAMOND_AMULET, JewelEnchantLevel.LVL_4),

    // ── Lvl-5 Enchant (68 Magic) ─────────────────────────
    DRAGONSTONE_RING("Dragonstone ring", ItemID.DRAGONSTONE_RING, JewelEnchantLevel.LVL_5),
    DRAGON_NECKLACE("Dragon necklace", ItemID.DRAGON_NECKLACE, JewelEnchantLevel.LVL_5),
    DRAGONSTONE_BRACELET("Dragonstone bracelet", ItemID.DRAGONSTONE_BRACELET, JewelEnchantLevel.LVL_5),
    DRAGONSTONE_AMULET("Dragonstone amulet", ItemID.DRAGONSTONE_AMULET, JewelEnchantLevel.LVL_5),

    // ── Lvl-6 Enchant (87 Magic) ─────────────────────────
    ONYX_RING("Onyx ring", ItemID.ONYX_RING, JewelEnchantLevel.LVL_6),
    ONYX_NECKLACE("Onyx necklace", ItemID.ONYX_NECKLACE, JewelEnchantLevel.LVL_6),
    ONYX_BRACELET("Onyx bracelet", ItemID.ONYX_BRACELET, JewelEnchantLevel.LVL_6),
    ONYX_AMULET("Onyx amulet", ItemID.ONYX_AMULET, JewelEnchantLevel.LVL_6),

    // ── Lvl-7 Enchant (93 Magic) ─────────────────────────
    ZENYTE_RING("Zenyte ring", ItemID.ZENYTE_RING, JewelEnchantLevel.LVL_7),
    ZENYTE_NECKLACE("Zenyte necklace", ItemID.ZENYTE_NECKLACE, JewelEnchantLevel.LVL_7),
    ZENYTE_BRACELET("Zenyte bracelet", ItemID.ZENYTE_BRACELET, JewelEnchantLevel.LVL_7),
    ZENYTE_AMULET("Zenyte amulet", ItemID.ZENYTE_AMULET, JewelEnchantLevel.LVL_7);

    private final String name;
    private final int unenchantedId;
    private final JewelEnchantLevel enchantLevel;

    @Override
    public String toString() {
        return name;
    }
}
