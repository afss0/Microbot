package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Gem-tipped bolts: base bolts + gem bolt tips = tipped bolts.
 * <p>
 * One entry per valid pair, so an invalid combination cannot be selected. Dragon bolts accept
 * every gem tip except amethyst, hence the {@code DRAGON_*} entries; there is deliberately no
 * Dragon + Amethyst entry.
 * <p>
 * Only unpoisoned base bolts and unenchanted products are referenced. The noted/dummy constants
 * are not used ({@code ItemID.OPAL_BOLTS} 3818 is the noted stack; the real item is
 * {@code ItemID.OPAL_BOLT} 879), and no {@code (p)/(p+)/(p++)} or {@code (e)} variant is reachable
 * from this table.
 * <p>
 * Each action consumes 10 bolts + 10 tips and creates 10 tipped bolts; "All" repeats that over
 * the whole stack.
 */
@Getter
@RequiredArgsConstructor
public enum FletchingBoltTipType {
    OPAL("Opal bolts", "Bronze bolts", "Opal bolt tips",
            ItemID.BOLT, ItemID.OPAL_BOLTTIPS, ItemID.OPAL_BOLT, 11, 1.6f),
    JADE("Jade bolts", "Blurite bolts", "Jade bolt tips",
            ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE, ItemID.XBOWS_BOLT_TIPS_JADE,
            ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE_TIPPED_JADE, 26, 2.4f),
    PEARL("Pearl bolts", "Iron bolts", "Pearl bolt tips",
            ItemID.XBOWS_CROSSBOW_BOLTS_IRON, ItemID.PEARL_BOLTTIPS,
            ItemID.PEARL_BOLT, 41, 3.2f),
    TOPAZ("Topaz bolts", "Steel bolts", "Red topaz bolt tips",
            ItemID.XBOWS_CROSSBOW_BOLTS_STEEL, ItemID.XBOWS_BOLT_TIPS_REDTOPAZ,
            ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_TIPPED_REDTOPAZ, 48, 3.9f),
    SAPPHIRE("Sapphire bolts", "Mithril bolts", "Sapphire bolt tips",
            ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL, ItemID.XBOWS_BOLT_TIPS_SAPPHIRE,
            ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_TIPPED_SAPPHIRE, 56, 4.7f),
    EMERALD("Emerald bolts", "Mithril bolts", "Emerald bolt tips",
            ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL, ItemID.XBOWS_BOLT_TIPS_EMERALD,
            ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_TIPPED_EMERALD, 58, 5.5f),
    RUBY("Ruby bolts", "Adamant bolts", "Ruby bolt tips",
            ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE, ItemID.XBOWS_BOLT_TIPS_RUBY,
            ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_TIPPED_RUBY, 63, 6.3f),
    DIAMOND("Diamond bolts", "Adamant bolts", "Diamond bolt tips",
            ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE, ItemID.XBOWS_BOLT_TIPS_DIAMOND,
            ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_TIPPED_DIAMOND, 65, 7.0f),
    DRAGONSTONE("Dragonstone bolts", "Runite bolts", "Dragonstone bolt tips",
            ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE, ItemID.XBOWS_BOLT_TIPS_DRAGONSTONE,
            ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_TIPPED_DRAGONSTONE, 71, 8.2f),
    ONYX("Onyx bolts", "Runite bolts", "Onyx bolt tips",
            ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE, ItemID.XBOWS_BOLT_TIPS_ONYX,
            ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_TIPPED_ONYX, 73, 9.4f),
    AMETHYST_BROAD("Amethyst broad bolts", "Broad bolts", "Amethyst bolt tips",
            ItemID.SLAYER_BROAD_BOLT, ItemID.XBOWS_BOLT_TIPS_AMETHYST,
            ItemID.SLAYER_BROAD_BOLT_AMETHYST, 76, 10.6f),
    DRAGON_OPAL("Opal dragon bolts", "Dragon bolts", "Opal bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.OPAL_BOLTTIPS, ItemID.DRAGON_BOLTS_UNENCHANTED_OPAL, 84, 1.6f),
    DRAGON_JADE("Jade dragon bolts", "Dragon bolts", "Jade bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.XBOWS_BOLT_TIPS_JADE, ItemID.DRAGON_BOLTS_UNENCHANTED_JADE, 84, 2.4f),
    DRAGON_PEARL("Pearl dragon bolts", "Dragon bolts", "Pearl bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.PEARL_BOLTTIPS, ItemID.DRAGON_BOLTS_UNENCHANTED_PEARL, 84, 3.2f),
    DRAGON_TOPAZ("Topaz dragon bolts", "Dragon bolts", "Red topaz bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.XBOWS_BOLT_TIPS_REDTOPAZ, ItemID.DRAGON_BOLTS_UNENCHANTED_TOPAZ, 84, 3.9f),
    DRAGON_SAPPHIRE("Sapphire dragon bolts", "Dragon bolts", "Sapphire bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.XBOWS_BOLT_TIPS_SAPPHIRE, ItemID.DRAGON_BOLTS_UNENCHANTED_SAPPHIRE, 84, 4.7f),
    DRAGON_EMERALD("Emerald dragon bolts", "Dragon bolts", "Emerald bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.XBOWS_BOLT_TIPS_EMERALD, ItemID.DRAGON_BOLTS_UNENCHANTED_EMERALD, 84, 5.5f),
    DRAGON_RUBY("Ruby dragon bolts", "Dragon bolts", "Ruby bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.XBOWS_BOLT_TIPS_RUBY, ItemID.DRAGON_BOLTS_UNENCHANTED_RUBY, 84, 6.3f),
    DRAGON_DIAMOND("Diamond dragon bolts", "Dragon bolts", "Diamond bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.XBOWS_BOLT_TIPS_DIAMOND, ItemID.DRAGON_BOLTS_UNENCHANTED_DIAMOND, 84, 7.0f),
    DRAGON_DRAGONSTONE("Dragonstone dragon bolts", "Dragon bolts", "Dragonstone bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.XBOWS_BOLT_TIPS_DRAGONSTONE,
            ItemID.DRAGON_BOLTS_UNENCHANTED_DRAGONSTONE, 84, 8.2f),
    DRAGON_ONYX("Onyx dragon bolts", "Dragon bolts", "Onyx bolt tips",
            ItemID.DRAGON_BOLTS, ItemID.XBOWS_BOLT_TIPS_ONYX, ItemID.DRAGON_BOLTS_UNENCHANTED_ONYX, 84, 9.4f);

    /** Bolts and tips consumed per action. */
    public static final int MATERIALS_PER_ACTION = 10;

    private final String label;
    private final String boltName;
    private final String tipName;
    private final int boltId;
    private final int tipId;
    private final int productId;
    private final int levelRequirement;
    private final float xpPerBolt;

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
