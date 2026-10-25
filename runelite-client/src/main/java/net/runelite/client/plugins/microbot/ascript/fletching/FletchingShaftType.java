package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * Knife + logs = arrow shafts. One log yields 15 shafts per tier step, with the tier's Fletching
 * level gate. The product is the same {@link ItemID#ARROW_SHAFT} for every tier.
 * <p>
 * {@code FletchingAction.ARROW_SHAFT} only records level 1 / 0.33 xp per shaft, so the tier
 * levels and quantities are the wiki's values and are pinned explicitly in
 * {@code FletchingDataTest}.
 */
@Getter
@RequiredArgsConstructor
public enum FletchingShaftType implements LogFletchProduct {
    LOGS("Logs -> 15 shafts", "Logs", ItemID.LOGS, 1, 15, 5),
    OAK("Oak logs -> 30 shafts", "Oak logs", ItemID.OAK_LOGS, 15, 30, 10),
    WILLOW("Willow logs -> 45 shafts", "Willow logs", ItemID.WILLOW_LOGS, 30, 45, 15),
    MAPLE("Maple logs -> 60 shafts", "Maple logs", ItemID.MAPLE_LOGS, 45, 60, 20),
    YEW("Yew logs -> 75 shafts", "Yew logs", ItemID.YEW_LOGS, 60, 75, 25),
    MAGIC("Magic logs -> 90 shafts", "Magic logs", ItemID.MAGIC_LOGS, 75, 90, 30),
    REDWOOD("Redwood logs -> 105 shafts", "Redwood logs", ItemID.REDWOOD_LOGS, 90, 105, 35);

    private final String label;
    private final String logName;
    private final int logId;
    private final int levelRequirement;
    private final int shaftsPerLog;
    private final int xpPerAction;

    @Override
    public int logId() {
        return logId;
    }

    @Override
    public int productId() {
        return ItemID.ARROW_SHAFT;
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
        return shaftsPerLog;
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
