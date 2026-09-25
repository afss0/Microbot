package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;

@Getter
@RequiredArgsConstructor
public enum JewelryType {
    GOLD(ItemID.GOLD_BAR, "Gold bar"),
    SILVER(ItemID.SILVER_BAR, "Silver bar");

    private final int metalBarId;
    private final String label;

    @Override
    public String toString() {
        return label;
    }
}
