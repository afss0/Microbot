package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Headless arrow + arrowtips = finished arrow.
 * <p>
 * The arrowtip ids are the gameval constants; broad arrowheads are the real item
 * {@code ItemID.SLAYER_BROAD_ARROWHEAD} (11874), and the finished broad arrow is
 * {@code ItemID.SLAYER_BROAD_ARROWS} (4160), not the Slayer-guide dummy
 * {@code ItemID.SLAYERGUIDE_BROAD_ARROWS} (4150).
 * <p>
 * Broad arrows also require the "Broader fletching" Slayer unlock.
 */
@Getter
@RequiredArgsConstructor
public enum FletchingArrowType {
    BRONZE("Bronze arrow", "Bronze arrowtips", ItemID.BRONZE_ARROWHEADS, ItemID.BRONZE_ARROW, 1),
    IRON("Iron arrow", "Iron arrowtips", ItemID.IRON_ARROWHEADS, ItemID.IRON_ARROW, 15),
    STEEL("Steel arrow", "Steel arrowtips", ItemID.STEEL_ARROWHEADS, ItemID.STEEL_ARROW, 30),
    MITHRIL("Mithril arrow", "Mithril arrowtips", ItemID.MITHRIL_ARROWHEADS, ItemID.MITHRIL_ARROW, 45),
    BROAD("Broad arrow", "Broad arrowheads", ItemID.SLAYER_BROAD_ARROWHEAD, ItemID.SLAYER_BROAD_ARROWS, 52),
    ADAMANT("Adamant arrow", "Adamant arrowtips", ItemID.ADAMANT_ARROWHEADS, ItemID.ADAMANT_ARROW, 60),
    RUNE("Rune arrow", "Rune arrowtips", ItemID.RUNE_ARROWHEADS, ItemID.RUNE_ARROW, 75),
    AMETHYST("Amethyst arrow", "Amethyst arrowtips", ItemID.AMETHYST_ARROWHEADS, ItemID.AMETHYST_ARROW, 82),
    DRAGON("Dragon arrow", "Dragon arrowtips", ItemID.DRAGON_ARROWHEADS, ItemID.DRAGON_ARROW, 90);

    /** Shared material for every arrow recipe. */
    public static final int HEADLESS_ARROW_ID = ItemID.HEADLESS_ARROW;

    private final String label;
    private final String tipName;
    private final int tipId;
    private final int finishedId;
    private final int levelRequirement;

    /** Level gate; an unloaded level (0) passes and the next tick re-checks. */
    public boolean meetsLevelRequirement() {
        int level = Rs2Player.getRealSkillLevel(Skill.FLETCHING);
        return level <= 0 || level >= levelRequirement;
    }

    @Override
    public String toString() {
        return label;
    }
}
