package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;

@Getter
@RequiredArgsConstructor
public enum JewelryItem {
    // Gold (group 446)
    GOLD_RING("Gold ring", ItemID.GOLD_RING, JewelryGem.NONE, ItemID.RING_MOULD, JewelryType.GOLD, 5, 446, 8),
    GOLD_NECKLACE("Gold necklace", ItemID.GOLD_NECKLACE, JewelryGem.NONE, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 6, 446, 23),
    GOLD_BRACELET("Gold bracelet", ItemID.GOLD_BRACELET, JewelryGem.NONE, ItemID.BRACELET_MOULD, JewelryType.GOLD, 7, 446, 50),
    GOLD_AMULET("Gold amulet", ItemID.GOLD_AMULET_U, JewelryGem.NONE, ItemID.AMULET_MOULD, JewelryType.GOLD, 8, 446, 37),
    GOLD_TIARA("Gold tiara", ItemID.GOLD_TIARA, JewelryGem.NONE, ItemID.TIARA_MOULD, JewelryType.GOLD, 42, 270, 13),

    // Silver (group 6) — tiaras use production widget (270)
    SILVER_TIARA("Tiara", ItemID.TIARA, JewelryGem.NONE, ItemID.TIARA_MOULD, JewelryType.SILVER, 23, 270, 13),
    UNSTRUNG_SYMBOL("Holy symbol", ItemID.UNSTRUNG_SYMBOL, JewelryGem.NONE, ItemID.HOLY_MOULD, JewelryType.SILVER, 16, 6, 23),

    // Opal (silver interface, group 6)
    OPAL_RING("Opal ring", ItemID.OPAL_RING, JewelryGem.OPAL, ItemID.RING_MOULD, JewelryType.SILVER, 1, 6, 7),
    OPAL_NECKLACE("Opal necklace", ItemID.OPAL_NECKLACE, JewelryGem.OPAL, ItemID.NECKLACE_MOULD, JewelryType.SILVER, 16, 6, 11),
    OPAL_BRACELET("Opal bracelet", ItemID.OPAL_BRACELET, JewelryGem.OPAL, ItemID.BRACELET_MOULD, JewelryType.SILVER, 22, 6, 19),
    OPAL_AMULET("Opal amulet", ItemID.OPAL_AMULET_U, JewelryGem.OPAL, ItemID.AMULET_MOULD, JewelryType.SILVER, 27, 6, 15),

    // Jade (silver interface, group 6)
    JADE_RING("Jade ring", ItemID.JADE_RING, JewelryGem.JADE, ItemID.RING_MOULD, JewelryType.SILVER, 13, 6, 8),
    JADE_NECKLACE("Jade necklace", ItemID.JADE_NECKLACE, JewelryGem.JADE, ItemID.NECKLACE_MOULD, JewelryType.SILVER, 25, 6, 12),
    JADE_BRACELET("Jade bracelet", ItemID.JADE_BRACELET, JewelryGem.JADE, ItemID.BRACELET_MOULD, JewelryType.SILVER, 29, 6, 20),
    JADE_AMULET("Jade amulet", ItemID.JADE_AMULET_U, JewelryGem.JADE, ItemID.AMULET_MOULD, JewelryType.SILVER, 34, 6, 16),

    // Red Topaz (silver interface, group 6)
    TOPAZ_RING("Topaz ring", ItemID.TOPAZ_RING, JewelryGem.RED_TOPAZ, ItemID.RING_MOULD, JewelryType.SILVER, 16, 6, 9),
    TOPAZ_NECKLACE("Topaz necklace", ItemID.TOPAZ_NECKLACE, JewelryGem.RED_TOPAZ, ItemID.NECKLACE_MOULD, JewelryType.SILVER, 32, 6, 13),
    TOPAZ_BRACELET("Topaz bracelet", ItemID.TOPAZ_BRACELET, JewelryGem.RED_TOPAZ, ItemID.BRACELET_MOULD, JewelryType.SILVER, 38, 6, 21),
    TOPAZ_AMULET("Topaz amulet", ItemID.TOPAZ_AMULET_U, JewelryGem.RED_TOPAZ, ItemID.AMULET_MOULD, JewelryType.SILVER, 45, 6, 17),

    // Sapphire (gold interface, group 446)
    SAPPHIRE_RING("Sapphire ring", ItemID.SAPPHIRE_RING, JewelryGem.SAPPHIRE, ItemID.RING_MOULD, JewelryType.GOLD, 20, 446, 9),
    SAPPHIRE_NECKLACE("Sapphire necklace", ItemID.SAPPHIRE_NECKLACE, JewelryGem.SAPPHIRE, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 22, 446, 24),
    SAPPHIRE_BRACELET("Sapphire bracelet", ItemID.SAPPHIRE_BRACELET_11072, JewelryGem.SAPPHIRE, ItemID.BRACELET_MOULD, JewelryType.GOLD, 23, 446, 52),
    SAPPHIRE_AMULET("Sapphire amulet", ItemID.SAPPHIRE_AMULET_U, JewelryGem.SAPPHIRE, ItemID.AMULET_MOULD, JewelryType.GOLD, 24, 446, 38),

