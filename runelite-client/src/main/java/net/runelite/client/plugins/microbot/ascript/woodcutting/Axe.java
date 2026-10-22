package net.runelite.client.plugins.microbot.ascript.woodcutting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Wieldable axes in tier order — the bank withdrawal takes the highest tier present
 * (crystal &gt; infernal &gt; dragon &gt; rune &gt; ... &gt; bronze). Numeric ids only: a name
 * match on "axe" is a substring of everything.
 * <p>
 * The declaration order is the preference order: the best-axe pick keeps the LAST
 * entry whose id is present, so entries are grouped by tier and a later entry wins a
 * tie inside the tier (the plain axe first, ornament/2H variants after it).
 * <p>
 * {@code woodcuttingLevel} is the wiki's Woodcutting requirement (Axe → Standard,
 * Felling and Other axes tables). {@code specialAttack} marks the axes whose special
 * attack benefits Woodcutting — the module's spec block reads that set from here
 * instead of keeping a second, hand-maintained id list that could drift out of the
 * catalogue and report "wearing a spec axe" for an axe {@code hasAxe()} cannot see.
 */
@Getter
@RequiredArgsConstructor
public enum Axe {
    BRONZE(ItemID.BRONZE_AXE, 1, false),
    BRONZE_2H(ItemID.BRONZE_AXE_2H, 1, false),
    IRON(ItemID.IRON_AXE, 1, false),
    IRON_2H(ItemID.IRON_AXE_2H, 1, false),
    STEEL(ItemID.STEEL_AXE, 6, false),
    STEEL_2H(ItemID.STEEL_AXE_2H, 6, false),
    BLACK(ItemID.BLACK_AXE, 11, false),
    BLACK_2H(ItemID.BLACK_AXE_2H, 11, false),
    MITHRIL(ItemID.MITHRIL_AXE, 21, false),
    MITHRIL_2H(ItemID.MITHRIL_AXE_2H, 21, false),
    ADAMANT(ItemID.ADAMANT_AXE, 31, false),
    ADAMANT_2H(ItemID.ADAMANT_AXE_2H, 31, false),
    RUNE(ItemID.RUNE_AXE, 41, false),
    RUNE_2H(ItemID.RUNE_AXE_2H, 41, false),
    GILDED(ItemID.TRAIL_GILDED_AXE, 41, false),
    DRAGON(ItemID.DRAGON_AXE, 61, true),
    DRAGON_2H(ItemID.DRAGON_AXE_2H, 61, true),
    INFERNAL(ItemID.INFERNAL_AXE, 61, true),
    THIRD_AGE(ItemID._3A_AXE, 61, false),
    THIRD_AGE_2H(ItemID._3A_AXE_2H, 61, false),
    /** Removed from the game in 2024; kept for parity — no level requirement to use. */
    TRAILBLAZER(ItemID.TRAILBLAZER_AXE, 1, true),
    CRYSTAL(ItemID.CRYSTAL_AXE, 71, true),
    CRYSTAL_2H(ItemID.CRYSTAL_AXE_2H, 71, true);

    private final int itemId;
    private final int woodcuttingLevel;
    private final boolean specialAttack;

    /**
     * True when {@code woodcuttingLevel} is high enough to chop with this axe.
     * <p>
     * A level read of 0 means "skill data not loaded yet" (both {@code Rs2Player} level
     * helpers fall back to 0 on a timed-out client-thread read), so it is treated as
     * unknown and does NOT filter — a false "no axe" verdict is worse than attempting
     * the action on an axe the player really can use.
     *
     * @param woodcuttingLevel the player's current Woodcutting level
     * @return true when the axe is usable (or the level is unknown)
     */
    public boolean isUsableAt(int woodcuttingLevel) {
        return woodcuttingLevel <= 0 || woodcuttingLevel >= this.woodcuttingLevel;
    }

    /** Ids of the axes whose special attack benefits Woodcutting (derived, never a copy). */
    public static Set<Integer> specialAttackAxeIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Axe axe : values()) {
            if (axe.specialAttack) ids.add(axe.itemId);
        }
        return ids;
    }
}
