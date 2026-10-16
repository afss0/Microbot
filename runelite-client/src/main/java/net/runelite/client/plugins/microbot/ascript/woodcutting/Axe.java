package net.runelite.client.plugins.microbot.ascript.woodcutting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * Wieldable axes in tier order — the bank withdrawal takes the highest tier present
 * (crystal > infernal > dragon > rune > ... > bronze). Numeric ids only: a name match
 * on "axe" is a substring of everything.
 */
@Getter
@RequiredArgsConstructor
public enum Axe {
    BRONZE(ItemID.BRONZE_AXE),
    IRON(ItemID.IRON_AXE),
    STEEL(ItemID.STEEL_AXE),
    BLACK(ItemID.BLACK_AXE),
    MITHRIL(ItemID.MITHRIL_AXE),
    ADAMANT(ItemID.ADAMANT_AXE),
    RUNE(ItemID.RUNE_AXE),
    DRAGON(ItemID.DRAGON_AXE),
    INFERNAL(ItemID.INFERNAL_AXE),
    CRYSTAL(ItemID.CRYSTAL_AXE);

    private final int itemId;
}
