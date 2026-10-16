package net.runelite.client.plugins.microbot.ascript.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.TileObject;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

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

    /**
     * Extra verification window granted after the main verify miss: a landing that is merely
     * late (transient lag) is confirmed here before the attempt counts as failed. Observed
     * live: a withdraw click whose items landed ~3-6 s after dispatch during a lag spike.
     */
    private static final int WITHDRAW_GRACE_TIMEOUT_MS = 2000;

    /** Total dispatch attempts for one withdraw call (1 initial + retries with backoff). */
    private static final int WITHDRAW_MAX_ATTEMPTS = 3;

    /**
     * Exponential backoff base between failed attempts when an attempt is unconfirmed:
     * attempt 2 waits ~800 ms, attempt 3 ~1600 ms (jittered and weather-modulated via
     * {@link Rs2Random#waitEx(double, double)}).
     */
    private static final int WITHDRAW_RETRY_BACKOFF_BASE_MS = 800;

    private AScriptBank() {
        // static utility
    }

    /**
     * Withdraw {@code quantity} of {@code name} from the bank and verify it landed
     * in the inventory. Use for stackable items where a specific amount is needed
     * (e.g. 14 unstrung bows + 14 bow strings for stringing).
     * <p>
     * The verify runs in two windows (main + grace) and an unconfirmed dispatch is
     * retried with exponential backoff — see {@link #withdrawWithRetry}.
     *
     * @param name item name (or numeric id as a string) to withdraw
     * @param quantity exact number to withdraw
     * @return true if withdrawn and verified in inventory; false on failure
     */
    public static boolean withdrawVerified(String name, int quantity) {
        return withdrawWithRetry(name, () -> withdrawBankItemX(name, quantity),
                name + " x" + quantity);
    }

    /**
     * Withdraw all of {@code name} from the bank and verify it landed in the
     * inventory. Handles the in-bank existence check; the caller decides whether
     * to send a Discord notification when {@code false} is returned.
     *
     * @param name item name (or numeric id as a string) to withdraw
     * @return true if withdrawn and verified in inventory; false if the bank
     *         lacks it or the withdraw didn't land (caller should return false)
     */
    public static boolean withdrawVerified(String name) {
        return withdrawWithRetry(name, () -> withdrawBankItem(name, true), name);
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
        return withdrawWithRetry(name, () -> withdrawBankItemOne(name), "one " + name);
    }

    /**
     * Shared withdraw core: dispatch, verify, grace re-check, retry with exponential backoff.
     * <p>
     * Each attempt verifies in two windows — the main {@link #WITHDRAW_VERIFY_TIMEOUT_MS}
     * window, then a {@link #WITHDRAW_GRACE_TIMEOUT_MS} grace window that confirms merely-late
     * landings (transient lag) before the attempt counts as failed. Unconfirmed attempts are
     * retried up to {@link #WITHDRAW_MAX_ATTEMPTS} total dispatches, spaced by an exponential
     * backoff.
     * <p>
     * A retry waits the backoff FIRST and re-checks the inventory BEFORE re-dispatching, so a
     * slow landing in flight is confirmed instead of double-clicked. The bank window is left
     * exactly as it is across attempts — the loop never closes or re-opens it (typical
     * scripting behavior); the caller keeps the window open for the whole call.
     *
     * @param nameOrId item name (or numeric id as a string) to withdraw
     * @param dispatch performs one withdraw dispatch and reports whether it had an item to click
     *                 (the retry loop owns the verification)
     * @param describe label for the log lines (e.g. "bow string x14", "one mould")
     * @return true once the item is verified in the inventory; false when every attempt
     *         failed or the bank lacks the item
     */
    private static boolean withdrawWithRetry(String nameOrId, BooleanSupplier dispatch, String describe) {
        if (!hasBankItem(nameOrId)) {
            return false; // bank lacks it — nothing to dispatch (silent, as before)
        }
        for (int attempt = 1; attempt <= WITHDRAW_MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                // Exponential backoff, jittered: give a pending landing time to arrive
                // BEFORE re-checking, so an in-flight withdraw is never double-dispatched.
                long backoffMs = (long) WITHDRAW_RETRY_BACKOFF_BASE_MS << (attempt - 2);
                Rs2Random.waitEx(backoffMs, backoffMs / 4.0);
                if (hasInventoryItem(nameOrId)) {
                    log.debug("[AScriptBank] Withdraw {} landed during backoff — not re-dispatching", describe);
                    return true;
                }
            }
            // Re-resolve per attempt. The dispatch resolves the item to click from the bank
            // mirror and the click geometry from the live widget at that slot, so a bank list
            // that re-sorted (a deposit) or a mirror that never refreshed leaves the click with
            // nothing to land on. Record both: whether the dispatch had anything to click, and
            // whether the mirror advanced during the attempt (a mirror that never advanced
            // cannot have changed its answer — retrying it is futile, and that is worth saying).
            int epochBefore = Rs2Bank.getBankLiveEpoch();
            boolean dispatched = dispatch.getAsBoolean();
            if (sleepUntil(() -> hasInventoryItem(nameOrId), WITHDRAW_VERIFY_TIMEOUT_MS)) {
                return true;
            }
            // Grace re-check: the observed failure mode is a landing that takes longer than
            // the verify window under transient lag — confirm it before failing the attempt.
            if (sleepUntil(() -> hasInventoryItem(nameOrId), WITHDRAW_GRACE_TIMEOUT_MS)) {
                log.debug("[AScriptBank] Withdraw {} landed during the grace window (late landing)", describe);
                return true;
            }
            if (attempt < WITHDRAW_MAX_ATTEMPTS) {
                log.debug("[AScriptBank] Withdraw {} not confirmed (attempt {}/{}): hadItemToClick={},"
                                + " bankEpoch {}->{}, {}",
                        describe, attempt, WITHDRAW_MAX_ATTEMPTS, dispatched, epochBefore,
                        Rs2Bank.getBankLiveEpoch(), resolution(nameOrId));
            }
        }
        log.warn("[AScriptBank] Failed to withdraw {} after {} attempts [{}]{}", describe, WITHDRAW_MAX_ATTEMPTS,
                resolution(nameOrId),
                Rs2Bank.getLastWithdrawMiss() == null ? "" : " — " + Rs2Bank.getLastWithdrawMiss());
        return false;
    }

    /**
     * One-line state of the item at a failed withdraw: what the bank mirror resolves, whether that
     * slot has a clickable widget, and (via the epoch the caller logs) whether the mirror could
     * have changed at all. Diagnostic only — never throws.
     */
    private static String resolution(String nameOrId) {
        try {
            if (isNumeric(nameOrId)) {
                return "bank mirror has id " + nameOrId + ": " + Rs2Bank.hasItem(Integer.parseInt(nameOrId));
            }
            Rs2ItemModel item = Rs2Bank.findBankItem(nameOrId);
            if (item == null) {
                return "bank mirror resolves no '" + nameOrId + "'";
            }
            return "bank mirror slot " + item.getSlot() + ", clickable widget: "
                    + (Rs2Bank.getItemWidget(item.getSlot()) != null);
        } catch (RuntimeException ex) {
            return "resolution unavailable (" + ex.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Ensure exactly one instance of {@code toolName} is held in the inventory and
     * its inventory slot is LOCKED, so future blanket deposits never remove it.
     * <p>
     * Handles tool switching between modules (e.g. Crafting mould -> Fletching knife):
     * any currently locked slot that does NOT contain {@code toolName} and is not in
     * {@code keepLocked} is unlocked, its item deposited, and then one {@code toolName}
     * is withdrawn and its slot locked.
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
        return ensureLocked(toolName, false, Collections.emptyList());
    }

    /**
     * {@link #ensureToolLocked(String)} with a keep-list: locked slots matching any entry
     * of {@code keepLocked} are owned by the caller's current bank cycle and are never
     * released as strays. Pass every item the cycle is acquiring — without it, the second
     * ensure call sees the lock the first one just established as a "different item" and
     * unlocks + deposits it (the rod/feathers ping-pong observed live 2026-10-10).
     *
     * @param toolName the tool to keep locked in inventory
     * @param keepLocked items never treated as strays (same match rules as the call itself)
     * @return true if the tool is now in the inventory with its slot locked
     */
    public static boolean ensureToolLocked(String toolName, Collection<String> keepLocked) {
        return ensureLocked(toolName, false, keepLocked);
    }

    /**
     * Ensure {@code nameOrId} is held in the inventory as a LOCKED slot — the
     * stackable-consumable variant of {@link #ensureToolLocked(String)}.
     * <p>
     * Same contract, except that a missing item is withdrawn WHOLE
     * ({@link #withdrawVerified(String)}) instead of one at a time: use it for
     * consumables that must survive the toolbar deposit as a persistent
     * inventory anchor (e.g. cosmic runes for Jewel Enchant). Withdrawing a
     * single one would be useless there — every cast consumes one, so the module
     * would bank again immediately. Tool switching is identical to
     * {@link #ensureToolLocked(String)}: a locked slot holding a DIFFERENT item
     * (and not in the caller's keep-list) is unlocked so the toolbar deposit
     * returns that stray to the bank.
     * <p>
     * A locked stack is still consumed by use: once the stack empties the slot
     * (and with it the lock) is gone, and the next bank cycle withdraws and locks
     * a fresh stack.
     *
     * @param nameOrId the item to keep locked in inventory (name or numeric id as string)
     * @return true if the item is now in the inventory with its slot locked
     */
    public static boolean ensureStackLocked(String nameOrId) {
        return ensureLocked(nameOrId, true, Collections.emptyList());
    }

    /**
     * {@link #ensureStackLocked(String)} with the caller-owned keep-list — see
     * {@link #ensureToolLocked(String, Collection)}.
     *
     * @param nameOrId the item to keep locked in inventory (name or numeric id as string)
     * @param keepLocked items never treated as strays (same match rules as the call itself)
     * @return true if the item is now in the inventory with its slot locked
     */
    public static boolean ensureStackLocked(String nameOrId, Collection<String> keepLocked) {
        return ensureLocked(nameOrId, true, keepLocked);
    }

    private static boolean ensureLocked(String nameOrId, boolean withdrawWholeStack,
            Collection<String> keepLocked) {
        // Already holding the item — make sure its slot is locked.
        Rs2ItemModel held = getInventoryItem(nameOrId);
        if (held != null) {
            if (!Rs2Bank.isLockedSlot(held.getSlot())) {
                toggleLockByItem(held);
            }
            return true;
        }

        // Item not in inventory: release any locked slot that holds a DIFFERENT item
        // (and is not in the caller's keep-list — those belong to this same bank cycle),
        // then blanket-deposit via the toolbar button (which ignores still-locked
        // slots, so the just-unlocked stray goes back to the bank), withdraw the
        // item, and lock its slot.
        String nameLc = nameOrId.toLowerCase();
        for (int slot : Rs2Bank.findLockedSlots()) {
            Rs2ItemModel stray = Rs2Inventory.getItemInSlot(slot);
            if (stray == null) continue;
            if (stray.getId() == ItemID.LAMP) continue; // never release the lamp — it must survive deposits
            if (isToolMatch(stray, nameOrId, nameLc)) continue; // already the item (race) — leave it
            if (isKeptLocked(stray, keepLocked)) continue; // owned by the caller's cycle — never a stray
            Rs2Bank.toggleItemLock(stray.getName(), false); // unlock so the toolbar deposit removes it
        }
        if (!depositAll()) return false; // depositAll() button ignores locked slots; stray is now gone

        boolean withdrew = withdrawWholeStack
                ? withdrawVerified(nameOrId)
                : withdrawOneVerified(nameOrId);
        if (!withdrew) {
            return false;
        }
        Rs2ItemModel item = getInventoryItem(nameOrId);
        if (item != null && !Rs2Bank.isLockedSlot(item.getSlot())) {
            toggleLockByItem(item);
        }
        return hasInventoryItem(nameOrId);
    }

    /**
     * Deposit the entire inventory via the toolbar button.
     * <p>
     * Before anything else, ensures the XP lamp's slot is LOCKED (see
     * {@link #ensureLampLocked()}) — the lamp cannot be deposited and must survive to be
     * used; an unlocked lamp would also make every "inventory empty" check fail.
     * <p>
     * When the bank is open and the inventory only contains items in LOCKED
     * slots (held tools/anchors) and/or the XP lamp, the deposit is skipped and
     * {@code true} is returned immediately — the toolbar button ignores locked
     * slots, so clicking it would be a no-op that triggers a 10-second
     * {@code waitForInventoryChanges} timeout in {@code Rs2Bank.depositAll()}.
     *
     * @return true if the inventory is already empty (or only has locked items and the
     *         lamp); false if the deposit didn't complete (timeout / bank not open)
     */
    public static boolean depositAll() {
        if (Rs2Bank.isOpen()) {
            ensureLampLocked();
            if (isEmptyExceptLocksAndLamp()) {
                log.debug("[AScriptBank] skipping deposit — only locked items and/or the XP lamp in inventory");
                return true;
            }
        }
        if (!Rs2Bank.depositAll()) {
            log.warn("[AScriptBank] depositAll toolbar button timed out");
            return false;
        }
        return true;
    }

    /**
     * Ensure the XP lamp's inventory slot is locked before a deposit. The toolbar deposit
     * cannot remove a lamp (it is not deposit-able), so like a held tool it must be made
     * lock-protected: locking makes the deposit skip it natively, and the bank-view lock
     * clears itself once the lamp leaves the slot. Same mechanism the modules use for the
     * mould/cosmic rune. Requires the bank to be open — no-op otherwise.
     */
    private static void ensureLampLocked() {
        if (!Rs2Bank.isOpen()) {
            return;
        }
        Rs2ItemModel lamp = Rs2Inventory.get(ItemID.LAMP);
        if (lamp == null || Rs2Bank.isLockedSlot(lamp.getSlot())) {
            return;
        }
        if (!Rs2Bank.toggleItemLock(lamp.getName(), true)) { // exact name — never match "Empty Oil Lamp"
            log.warn("[AScriptBank] Failed to lock the lamp slot — the deposit may stall on it");
        }
    }

    /**
     * Deposit all, then wait for the inventory to actually empty — ignoring any
     * LOCKED slots (held tools like chisel/knife/mould stay across deposits) and the
     * XP lamp (which cannot be deposited at all). "Empty" here means "no items in
     * non-locked slots other than the lamp".
     *
     * @return false if the deposit failed or the inventory did not empty in
     *         time (e.g. silent partial deposit) — callers should retry next tick
     */
    public static boolean depositAndWaitEmpty() {
        if (!depositAll()) {
            // depositAll() may return false when the inventory only contains
            // LOCKED items and/or the lamp (e.g. a locked mould) — the toolbar
            // deposit button ignores locked slots, so nothing changes and
            // waitForInventoryChanges times out.  Check if we're already in the
            // desired state.
            if (!isEmptyExceptLocksAndLamp()) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the inventory holds no item in a NON-locked slot other than the XP lamp.
     * Locked slots (held tools) and the lamp are both ignored: the toolbar deposit cannot
     * remove either — the lamp cannot be banked at all — so a deposit that leaves them
     * behind still counts as empty for banking purposes.
     * <p>
     * Public for modules that need to wait on the deposit landing without stalling on a
     * lamp (e.g. Motherload Mine's post-deposit wait).
     */
    public static boolean isEmptyExceptLocksAndLamp() {
        return !Rs2Inventory.items()
                .anyMatch(item -> !Rs2Bank.isLockedSlot(item.getSlot()) && item.getId() != ItemID.LAMP);
    }

    /**
     * Deposit every inventory item whose id is NOT in {@code keepIds} — the
     * contained-inventory sweep for bank cycles that must end holding exactly the
     * run's own materials (Jewel Enchant, 2026-10-16: a staff equip displaces the
     * previously worn item into the inventory, where it held a slot for the whole
     * batch — for good when the run stopped before the next clean bank cycle — and
     * left an extraneous clickable under the enchant click).
     * <p>
     * Mechanism: the toolbar "Deposit inventory" button empties every UNLOCKED slot,
     * so the keepers are locked for the duration (slots already locked stay as found)
     * and unlocked again afterwards; the strays then leave through the same toolbar
     * path the rest of this class uses. Never the grid-targeted
     * {@code Rs2Bank.depositAll(name/id)} — it dies silently on some machines (see
     * {@link #ensureLocked}). Locked slots and the XP lamp are never touched.
     *
     * @param keepIds item ids that must survive the sweep
     * @return true when no stray remains (no-op true when the inventory is already
     *         clean); false when the bank is closed, a keeper lock could not be
     *         established, or the strays were still present after the deposit
     */
    public static boolean depositAllExcept(Collection<Integer> keepIds) {
        if (!Rs2Bank.isOpen()) {
            log.warn("[AScriptBank] depositAllExcept needs the open bank");
            return false;
        }
        List<Rs2ItemModel> strays = new ArrayList<>();
        List<Rs2ItemModel> keepers = new ArrayList<>();
        for (Rs2ItemModel item : Rs2Inventory.items().filter(Objects::nonNull).collect(Collectors.toList())) {
            if (Rs2Bank.isLockedSlot(item.getSlot())) continue; // anchors stay as found
            if (item.getId() == ItemID.LAMP) continue;          // cannot be deposited at all
            if (keepIds != null && keepIds.contains(item.getId())) {
                keepers.add(item);
            } else {
                strays.add(item);
            }
        }
        if (strays.isEmpty()) {
            return true;
        }

        // Lock the keepers so the toolbar deposit spares them. A keeper that cannot be
        // locked aborts the sweep with nothing deposited — better a retry next cycle
        // than a deposit with a gap in the keep-set.
        List<Rs2ItemModel> lockedNow = new ArrayList<>();
        for (Rs2ItemModel keeper : keepers) {
            if (!Rs2Bank.toggleItemLock(keeper.getName(), true)) {
                for (Rs2ItemModel locked : lockedNow) {
                    Rs2Bank.toggleItemLock(locked.getName(), false);
                }
                log.warn("[AScriptBank] could not lock keeper {} — sweep aborted, nothing deposited",
                        keeper.getName());
                return false;
            }
            lockedNow.add(keeper);
        }

        depositAll(); // toolbar button ignores locked slots; locks the lamp if needed

        // Verify by invariant, not by the button's return: no stray id may remain in a
        // non-locked slot.
        boolean clean = sleepUntil(() -> {
            for (Rs2ItemModel stray : strays) {
                Rs2ItemModel again = Rs2Inventory.get(stray.getId());
                if (again != null && !Rs2Bank.isLockedSlot(again.getSlot())) {
                    return false;
                }
            }
            return true;
        }, 3000);

        // Always release the keeper locks this call established.
        for (Rs2ItemModel locked : lockedNow) {
            if (Rs2Bank.isLockedSlot(locked.getSlot())) {
                Rs2Bank.toggleItemLock(locked.getName(), false);
            }
        }

        if (!clean) {
            log.warn("[AScriptBank] depositAllExcept: strays still present after the deposit");
        }
        return clean;
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

    /**
     * Returns true if an unnoted (not banknote) item matching {@code name} exists
     * in the inventory.
     * <p>
     * In OSRS, noted and unnoted items share the same display name, so
     * {@link Rs2Inventory#hasItem(String)} alone cannot distinguish them.
     * This method excludes noted items so callers know there are actual
     * usable materials — not banknotes that cannot be fletched/crafted.
     *
     * @param name the item name to match (substring, case-insensitive)
     * @return true if a matching unnoted item is found
     */
    public static boolean hasUnnotedItem(String name) {
        return Rs2Inventory.items().anyMatch(item ->
                !item.isNoted() && item.getName() != null
                        && item.getName().toLowerCase().contains(name.toLowerCase()));
    }

    /**
     * Bank presence check used by every withdraw pre-check. Uses the retrying
     * {@link Rs2Bank#hasBankItem} family, never the bare {@link Rs2Bank#hasItem} mirror
     * read: the mirror is refreshed only by bank container events, so one stale read
     * must not fail a withdraw outright — and must not let a dispatch go out against an
     * item the bank no longer lists (that produced "Failed to withdraw ... after 3
     * attempts" with no item click at all).
     */
    private static boolean hasBankItem(String nameOrId) {
        if (isNumeric(nameOrId)) {
            return Rs2Bank.hasBankItem(Integer.parseInt(nameOrId), 1);
        }
        return Rs2Bank.hasBankItem(nameOrId, 1);
    }

    private static boolean withdrawBankItem(String nameOrId, boolean checkInv) {
        if (isNumeric(nameOrId)) {
            if (checkInv && Rs2Inventory.isFull()) return false;
            return Rs2Bank.withdrawAll(Integer.parseInt(nameOrId));
        }
        return Rs2Bank.withdrawAll(checkInv, nameOrId);
    }

    private static boolean withdrawBankItemOne(String nameOrId) {
        if (isNumeric(nameOrId)) {
            return Rs2Bank.withdrawOne(Integer.parseInt(nameOrId));
        }
        return Rs2Bank.withdrawOne(nameOrId);
    }

    private static boolean withdrawBankItemX(String nameOrId, int quantity) {
        if (isNumeric(nameOrId)) {
            return Rs2Bank.withdrawX(Integer.parseInt(nameOrId), quantity);
        }
        return Rs2Bank.withdrawX(nameOrId, quantity);
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
     * True when {@code item} matches any entry of the caller's keep-list — a locked slot
     * this bank cycle established itself, which must never be released as a stray.
     */
    private static boolean isKeptLocked(Rs2ItemModel item, Collection<String> keepLocked) {
        for (String kept : keepLocked) {
            if (isToolMatch(item, kept, kept.toLowerCase())) return true;
        }
        return false;
    }

    // ── Bank readiness (the readyForBank gate) ────────────────

    /**
     * The nearest bank object the orchestrator could open from where the player stands —
     * the same candidates {@link Rs2Bank#openBank()} uses (a bank booth/chest or a Grand
     * Exchange booth, both searched within 20 tiles). {@code null} means no bank is loaded
     * in the scene at all, which is the only case that justifies walking.
     */
    public static TileObject findBankObject() {
        TileObject bank = Rs2GameObject.findBank();
        return bank != null ? bank : Rs2GameObject.findGrandExchangeBooth();
    }

    /**
     * Whether a bank the orchestrator can actually open is in the loaded scene and on
     * camera. This is the standard body for
     * {@link net.runelite.client.plugins.microbot.ascript.AModule#readyForBank}: judge
     * readiness on the OBJECT, never on a distance to a coordinate — a distance proxy
     * reports not-ready while the bank is in plain sight, and the module then walks to a
     * hardcoded point instead of clicking the booth beside it. Clicking a visible bank IS
     * the human pattern: the game walks the last few tiles as part of the interaction, so
     * only a bank that is not loaded at all warrants the walker.
     */
    public static boolean bankVisible() {
        TileObject bank = findBankObject();
        return bank != null && Rs2Camera.isTileOnScreen(bank);
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
