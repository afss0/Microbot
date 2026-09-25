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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    public enum Phase {
        NONE, ENCHANT
    }

    // ── Lifecycle ─────────────────────────────────────────

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveEnchantFailures = 0;
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

        // Need the unenchanted jewellery
        if (!Rs2Inventory.hasItem(item.getUnenchantedId())) return true;

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

        // Missing from both inventory AND bank
        if (!Rs2Inventory.hasItem(item.getUnenchantedId()) && !Rs2Bank.hasItem(item.getUnenchantedId())) {
            return true;
        }
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId()) && !Rs2Bank.hasItem(Runes.COSMIC.getItemId())) {
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
                && !Rs2Bank.hasItem(item.getUnenchantedId())) {
            sb.append("jewellery (not in bank), ");
        }
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId())
                && !Rs2Bank.hasItem(Runes.COSMIC.getItemId())) {
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

        // 3. Equip the elemental staff when staff mode is on — it then supplies its
        //    own runes, so only the remaining ones are withdrawn in step 4.
        if (config.jewelEnchantUseStaff() && !staffCoverableRunes(level).isEmpty()) {
            if (!equipStaff(level)) {
                log.warn("[JewelEnchant] No usable elemental staff for {}", level.getName());
                AScriptNotify.notify("Staff Equip Failed",
                        "No usable elemental staff in bank — uncheck \"Use Elemental Staff\" or bank one");
                return false;
            }
        }

        // 4. Withdraw the elemental runes the equipped staff does not supply
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, level).entrySet()) {
            if (!AScriptBank.withdrawVerified(String.valueOf(entry.getKey().getItemId()))) {
                AScriptNotify.notify("Banking Failed", "No " + entry.getKey().name() + " runes in bank");
                return false;
            }
        }

        // 5. Withdraw unenchanted jewellery (fill remaining inventory)
        if (!AScriptBank.withdrawVerified(String.valueOf(item.getUnenchantedId()))) {
            AScriptNotify.notify("Banking Failed", "No " + item.getName() + " in bank");
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

        // Verify we have runes and jewellery
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId())) {
            log.warn("[JewelEnchant] No cosmic runes in inventory");
            exitRequested = true;
            stopWithMessage("No cosmic runes");
            return;
        }

        // Nothing to enchant — bail before spending a spell selection on an empty
        // stack; the orchestrator banks for more.
        if (Rs2Inventory.getLast(item.getUnenchantedId()) == null) {
            consecutiveEnchantFailures = 0;
            return;
        }
        int remaining = Rs2Inventory.itemQuantity(item.getUnenchantedId());

        // 1. Select the enchant spell by clicking its spellbook icon with the
        //    client mouse (no injected menu entries).
        if (!selectSpell(spell)) {
            failEnchant("could not click " + level.getName() + " in the spellbook");
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
        if (!clickInventoryItem(jewellery)) {
            failEnchant("no clickable inventory slot for " + item.getName());
            return;
        }

        // 3. Confirm the enchant landed — the jewellery count drops or the player
        //    animates. Anything else (missed click, spell not selected) is a failed
        //    attempt, counted so a broken run stops instead of spinning forever.
        boolean enchanted = sleepUntil(
                () -> Rs2Inventory.itemQuantity(item.getUnenchantedId()) < remaining
                        || Rs2Player.isAnimating(),
                Rs2Random.logNormalBounded(1500, 3000, weatherMultiplier));
        if (!enchanted) {
            failEnchant("no enchant animation and " + item.getName() + " count unchanged");
            return;
        }

        consecutiveEnchantFailures = 0;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(300, 700, weatherMultiplier));
    }

    @Override
    public boolean isPrecisionModule() {
        return false; // enchanting uses spellbook + inventory clicks, not precise widget positioning
    }

    // ── Internal helpers ──────────────────────────────────

    /**
     * Select the enchant spell by clicking its icon in the spellbook with the
     * client mouse — no injected menu entries. Opens the "Jewellery
     * Enchantments" sub-tab first when the icons are not visible yet.
     *
     * @return true when the spell icon was clicked
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

        Microbot.getMouse().click(bounds);
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
     * Only meaningful with the bank open — {@code Rs2Bank.hasItem} reads a cached
     * snapshot that stays empty while the bank never opened. Returns {@code null}
     * when the bank holds no usable staff.
     */
    private ElementalStaff bestStaffInBank(JewelEnchantLevel level) {
        ElementalStaff best = null;
        int bestCovered = 0;
        for (ElementalStaff staff : ElementalStaff.values()) {
            if (!Rs2Bank.hasItem(staff.getItemId())) continue;
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
