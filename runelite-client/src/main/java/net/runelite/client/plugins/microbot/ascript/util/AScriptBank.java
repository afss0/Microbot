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
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntilTrue;

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
 *   <li>Treat a bank-slot lock as a verification postcondition: every anchor (tool,
 *       mould, rune stack, keeper set) must be confirmed locked through
 *       {@code Rs2Bank.isLockedSlot(slot)} before a deposit runs around it, and a cycle
 *       that needs locking refuses to start when the bank's slot locking cannot be
 *       enabled — see {@link #ensureSlotLockingCapability()}.</li>
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

    // ── Slot-lock capability (P1-30) ──────────────────────────

    /**
     * Why a locked-anchor bank cycle cannot run, in the words the stop message uses. A bank
     * cycle that must keep an anchor (tool, mould, rune stack, keeper set) has one hard
     * precondition: bank slot locking has to be ON, otherwise the toolbar "Deposit inventory"
     * button sweeps the anchor away and the module either loses its tool or loops forever
     * re-withdrawing it. {@link Rs2Bank#toggleItemLock} returns {@code false} in that state,
     * and treating that as "carry on" is what produced the endless "Bank Cycle Failed" runs.
     */
    private static final String SLOT_LOCKING_REQUIRED =
            "bank slot locking is disabled — enable it in the bank settings"
                    + " (bank menu → Locks → 'Ignore inventory locks' OFF and"
                    + " 'Show inventory locks' ON); the bank cycle needs it to keep tool/material slots";

    /**
     * Set by the first locking-dependent helper that finds slot locking unavailable, consumed by
     * the orchestrator's bank phase ({@code AScript.tick}) so the run stops with a distinct
     * message instead of retrying a deposit that would sweep the anchor on every tick.
     */
    private static volatile String bankCapabilityFailure;

    /**
     * Ensure the bank can actually lock inventory slots. When the settings are off this clicks
     * the bank's own Locks panel ({@link Rs2Settings#enableBankSlotLocking()}, needs the bank
     * open) and re-checks; when it still cannot be enabled the capability failure is recorded
     * for the orchestrator and {@code false} is returned, so the caller refuses to start the
     * cycle rather than deposit with no protection.
     *
     * @return true when slot locking is enabled and usable
     */
    public static boolean ensureSlotLockingCapability() {
        if (tryEnableSlotLocking()) {
            return true;
        }
        bankCapabilityFailure = SLOT_LOCKING_REQUIRED;
        log.warn("[AScriptBank] {}", SLOT_LOCKING_REQUIRED);
        return false;
    }

    /**
     * Enable bank slot locking when it is off, without recording a capability failure.
     * Best-effort protection (the XP lamp) uses this: an unprotected lamp makes a deposit
     * incomplete but cannot lose anything, so it must not stop the run.
     */
    private static boolean tryEnableSlotLocking() {
        if (Rs2Settings.isBankSlotLockingEnabled()) {
            return true;
        }
        return Rs2Bank.isOpen() && Rs2Settings.enableBankSlotLocking();
    }

    /**
     * Take (and clear) the recorded slot-lock capability failure, if any. The orchestrator's
     * bank phase calls this to stop the run with a dedicated message; a null answer means no
     * capability gap was detected since the last call.
     *
     * @return the failure description, or {@code null} when there is nothing to report
     */
    public static String takeBankCapabilityFailure() {
        String failure = bankCapabilityFailure;
        bankCapabilityFailure = null;
        return failure;
    }

    /**
     * Sets the bank-slot lock of the inventory item in {@code slot} to {@code locked} and treats
     * {@link Rs2Bank#isLockedSlot(int)} as the POSTCONDITION — the dispatch's own return value is
     * only a varbit-change proxy, so it never decides on its own.
     * <p>
     * Slot-addressed on purpose. {@link Rs2Bank#toggleItemLock(String, boolean)} resolves the
     * FIRST inventory item carrying that display name, so with same-name duplicates (e.g. 27
     * rings) calling it once per duplicate always toggles that first slot — locking it, then
     * unlocking it, leaving the other 26 unlocked. Callers that must protect (or release) a
     * specific stack have to name the slot, which is what this does.
     * <p>
     * Preconditions: the bank is open (the bank-inventory widget must be loaded) and bank slot
     * locking is enabled in the bank settings ({@code Rs2Settings.enableBankSlotLocking()}).
     *
     * @param slot   inventory slot index (0-based, 0–27)
     * @param locked desired state after the call ({@code true} = lock, {@code false} = unlock)
     * @return true once {@code isLockedSlot(slot) == locked}; false when the slot is out of
     *         range/empty, the bank is closed, or the state did not confirm
     */
    private static boolean setSlotLocked(int slot, boolean locked) {
        if (slot < 0 || slot >= 28 || !Rs2Bank.isOpen()) return false;
        if (Rs2Bank.isLockedSlot(slot) == locked) return true;

        Rs2ItemModel item = Rs2Inventory.getItemInSlot(slot);
        if (item == null || item.getId() <= 0) return false;

        // Two dispatches at most: the varbit can flip before the slot widget's actions update,
        // in which case the first confirmation wait times out on a lock that did land. The
        // toggle's own return value is only a varbit-change proxy, so it never decides on its
        // own — only a lock state that also failed to change makes the dispatch a no-op.
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean toggled = Rs2Bank.toggleItemLock(item);
            if (sleepUntilTrue(() -> Rs2Bank.isLockedSlot(slot) == locked, 100, 1000)) return true;
            if (!toggled) return false;
        }
        return Rs2Bank.isLockedSlot(slot) == locked;
    }

    /**
     * Re-check the bank window before a withdraw step (the Jewel Enchant {@code ensureBankOpen()}
     * pattern, shared here). A bank that closed mid-cycle must surface as a RETRY, never as a
     * silent material miss: the withdraw helpers read the bank mirror, and against a closed
     * window that mirror is empty or stale, so a missing material and a closed window look
     * identical from the outside. The caller returns {@code false} and the orchestrator retries
     * on a later tick, with this WARN naming the real cause.
     *
     * @return true when the bank is open (reopening it when it had closed); false when it is
     *         closed and could not be reopened — the caller must retry next cycle
     */
    public static boolean ensureBankOpen() {
        if (Rs2Bank.isOpen()) {
            return true;
        }
        log.warn("[AScriptBank] Bank window closed mid-cycle — reopening before the next withdraw step");
        if (!Rs2Bank.openBank()) {
            log.warn("[AScriptBank] Bank could not be reopened — retrying next cycle");
            return false;
        }
        if (!sleepUntil(Rs2Bank::isOpen, WITHDRAW_VERIFY_TIMEOUT_MS)) {
            log.warn("[AScriptBank] Bank reopen did not confirm within {} ms — retrying next cycle",
                    WITHDRAW_VERIFY_TIMEOUT_MS);
            return false;
        }
        return true;
    }

    /**
     * Withdraw up to {@code quantity} of {@code name} from the bank and verify the AMOUNT that
     * landed, not merely that the item is present. Use for stackable items where a specific
     * amount is needed (e.g. 14 unstrung bows + 14 bow strings for stringing).
     * <p>
     * Quantity semantics (P1-18): the available bank quantity is read first and
     * {@code min(available, needed)} is withdrawn — a 14 request against a 9-stack strings 9 and
     * returns {@code false} after reporting the shortfall, instead of dispatching a 14 that the
     * bank cannot serve and leaving the caller to advance with 9. The verification is an
     * inventory count delta (the pre-call amount plus the dispatched amount), because a
     * presence check passes as soon as the first unit lands.
     * <p>
     * The verify runs in two windows (main + grace) and an unconfirmed dispatch is
     * retried with exponential backoff — see {@link #withdrawWithRetry}.
     *
     * @param name item name (or numeric id as a string) to withdraw
     * @param quantity number of units needed (0 or less is a no-op {@code true})
     * @return true when the inventory holds at least {@code quantity} of {@code name} after the
     *         call; false on a failed withdraw, or when the bank could only serve part of it
     *         (the part that was available is still withdrawn and stays in the inventory)
     */
    public static boolean withdrawVerified(String name, int quantity) {
        if (quantity <= 0) {
            return true;
        }
        int held = inventoryQuantity(name);
        if (held >= quantity) {
            return true;
        }
        if (!Rs2Bank.isOpen()) {
            // Same rule as {@link #withdrawWithRetry}: a closed window is not evidence that the
            // bank lacks the item. The amount read below consults a bank mirror that is empty or
            // stale while the bank is closed, so it would report a missing material for what is
            // really a mid-cycle close. Surface it as a retry instead.
            log.warn("[AScriptBank] Cannot withdraw {} x{} — the bank window is closed; retrying next cycle",
                    name, quantity);
            return false;
        }
        int missing = quantity - held;
        int available = availableBankQuantity(name);
        if (available <= 0) {
            log.warn("[AScriptBank] Cannot withdraw {} x{}: the bank holds none [{}]",
                    name, quantity, resolution(name));
            return false;
        }
        int toWithdraw = Math.min(available, missing);
        int target = held + toWithdraw;
        boolean withdrawn = withdrawWithRetry(name, () -> withdrawBankItemX(name, toWithdraw),
                name + " x" + toWithdraw, () -> inventoryQuantity(name) >= target);
        if (!withdrawn) {
            // A landing that arrived as BANKNOTES is not a verification failure to hide: the game
            // notes multi-withdraws when the bank is set to "Withdraw as note", and a noted stack
            // can never be acted on. Say so instead of letting the module report a bare timeout.
            if (inventoryQuantity(name, true) >= target) {
                log.warn("[AScriptBank] {} arrived as BANKNOTES ({} units, none usable) — set the"
                                + " bank withdraw mode to 'Withdraw as item'", name,
                        inventoryQuantity(name, true));
            }
            return false;
        }
        if (toWithdraw < missing) {
            log.warn("[AScriptBank] {} shortfall: wanted {} more, the bank held {} — withdrew {},"
                            + " the inventory now holds {} of {}",
                    name, missing, available, toWithdraw, inventoryQuantity(name), quantity);
            return false;
        }
        return true;
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
        return withdrawWithRetry(nameOrId, dispatch, describe, () -> hasInventoryItem(nameOrId));
    }

    /**
     * {@link #withdrawWithRetry(String, BooleanSupplier, String)} with a caller-supplied success
     * predicate: the amount-based withdraw confirms on a count delta, the all/one variants on
     * presence. Everything else (bank-presence pre-check, main + grace windows, backoff before
     * re-checking, never re-opening the bank) is shared.
     *
     * @param nameOrId item name (or numeric id as a string) to withdraw
     * @param dispatch performs one withdraw dispatch and reports whether it had an item to click
     * @param describe label for the log lines
     * @param landed reports whether the dispatch landed (presence or amount, per caller)
     * @return true once {@code landed} holds; false when every attempt failed or the bank lacks it
     */
    private static boolean withdrawWithRetry(String nameOrId, BooleanSupplier dispatch, String describe,
                                             BooleanSupplier landed) {
        if (!Rs2Bank.isOpen()) {
            // A closed window is NOT evidence that the bank lacks the item: the mirror those
            // reads consult is empty or stale while the bank is closed, so failing silently here
            // reports a missing material for what is really a mid-cycle close. Surface it as a
            // retry (the caller returns false and re-runs the cycle, reopening the bank), with a
            // line naming the cause — never a silent miss.
            log.warn("[AScriptBank] Cannot withdraw {} — the bank window is closed; retrying next cycle",
                    describe);
            return false;
        }
        if (!hasBankItem(nameOrId)) {
            return false; // bank lacks it — nothing to dispatch (silent, as before)
        }
        for (int attempt = 1; attempt <= WITHDRAW_MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                // Exponential backoff, jittered: give a pending landing time to arrive
                // BEFORE re-checking, so an in-flight withdraw is never double-dispatched.
                long backoffMs = (long) WITHDRAW_RETRY_BACKOFF_BASE_MS << (attempt - 2);
                Rs2Random.waitEx(backoffMs, backoffMs / 4.0);
                if (landed.getAsBoolean()) {
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
            if (sleepUntil(landed::getAsBoolean, WITHDRAW_VERIFY_TIMEOUT_MS)) {
                return true;
            }
            // Grace re-check: the observed failure mode is a landing that takes longer than
            // the verify window under transient lag — confirm it before failing the attempt.
            if (sleepUntil(landed::getAsBoolean, WITHDRAW_GRACE_TIMEOUT_MS)) {
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
     * <p>
     * Requires bank slot locking to be ON and verified, even when the tool is already held and
     * its slot already reads locked: a lock is only protection while the bank honours it, so
     * the capability is confirmed before anything is deposited (see
     * {@link #ensureSlotLockingCapability()}).
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
        // Bank slot locking is a precondition of the WHOLE cycle, so it is checked BEFORE any
        // early return (review round 1, P2-1). A slot can already READ locked while the bank is
        // set to ignore inventory locks (BANK_SIDE_SLOT_IGNOREINVLOCKS=1, typically paired with
        // show-locks=1), and that combination deposits straight through the anchor: reading the
        // pre-existing lock as "nothing left to do" started the cycle without the capability and
        // the next depositAll() swept the tool. Costs one varbit read when locking is enabled.
        if (!ensureSlotLockingCapability()) {
            return false;
        }

        // Already holding the item — the lock is the only thing left to establish (and confirm).
        Rs2ItemModel held = getInventoryItem(nameOrId);
        if (held != null) {
            return lockHeld(held, nameOrId);
        }

        // The item is missing, so this call is going to release strays and withdraw it; both
        // halves need the capability confirmed above (an unprotected inventory is exactly what
        // the old behaviour deposited, losing tools).

        // Release any locked slot that holds a DIFFERENT item (and is not in the caller's
        // keep-list — those belong to this same bank cycle), then blanket-deposit via the
        // toolbar button (which ignores still-locked slots, so the just-unlocked stray goes
        // back to the bank), withdraw the item, and lock its slot.
        String nameLc = nameOrId.toLowerCase();
        for (int slot : Rs2Bank.findLockedSlots()) {
            Rs2ItemModel stray = Rs2Inventory.getItemInSlot(slot);
            if (stray == null) continue;
            if (stray.getId() == ItemID.LAMP) continue; // never release the lamp — it must survive deposits
            if (isToolMatch(stray, nameOrId, nameLc)) continue; // already the item (race) — leave it
            if (isKeptLocked(stray, keepLocked)) continue; // owned by the caller's cycle — never a stray
            // Slot-addressed: unlocking by name would release the wrong duplicate.
            if (!setSlotLocked(slot, false)) {
                log.warn("[AScriptBank] could not unlock stray '{}' in slot {} — bank cycle refused",
                        stray.getName(), slot);
                return false;
            }
        }
        if (!depositAll()) return false; // depositAll() button ignores locked slots; stray is now gone

        boolean withdrew = withdrawWholeStack
                ? withdrawVerified(nameOrId)
                : withdrawOneVerified(nameOrId);
        if (!withdrew) {
            return false;
        }
        Rs2ItemModel item = getInventoryItem(nameOrId);
        if (item == null) {
            log.warn("[AScriptBank] '{}' is absent right after a confirmed withdraw — cannot lock its slot",
                    nameOrId);
            return false;
        }
        return lockHeld(item, nameOrId);
    }

    /**
     * Make sure {@code item}'s inventory slot is LOCKED and confirm the lock landed (P1-16).
     * Returns {@code false} when the lock cannot be established — the caller must not deposit
     * around an unprotected anchor, and a silent {@code true} here is what let the tool be
     * swept. Also re-checks that the item is still in the inventory afterwards: a lock on a
     * slot the item has already left protects nothing.
     * <p>
     * The slot-locking capability is NOT re-checked here: {@link #ensureLocked} confirms it
     * before dispatching anything (including this method's already-held path), because an
     * already-locked slot can coexist with a bank that ignores inventory locks — in which case
     * {@link Rs2Bank#isLockedSlot(int)} reporting {@code true} protects nothing.
     *
     * @param item    the inventory item that must end up locked
     * @param nameOrId the caller's lookup key, for the final presence check and log lines
     * @return true when the slot is locked and the item is still held
     */
    private static boolean lockHeld(Rs2ItemModel item, String nameOrId) {
        int slot = item.getSlot();
        if (!Rs2Bank.isLockedSlot(slot) && !toggleLockByItem(item)) {
            log.warn("[AScriptBank] could not lock slot {} for '{}' — bank cycle refused",
                    slot, item.getName());
            return false;
        }
        if (!hasInventoryItem(nameOrId)) {
            log.warn("[AScriptBank] '{}' left slot {} after locking it — bank cycle refused",
                    nameOrId, slot);
            return false;
        }
        return true;
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
        // Slot-addressed + verified: a name lookup ("lamp") can resolve "Empty oil lamp" and a
        // toggle that is not confirmed leaves the deposit stalling on an unprotected lamp.
        // Best effort only — an unprotected lamp cannot be lost, so this never stops the run.
        if (!tryEnableSlotLocking()) {
            log.warn("[AScriptBank] bank slot locking is off — the lamp slot cannot be protected");
            return;
        }
        if (!setSlotLocked(lamp.getSlot(), true)) {
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
     * <p>
     * The capability is required as soon as a keeper is present in the inventory, whether its
     * slot already reads locked or not: with the bank set to ignore inventory locks the lock
     * protects nothing, so "it is already locked" is not evidence that it will survive.
     *
     * @param keepIds item ids that must survive the sweep
     * @return true when no stray remains (no-op true when the inventory is already
     *         clean); false when the bank is closed, bank slot locking cannot be
     *         enabled while a keeper is held, a keeper lock could not be
     *         established, or the strays were still present after the deposit
     */
    public static boolean depositAllExcept(Collection<Integer> keepIds) {
        if (!Rs2Bank.isOpen()) {
            log.warn("[AScriptBank] depositAllExcept needs the open bank");
            return false;
        }
        List<Rs2ItemModel> strays = new ArrayList<>();
        List<Rs2ItemModel> keepers = new ArrayList<>();
        int keeperCount = 0;
        for (Rs2ItemModel item : Rs2Inventory.items().filter(Objects::nonNull).collect(Collectors.toList())) {
            if (item.getId() == ItemID.LAMP) continue;          // cannot be deposited at all
            boolean keeper = keepIds != null && keepIds.contains(item.getId());
            // A keeper counts even when its slot already READS locked (review round 1, P2-1):
            // with the bank set to ignore inventory locks the lock protects nothing, so the
            // capability must be confirmed instead of inferred from the lock state.
            if (keeper) keeperCount++;
            if (Rs2Bank.isLockedSlot(item.getSlot())) continue; // anchors stay as found
            if (keeper) {
                keepers.add(item);
            } else {
                strays.add(item);
            }
        }

        // Keepers are protected by locking their OWN slots, one toggle per slot: they are often
        // same-name duplicates (27 identical rings), and a name-addressed toggle always hits the
        // first duplicate — locking it repeatedly and leaving every other copy exposed to the
        // toolbar deposit. Without the capability there is no way to protect them at all, so the
        // sweep refuses (nothing deposited) and the orchestrator stops with the capability
        // message instead of retrying a deposit that would empty the keep-set. Checked on keeper
        // PRESENCE, not on the unlocked ones: an already-locked keeper does not prove the
        // protection is real (see above).
        if (keeperCount > 0 && !ensureSlotLockingCapability()) {
            log.warn("[AScriptBank] depositAllExcept declined: {} keeper(s) cannot be locked —"
                    + " nothing deposited", keeperCount);
            return false;
        }
        if (strays.isEmpty()) {
            return true;
        }
        List<Rs2ItemModel> lockedNow = new ArrayList<>();
        for (Rs2ItemModel keeper : keepers) {
            if (!setSlotLocked(keeper.getSlot(), true)) {
                for (Rs2ItemModel locked : lockedNow) {
                    setSlotLocked(locked.getSlot(), false);
                }
                log.warn("[AScriptBank] could not lock keeper {} in slot {} — sweep aborted,"
                                + " nothing deposited",
                        keeper.getName(), keeper.getSlot());
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

        // Always release the keeper locks this call established — again by slot, and only while
        // the slot still holds the same item (a deposit can leave the surviving items where they
        // were, but never assume it: releasing a slot that now holds something else would strip
        // a lock another cycle owns).
        for (Rs2ItemModel locked : lockedNow) {
            Rs2ItemModel now = Rs2Inventory.getItemInSlot(locked.getSlot());
            if (now != null && now.getId() == locked.getId()
                    && Rs2Bank.isLockedSlot(locked.getSlot())
                    && !setSlotLocked(locked.getSlot(), false)) {
                log.warn("[AScriptBank] could not release keeper lock on slot {} ('{}')",
                        locked.getSlot(), locked.getName());
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
     * Free inventory slots right now — the reservation basis for every multi-material withdraw.
     * <p>
     * Locked slots (held tools, the mould, the XP lamp) count as OCCUPIED, which is the point:
     * a locked anchor must shrink the batch instead of letting the first withdraw fill the bag
     * and make the second material unwithdrawable ({@code Rs2Bank.withdrawAll} bails on a full
     * inventory).
     *
     * @return number of empty inventory slots (0 when the bag is full)
     */
    public static int freeSlots() {
        return Rs2Inventory.emptySlotCount();
    }

    /**
     * Units of {@code nameOrId} the bank holds right now (0 when absent or when no bank snapshot
     * is loaded). Public front door for the amount planning a module does BEFORE a withdraw: a
     * batch has to be computed from what the bank actually holds instead of a hardcoded 14 or 13,
     * because the withdraw dispatch itself resolves the bank row by the requested amount — a
     * request for more than the stack holds finds no row at all and dispatches nothing.
     *
     * @param nameOrId item name (substring match, shortest display name wins — the same row the
     *                 withdraw would click) or numeric id as a string
     * @return units available in the bank, stacked rows summed
     */
    public static int bankQuantity(String nameOrId) {
        int quantity = availableBankQuantity(nameOrId);
        if (quantity > 0) {
            return quantity;
        }
        // The bank mirror is refreshed by bank container events, so one raw read can return 0
        // right after the bank opens or after a deposit. The retrying {@link #hasBankItem}
        // family gives the mirror its window; when it confirms the item, one bounded re-read
        // corrects the amount instead of the caller concluding "no materials" from a stale row.
        if (!hasBankItem(nameOrId)) {
            return 0;
        }
        sleepUntil(() -> availableBankQuantity(nameOrId) > 0, 1000);
        return availableBankQuantity(nameOrId);
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
     * Units of {@code nameOrId} currently in the inventory, stacked entries counted by quantity
     * rather than by slot — a 14-string withdraw lands as ONE slot holding 14, so
     * {@link Rs2Inventory#count(String)} (which counts slots) would confirm nothing.
     * Banknotes are excluded: a noted stack is not material the module can use, and including
     * it would make the amount verification pass on a stack the module cannot act on.
     */
    private static int inventoryQuantity(String nameOrId) {
        return inventoryQuantity(nameOrId, false);
    }

    /**
     * {@link #inventoryQuantity(String)} with an explicit banknote policy: {@code includeNoted}
     * counts banknotes too, which is only meaningful for a diagnostic (the amount check must not
     * accept notes — a noted stack cannot be crafted/fletched with).
     *
     * @param nameOrId    item name (substring match) or numeric id as a string
     * @param includeNoted whether banknote units count
     * @return units held, stacked entries summed by quantity
     */
    private static int inventoryQuantity(String nameOrId, boolean includeNoted) {
        if (isNumeric(nameOrId)) {
            int id = Integer.parseInt(nameOrId);
            return Rs2Inventory.items()
                    .filter(item -> item.getId() == id && (includeNoted || !item.isNoted()))
                    .mapToInt(Rs2ItemModel::getQuantity)
                    .sum();
        }
        String nameLc = nameOrId.toLowerCase();
        return Rs2Inventory.items()
                .filter(item -> item.getName() != null && (includeNoted || !item.isNoted())
                        && item.getName().toLowerCase().contains(nameLc))
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();
    }

    /**
     * Units of {@code nameOrId} available in the bank right now (0 when absent or when no bank
     * snapshot is loaded). Name lookups resolve the same row {@code Rs2Bank.findBankItem} would
     * click — the shortest matching display name — so the amount read and the withdraw dispatch
     * cannot disagree about which stack they mean.
     */
    private static int availableBankQuantity(String nameOrId) {
        List<Rs2ItemModel> rows = Rs2Bank.bankItems();
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        if (isNumeric(nameOrId)) {
            int id = Integer.parseInt(nameOrId);
            return rows.stream()
                    .filter(item -> item.getId() == id)
                    .mapToInt(Rs2ItemModel::getQuantity)
                    .sum();
        }
        String nameLc = nameOrId.toLowerCase();
        Rs2ItemModel row = rows.stream()
                .filter(item -> item.getName() != null
                        && item.getName().toLowerCase().contains(nameLc))
                .min(Comparator.comparingInt(item -> item.getName().length()))
                .orElse(null);
        return row == null ? 0 : row.getQuantity();
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
     * Lock the inventory slot holding {@code item} and CONFIRM the lock landed.
     * <p>
     * P2 asked for {@code Rs2Bank.toggleItemLock(name, exact = true)} here. Exactness does not
     * fix the defect that matters: the name lookup resolves the FIRST inventory item with that
     * display name, so with same-name duplicates "exact" still toggles the wrong slot (and a
     * second call toggles it back). The lock is therefore slot-addressed
     * ({@link #setSlotLocked(int, boolean)}), and — unlike the old body, whose result was
     * discarded — a lock that does not confirm makes this return {@code false} so the caller
     * refuses the cycle instead of depositing around an unprotected anchor.
     *
     * @param item inventory item whose slot must be locked
     * @return true when the slot is locked afterwards; false when it cannot be locked
     */
    private static boolean toggleLockByItem(Rs2ItemModel item) {
        if (item == null) return false;
        if (Rs2Bank.isLockedSlot(item.getSlot())) return true;
        if (!ensureSlotLockingCapability()) return false;
        return setSlotLocked(item.getSlot(), true);
    }
}
