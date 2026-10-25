package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * Knife + logs = crossbow stock. One log per stock. Ids are the real (unnoted) gameval stock
 * constants; the duplicate constants in the gameval nested class are not used.
 */
@Getter
@RequiredArgsConstructor
public enum FletchingStockType implements LogFletchProduct {
    WOODEN("Wooden stock", "Logs", ItemID.LOGS, ItemID.XBOWS_CROSSBOW_STOCK_WOOD, 9),
    OAK("Oak stock", "Oak logs", ItemID.OAK_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_OAK, 24),
    WILLOW("Willow stock", "Willow logs", ItemID.WILLOW_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_WILLOW, 39),
    TEAK("Teak stock", "Teak logs", ItemID.TEAK_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_TEAK, 46),
    MAPLE("Maple stock", "Maple logs", ItemID.MAPLE_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_MAPLE, 54),
    MAHOGANY("Mahogany stock", "Mahogany logs", ItemID.MAHOGANY_LOGS,
            ItemID.XBOWS_CROSSBOW_STOCK_MAHOGANY, 61),
    YEW("Yew stock", "Yew logs", ItemID.YEW_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_YEW, 69),
    MAGIC("Magic stock", "Magic logs", ItemID.MAGIC_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_MAGIC, 78);

    private final String label;
    private final String logName;
    private final int logId;
    private final int productId;
    private final int levelRequirement;

    @Override
    public int logId() {
        return logId;
    }

    @Override
    public int productId() {
        return productId;
    }

    @Override
    public int levelRequirement() {
        return levelRequirement;
    }

    @Override
    public int logsPerAction() {
        return 1;
    }

    @Override
    public int productsPerAction() {
        return 1;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
