package net.runelite.client.plugins.microbot.ascript.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Shared banking helpers for aScript modules.
 * <p>
 * Centralizes the grid-silent-safe banking conventions documented in
 * {@code ascript/AGENTS.md}:
 * <ul>
 *   <li>Deposit via the toolbar "Deposit inventory" button ({@link Rs2Bank#depositAll()}),
 *       never grid-targeted {@code depositAll(String/id)}.</li>
 *   <li>Verify every withdraw landed in inventory before returning, so a failed
 *       withdraw never advances to CRAFTING with an empty inventory.</li>
 * </ul>
 * New modules should call these instead of re-implementing the pattern.
 * <p>
 * All public methods accept either a display name (e.g. "chisel") or a
 * numeric id as a string (e.g. "5525" for ring mould).  When the argument is
 * purely numeric the helpers route to the {@code int}-based overloads of
 * {@link Rs2Bank} / {@link Rs2Inventory} that match by item ID, avoiding the
 * silent failure that occurs when a numeric string is used as a display-name
 * substring.
 */
@Slf4j
public final class AScriptBank {

    private static final int WITHDRAW_VERIFY_TIMEOUT_MS = 3000;

    private AScriptBank() {
        // static utility
    }

    /**
     * Withdraw all of {@code name} from the bank and verify it landed in the
     * inventory. Also handles in-bank existence check + Discord notify on miss.
     *
     * @param name item name (or numeric id as a string) to withdraw
     * @return true if withdrawn and verified in inventory; false if the bank
     *         lacks it or the withdraw didn't land (caller should return false)
     */
    public static boolean withdrawVerified(String name) {
        if (!hasBankItem(name)) {
            return false;
        }
        withdrawBankItem(name, true);
        if (!sleepUntil(() -> hasInventoryItem(name), WITHDRAW_VERIFY_TIMEOUT_MS)) {
            log.warn("[AScriptBank] Failed to withdraw {}", name);
            return false;
        }
        return true;
    }

    /**
     * Withdraw ONE of {@code name} from the bank and verify it landed in the
     * inventory. Use for tools (chisel, knife, mould, needle, ...) where exactly
     * one is needed and extras would just be re-deposited.
     *
     * @param name item name (or numeric id as a string) to withdraw
     * @return true if one was withdrawn and is now in the inventory
     */
    public static boolean withdrawOneVerified(String name) {
        if (!hasBankItem(name)) {
            return false;
        }
        withdrawBankItemOne(name);
        if (!sleepUntil(() -> hasInventoryItem(name), WITHDRAW_VERIFY_TIMEOUT_MS)) {
            log.warn("[AScriptBank] Failed to withdraw one {}", name);
            return false;
        }
        return true;
    }

    /**
     * Ensure exactly one instance of {@code toolName} is held in the inventory and
     * its inventory slot is LOCKED, so future blanket deposits never remove it.
     * <p>
     * Handles tool switching between modules (e.g. Crafting mould -> Fletching knife):
     * any currently locked slot that does NOT contain {@code toolName} is unlocked,
     * its item deposited, and then one {@code toolName} is withdrawn and its slot locked.
     * If the tool is already in the inventory, only the lock is (re)applied — no
     * extra withdraw, no deposit of unrelated items.
     * <p>
     * Slots are account-wide and persist, so this is effectively one-time setup per
     * tool; the unlock-and-deposit branch only runs when switching tools.
     *
     * @param toolName the tool to keep locked in inventory (name or numeric id as string)
     * @return true if the tool is now in the inventory with its slot locked
     */
    public static boolean ensureToolLocked(String toolName) {
        // Already holding the tool — make sure its slot is locked.
        Rs2ItemModel held = getInventoryItem(toolName);
        if (held != null) {
            if (!Rs2Bank.isLockedSlot(held.getSlot())) {
                toggleLockByItem(held);
            }
            return true;
        }

        // Tool not in inventory: release any locked slot that holds a DIFFERENT item,
        // then blanket-deposit via the toolbar button (which ignores still-locked
        // slots, so the just-unlocked stray goes back to the bank), withdraw one
        // tool, and lock its slot.
        String toolNameLc = toolName.toLowerCase();
        for (int slot : Rs2Bank.findLockedSlots()) {
            Rs2ItemModel stray = Rs2Inventory.getItemInSlot(slot);
            if (stray == null) continue;
            if (isToolMatch(stray, toolName, toolNameLc)) continue; // already the tool (race) — leave it
            Rs2Bank.toggleItemLock(stray.getName(), false); // unlock so the toolbar deposit removes it
        }
        if (!depositAll()) return false; // depositAll() button ignores locked slots; stray is now gone

        if (!withdrawOneVerified(toolName)) {
            return false;
        }
        Rs2ItemModel tool = getInventoryItem(toolName);
        if (tool != null && !Rs2Bank.isLockedSlot(tool.getSlot())) {
            toggleLockByItem(tool);
        }
        return hasInventoryItem(toolName);
    }

    /**
     * Deposit the entire inventory via the toolbar button.
     *
     * @return false if the deposit didn't complete (timeout / bank not open)
     */
    public static boolean depositAll() {
        if (!Rs2Bank.depositAll()) {
            log.warn("[AScriptBank] depositAll toolbar button timed out");
            return false;
        }
        return true;
    }

    /**
     * Deposit all, then wait for the inventory to actually empty — ignoring any
     * LOCKED slots (held tools like chisel/knife/mould stay across deposits).
     * If a tool is locked in the inventory the toolbar deposit button will not
     * remove it, so "empty" here means "no items in non-locked slots".
     *
     * @return false if deposit failed
     */
    public static boolean depositAndWaitEmpty() {
        if (!depositAll()) return false;
        sleepUntil(AScriptBank::isInventoryEmptyExceptLocks, WITHDRAW_VERIFY_TIMEOUT_MS);
        return true;
    }

    /**
     * True when the inventory holds no item in a NON-locked slot. Locked slots
     * (held tools) are ignored, so a deposit that leaves a locked tool behind
     * still counts as empty for banking purposes.
     */
    private static boolean isInventoryEmptyExceptLocks() {
        return !Rs2Inventory.items()
                .anyMatch(item -> !Rs2Bank.isLockedSlot(item.getSlot()));
    }

    // ── Numeric-id dispatch helpers ───────────────────────────
    // Rs2Bank/Rs2Inventory name-based overloads do substring match on display
    // names.  When the caller passes a numeric id-as-string (e.g. "5525" for a
    // ring mould), the name search silently fails because no item has "5525" in
    // its display name.  The helpers below detect the numeric case and route to
    // the int-based overloads instead.

    /** Returns true if {@code name} is a purely numeric string (e.g. "5525"). */
    private static boolean isNumeric(String name) {
        if (name == null || name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean hasBankItem(String nameOrId) {
        if (isNumeric(nameOrId)) {
            return Rs2Bank.hasItem(Integer.parseInt(nameOrId));
        }
        return Rs2Bank.hasItem(nameOrId);
    }

    private static void withdrawBankItem(String nameOrId, boolean checkInv) {
        if (isNumeric(nameOrId)) {
            if (checkInv && Rs2Inventory.isFull()) return;
            Rs2Bank.withdrawAll(Integer.parseInt(nameOrId));
        } else {
            Rs2Bank.withdrawAll(checkInv, nameOrId);
        }
    }

    private static void withdrawBankItemOne(String nameOrId) {
        if (isNumeric(nameOrId)) {
            Rs2Bank.withdrawOne(Integer.parseInt(nameOrId));
        } else {
            Rs2Bank.withdrawOne(nameOrId);
        }
    }

    private static boolean hasInventoryItem(String nameOrId) {
        if (isNumeric(nameOrId)) {
            return Rs2Inventory.hasItem(Integer.parseInt(nameOrId));
        }
        return Rs2Inventory.hasItem(nameOrId);
    }

    private static Rs2ItemModel getInventoryItem(String nameOrId) {
        if (isNumeric(nameOrId)) {
            return Rs2Inventory.get(Integer.parseInt(nameOrId));
        }
        return Rs2Inventory.get(nameOrId, false);
    }

    /**
     * Check if {@code stray} matches the target tool.  For numeric IDs, compare
     * the item's ID; for names, use the existing substring-on-display-name check.
     */
    private static boolean isToolMatch(Rs2ItemModel stray, String toolName, String toolNameLc) {
        if (isNumeric(toolName)) {
            return stray.getId() == Integer.parseInt(toolName);
        }
        return stray.getName() != null && stray.getName().toLowerCase().contains(toolNameLc);
    }

    /**
     * Toggle the bank-slot lock for an inventory item using its display name.
     * {@code Rs2Bank.toggleItemLock(String, boolean)} resolves by name, so
     * passing a numeric id-as-string (e.g. "5525") would fail silently.  This
     * helper uses the item model's actual display name to avoid that.
     */
    private static void toggleLockByItem(Rs2ItemModel item) {
        if (item != null && item.getName() != null) {
            Rs2Bank.toggleItemLock(item.getName(), false);
        }
    }
}
