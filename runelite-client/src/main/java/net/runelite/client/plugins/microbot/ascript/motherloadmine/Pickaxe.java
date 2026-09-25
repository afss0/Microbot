package net.runelite.client.plugins.microbot.ascript.motherloadmine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

/**
 * Pickaxe enum — tracks item IDs and level requirements.
 * Adapted from the Hub's MotherloadMineScript Pickaxe enum.
 */
@Getter
@RequiredArgsConstructor
public enum Pickaxe {
    BRONZE_PICKAXE("bronze pickaxe", ItemID.BRONZE_PICKAXE, 1, 1),
    IRON_PICKAXE("iron pickaxe", ItemID.IRON_PICKAXE, 1, 1),
    STEEL_PICKAXE("steel pickaxe", ItemID.STEEL_PICKAXE, 6, 5),
    BLACK_PICKAXE("black pickaxe", ItemID.BLACK_PICKAXE, 6, 10),
    MITHRIL_PICKAXE("mithril pickaxe", ItemID.MITHRIL_PICKAXE, 21, 20),
    ADAMANT_PICKAXE("adamant pickaxe", ItemID.ADAMANT_PICKAXE, 31, 30),
    RUNE_PICKAXE("rune pickaxe", ItemID.RUNE_PICKAXE, 41, 40),
    GILDED_PICKAXE("gilded pickaxe", ItemID.TRAIL_GILDED_PICKAXE, 41, 40),
    DRAGON_PICKAXE("dragon pickaxe", ItemID.DRAGON_PICKAXE, 61, 60),
    INFERNAL_PICKAXE("infernal pickaxe", ItemID.INFERNAL_PICKAXE, 61, 60),
    CRYSTAL_PICKAXE("crystal pickaxe", ItemID.CRYSTAL_PICKAXE, 71, 70);

    private final String itemName;
    private final int itemId;
    private final int miningLevel;
    private final int attackLevel;

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
        int miningLevel = Microbot.getClient().getBoostedSkillLevel(net.runelite.api.Skill.MINING);
        Pickaxe best = null;
        for (Pickaxe p : values()) {
            if (p.hasItem() && miningLevel >= p.getMiningLevel()) {
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
        int miningLevel = Microbot.getClient().getBoostedSkillLevel(net.runelite.api.Skill.MINING);
        Pickaxe best = null;
        for (Pickaxe p : values()) {
            if (Rs2Bank.hasItem(p.getItemId()) && miningLevel >= p.getMiningLevel()) {
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
     * Check if any pickaxe is available in the bank.
     */
    public static boolean hasAnyPickaxeInBank() {
        for (Pickaxe p : values()) {
            if (Rs2Bank.hasItem(p.getItemId())) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return itemName;
    }
}
