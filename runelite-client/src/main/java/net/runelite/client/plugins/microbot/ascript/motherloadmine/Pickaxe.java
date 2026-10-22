package net.runelite.client.plugins.microbot.ascript.motherloadmine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Pickaxe enum — tracks item IDs and level requirements.
 * Adapted from the Hub's MotherloadMineScript Pickaxe enum.
 * <p>
 * Levels are the wiki's Mining / Attack requirements (Pickaxe → Standard and Other
 * pickaxes tables). Every wieldable-mining pickaxe is listed, ornament-kit and 3rd-age
 * variants included, so a player holding one is never reported as having no pickaxe.
 * The obsolete {@code *_EMPTY} (uncharged infernal) and {@code *_INACTIVE} (crystal out
 * of charges) ids are deliberately absent: those cannot mine until recharged.
 */
@Getter
@RequiredArgsConstructor
public enum Pickaxe {
    BRONZE_PICKAXE("bronze pickaxe", ItemID.BRONZE_PICKAXE, 1, 1),
    IRON_PICKAXE("iron pickaxe", ItemID.IRON_PICKAXE, 1, 1),
    STEEL_PICKAXE("steel pickaxe", ItemID.STEEL_PICKAXE, 6, 5),
    BLACK_PICKAXE("black pickaxe", ItemID.BLACK_PICKAXE, 11, 10),
    MITHRIL_PICKAXE("mithril pickaxe", ItemID.MITHRIL_PICKAXE, 21, 20),
    ADAMANT_PICKAXE("adamant pickaxe", ItemID.ADAMANT_PICKAXE, 31, 30),
    RUNE_PICKAXE("rune pickaxe", ItemID.RUNE_PICKAXE, 41, 40),
    GILDED_PICKAXE("gilded pickaxe", ItemID.TRAIL_GILDED_PICKAXE, 41, 40),
    DRAGON_PICKAXE("dragon pickaxe", ItemID.DRAGON_PICKAXE, 61, 60),
    /** "Dragon pickaxe (upgraded)" — the ornament-kit look, same stats as the dragon pickaxe. */
    DRAGON_PICKAXE_UPGRADED("dragon pickaxe (upgraded)", ItemID.DRAGON_PICKAXE_PRETTY, 61, 60),
    INFERNAL_PICKAXE("infernal pickaxe", ItemID.INFERNAL_PICKAXE, 61, 60),
    THIRD_AGE_PICKAXE("3rd age pickaxe", ItemID._3A_PICKAXE, 61, 65),
    CRYSTAL_PICKAXE("crystal pickaxe", ItemID.CRYSTAL_PICKAXE, 71, 70);

    private final String itemName;
    private final int itemId;
    private final int miningLevel;
    private final int attackLevel;

    /**
     * True when {@code miningLevel} is high enough to mine with this pickaxe.
     * <p>
     * A level read of 0 means "skill data not loaded yet" (both {@code Rs2Player} level
     * helpers fall back to 0 on a timed-out client-thread read), so it is treated as
     * unknown and does NOT filter — a false "no pickaxe" verdict is worse than
     * attempting the action.
     *
     * @param miningLevel the player's current Mining level
     * @return true when the pickaxe is usable (or the level is unknown)
     */
    public boolean isUsableAt(int miningLevel) {
        return miningLevel <= 0 || miningLevel >= this.miningLevel;
    }

    /**
     * Check if a pickaxe of this type is in the player's inventory or equipped.
     */
    public boolean hasItem() {
        return Rs2Inventory.hasItem(itemId) || Rs2Equipment.isWearing(itemId);
    }

    /**
     * Get the best pickaxe the player currently has (equipped or in inventory),
     * based on mining level requirement. Returns null if none found.
     */
    public static Pickaxe getBestPickaxe() {
        int miningLevel = Rs2Player.getBoostedSkillLevel(Skill.MINING);
        Pickaxe best = null;
        for (Pickaxe p : values()) {
            if (p.hasItem() && p.isUsableAt(miningLevel)) {
                if (best == null || p.getMiningLevel() > best.getMiningLevel()) {
                    best = p;
                }
            }
        }
        return best;
    }

    /**
     * Get the best pickaxe available in the bank, based on mining level requirement.
     * Returns null if none found.
     */
    public static Pickaxe getBestPickaxeFromBank() {
        int miningLevel = Rs2Player.getBoostedSkillLevel(Skill.MINING);
        Pickaxe best = null;
        for (Pickaxe p : values()) {
            if (Rs2Bank.hasBankItem(p.getItemId(), 1) && p.isUsableAt(miningLevel)) {
                if (best == null || p.getMiningLevel() > best.getMiningLevel()) {
                    best = p;
                }
            }
        }
        return best;
    }

    /**
     * Check if any pickaxe is in the player's inventory or equipped.
     */
    public static boolean hasAnyPickaxe() {
        for (Pickaxe p : values()) {
            if (p.hasItem()) return true;
        }
        return false;
    }

    /**
     * Check if any pickaxe is available in the bank. The read is the retrying
     * {@code Rs2Bank.hasBankItem} family: this feeds the module's missing-materials
     * verdict, and the bare mirror read can report a pickaxe the bank no longer lists.
     */
    public static boolean hasAnyPickaxeInBank() {
        for (Pickaxe p : values()) {
            if (Rs2Bank.hasBankItem(p.getItemId(), 1)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return itemName;
    }
}
