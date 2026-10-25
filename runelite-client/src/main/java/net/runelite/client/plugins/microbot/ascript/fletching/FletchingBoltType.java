package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Unfinished bolts + feather = finished bolts.
 * <p>
 * The unfinished names are the current cache names ({@code getMembersName()}): note that
 * adamant is <b>"Adamant bolts(unf)"</b> with no space before the parenthesis, one of the
 * reasons material lookups must never be name-based. The noted/placeholder constants are not
 * used; the real silver item is {@code ItemID.XBOWS_CROSSBOW_BOLTS_SILVER} (9145), not a
 * plural "SILVER_BOLTS" stack constant.
 * <p>
 * Broad bolts also require the "Broader fletching" Slayer unlock; the module cannot check
 * that, so the game simply rejects the action if it is not unlocked.
 */
@Getter
@RequiredArgsConstructor
public enum FletchingBoltType {
    BRONZE("Bronze bolts", "Bronze bolts (unf)",
            ItemID.XBOWS_CROSSBOW_BOLTS_BRONZE_UNFEATHERED, ItemID.BOLT, 9),
    BLURITE("Blurite bolts", "Blurite bolts (unf)",
            ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE, 24),
    SILVER("Silver bolts", "Silver bolts (unf)",
            ItemID.XBOWS_CROSSBOW_BOLTS_SILVER_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_SILVER, 43),
    IRON("Iron bolts", "Iron bolts (unf)",
            ItemID.XBOWS_CROSSBOW_BOLTS_IRON_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_IRON, 39),
    STEEL("Steel bolts", "Steel bolts (unf)",
            ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_STEEL, 46),
    MITHRIL("Mithril bolts", "Mithril bolts (unf)",
            ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL, 54),
    BROAD("Broad bolts", "Unfinished broad bolts",
            ItemID.SLAYER_BROAD_BOLT_UNFINISHED, ItemID.SLAYER_BROAD_BOLT, 55),
    ADAMANT("Adamant bolts", "Adamant bolts(unf)",
            ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE, 61),
    RUNITE("Runite bolts", "Runite bolts (unf)",
            ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE, 69),
    DRAGON("Dragon bolts", "Dragon bolts (unf)",
            ItemID.DRAGON_BOLTS_UNFEATHERED, ItemID.DRAGON_BOLTS, 84);

    private final String label;
    private final String unfinishedName;
    private final int unfinishedId;
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
