package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * Unstrung bow type. Used by both bow activities:
 * <ul>
 *   <li>{@code UNSTRUNG_BOWS} — knife + {@link #getLogId()} → {@link #getUnstrungId()};</li>
 *   <li>{@code BOWS} — bow string + {@link #getUnstrungId()} → strung bow.</li>
 * </ul>
 * Ids are the real gameval unstrung-bow constants; the stringing level is the same as the
 * unstrung-bow creation level (both are the {@code *_U} FletchingAction level).
 */
@Getter
@RequiredArgsConstructor
public enum FletchingBowType implements LogFletchProduct {
    NONE(" ", "none", 0, 0, 0),
    SHORTBOW("Shortbow (u)", "shortbow (u)", ItemID.UNSTRUNG_SHORTBOW, ItemID.LOGS, 5),
    LONGBOW("Longbow (u)", "longbow (u)", ItemID.UNSTRUNG_LONGBOW, ItemID.LOGS, 10),
    OAK_SHORTBOW("Oak shortbow (u)", "oak shortbow (u)", ItemID.UNSTRUNG_OAK_SHORTBOW, ItemID.OAK_LOGS, 20),
    OAK_LONGBOW("Oak longbow (u)", "oak longbow (u)", ItemID.UNSTRUNG_OAK_LONGBOW, ItemID.OAK_LOGS, 25),
    WILLOW_SHORTBOW("Willow shortbow (u)", "willow shortbow (u)", ItemID.UNSTRUNG_WILLOW_SHORTBOW,
            ItemID.WILLOW_LOGS, 35),
    WILLOW_LONGBOW("Willow longbow (u)", "willow longbow (u)", ItemID.UNSTRUNG_WILLOW_LONGBOW,
            ItemID.WILLOW_LOGS, 40),
    MAPLE_SHORTBOW("Maple shortbow (u)", "maple shortbow (u)", ItemID.UNSTRUNG_MAPLE_SHORTBOW,
            ItemID.MAPLE_LOGS, 50),
    MAPLE_LONGBOW("Maple longbow (u)", "maple longbow (u)", ItemID.UNSTRUNG_MAPLE_LONGBOW,
            ItemID.MAPLE_LOGS, 55),
    YEW_SHORTBOW("Yew shortbow (u)", "yew shortbow (u)", ItemID.UNSTRUNG_YEW_SHORTBOW, ItemID.YEW_LOGS, 65),
    YEW_LONGBOW("Yew longbow (u)", "yew longbow (u)", ItemID.UNSTRUNG_YEW_LONGBOW, ItemID.YEW_LOGS, 70),
    MAGIC_SHORTBOW("Magic shortbow (u)", "magic shortbow (u)", ItemID.UNSTRUNG_MAGIC_SHORTBOW,
            ItemID.MAGIC_LOGS, 80),
    MAGIC_LONGBOW("Magic longbow (u)", "magic longbow (u)", ItemID.UNSTRUNG_MAGIC_LONGBOW,
            ItemID.MAGIC_LOGS, 85);

    private final String label;
    private final String unstrungName;
    private final int unstrungId;
    private final int logId;
    private final int levelRequirement;

    @Override
    public int logId() {
        return logId;
    }

    @Override
    public int productId() {
        return unstrungId;
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
