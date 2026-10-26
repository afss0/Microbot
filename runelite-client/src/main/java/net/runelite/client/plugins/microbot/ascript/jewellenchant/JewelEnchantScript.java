package net.runelite.client.plugins.microbot.ascript.jewellenchant;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptPick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.input.PointerState;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;

import java.awt.Rectangle;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Jewellery Enchanting sub-script — stateless helper called by AScript.
 * <p>
 * Enchants jewellery using the Lvl-1 through Lvl-7 Enchant spells on the
 * Modern spellbank. Handles rune withdrawal, elemental staff equipping, and
 * the enchant spell cast + jewellery click sequence.
 * <p>
 * The elemental staff is taken from wherever it already is: the WORN one (kept as-is),
 * else the one carried in the inventory, else the best one in the bank — where "best" is
 * the coverage of the level's staff-coverable runes and ties go to the item already at
 * hand. It is then WORN FIRST, before any deposit of a bank cycle: a deposit would bank the
 * staff the player is carrying, and a staff withdrawn after the jewellery fill has no free
 * slot left. The wear is a client-mouse click with the bank CLOSED (with it open an
 * inventory left-click is a deposit, never a Wear), and the restock completes on a later
 * tick with the staff on.
 * <p>
 * The cosmic rune stack is held as a locked inventory slot (like the jewellery
 * mould in {@code CraftingScript}) so the banking deposit never removes it —
 * {@link AScriptBank#ensureStackLocked(String)} withdraws the whole stack when
 * it runs out and re-locks it.
 * <p>
 * The game auto-recasts an enchantment spell every 7 game ticks after a cast. The cast path
 * treats that as progress: it baselines the enchanted count at tick start, re-reads the target
 * slot and the armed spell immediately before the press, and never clicks a piece the
 * auto-cast already converted.
 * <p>
 * Adapted from Microbot Hub JewelleryEnchantPlugin.
 */
@Slf4j
public class JewelEnchantScript implements AModule {

    /** Spellbook widgets holding the enchant spells — the "Jewellery Enchantments" sub-tab. */
    private static final int SPELLBOOK_PARENT_ID = 218;
    private static final int SPELLBOOK_CHILD_ID = 3;
    /** Failed enchant attempts in a row before the module stops and notifies. */
    private static final int MAX_ENCHANT_FAILURES = 3;

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private double weatherMultiplier = 1.0;
    private int consecutiveEnchantFailures = 0;
    /** Timestamp of the last random AFK — minimum 5 s between AFKs. */
    private long lastAfkTime = 0L;
    /** Set while the post-batch break is pending/done — see {@link #needsBank}. */
    private boolean jewelEnchantPostBatchBreakDone = false;
    /**
     * True once a batch has actually run since the last bank cycle (set in {@link #doAction} when
     * jewellery is in hand, cleared by a completed bank cycle and on reset). Gates the
     * bank-closed yield in {@link #needsBank}: with nothing yet worked, the first bank trip
     * happens immediately — never a post-batch break (or a stop) before it.
     */
    private boolean batchRanSinceBank = false;

    public enum Phase {
        NONE, ENCHANT
    }

    // ── Lifecycle ─────────────────────────────────────────

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveEnchantFailures = 0;
        lastAfkTime = 0L;
        jewelEnchantPostBatchBreakDone = false;
        batchRanSinceBank = false;
    }

    // ── Phase resolution ──────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.JEWEL_ENCHANT
                || config.jewelEnchantActivity() == null) {
            this.currentPhase = Phase.NONE;
            return;
        }
        switch (config.jewelEnchantActivity()) {
            case ENCHANT_JEWELLERY: this.currentPhase = Phase.ENCHANT; return;
            default:                this.currentPhase = Phase.NONE; return;
        }
    }

    @Override
    public boolean isActive() {
        return currentPhase != Phase.NONE;
    }

    @Override
    public String runKey(AScriptConfig config) {
        return "JEWEL_ENCHANT:" + config.jewelEnchantActivity()
                + ":" + config.jewelEnchantItem()
                + ":" + config.jewelEnchantUseStaff();
    }

    // ── Selection validation ──────────────────────────────

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.JEWEL_ENCHANT) {
            return "no enchant activity selected";
        }
        if (currentPhase == Phase.ENCHANT) {
            JewelEnchantItem item = config.jewelEnchantItem();
            if (item == null || item == JewelEnchantItem.NONE) {
                return "no jewellery item selected";
            }
            // Capability checks belong here, not in doBank(): the orchestrator stops
            // within one tick with a visible message, instead of walking to a bank
            // first and writing status from a banking method.
            JewelEnchantLevel level = item.getEnchantLevel();
            int magic = Rs2Player.getRealSkillLevel(Skill.MAGIC);
            // A real-level read of 0 means skill data has not loaded yet (or the
            // client-thread read timed out) — that is "retry", not a locked spell.
            if (magic > 0 && magic < level.getRequiredLevel()) {
                return "need level " + level.getRequiredLevel() + " Magic for " + level.getName()
                        + " (current: " + magic + ")";
            }
        }
        return null;
    }

    // ── Banking needs ─────────────────────────────────────

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        JewelEnchantItem item = config.jewelEnchantItem();
        if (item == null || item == JewelEnchantItem.NONE) return false;

        // Need the unenchanted jewellery. When a batch just ran out, yield one tick so
        // the post-batch break runs with the bank still closed; only a batch that actually
        // ran earns that break — a fresh empty state banks immediately.
        if (!Rs2Inventory.hasItem(item.getUnenchantedId())) {
            return !batchRanSinceBank || jewelEnchantPostBatchBreakDone;
        }

        // Need cosmic runes — no staff supplies them
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId())) return true;

        JewelEnchantLevel level = item.getEnchantLevel();

        // Staff mode. A staff HELD in the inventory is worn by doAction — doBank can never
        // equip it (with the bank open an inventory left-click is a deposit) — so it only
        // justifies a bank trip when the bank holds a STRICTLY better one: a partial held
        // staff is completed with withdrawn runes, never with a bank round trip. With
        // nothing usable in hand the trip is what fetches a staff, and the orchestrator's
        // missing-materials verdict then owns "no usable staff anywhere". The bank read here
        // is the cached mirror — a wrong answer costs ONE extra bank cycle, which the live
        // read at the bank corrects.
        if (config.jewelEnchantUseStaff()
                && !staffCoverableRunes(level).isEmpty()
                && !isWearingStaffFor(level)) {
            ElementalStaff held = bestStaffInInventory(level);
            if (held == null) return true;
            if (bankStaffWins(held, bankStaffFromMirror(level), level)) return true;
        }

        // Every elemental rune the (worn or to-be-equipped) staff does not supply
        // must be carried in the inventory.
        return !runesNeededInInventory(config, level).isEmpty();
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;

        JewelEnchantItem item = config.jewelEnchantItem();
        if (item == null || item == JewelEnchantItem.NONE) return false;
        JewelEnchantLevel level = item.getEnchantLevel();

        // Missing from both inventory AND bank. Noted jewellery is a banknote for the base
        // item: the cycle's deposit converts it back, so it counts as available here even
        // though it can never be enchanted directly.
        if (!AScriptBank.bankableAtLeast(item.getUnenchantedId(), 1)) {
            return true;
        }
        if (!AScriptBank.bankableAtLeast(Runes.COSMIC.getItemId(), 1)) {
            return true;
        }

        // Staff mode: no usable staff ANYWHERE — not worn, not held, not noted in the
        // inventory, not in the (now open) bank. Stop with a message instead of looping on a
        // staff that can never be equipped. A staff merely HELD in the inventory counts as
        // usable: doAction wears it once the bank closes, so it is never a reason to stop.
        if (config.jewelEnchantUseStaff()
                && !staffCoverableRunes(level).isEmpty()
                && !isWearingStaffFor(level)
                && bestStaffInInventory(level) == null
                && notedStaffInInventory(level) == null
                && bestStaffInBank(level) == null) {
            return true;
        }

        // Runes the staff will not supply must be obtainable for a full cast from the
        // inventory + bank combined: a partial inventory stack (3 of 5) plus a partial bank
        // stack (2 of 5) is enough, and doBank withdraws all of them. Requiring the bank
        // ALONE to hold the full amount produced a false "No Materials" stop.
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, level).entrySet()) {
            if (!AScriptBank.bankableAtLeast(entry.getKey().getItemId(), entry.getValue())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        JewelEnchantItem item = config.jewelEnchantItem();
        if (item == null || item == JewelEnchantItem.NONE) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(item.getName()).append(" enchant: ");
        if (!AScriptBank.bankableAtLeast(item.getUnenchantedId(), 1)) {
            sb.append("jewellery (not in bank), ");
        }
        if (!AScriptBank.bankableAtLeast(Runes.COSMIC.getItemId(), 1)) {
            sb.append("cosmic runes (not in bank), ");
        }
        JewelEnchantLevel level = item.getEnchantLevel();
        if (config.jewelEnchantUseStaff()
                && !staffCoverableRunes(level).isEmpty()
                && !isWearingStaffFor(level)
                && bestStaffInInventory(level) == null
                && notedStaffInInventory(level) == null
                && bestStaffInBank(level) == null) {
            sb.append("no usable elemental staff (not worn, not held, not in bank), ");
        }
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, level).entrySet()) {
            if (!AScriptBank.bankableAtLeast(entry.getKey().getItemId(), entry.getValue())) {
                sb.append(entry.getKey().name()).append(" runes (need ")
                        .append(entry.getValue()).append(" per cast), ");
            }
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2); // trailing ", "
        return sb.toString();
    }

    // ── Banking ───────────────────────────────────────────

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING — Enchant";

        JewelEnchantItem item = config.jewelEnchantItem();
        if (item == null || item == JewelEnchantItem.NONE) return false;

        JewelEnchantLevel level = item.getEnchantLevel();

        // The Magic-level requirement is validated in validateSelection(): the
        // orchestrator stops with a visible message before any bank trip.

        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            return false;
        }

        // A usable staff held only as a banknote: bank it first so the staff step below can
        // withdraw and wear the base item in this cycle. The toolbar deposit also un-notes any
        // banknote jewellery, which step 4 then withdraws as base items.
        if (config.jewelEnchantUseStaff() && !staffCoverableRunes(level).isEmpty()
                && !isWearingStaffFor(level)
                && bestStaffInInventory(level) == null
                && bankStaffFromMirror(level) == null
                && notedStaffInInventory(level) != null) {
            if (!AScriptBank.depositAll()) {
                log.warn("[JewelEnchant] could not deposit the noted staff/jewellery");
                return false;
            }
        }

        // 0. STAFF FIRST — before any deposit or withdraw of this cycle:
        //    - a deposit would bank the staff the player is carrying (2026-10-18 live: the
        //      staff went with the very first "Deposit inventory" of the run, because that
        //      deposit runs before any keep-set of this cycle exists);
        //    - a staff still in the bank must be withdrawn BEFORE the jewellery fill, which
        //      takes every remaining slot — a staff withdraw after it can never fit.
        //    The wear needs the bank CLOSED (with it open an inventory left-click is a
        //    deposit, never a Wear), so the cycle is handed back to the orchestrator once the
        //    staff is on: the next tick reopens the bank and runs the restock with the staff
        //    WORN — equipped items are never deposited, so nothing needs protecting, no slot
        //    lock and no keep-set entry is involved.
        if (config.jewelEnchantUseStaff() && !staffCoverableRunes(level).isEmpty()
                && !isWearingStaffFor(level)) {
            ElementalStaff staff = bestStaffInInventory(level);
            if (staff == null) {
                staff = bestStaffInBank(level);
                if (staff != null) {
                    // A full bag is a state banking legitimately fixes (leftover enchanted
                    // jewellery): free the slot the withdraw will ask for.
                    if (Rs2Inventory.isFull() && !AScriptBank.depositAll()) {
                        log.warn("[JewelEnchant] could not free a slot for the staff withdraw");
                        return false;
                    }
                    if (!AScriptBank.withdrawVerified(String.valueOf(staff.getItemId()))) {
                        log.warn("[JewelEnchant] {} staff withdraw failed (bank open: {}) — retrying next cycle",
                                staff.name(), Rs2Bank.isOpen());
                        return false;
                    }
                }
            }
            if (staff != null) {
                if (!closeBankForWear()) return false;
                ensureStaffWorn(config, level);
                if (!isWearingStaffFor(level)) {
                    log.warn("[JewelEnchant] {} not worn after closing the bank — retrying next cycle",
                            staff.name());
                    return false;
                }
                return true; // staff on — the rest of the cycle happens on a later tick
            }
        }

        // 1. Lock the cosmic rune slot FIRST — if the runes are already in the
        //    inventory their slot gets locked before the deposit, so the blanket
        //    deposit below preserves them. If they are missing, ensureStackLocked
        //    withdraws the WHOLE stack (one rune per cast would bank again on the
        //    next cast) and locks its slot. Same pattern as the jewellery mould in
        //    CraftingScript.bankJewelry().
        if (!AScriptBank.ensureStackLocked(Integer.toString(Runes.COSMIC.getItemId()))) {
            return false;
        }

        // 2. Deposit everything the cycle does not need. The locked cosmic slot survives the
        //    toolbar deposit (a locked stack is only released when it is consumed empty), so
        //    the sweep is keeper-aware for exactly one item. The staff needs no protection
        //    here: step 0 wears it BEFORE this deposit ever runs, and an equipped item is
        //    never deposited.
        if (AScriptBank.hasNotedItem(item.getUnenchantedId())) {
            Microbot.status = "UN-NOTING " + item.getName().toUpperCase();
            log.info("[JewelEnchant] banking noted {} to un-note the stack", item.getName());
        }
        if (!AScriptBank.depositAllExcept(Set.of(Runes.COSMIC.getItemId()))) {
            log.warn("[JewelEnchant] deposit failed");
            return false;
        }

        // 3. Withdraw the elemental runes the (worn or to-be-equipped) staff does not
        //    supply. Re-verify the bank window first: a mid-cycle close (a human taking
        //    over at the keyboard, a stray ESC) used to leave the remaining withdraws
        //    dispatching into nothing, and the cycle ended with a misleading "no
        //    materials" stop (2026-10-14). The retry/stop verdicts for genuinely
        //    missing materials belong to the orchestrator's missing-materials path.
        if (!AScriptBank.ensureBankOpen()) return false;
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, level).entrySet()) {
            if (!AScriptBank.withdrawVerified(String.valueOf(entry.getKey().getItemId()))) {
                log.warn("[JewelEnchant] {} rune withdraw failed (bank open: {}) — retrying next cycle",
                        entry.getKey().name(), Rs2Bank.isOpen());
                return false;
            }
        }

        // 4. Withdraw unenchanted jewellery (fill remaining inventory)
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(item.getUnenchantedId()))) {
            log.warn("[JewelEnchant] {} withdraw failed (bank open: {}) — retrying next cycle",
                    item.getName(), Rs2Bank.isOpen());
            return false;
        }

        // 5. Nothing else is dispatched here: the staff was already resolved at step 0 (worn
        //    before any deposit could touch the bag), so this cycle is purely the restock.

        // 6. Contained-inventory sweep — the cycle must end holding exactly its own
        //    materials. A staff equip just displaced the previously worn item into the
        //    inventory (equips always do); left alone it holds a slot for the rest of
        //    the batch — for good when the run stops before the next clean bank cycle
        //    (2026-10-16 live report) — and it is an extraneous clickable the enchant
        //    click can misland on. Deposit everything outside the keep-set NOW, while
        //    the bank is open; a clean inventory makes this a zero-click no-op.
        Set<Integer> keptIds = new HashSet<>();
        keptIds.add(Runes.COSMIC.getItemId());
        keptIds.add(item.getUnenchantedId());
        for (Runes rune : level.getRequiredRunes().keySet()) {
            keptIds.add(rune.getItemId());
        }
        if (!AScriptBank.depositAllExcept(keptIds)) {
            log.warn("[JewelEnchant] contained-inventory sweep failed — retrying next cycle");
            return false;
        }

        // Fresh batch scope — the next empty state only earns its closing break after a
        // batch runs on these materials.
        batchRanSinceBank = false;
        return true;
    }

    /**
     * Close the bank window so the staff can be worn — with it open, an inventory left-click is
     * a deposit, never a Wear. Bounded wait; {@code false} when the window stayed open, so the
     * cycle retries instead of clicking the staff into the bank.
     */
    private boolean closeBankForWear() {
        if (!Rs2Bank.isOpen()) return true;
        Rs2Bank.closeBank();
        if (sleepUntil(() -> !Rs2Bank.isOpen(), 2000)) return true;
        log.warn("[JewelEnchant] bank did not close for the staff wear — retrying next cycle");
        return false;
    }

    // ── Action (enchant) ──────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;

        JewelEnchantItem item = config.jewelEnchantItem();
        if (item == null || item == JewelEnchantItem.NONE) return;

        JewelEnchantLevel level = item.getEnchantLevel();
        MagicAction spell = level.getMagicAction();

        Microbot.status = "ENCHANTING — " + item.getName();

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // Nothing to enchant — the batch ran out: run the post-batch break once (the
        // orchestrator banks on a later tick; needsBank yields while the break is
        // pending), then let the bank cycle fetch more jewellery.
        if (Rs2Inventory.getLast(item.getUnenchantedId()) == null) {
            consecutiveEnchantFailures = 0;
            postBatchBreak(config);
            return;
        }
        // Baseline at the START of the tick: the game's auto-recast (every 7 game ticks) can
        // convert a piece while the spell is being armed or the tab is switching. A rise from
        // this baseline is progress, not a failed manual click.
        int enchantedAtStart = enchantedCount(item);
        jewelEnchantPostBatchBreakDone = false; // jewellery present again — the next empty state is a new batch end
        batchRanSinceBank = true;               // a batch is being worked — its end earns the closing break

        // 0. Wear the elemental staff held in the inventory before anything else: the runes it
        //    supplies are exactly the ones needsBank may have left in the bank, so the wear has
        //    to precede the cast. This is the no-bank-trip path — the bank cycle wears the staff
        //    in its own step 0; either way it is a client-mouse click with the bank closed. One
        //    tick consumed by the wear; the cast follows on the next one.
        if (!ensureStaffWorn(config, level)) return;

        // Cosmics ran out while jewellery remains — needsBank routes this state to the
        // bank cycle on the next tick. Never stop the run from here: the old hard stop
        // killed fresh starts before the first bank ever opened, and any batch that
        // ended with the cosmic stack empty.
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId())) {
            log.debug("[JewelEnchant] Cosmics missing with jewellery in hand — waiting for the bank cycle");
            return;
        }
        // 1. Select the enchant spell by clicking its spellbook icon with the
        //    client mouse (no injected menu entries).
        if (!selectSpell(spell)) {
            failEnchant("could not arm " + level.getName() + " from the spellbook");
            return;
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(150, 350, weatherMultiplier));

        // 2. Click the jewellery with the client mouse. The spell stays targeted
        //    across the tab switch, so this left-click is the enchant itself.
        if (!Rs2Tab.switchTo(InterfaceTab.INVENTORY)) {
            failEnchant("could not open the inventory tab");
            return;
        }

        // The game auto-recasts every 7 ticks; it may have converted a piece while the spell
        // was armed / the tab switched. A count rise is progress — never click a piece that
        // the auto-cast may already have consumed.
        if (enchantedCount(item) > enchantedAtStart) {
            consecutiveEnchantFailures = 0;
            log.debug("[JewelEnchant] auto-enchant progressed while arming — skipping manual cast");
            return;
        }

        // Resolve the target slot HERE, immediately before the click — never at the
        // top of doAction. The enchant spell consumes the FIRST inventory item of
        // the type it finds regardless of which item was selected, so a piece
        // resolved earlier can already have been enchanted by the time the
        // virtual-mouse click lands, and casting on an already-enchanted item does
        // nothing. Spell selection + tab switch above cost ~0.3-2.5s; resolving
        // here leaves only the mouse travel time. The LAST occupied slot
        // (Rs2Inventory.items() walks the container in ascending slot order, so
        // getLast(id) is the bottom-most piece) is the one least likely to be the
        // spell's first-of-type pick. This is the documented opt-out from the AScriptPick
        // slot randomization (ascript/AGENTS.md): a drawn slot near the front could
        // resolve to a piece the queued cast already consumed.
        Rs2ItemModel jewellery = AScriptPick.last(item.getUnenchantedId());
        if (jewellery == null) {
            // Consumed while the spell was being selected — bank for more next tick.
            consecutiveEnchantFailures = 0;
            return;
        }
        Point castPoint = prepareJewelleryClick(jewellery, spell, item.getUnenchantedId());
        if (castPoint == null) {
            // The auto-cast consumed the target, or the spell/pointer was lost while
            // hovering. That is progress/retry, never a failed cast.
            consecutiveEnchantFailures = 0;
            return;
        }
        Microbot.getMouse().click(castPoint);

        // 3. Confirm the enchant landed — the ENCHANTED side count rises (all known
        //    charge variants) against the TICK-START baseline, or the player animates.
        //    The tick-start baseline makes an auto-cast that landed during this tick count
        //    as success; a bare raw-count drop is still not success (it can be a wear).
        boolean enchanted = sleepUntil(
                () -> enchantedCount(item) > enchantedAtStart
                        || Rs2Player.isAnimating(),
                Rs2Random.logNormalBounded(1500, 3000, weatherMultiplier));
        if (!enchanted) {
            // A consumed target is progress too: the auto-cast may have enchanted the piece
            // between the last re-read and the press. Only an unchanged slot that still
            // holds an unenchanted item, with no conversion, is a genuine missed cast.
            Rs2ItemModel after = Rs2Inventory.getItemInSlot(jewellery.getSlot());
            if (after == null || after.getId() != item.getUnenchantedId()
                    || Rs2Inventory.getLast(item.getUnenchantedId()) == null) {
                consecutiveEnchantFailures = 0;
                return;
            }
            failEnchant("no enchant conversion for " + item.getName());
            return;
        }

        consecutiveEnchantFailures = 0;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(300, 700, weatherMultiplier));
    }

    // ── Internal helpers ──────────────────────────────────

    /**
     * Post-batch break — the house randomness layer (crafting-jewelry parity): a short
     * randomized pause after every completed batch, then the optional random AFK
     * (log-normal, weather-modulated, interruptible so blocking events still fire).
     * Runs while {@link #needsBank} yields, i.e. with the bank still closed.
     */
    private void postBatchBreak(AScriptConfig config) {
        if (jewelEnchantPostBatchBreakDone) return;
        jewelEnchantPostBatchBreakDone = true;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.jewelEnchantAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3_000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    /** Total count of {@code item}'s enchanted variants (all known charge states)
     *  currently in the inventory — the enchant-success signal. */
    private int enchantedCount(JewelEnchantItem item) {
        int count = 0;
        for (int id : item.getEnchantedIds()) {
            count += Rs2Inventory.itemQuantity(id);
        }
        return count;
    }

    /**
     * Select the enchant spell by clicking its icon in the spellbook with the
     * client mouse — no injected menu entries. Opens the "Jewellery
     * Enchantments" sub-tab first when the icons are not visible yet.
     *
     * @return true when the spell icon was clicked AND the spell is armed
     */
    private boolean selectSpell(MagicAction spell) {
        // One-time spellbook filter reconciliation (shared util): a filter like
        // "hide spells I lack runes for" hides the enchant icon entirely, and the
        // sprite lookup below would find nothing.
        Rs2Magic.oneTimeSpellBookCheck();

        // The auto-recast does not disarm the spell: between casts the correct icon is still
        // on the cursor. Re-arm only when it is not, which both shortens the race window and
        // avoids a needless icon click.
        if (isSpellArmed(spell)) {
            return true;
        }
        // A stale item / different spell on the cursor is not the enchant spell. Click the
        // selected widget itself to cancel it — never an arbitrary inventory item, which an
        // armed spell would cast on / use the item against.
        if (Rs2Inventory.isItemSelected() && !clearCursorSelection()) {
            log.warn("[JewelEnchant] could not clear a stale cursor selection before arming {}",
                    spell.getName());
            return false;
        }

        if (!Rs2Tab.switchTo(InterfaceTab.MAGIC)) {
            log.warn("[JewelEnchant] Could not open the magic tab");
            return false;
        }

        Rectangle bounds = spellIconBounds(spell);
        if (bounds == null) {
            // Enchant spells live in the "Jewellery Enchantments" sub-tab
            Rs2Widget.clickWidget("Jewellery Enchantments",
                    Optional.of(SPELLBOOK_PARENT_ID), SPELLBOOK_CHILD_ID, true);
            sleepUntil(() -> spellIconBounds(spell) != null, 2000);
            bounds = spellIconBounds(spell);
        }
        if (bounds == null) {
            log.warn("[JewelEnchant] {} not found in the spellbook", spell.getName());
            return false;
        }

        // Click, then VERIFY the SPECIFIC spell actually armed. "Something is selected" is not
        // enough: a stale item or another spell also satisfies it, and the next inventory
        // left-click would then wear/use the jewellery instead of enchanting it. One bounded
        // re-click keeps a single lost click from failing the tick.
        for (int attempt = 0; attempt < 2; attempt++) {
            Microbot.getMouse().click(bounds);
            if (sleepUntil(() -> isSpellArmed(spell), 1200)) {
                return true;
            }
        }
        log.warn("[JewelEnchant] {} click did not arm the spell", spell.getName());
        return false;
    }

    /**
     * Cancel a stale cursor selection by clicking the selected widget itself: an inventory
     * item's own slot cancels the item, and clicking the selected spell icon again deselects
     * the spell. Never click an arbitrary inventory item here — an armed spell would cast on
     * it instead of cancelling.
     */
    private boolean clearCursorSelection() {
        Rectangle bounds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget selected = Microbot.getClient().getSelectedWidget();
            return selected == null ? null : selected.getBounds();
        }).orElse(null);
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return false;
        }
        Microbot.getMouse().click(bounds);
        return sleepUntil(() -> !Rs2Inventory.isItemSelected(),
                Rs2Random.logNormalBounded(400, 900, weatherMultiplier));
    }

    /**
     * Cheap check that {@code spell}'s icon is the widget currently on the cursor.
     * <p>
     * The common path is one client-thread read + sprite/interface comparison. The widget-id
     * fallback scans the spellbook's static children only when the selected widget does not
     * expose the expected sprite (e.g. a frame where the sprite read is not settled), so the
     * auto-recast's "spell stays armed" state is detected without re-arming.
     */
    private boolean isSpellArmed(MagicAction spell) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget selected = Microbot.getClient().getSelectedWidget();
            if (selected == null) {
                return false;
            }
            if (selected.getSpriteId() == spell.getSprite()
                    && WidgetUtil.componentToInterface(selected.getId()) == SPELLBOOK_PARENT_ID) {
                return true;
            }
            Widget spellbook = Microbot.getClient().getWidget(SPELLBOOK_PARENT_ID, SPELLBOOK_CHILD_ID);
            if (spellbook == null || spellbook.getStaticChildren() == null) {
                return false;
            }
            for (Widget child : spellbook.getStaticChildren()) {
                if (child != null && child.getSpriteId() == spell.getSprite()) {
                    return selected.getId() == child.getId();
                }
            }
            return false;
        }).orElse(false);
    }

    /**
     * Bounds of the spellbook icon matching {@code spell}, or {@code null} when
     * the icon is not on screen. Widget reads stay on the client thread.
     */
    private Rectangle spellIconBounds(MagicAction spell) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget spellbook = Rs2Widget.getWidget(SPELLBOOK_PARENT_ID, SPELLBOOK_CHILD_ID);
            if (spellbook == null || spellbook.getStaticChildren() == null) return null;
            for (Widget child : spellbook.getStaticChildren()) {
                if (child != null && child.getSpriteId() == spell.getSprite()) {
                    return child.getBounds();
                }
            }
            return null;
        }).orElse(null);
    }

    /**
     * Move the cursor to the resolved jewellery slot and re-verify the two things the game's
     * auto-recast can invalidate between target resolution and the press:
     * <ul>
     *   <li>the slot still holds the <b>unenchanted</b> id — the auto-recast (every 7 game
     *       ticks) can consume the piece while the spell is armed;</li>
     *   <li>the enchant spell is still on the cursor — an inventory left-click with no spell
     *       armed <b>wears</b> the jewellery instead of casting.</li>
     * </ul>
     * A {@code null} result means "skip this tick": progress or a retry, never a failed cast,
     * so the caller must not spend a failure life.
     */
    private Point prepareJewelleryClick(Rs2ItemModel jewellery, MagicAction spell, int unenchantedId) {
        Rectangle bounds = Rs2Inventory.itemBounds(jewellery);
        if (bounds == null) {
            log.warn("[JewelEnchant] No bounds for {} (slot {})", jewellery.getName(), jewellery.getSlot());
            return null;
        }
        Point point = Rs2UiHelper.getClickingPoint(bounds, true);
        Microbot.getMouse().move(point);
        if (!sleepUntil(() -> PointerState.isAt(point.getX(), point.getY()),
                Rs2Random.logNormalBounded(400, 900, weatherMultiplier))) {
            log.debug("[JewelEnchant] cursor did not settle on {} — retrying next tick",
                    jewellery.getName());
            return null;
        }
        Rs2ItemModel live = Rs2Inventory.getItemInSlot(jewellery.getSlot());
        if (live == null || live.getId() != unenchantedId) {
            log.debug("[JewelEnchant] {} was enchanted/moved before the click — skipping",
                    jewellery.getName());
            return null;
        }
        if (!isSpellArmed(spell)) {
            log.debug("[JewelEnchant] enchantment spell lost before the click — re-arming next tick");
            return null;
        }
        return point;
    }

    /**
     * Register a failed enchant attempt: log it, back off, and stop the module
     * (with a Discord notification) after {@link #MAX_ENCHANT_FAILURES} in a row —
     * a missed click or a data problem must never spin the loop forever.
     */
    private void failEnchant(String reason) {
        consecutiveEnchantFailures++;
        log.warn("[JewelEnchant] Enchant attempt failed ({}/{}): {}",
                consecutiveEnchantFailures, MAX_ENCHANT_FAILURES, reason);
        if (consecutiveEnchantFailures >= MAX_ENCHANT_FAILURES) {
            exitRequested = true;
            stopWithMessage(reason);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }

    /**
     * Runes of {@code level} that an elemental staff can supply for free.
     * <p>
     * Blood and soul (Lvl-7 Enchant) are supplied by no staff, so a level whose
     * elemental runes are all blood/soul yields an empty set — staff mode is a
     * no-op there and those runes are always withdrawn.
     */
    private Set<Runes> staffCoverableRunes(JewelEnchantLevel level) {
        Set<Runes> coverable = EnumSet.noneOf(Runes.class);
        for (Runes rune : level.getRequiredRunes().keySet()) {
            if (rune != Runes.COSMIC && ElementalStaff.anyProvides(rune)) {
                coverable.add(rune);
            }
        }
        return coverable;
    }

    /** True when a currently equipped staff supplies at least one rune of {@code level}. */
    private boolean isWearingStaffFor(JewelEnchantLevel level) {
        return wornStaffFor(level) != null;
    }

    /**
     * The equipped staff supplying the most staff-coverable runes of {@code level}, or
     * {@code null} when none supplies a single one. Only one staff can be worn, so this is
     * that staff. A worn staff is never upgraded: the module keeps what the player has on
     * and withdraws the runes it does not supply.
     */
    private ElementalStaff wornStaffFor(JewelEnchantLevel level) {
        return bestStaffFor(id -> Rs2Equipment.isWearing(id), level);
    }

    /** Number of {@code level}'s staff-coverable runes {@code staff} supplies. */
    private int coverageOf(ElementalStaff staff, JewelEnchantLevel level) {
        int covered = 0;
        for (Runes rune : staffCoverableRunes(level)) {
            if (staff.providesRune(rune)) covered++;
        }
        return covered;
    }

    /**
     * The staff covering the most of {@code level}'s staff-coverable runes among the ids
     * {@code present} accepts, or {@code null} when none of them covers a single one
     * (which is also what keeps a useless staff — an air staff for Lvl-5 — out of every
     * "is a staff available?" answer). Ties keep the enum-declared order.
     */
    private ElementalStaff bestStaffFor(IntPredicate present, JewelEnchantLevel level) {
        ElementalStaff best = null;
        int bestCovered = 0;
        for (ElementalStaff staff : ElementalStaff.values()) {
            if (!present.test(staff.getItemId())) continue;
            int covered = coverageOf(staff, level);
            if (covered > bestCovered) {
                bestCovered = covered;
                best = staff;
            }
        }
        return best;
    }

    /**
     * The best usable staff held in the inventory, or {@code null}. Inventory reads are
     * plain container reads — no bank race-retry sleep — so the per-staff scan is free.
     */
    private ElementalStaff bestStaffInInventory(JewelEnchantLevel level) {
        return bestStaffFor(id -> Rs2Inventory.hasItem(id), level);
    }

    /**
     * Best usable staff held only as a banknote. A note is not wearable, but it is bankable:
     * the cycle deposits it and the bank then holds the base staff.
     */
    private ElementalStaff notedStaffInInventory(JewelEnchantLevel level) {
        return bestStaffFor(AScriptBank::hasNotedItem, level);
    }

    /**
     * Best staff available in the bank for {@code level}: the one covering the
     * most of its staff-coverable runes (a mud battlestaff beats a plain earth
     * staff for Lvl-5).
     * <p>
     * ONE pass over the bank snapshot ({@link Rs2Bank#getAll()}). The previous
     * per-staff {@code hasBankItem} scan paid a 2-tick race-retry sleep for every
     * ABSENT staff — ~20 s across the enum — and read as a frozen step: a human
     * watching took over mid-cycle on 2026-10-14. The snapshot is fresh here —
     * every caller runs with the bank just opened. If the pass finds nothing, ONE
     * re-read after two ticks guards the stale-mirror case the retrying scan was
     * built for. Returns {@code null} when the bank holds no usable staff.
     */
    private ElementalStaff bestStaffInBank(JewelEnchantLevel level) {
        ElementalStaff best = bankStaffFromMirror(level);
        if (best == null) {
            Rs2Random.waitEx(1200, 300);
            best = bankStaffFromMirror(level);
        }
        return best;
    }

    /**
     * Mirror-only variant of {@link #bestStaffInBank(JewelEnchantLevel)} — the same read
     * without the 1.2 s stale-mirror re-read. Used from {@link #needsBank}, which runs on
     * every tick: a sleep there would stall the orchestrator loop.
     */
    private ElementalStaff bankStaffFromMirror(JewelEnchantLevel level) {
        Set<Integer> ids = bankItemIds();
        return bestStaffFor(ids::contains, level);
    }

    /** Item ids currently in the bank snapshot. */
    private Set<Integer> bankItemIds() {
        return Rs2Bank.getAll().map(Rs2ItemModel::getId).collect(Collectors.toSet());
    }

    /**
     * The staff the module will actually wear for {@code level} this cycle: the WORN one
     * (kept as-is), else the better of the held one and — when {@code includeBank} — the
     * bank one. Only ONE staff can be worn, so "better" is coverage of the level's
     * staff-coverable runes, and ties go to the item already at hand: a staff the player
     * carries wins over a bank round trip, while a held staff that does not cover the
     * spell's rune needs in full lets the bank's better staff win.
     */
    private ElementalStaff staffToWear(JewelEnchantLevel level, boolean includeBank) {
        ElementalStaff worn = wornStaffFor(level);
        if (worn != null) return worn;
        ElementalStaff held = bestStaffInInventory(level);
        ElementalStaff bankStaff = includeBank ? bankStaffFromMirror(level) : null;
        return bankStaffWins(held, bankStaff, level) ? bankStaff : held;
    }

    /**
     * True when the bank's staff is the one to wear: nothing usable is held, or the bank's
     * covers STRICTLY more of the level's staff-coverable runes (a mud battlestaff beats the
     * earth staff in hand for Lvl-5). This is the single tie-break rule behind every staff
     * decision — ties go to the item already at hand, so a staff the player carries wins over
     * a bank action and a held staff that already covers the spell's rune needs in full is
     * never traded for a bank round trip.
     */
    private boolean bankStaffWins(ElementalStaff held, ElementalStaff bankStaff, JewelEnchantLevel level) {
        return bankStaff != null && (held == null || coverageOf(bankStaff, level) > coverageOf(held, level));
    }

    /**
     * Elemental runes of {@code level} still missing from the inventory, given
     * the runes the staff the module WILL wear supplies for free — the worn one,
     * else the better of the held one and the one {@link #doBank} pulls out of
     * the open bank for {@link #ensureStaffWorn} to wear. Cosmic is handled
     * separately by the callers.
     */
    private Map<Runes, Integer> runesNeededInInventory(AScriptConfig config, JewelEnchantLevel level) {
        Set<Runes> covered = EnumSet.noneOf(Runes.class);
        if (config.jewelEnchantUseStaff()) {
            ElementalStaff staff = staffToWear(level, Rs2Bank.isOpen());
            if (staff != null) {
                for (Runes rune : staffCoverableRunes(level)) {
                    if (staff.providesRune(rune)) covered.add(rune);
                }
            }
        }

        Map<Runes, Integer> needed = new LinkedHashMap<>();
        for (Map.Entry<Runes, Integer> entry : level.getRequiredRunes().entrySet()) {
            Runes rune = entry.getKey();
            if (rune == Runes.COSMIC || covered.contains(rune)) continue;
            if (!Rs2Inventory.hasItemAmount(rune.getItemId(), entry.getValue())) {
                needed.put(rune, entry.getValue());
            }
        }
        return needed;
    }

    /**
     * Wear the elemental staff held in the inventory, so the runes it supplies do not have
     * to be carried. Runs with the BANK CLOSED — with the bank open an inventory left-click
     * is a deposit, never a Wear, which is why every wear goes through here: from
     * {@link #doAction} when no bank trip is needed, and from {@link #doBank}'s staff step
     * (which closes the window first) when the run needs the bank. The click is the client
     * mouse (no injected menu entries); a staff is a single held instance, so it resolves
     * through {@link Rs2Inventory#get(int)} like a tool, not through the slot-randomising
     * pick. ONE re-click covers a single lost click, mirroring {@link #selectSpell}.
     *
     * @return true when a staff is worn, when none is held (the bank cycle owns that state)
     *         or when a cast is armed and the wear must wait a tick; false when the wear
     *         failed, which counts as a failed attempt
     */
    private boolean ensureStaffWorn(AScriptConfig config, JewelEnchantLevel level) {
        if (!config.jewelEnchantUseStaff() || staffCoverableRunes(level).isEmpty()) return true;
        if (isWearingStaffFor(level)) return true;

        ElementalStaff staff = bestStaffInInventory(level);
        if (staff == null) return true; // nothing usable in hand — needsBank/doBank own that state

        // Never left-click the inventory while a cast is armed: the click would cast the
        // armed spell on the staff instead of wearing it. Skipping one tick lets the cast
        // flow below consume the selection; the next tick wears the staff.
        if (Rs2Inventory.isItemSelected()) return true;

        Microbot.status = "WEARING — " + staff.name();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!Rs2Tab.switchTo(InterfaceTab.INVENTORY)) {
                failEnchant("could not open the inventory tab to wear " + staff.name());
                return false;
            }
            Rs2ItemModel held = Rs2Inventory.get(staff.getItemId());
            Rectangle bounds = held == null ? null : Rs2Inventory.itemBounds(held);
            if (bounds == null) {
                log.warn("[JewelEnchant] No clickable inventory slot for {}", staff.name());
                failEnchant("no clickable inventory slot for " + staff.name());
                return false;
            }
            Microbot.getMouse().click(bounds);
            if (sleepUntil(() -> isWearingStaffFor(level),
                    Rs2Random.logNormalBounded(1200, 2200, weatherMultiplier))) {
                log.debug("[JewelEnchant] Wore the held {}", staff.name());
                return true;
            }
        }
        failEnchant("could not wear " + staff.name());
        return false;
    }

    /**
     * Stop the module: status, log, Discord notification and config reset. Every
     * stop path goes through here so a self-stop is never silent.
     */
    private void stopWithMessage(String message) {
        Microbot.status = "STOPPED — " + message;
        log.warn("[JewelEnchant] Stopping: {}", message);
        AScriptNotify.notify("aScript Stopped — Jewel Enchant", message);
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
    }
}
