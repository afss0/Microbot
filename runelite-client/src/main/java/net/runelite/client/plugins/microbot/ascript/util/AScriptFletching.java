package net.runelite.client.plugins.microbot.ascript.util;

import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

/**
 * Item-resolution helpers for the aScript Fletching module.
 * <p>
 * Fletching materials are resolved by <b>id</b> only. Display names are never used to decide
 * whether an item is present or which item to click — they are only for logs, stop messages and
 * the production-interface row match. Names therefore come from
 * {@link ItemComposition#getMembersName()} (the real name, without the F2P " (Members)" suffix)
 * at runtime, never from a hand-maintained string table.
 * <p>
 * This class is aScript-owned on purpose: the shared {@code microbot/util/skills/fletching}
 * package stays untouched, and the module does not use its name-based helpers.
 */
public final class AScriptFletching {

    private AScriptFletching() {
    }

    /**
     * The item's real display name, or {@code fallback} when the cache composition is not
     * available (skill data still loading / client-thread read timed out).
     */
    public static String itemName(int itemId, String fallback) {
        if (itemId <= 0) {
            return fallback;
        }
        ItemComposition composition = Microbot.getRs2ItemManager().getItemComposition(itemId);
        if (composition == null) {
            return fallback;
        }
        String name = composition.getMembersName();
        return name == null || name.isEmpty() ? fallback : name;
    }

    /**
     * Units of {@code itemId} in the inventory, stacked entries summed by quantity and
     * banknotes excluded — a noted stack is not material the module can fletch with.
     */
    public static int inventoryQuantity(int itemId) {
        if (itemId <= 0) {
            return 0;
        }
        return Rs2Inventory.items()
                .filter(item -> item != null && !item.isNoted() && item.getId() == itemId)
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();
    }

    /** True when the inventory holds at least one unnoted instance of {@code itemId}. */
    public static boolean hasUnnoted(int itemId) {
        return inventoryQuantity(itemId) > 0;
    }

    /** True when the inventory holds a knife usable for fletching (plain or fletching knife). */
    public static boolean hasKnife() {
        return Rs2Inventory.hasItem(ItemID.KNIFE) || Rs2Inventory.hasItem(ItemID.FLETCHING_KNIFE);
    }

    /** The held knife id, or {@code -1} when no knife is held. */
    public static int heldKnifeId() {
        if (Rs2Inventory.hasItem(ItemID.KNIFE)) {
            return ItemID.KNIFE;
        }
        if (Rs2Inventory.hasItem(ItemID.FLETCHING_KNIFE)) {
            return ItemID.FLETCHING_KNIFE;
        }
        return -1;
    }

    /** True when the bank holds a knife the log activities can withdraw. */
    public static boolean bankHasKnife() {
        return Rs2Bank.hasBankItem(ItemID.KNIFE, 1)
                || Rs2Bank.hasBankItem(ItemID.FLETCHING_KNIFE, 1);
    }

    /**
     * True when the inventory + bank can supply {@code amount} units of {@code itemId} for a
     * bank cycle. Unnoted inventory, <b>noted</b> inventory (a banknote is a certificate for the
     * same base item; depositing it restores the base item in the bank) and the bank all count.
     * This is the missing-materials check; {@link #inventoryQuantity} stays unnoted-only because
     * notes cannot be fletched with.
     */
    public static boolean combinedAtLeast(int itemId, int amount) {
        return AScriptBank.bankableAtLeast(itemId, amount);
    }
}
