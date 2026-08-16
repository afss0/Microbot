package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;

@Getter
@RequiredArgsConstructor
public enum JewelryItem {
    // Gold
    GOLD_RING("Gold ring", ItemID.GOLD_RING, JewelryGem.NONE, ItemID.RING_MOULD, JewelryType.GOLD, 5),
    GOLD_NECKLACE("Gold necklace", ItemID.GOLD_NECKLACE, JewelryGem.NONE, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 6),
    GOLD_BRACELET("Gold bracelet", ItemID.GOLD_BRACELET, JewelryGem.NONE, ItemID.BRACELET_MOULD, JewelryType.GOLD, 7),
    GOLD_AMULET("Gold amulet", ItemID.GOLD_AMULET_U, JewelryGem.NONE, ItemID.AMULET_MOULD, JewelryType.GOLD, 8),
    GOLD_TIARA("Gold tiara", ItemID.GOLD_TIARA, JewelryGem.NONE, ItemID.TIARA_MOULD, JewelryType.GOLD, 42),

    // Silver
    SILVER_TIARA("Tiara", ItemID.TIARA, JewelryGem.NONE, ItemID.TIARA_MOULD, JewelryType.SILVER, 23),
    UNSTRUNG_SYMBOL("Holy symbol", ItemID.UNSTRUNG_SYMBOL, JewelryGem.NONE, ItemID.HOLY_MOULD, JewelryType.SILVER, 16),

    // Opal
    OPAL_RING("Opal ring", ItemID.OPAL_RING, JewelryGem.OPAL, ItemID.RING_MOULD, JewelryType.SILVER, 1),
    OPAL_NECKLACE("Opal necklace", ItemID.OPAL_NECKLACE, JewelryGem.OPAL, ItemID.NECKLACE_MOULD, JewelryType.SILVER, 16),
    OPAL_BRACELET("Opal bracelet", ItemID.OPAL_BRACELET, JewelryGem.OPAL, ItemID.BRACELET_MOULD, JewelryType.SILVER, 22),
    OPAL_AMULET("Opal amulet", ItemID.OPAL_AMULET_U, JewelryGem.OPAL, ItemID.AMULET_MOULD, JewelryType.SILVER, 27),

    // Jade
    JADE_RING("Jade ring", ItemID.JADE_RING, JewelryGem.JADE, ItemID.RING_MOULD, JewelryType.SILVER, 13),
    JADE_NECKLACE("Jade necklace", ItemID.JADE_NECKLACE, JewelryGem.JADE, ItemID.NECKLACE_MOULD, JewelryType.SILVER, 25),
    JADE_BRACELET("Jade bracelet", ItemID.JADE_BRACELET, JewelryGem.JADE, ItemID.BRACELET_MOULD, JewelryType.SILVER, 29),
    JADE_AMULET("Jade amulet", ItemID.JADE_AMULET_U, JewelryGem.JADE, ItemID.AMULET_MOULD, JewelryType.SILVER, 34),

    // Red Topaz
    TOPAZ_RING("Topaz ring", ItemID.TOPAZ_RING, JewelryGem.RED_TOPAZ, ItemID.RING_MOULD, JewelryType.SILVER, 16),
    TOPAZ_NECKLACE("Topaz necklace", ItemID.TOPAZ_NECKLACE, JewelryGem.RED_TOPAZ, ItemID.NECKLACE_MOULD, JewelryType.SILVER, 32),
    TOPAZ_BRACELET("Topaz bracelet", ItemID.TOPAZ_BRACELET, JewelryGem.RED_TOPAZ, ItemID.BRACELET_MOULD, JewelryType.SILVER, 38),
    TOPAZ_AMULET("Topaz amulet", ItemID.TOPAZ_AMULET_U, JewelryGem.RED_TOPAZ, ItemID.AMULET_MOULD, JewelryType.SILVER, 45),

    // Sapphire
    SAPPHIRE_RING("Sapphire ring", ItemID.SAPPHIRE_RING, JewelryGem.SAPPHIRE, ItemID.RING_MOULD, JewelryType.GOLD, 20),
    SAPPHIRE_NECKLACE("Sapphire necklace", ItemID.SAPPHIRE_NECKLACE, JewelryGem.SAPPHIRE, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 22),
    SAPPHIRE_BRACELET("Sapphire bracelet", ItemID.SAPPHIRE_BRACELET_11072, JewelryGem.SAPPHIRE, ItemID.BRACELET_MOULD, JewelryType.GOLD, 23),
    SAPPHIRE_AMULET("Sapphire amulet", ItemID.SAPPHIRE_AMULET_U, JewelryGem.SAPPHIRE, ItemID.AMULET_MOULD, JewelryType.GOLD, 24),

