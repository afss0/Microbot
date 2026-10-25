package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * Knife + <b>two</b> logs = wooden shield. Shields are the only log action that consumes two logs
 * per product; the batch planner withdraws an even number so the last action is never left with a
 * single unusable log.
 */
@Getter
@RequiredArgsConstructor
public enum FletchingShieldType implements LogFletchProduct {
    OAK("Oak shield", "Oak logs", ItemID.OAK_LOGS, ItemID.OAK_SHIELD, 27),
    WILLOW("Willow shield", "Willow logs", ItemID.WILLOW_LOGS, ItemID.WILLOW_SHIELD, 42),
    MAPLE("Maple shield", "Maple logs", ItemID.MAPLE_LOGS, ItemID.MAPLE_SHIELD, 57),
    YEW("Yew shield", "Yew logs", ItemID.YEW_LOGS, ItemID.YEW_SHIELD, 72),
    MAGIC("Magic shield", "Magic logs", ItemID.MAGIC_LOGS, ItemID.MAGIC_SHIELD, 87),
    REDWOOD("Redwood shield", "Redwood logs", ItemID.REDWOOD_LOGS, ItemID.REDWOOD_SHIELD, 92);

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
        return 2;
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
