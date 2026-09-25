package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;

@Getter
@RequiredArgsConstructor
public enum JewelryGem {
    NONE("", "", 0, 0),
    OPAL("uncut opal", "opal", ItemID.UNCUT_OPAL, ItemID.OPAL),
    JADE("uncut jade", "jade", ItemID.UNCUT_JADE, ItemID.JADE),
    RED_TOPAZ("uncut red topaz", "red topaz", ItemID.UNCUT_RED_TOPAZ, ItemID.RED_TOPAZ),
    SAPPHIRE("uncut sapphire", "sapphire", ItemID.UNCUT_SAPPHIRE, ItemID.SAPPHIRE),
    EMERALD("uncut emerald", "emerald", ItemID.UNCUT_EMERALD, ItemID.EMERALD),
    RUBY("uncut ruby", "ruby", ItemID.UNCUT_RUBY, ItemID.RUBY),
    DIAMOND("uncut diamond", "diamond", ItemID.UNCUT_DIAMOND, ItemID.DIAMOND),
    DRAGONSTONE("uncut dragonstone", "dragonstone", ItemID.UNCUT_DRAGONSTONE, ItemID.DRAGONSTONE),
    ONYX("uncut onyx", "onyx", ItemID.UNCUT_ONYX, ItemID.ONYX),
    ZENYTE("uncut zenyte", "zenyte", ItemID.UNCUT_ZENYTE, ItemID.ZENYTE);

    private final String uncutItemName;
    private final String cutItemName;
    private final int uncutItemID;
    private final int cutItemID;

    @Override
    public String toString() {
        return cutItemName;
    }
}
