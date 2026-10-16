package net.runelite.client.plugins.microbot.ascript.jewellenchant;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
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
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Jewellery Enchanting sub-script — stateless helper called by AScript.
 * <p>
 * Enchants jewellery using the Lvl-1 through Lvl-7 Enchant spells on the
 * Modern spellbank. Handles rune withdrawal, elemental staff equipping, and
 * the enchant spell cast + jewellery click sequence.
 * <p>
 * The cosmic rune stack is held as a locked inventory slot (like the jewellery
 * mould in {@code CraftingScript}) so the banking deposit never removes it —
 * {@link AScriptBank#ensureStackLocked(String)} withdraws the whole stack when
 * it runs out and re-locks it.
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
            if (!Rs2Player.getSkillRequirement(Skill.MAGIC, level.getRequiredLevel())) {
                return "need level " + level.getRequiredLevel() + " Magic for " + level.getName()
                        + " (current: " + Rs2Player.getRealSkillLevel(Skill.MAGIC) + ")";
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

        // Staff mode: the configured staff must be equipped. A level whose runes no
        // staff supplies (Lvl-7 blood/soul) never requires one.
        if (config.jewelEnchantUseStaff()
                && !staffCoverableRunes(level).isEmpty()
                && !isWearingStaffFor(level)) {
            return true;
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

        // Missing from both inventory AND bank (retrying bank reads — the bare mirror read
        // can report an item the bank no longer lists)
        if (!Rs2Inventory.hasItem(item.getUnenchantedId()) && !Rs2Bank.hasBankItem(item.getUnenchantedId(), 1)) {
            return true;
        }
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId()) && !Rs2Bank.hasBankItem(Runes.COSMIC.getItemId(), 1)) {
            return true;
        }

        // Staff mode: no usable staff worn and none in the (now open) bank — stop
        // with a message instead of looping on a staff that can never be equipped.
        if (config.jewelEnchantUseStaff()
                && !staffCoverableRunes(level).isEmpty()
                && !isWearingStaffFor(level)
                && bestStaffInBank(level) == null) {
            return true;
        }

        // Runes the staff will not supply must be in the bank in full-cast
        // quantity: a partial stack would bank forever — the withdraw succeeds
        // while every cast keeps failing on the missing runes.
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, level).entrySet()) {
            if (!Rs2Bank.hasBankItem(entry.getKey().getItemId(), entry.getValue())) {
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
        if (!Rs2Inventory.hasItem(item.getUnenchantedId())
                && !Rs2Bank.hasBankItem(item.getUnenchantedId(), 1)) {
            sb.append("jewellery (not in bank), ");
        }
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId())
                && !Rs2Bank.hasBankItem(Runes.COSMIC.getItemId(), 1)) {
            sb.append("cosmic runes (not in bank), ");
        }
        JewelEnchantLevel level = item.getEnchantLevel();
        if (config.jewelEnchantUseStaff()
                && !staffCoverableRunes(level).isEmpty()
                && !isWearingStaffFor(level)
                && bestStaffInBank(level) == null) {
            sb.append("no elemental staff (not worn, not in bank), ");
        }
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, level).entrySet()) {
            if (!Rs2Bank.hasBankItem(entry.getKey().getItemId(), entry.getValue())) {
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

        // 1. Lock the cosmic rune slot FIRST — if the runes are already in the
        //    inventory their slot gets locked before the deposit, so the blanket
        //    deposit below preserves them. If they are missing, ensureStackLocked
        //    withdraws the WHOLE stack (one rune per cast would bank again on the
        //    next cast) and locks its slot. Same pattern as the jewellery mould in
        //    CraftingScript.bankJewelry().
        if (!AScriptBank.ensureStackLocked(Integer.toString(Runes.COSMIC.getItemId()))) {
            AScriptNotify.notify("Banking Failed", "No cosmic runes in bank");
            return false;
        }

        // 2. Deposit the rest via the toolbar button; the locked cosmic slot is
        //    preserved (a locked stack is only released when it is consumed empty).
        if (!AScriptBank.depositAndWaitEmpty()) {
            log.warn("[JewelEnchant] deposit failed");
            return false;
        }

        // 3. Withdraw the elemental runes the (worn or to-be-equipped) staff does not
        //    supply. Re-verify the bank window first: a mid-cycle close (a human taking
        //    over at the keyboard, a stray ESC) used to leave the remaining withdraws
        //    dispatching into nothing, and the cycle ended with a misleading "no
        //    materials" stop (2026-10-14). The retry/stop verdicts for genuinely
        //    missing materials belong to the orchestrator's missing-materials path.
        if (!ensureBankOpen()) return false;
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, level).entrySet()) {
            if (!AScriptBank.withdrawVerified(String.valueOf(entry.getKey().getItemId()))) {
                log.warn("[JewelEnchant] {} rune withdraw failed (bank open: {}) — retrying next cycle",
                        entry.getKey().name(), Rs2Bank.isOpen());
                return false;
            }
        }

        // 4. Withdraw unenchanted jewellery (fill remaining inventory)
        if (!ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(item.getUnenchantedId()))) {
            log.warn("[JewelEnchant] {} withdraw failed (bank open: {}) — retrying next cycle",
                    item.getName(), Rs2Bank.isOpen());
            return false;
        }

        // 5. Equip the elemental staff LAST — after every withdraw that needs the bank
        //    window. The equip path interacts with the bank UI; with nothing left to
        //    withdraw, a state change mid-equip can no longer break the cycle. The
        //    early isWearingStaffFor() check also picks up a staff a human equipped
        //    while the earlier steps ran.
        if (config.jewelEnchantUseStaff() && !staffCoverableRunes(level).isEmpty()) {
            if (!ensureBankOpen()) return false;
            if (!equipStaff(level)) {
                log.warn("[JewelEnchant] No usable elemental staff for {}", level.getName());
                AScriptNotify.notify("Staff Equip Failed",
                        "No usable elemental staff in bank — uncheck \"Use Elemental Staff\" or bank one");
                return false;
            }
        }

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
     * True when the bank window is open, reopening it once when it is not. Guards the
     * withdraw steps against a mid-cycle close — the failure that stopped a live run
     * with a misleading "No Sapphire ring in bank" (2026-10-14): the withdraws below
     * dispatch into nothing while the window is closed, and AScriptBank has no
     * reopen logic of its own by design.
     */
    private boolean ensureBankOpen() {
        if (Rs2Bank.isOpen()) return true;
        if (!Rs2Bank.openBank()) {
            log.warn("[JewelEnchant] Bank closed mid-cycle and could not be reopened — retrying next cycle");
            return false;
        }
        return true;
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
        jewelEnchantPostBatchBreakDone = false; // jewellery present again — the next empty state is a new batch end
        batchRanSinceBank = true;               // a batch is being worked — its end earns the closing break

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

        // Resolve the target slot HERE, immediately before the click — never at the
        // top of doAction. The enchant spell consumes the FIRST inventory item of
        // the type it finds regardless of which item was selected, so a piece
        // resolved earlier can already have been enchanted by the time the
        // virtual-mouse click lands, and casting on an already-enchanted item does
        // nothing. Spell selection + tab switch above cost ~0.3-2.5s; resolving
        // here leaves only the mouse travel time. The LAST occupied slot
        // (Rs2Inventory.items() walks the container in ascending slot order, so
        // getLast(id) is the bottom-most piece) is the one least likely to be the
        // spell's first-of-type pick.
        Rs2ItemModel jewellery = Rs2Inventory.getLast(item.getUnenchantedId());
        if (jewellery == null) {
            // Consumed while the spell was being selected — bank for more next tick.
            consecutiveEnchantFailures = 0;
            return;
        }
        int enchantedBefore = enchantedCount(item);
        if (!clickInventoryItem(jewellery)) {
            failEnchant("no clickable inventory slot for " + item.getName());
            return;
        }

        // 3. Confirm the enchant landed — the ENCHANTED side count rises (all known
        //    charge variants), or the player animates while a queued conversion lands.
        //    A bare "raw count dropped" would also fire when a misclick WORE a piece
        //    (the 2026-10-16 report): the raw count drops and the piece sits worn.
        //    Anything else is a failed attempt, counted so a broken run stops instead
        //    of spinning forever.
        boolean enchanted = sleepUntil(
                () -> enchantedCount(item) > enchantedBefore
                        || Rs2Player.isAnimating(),
                Rs2Random.logNormalBounded(1500, 3000, weatherMultiplier));
        if (!enchanted) {
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

        // Click, then VERIFY the spell actually armed: a click that does not land
        // (lag, sub-tab still opening) would leave the next inventory left-click
        // meaning WEAR under the cursor — the misclick class of the 2026-10-16
        // report. One bounded re-click keeps a single lost click from failing the
        // tick; still unarmed counts as a failed attempt.
        Microbot.getMouse().click(bounds);
        if (!sleepUntil(Rs2Inventory::isItemSelected, 1200)) {
            Microbot.getMouse().click(bounds);
            if (!sleepUntil(Rs2Inventory::isItemSelected, 1200)) {
                log.warn("[JewelEnchant] {} click did not arm the spell", spell.getName());
                return false;
            }
        }
        return true;
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
     * Left-click an inventory item's slot with the client mouse. The inventory
     * tab must be open — {@link Rs2Inventory#itemBounds} resolves the dynamic
     * slot widget bounds only for the visible inventory container.
     */
    private boolean clickInventoryItem(Rs2ItemModel item) {
        Rectangle bounds = Rs2Inventory.itemBounds(item);
        if (bounds == null) {
            log.warn("[JewelEnchant] No bounds for {} (slot {})", item.getName(), item.getSlot());
            return false;
        }
        Microbot.getMouse().click(bounds);
        return true;
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
        for (ElementalStaff staff : ElementalStaff.values()) {
            if (Rs2Equipment.isWearing(staff.getItemId()) && coversAny(staff, level)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code staff} supplies at least one staff-coverable rune of {@code level}. */
    private boolean coversAny(ElementalStaff staff, JewelEnchantLevel level) {
        for (Runes rune : staffCoverableRunes(level)) {
            if (staff.providesRune(rune)) return true;
        }
        return false;
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
        ElementalStaff best = pickBestStaff(bankItemIds(), level);
        if (best == null) {
            Rs2Random.waitEx(1200, 300);
            best = pickBestStaff(bankItemIds(), level);
        }
        return best;
    }

    /** Item ids currently in the bank snapshot. */
    private Set<Integer> bankItemIds() {
        return Rs2Bank.getAll().map(Rs2ItemModel::getId).collect(Collectors.toSet());
    }

    /** The bank staff covering the most staff-coverable runes of {@code level}. */
    private ElementalStaff pickBestStaff(Set<Integer> bankIds, JewelEnchantLevel level) {
        ElementalStaff best = null;
        int bestCovered = 0;
        for (ElementalStaff staff : ElementalStaff.values()) {
            if (!bankIds.contains(staff.getItemId())) continue;
            int covered = 0;
            for (Runes rune : staffCoverableRunes(level)) {
                if (staff.providesRune(rune)) covered++;
            }
            if (covered > bestCovered) {
                bestCovered = covered;
                best = staff;
            }
        }
        return best;
    }

    /**
     * Elemental runes of {@code level} still missing from the inventory, given
     * the runes a staff supplies for free: the worn one, plus — when no staff is
     * worn yet — the one that {@link #equipStaff} would take from the open bank.
     * Cosmic is handled separately by the callers.
     */
    private Map<Runes, Integer> runesNeededInInventory(AScriptConfig config, JewelEnchantLevel level) {
        Set<Runes> covered = EnumSet.noneOf(Runes.class);
        if (config.jewelEnchantUseStaff()) {
            // A bank staff only counts as coverage while nothing usable is worn:
            // equipStaff keeps the worn staff, it does not upgrade it.
            ElementalStaff bankStaff = Rs2Bank.isOpen() && !isWearingStaffFor(level)
                    ? bestStaffInBank(level)
                    : null;
            for (ElementalStaff staff : ElementalStaff.values()) {
                if (staff != bankStaff && !Rs2Equipment.isWearing(staff.getItemId())) continue;
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
     * Equip a usable elemental staff for {@code level}: the worn one when it
     * already supplies a required rune, otherwise the best one in the open bank.
     *
     * @return true when a staff supplying at least one required rune is worn
     */
    private boolean equipStaff(JewelEnchantLevel level) {
        if (isWearingStaffFor(level)) return true;

        ElementalStaff staff = bestStaffInBank(level);
        if (staff == null) {
            log.warn("[JewelEnchant] No elemental staff in bank for {}", level.getName());
            return false;
        }

        Rs2Bank.withdrawAndEquip(staff.getItemId());
        if (!sleepUntil(() -> isWearingStaffFor(level), 3000)) {
            log.warn("[JewelEnchant] Failed to equip {}", staff.name());
            return false;
        }

        // A staff swapped out by the equip lands back in the inventory. Leave it:
        // the toolbar deposit in doBank() step 1 returns it next cycle, and a
        // grid-targeted depositAll(id) is forbidden here — it dies silently on
        // grid-silent machines, which would leave the stray in its slot anyway.
        return true;
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
