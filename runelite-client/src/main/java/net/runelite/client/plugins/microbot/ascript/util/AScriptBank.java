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
        if (!Rs2Bank.hasItem(name)) {
            return false;
        }
        Rs2Bank.withdrawAll(true, name);
        if (!sleepUntil(() -> Rs2Inventory.hasItem(name), WITHDRAW_VERIFY_TIMEOUT_MS)) {
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
        if (!Rs2Bank.hasItem(name)) {
            return false;
        }
        Rs2Bank.withdrawOne(name);
        if (!sleepUntil(() -> Rs2Inventory.hasItem(name), WITHDRAW_VERIFY_TIMEOUT_MS)) {
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
        Rs2ItemModel held = Rs2Inventory.get(toolName, false);
        if (held != null) {
            if (!Rs2Bank.isLockedSlot(held.getSlot())) {
                Rs2Bank.toggleItemLock(toolName, false);
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
            if (stray.getName() != null && stray.getName().toLowerCase().contains(toolNameLc)) continue; // already the tool (race) — leave it
            Rs2Bank.toggleItemLock(stray.getName(), false); // unlock so the toolbar deposit removes it
        }
        if (!depositAll()) return false; // depositAll() button ignores locked slots; stray is now gone

        if (!withdrawOneVerified(toolName)) {
            return false;
        }
        Rs2ItemModel tool = Rs2Inventory.get(toolName, false);
        if (tool != null && !Rs2Bank.isLockedSlot(tool.getSlot())) {
            Rs2Bank.toggleItemLock(toolName, false);
        }
        return Rs2Inventory.hasItem(toolName);
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
     * Deposit all, then wait for the inventory to actually empty.
     *
     * @return false if deposit failed or inventory didn't empty in time
     */
    public static boolean depositAndWaitEmpty() {
        if (!depositAll()) return false;
        sleepUntil(() -> Rs2Inventory.isEmpty(), WITHDRAW_VERIFY_TIMEOUT_MS);
        return true;
    }
}