    // Emerald
    EMERALD_RING("Emerald ring", ItemID.EMERALD_RING, JewelryGem.EMERALD, ItemID.RING_MOULD, JewelryType.GOLD, 27),
    EMERALD_NECKLACE("Emerald necklace", ItemID.EMERALD_NECKLACE, JewelryGem.EMERALD, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 29),
    EMERALD_BRACELET("Emerald bracelet", ItemID.EMERALD_BRACELET, JewelryGem.EMERALD, ItemID.BRACELET_MOULD, JewelryType.GOLD, 30),
    EMERALD_AMULET("Emerald amulet", ItemID.EMERALD_AMULET_U, JewelryGem.EMERALD, ItemID.AMULET_MOULD, JewelryType.GOLD, 31),

    // Ruby
    RUBY_RING("Ruby ring", ItemID.RUBY_RING, JewelryGem.RUBY, ItemID.RING_MOULD, JewelryType.GOLD, 34),
    RUBY_NECKLACE("Ruby necklace", ItemID.RUBY_NECKLACE, JewelryGem.RUBY, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 40),
    RUBY_BRACELET("Ruby bracelet", ItemID.RUBY_BRACELET, JewelryGem.RUBY, ItemID.BRACELET_MOULD, JewelryType.GOLD, 42),
    RUBY_AMULET("Ruby amulet", ItemID.RUBY_AMULET_U, JewelryGem.RUBY, ItemID.AMULET_MOULD, JewelryType.GOLD, 50),

    // Diamond
    DIAMOND_RING("Diamond ring", ItemID.DIAMOND_RING, JewelryGem.DIAMOND, ItemID.RING_MOULD, JewelryType.GOLD, 43),
    DIAMOND_NECKLACE("Diamond necklace", ItemID.DIAMOND_NECKLACE, JewelryGem.DIAMOND, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 56),
    DIAMOND_BRACELET("Diamond bracelet", ItemID.DIAMOND_BRACELET, JewelryGem.DIAMOND, ItemID.BRACELET_MOULD, JewelryType.GOLD, 58),
    DIAMOND_AMULET("Diamond amulet", ItemID.DIAMOND_AMULET_U, JewelryGem.DIAMOND, ItemID.AMULET_MOULD, JewelryType.GOLD, 70),

    // Dragonstone
    DRAGONSTONE_RING("Dragonstone ring", ItemID.DRAGONSTONE_RING, JewelryGem.DRAGONSTONE, ItemID.RING_MOULD, JewelryType.GOLD, 55),
    DRAGON_NECKLACE("Dragon necklace", ItemID.DRAGON_NECKLACE, JewelryGem.DRAGONSTONE, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 72),
    DRAGONSTONE_BRACELET("Dragon bracelet", ItemID.DRAGONSTONE_BRACELET, JewelryGem.DRAGONSTONE, ItemID.BRACELET_MOULD, JewelryType.GOLD, 74),
    DRAGONSTONE_AMULET("Dragonstone amulet", ItemID.DRAGONSTONE_AMULET_U, JewelryGem.DRAGONSTONE, ItemID.AMULET_MOULD, JewelryType.GOLD, 80),

    // Onyx
    ONYX_RING("Onyx ring", ItemID.ONYX_RING, JewelryGem.ONYX, ItemID.RING_MOULD, JewelryType.GOLD, 67),
    ONYX_NECKLACE("Onyx necklace", ItemID.ONYX_NECKLACE, JewelryGem.ONYX, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 82),
    ONYX_BRACELET("Onyx bracelet", ItemID.ONYX_BRACELET, JewelryGem.ONYX, ItemID.BRACELET_MOULD, JewelryType.GOLD, 84),
    ONYX_AMULET("Onyx amulet", ItemID.ONYX_AMULET_U, JewelryGem.ONYX, ItemID.AMULET_MOULD, JewelryType.GOLD, 90),

    // Zenyte
    ZENYTE_RING("Zenyte ring", ItemID.ZENYTE_RING, JewelryGem.ZENYTE, ItemID.RING_MOULD, JewelryType.GOLD, 89),
    ZENYTE_NECKLACE("Zenyte necklace", ItemID.ZENYTE_NECKLACE, JewelryGem.ZENYTE, ItemID.NECKLACE_MOULD, JewelryType.GOLD, 92),
    ZENYTE_BRACELET("Zenyte bracelet", ItemID.ZENYTE_BRACELET, JewelryGem.ZENYTE, ItemID.BRACELET_MOULD, JewelryType.GOLD, 95),
    ZENYTE_AMULET("Zenyte amulet", ItemID.ZENYTE_AMULET_U, JewelryGem.ZENYTE, ItemID.AMULET_MOULD, JewelryType.GOLD, 98);

    private final String name;
    private final int itemID;
    private final JewelryGem gem;
    private final int toolItemID; // mould
    private final JewelryType jewelryType;
    private final int levelRequired;

    public boolean hasGem() {
        return gem != JewelryGem.NONE;
    }

    @Override
    public String toString() {
        return name;
    }
}