    // Emerald (gold interface, group 446)
    EMERALD_RING("Emerald ring", ItemID.EMERALD_RING, JewelryGem.EMERALD, ItemID.RING_MOULD, JewelryType.GOLD, 27, 446, 10),
    EMERALD_NECKLACE("Emerald necklace", ItemID.EMERALD_NECKLACE, JewelryGem.EMERALD, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 29, 446, 25),
    EMERALD_BRACELET("Emerald bracelet", ItemID.EMERALD_BRACELET, JewelryGem.EMERALD, ItemID.BRACELET_MOULD, JewelryType.GOLD, 30, 446, 53),
    EMERALD_AMULET("Emerald amulet", ItemID.EMERALD_AMULET_U, JewelryGem.EMERALD, ItemID.AMULET_MOULD, JewelryType.GOLD, 31, 446, 39),

    // Ruby (gold interface, group 446)
    RUBY_RING("Ruby ring", ItemID.RUBY_RING, JewelryGem.RUBY, ItemID.RING_MOULD, JewelryType.GOLD, 34, 446, 11),
    RUBY_NECKLACE("Ruby necklace", ItemID.RUBY_NECKLACE, JewelryGem.RUBY, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 40, 446, 26),
    RUBY_BRACELET("Ruby bracelet", ItemID.RUBY_BRACELET, JewelryGem.RUBY, ItemID.BRACELET_MOULD, JewelryType.GOLD, 42, 446, 54),
    RUBY_AMULET("Ruby amulet", ItemID.RUBY_AMULET_U, JewelryGem.RUBY, ItemID.AMULET_MOULD, JewelryType.GOLD, 50, 446, 40),

    // Diamond (gold interface, group 446)
    DIAMOND_RING("Diamond ring", ItemID.DIAMOND_RING, JewelryGem.DIAMOND, ItemID.RING_MOULD, JewelryType.GOLD, 43, 446, 12),
    DIAMOND_NECKLACE("Diamond necklace", ItemID.DIAMOND_NECKLACE, JewelryGem.DIAMOND, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 56, 446, 27),
    DIAMOND_BRACELET("Diamond bracelet", ItemID.DIAMOND_BRACELET, JewelryGem.DIAMOND, ItemID.BRACELET_MOULD, JewelryType.GOLD, 58, 446, 55),
    DIAMOND_AMULET("Diamond amulet", ItemID.DIAMOND_AMULET_U, JewelryGem.DIAMOND, ItemID.AMULET_MOULD, JewelryType.GOLD, 70, 446, 41),

    // Dragonstone (gold interface, group 446)
    DRAGONSTONE_RING("Dragonstone ring", ItemID.DRAGONSTONE_RING, JewelryGem.DRAGONSTONE, ItemID.RING_MOULD, JewelryType.GOLD, 55, 446, 13),
    DRAGON_NECKLACE("Dragon necklace", ItemID.DRAGON_NECKLACE, JewelryGem.DRAGONSTONE, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 72, 446, 28),
    DRAGONSTONE_BRACELET("Dragon bracelet", ItemID.DRAGONSTONE_BRACELET, JewelryGem.DRAGONSTONE, ItemID.BRACELET_MOULD, JewelryType.GOLD, 74, 446, 56),
    DRAGONSTONE_AMULET("Dragonstone amulet", ItemID.DRAGONSTONE_AMULET_U, JewelryGem.DRAGONSTONE, ItemID.AMULET_MOULD, JewelryType.GOLD, 80, 446, 42),

    // Onyx (gold interface, group 446)
    ONYX_RING("Onyx ring", ItemID.ONYX_RING, JewelryGem.ONYX, ItemID.RING_MOULD, JewelryType.GOLD, 67, 446, 14),
    ONYX_NECKLACE("Onyx necklace", ItemID.ONYX_NECKLACE, JewelryGem.ONYX, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 82, 446, 29),
    ONYX_BRACELET("Onyx bracelet", ItemID.ONYX_BRACELET, JewelryGem.ONYX, ItemID.BRACELET_MOULD, JewelryType.GOLD, 84, 446, 57),
    ONYX_AMULET("Onyx amulet", ItemID.ONYX_AMULET_U, JewelryGem.ONYX, ItemID.AMULET_MOULD, JewelryType.GOLD, 90, 446, 43),

    // Zenyte (gold interface, group 446)
    ZENYTE_RING("Zenyte ring", ItemID.ZENYTE_RING, JewelryGem.ZENYTE, ItemID.RING_MOULD, JewelryType.GOLD, 89, 446, 15),
    ZENYTE_NECKLACE("Zenyte necklace", ItemID.ZENYTE_NECKLACE, JewelryGem.ZENYTE, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 92, 446, 30),
    ZENYTE_BRACELET("Zenyte bracelet", ItemID.ZENYTE_BRACELET, JewelryGem.ZENYTE, ItemID.BRACELET_MOULD, JewelryType.GOLD, 95, 446, 58),
    ZENYTE_AMULET("Zenyte amulet", ItemID.ZENYTE_AMULET_U, JewelryGem.ZENYTE, ItemID.AMULET_MOULD, JewelryType.GOLD, 98, 446, 44);

    private final String name;
    private final int itemID;
    private final JewelryGem gem;
    private final int toolItemID; // mould
    private final JewelryType jewelryType;
    private final int levelRequired;
    /** Widget group ID in the crafting interface (446=gold, 6=silver, 270=production/tiaras). */
    private final int widgetGroup;
    /** Widget child ID within the group. */
    private final int widgetChild;

    public boolean hasGem() {
        return gem != JewelryGem.NONE;
    }

    @Override
    public String toString() {
        return name;
    }
}
