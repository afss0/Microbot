package net.runelite.client.plugins.microbot.ascript.boltenchant;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.magic.Rs2Staff;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.Rectangle;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Bolt Enchant sub-script — enchants gem-tipped crossbow bolts with the single
 * "Enchant Crossbow Bolt" spell.
 * <p>
 * <b>Batch model.</b> The spellbook icon ({@code 218/13}, gameval
 * {@code MagicSpellbook.XBOWS_ENCHANT}) opens the bolt interface; clicking a bolt's row there
 * makes the game repeat the cast ten times (up to 100 bolts per selection) and then stop on its
 * own. The module therefore clicks <b>once per batch</b> and tracks the batch across ticks by the
 * unenchanted bolt count — a mid-batch row click resolves as a cancel, so the click is
 * unreachable until the count has been still for {@link #STALL_MS}.
 * <p>
 * <b>Banking</b> follows {@code JewelEnchantScript}: the cosmic rune stack is the locked anchor,
 * an elemental staff is taken from wherever it already is (worn &gt; held &gt; bank, worn with the
 * bank CLOSED), and the restock ends with a contained-inventory sweep. Staff mode is an
 * optimisation — no usable staff anywhere means the runes are carried instead, never a stop.
 */
@Slf4j
public class BoltEnchantScript implements AModule {

    /** Failed batch starts in a row before the module stops and notifies. */
    private static final int MAX_FAILURES = 3;
    /** Spellbook container; {@code 218/13} is the "Enchant Crossbow Bolt" icon (action: View). */
    private static final int SPELLBOOK_PARENT_ID = 218;
    private static final int SPELLBOOK_CHILD_ID = 3;
    private static final int ENCHANT_BOLT_SPELL_CHILD_ID = 13;
    /** "Back" button of a spellbook sub-page (gameval {@code MagicSpellbook.BACK_BUTTON}). */
    private static final int SPELLBOOK_BACK_CHILD_ID = 4;
    /** Minimum gap between two "row not found" WARNs — the tick runs every ~600 ms. */
    private static final long ROW_WARN_INTERVAL_MS = 10_000L;
    /**
     * A batch is over when the unenchanted count has not moved for this long. One cast is ~2 game
     * ticks and refreshes the timestamp, so the window is several times the per-cast interval
     * (it cannot fire mid-batch) while a finished repeat is picked up within 7 s.
     */
    private static final long STALL_MS = 7_000L;

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private double weatherMultiplier = 1.0;
    private int consecutiveFailures = 0;
    /** Timestamp of the last random AFK — minimum 5 s between AFKs. */
    private long lastAfkTime = 0L;
    /** Set while the post-batch break is pending/done — see {@link #needsBank}. */
    private boolean postBatchBreakDone = false;
    /**
     * True once a batch has actually started since the last bank cycle, so the bank-closed yield in
     * {@link #needsBank} only ever runs after work: with nothing yet enchanted the first bank trip
     * happens immediately.
     */
    private boolean batchRanSinceBank = false;
    /** Unenchanted count seen on the previous tick; -1 until the first read. */
    private int lastSeenRaw = -1;
    /** When the count last dropped (or a batch was started); 0 = stale, dispatch allowed. */
    private long lastProgressAt = 0L;
    /** Rate limit for the row-not-found warn. */
    private long lastRowWarnAt = 0L;

    public enum Phase {
        NONE, ENCHANT
    }

    // ── Lifecycle ─────────────────────────────────────────

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFailures = 0;
        lastAfkTime = 0L;
        postBatchBreakDone = false;
        batchRanSinceBank = false;
        lastSeenRaw = -1;
        lastProgressAt = 0L;
        lastRowWarnAt = 0L;
    }

    // ── Phase resolution ──────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.BOLT_ENCHANT
                || config.boltEnchantBoltType() == null
                || config.boltEnchantBoltType() == BoltEnchantType.NONE) {
            currentPhase = Phase.NONE;
            return;
        }
        currentPhase = Phase.ENCHANT;
    }

    @Override
    public boolean isActive() {
        return currentPhase != Phase.NONE;
    }

    @Override
    public String runKey(AScriptConfig config) {
        return "BOLT_ENCHANT:" + config.boltEnchantBoltType()
                + ":" + config.boltEnchantUseStaff();
    }

    // ── Selection validation ──────────────────────────────

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.BOLT_ENCHANT) {
            return "no bolt type selected";
        }
        if (currentPhase == Phase.ENCHANT) {
            BoltEnchantType type = config.boltEnchantBoltType();
            int magic = Rs2Player.getRealSkillLevel(Skill.MAGIC);
            // A real-level read of 0 means skill data has not loaded yet (or the client-thread
            // read timed out) — that is "retry", not a locked spell.
            if (magic > 0 && magic < type.getMagicLevel()) {
                return "need level " + type.getMagicLevel() + " Magic for "
                        + type.getDisplayName() + " (current: " + magic + ")";
            }
            if (!Rs2Magic.isSpellbook(Rs2Spellbook.MODERN)) {
                return "switch to the modern spellbook for Enchant Crossbow Bolt";
            }
        }
        return null;
    }

    // ── Banking needs ─────────────────────────────────────

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        BoltEnchantType type = config.boltEnchantBoltType();
        if (type == null || type == BoltEnchantType.NONE) return false;

        // Fewer bolts than one cast: a batch just ended. Yield one tick so the post-batch break
        // runs with the bank still closed; only a batch that actually started earns that break.
        if (Rs2Inventory.itemQuantity(type.getUnenchantedId()) < BoltEnchantType.BOLTS_PER_CAST) {
            return !batchRanSinceBank || postBatchBreakDone;
        }

        // Cosmic runes are needed by every type and no staff supplies them.
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId())) return true;

        if (config.boltEnchantUseStaff() && type.hasStaffCoverableRune()
                && !isWearingStaffFor(type)) {
            Rs2Staff held = bestStaffInInventory(type);
            Rs2Staff bank = bankStaffFromMirror(type);
            // A bank trip is only justified when the bank's staff is the one to wear. A held
            // staff is worn by doAction (the bank cannot equip), and with no staff anywhere the
            // runes below carry the cast — never a trip, never a stop.
            if (bankStaffWins(held, bank, type)) return true;
        }

        return !runesNeededInInventory(config, type).isEmpty();
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        BoltEnchantType type = config.boltEnchantBoltType();
        if (type == null || type == BoltEnchantType.NONE) return false;

        // Ten per cast, not one: nine bolts in the bank cannot start a batch.
        if (!AScriptBank.bankableAtLeast(type.getUnenchantedId(), BoltEnchantType.BOLTS_PER_CAST)) {
            return true;
        }
        if (!AScriptBank.bankableAtLeast(Runes.COSMIC.getItemId(), 1)) return true;
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, type).entrySet()) {
            if (!AScriptBank.bankableAtLeast(entry.getKey().getItemId(), entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        BoltEnchantType type = config.boltEnchantBoltType();
        if (type == null || type == BoltEnchantType.NONE) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(type.getDisplayName()).append(" enchant: ");
        if (!AScriptBank.bankableAtLeast(type.getUnenchantedId(), BoltEnchantType.BOLTS_PER_CAST)) {
            sb.append("bolts x").append(BoltEnchantType.BOLTS_PER_CAST)
                    .append(" (not in bank), ");
        }
        if (!AScriptBank.bankableAtLeast(Runes.COSMIC.getItemId(), 1)) {
            sb.append("cosmic runes (not in bank), ");
        }
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, type).entrySet()) {
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
        Microbot.status = "BANKING — Bolt Enchant";

        BoltEnchantType type = config.boltEnchantBoltType();
        if (type == null || type == BoltEnchantType.NONE) return false;

        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) return false;

        // 0. STAFF FIRST — before any deposit (a deposit would bank the staff the player is
        //    carrying) and before the bolt/ rune fill (which can take every free slot). Wearing
        //    needs the bank CLOSED, so the cycle is handed back once the staff is on: the next
        //    tick reopens the bank and runs the restock with the staff worn.
        if (config.boltEnchantUseStaff() && type.hasStaffCoverableRune()
                && !isWearingStaffFor(type)) {
            Rs2Staff staff = bestStaffInInventory(type);
            if (staff == null) {
                staff = bestStaffInBank(type);
                if (staff != null) {
                    if (Rs2Inventory.isFull() && !AScriptBank.depositAll()) {
                        log.warn("[BoltEnchant] could not free a slot for the staff withdraw");
                        return false;
                    }
                    if (!AScriptBank.withdrawVerified(String.valueOf(staff.getItemID()))) {
                        log.warn("[BoltEnchant] {} staff withdraw failed (bank open: {}) — retrying",
                                staff.name(), Rs2Bank.isOpen());
                        return false;
                    }
                }
            }
            if (staff != null) {
                if (!closeBankForWear()) return false;
                if (!ensureStaffWorn(config, type)) return false;
                return true; // staff on — the rest of the cycle happens on a later tick
            }
        }

        // 1. Lock the cosmic anchor BEFORE the blanket deposit so the deposit preserves it.
        if (!AScriptBank.ensureStackLocked(Integer.toString(Runes.COSMIC.getItemId()))) {
            return false;
        }

        // 2. Deposit everything the cycle does not need (the locked cosmic slot survives).
        if (!AScriptBank.depositAllExcept(Set.of(Runes.COSMIC.getItemId()))) {
            log.warn("[BoltEnchant] deposit failed");
            return false;
        }

        // 3. Runes the staff does not supply. Re-verify the window before each step: a mid-cycle
        //    close (human takeover, stray ESC) must retry the cycle, not dispatch into nothing.
        if (!AScriptBank.ensureBankOpen()) return false;
        for (Map.Entry<Runes, Integer> entry : runesNeededInInventory(config, type).entrySet()) {
            if (!AScriptBank.withdrawVerified(String.valueOf(entry.getKey().getItemId()))) {
                log.warn("[BoltEnchant] {} rune withdraw failed (bank open: {}) — retrying",
                        entry.getKey().name(), Rs2Bank.isOpen());
                return false;
            }
        }

        // 4. Bolts (whole stack — one batch consumes up to 100).
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(type.getUnenchantedId()))) {
            log.warn("[BoltEnchant] {} withdraw failed (bank open: {}) — retrying",
                    type.getDisplayName(), Rs2Bank.isOpen());
            return false;
        }

        // 5. Contained-inventory sweep: keep exactly this run's materials in the bag, so an item
        //    a staff equip displaced is deposited in the SAME cycle instead of holding a slot.
        Set<Integer> keep = new HashSet<>();
        keep.add(Runes.COSMIC.getItemId());
        keep.add(type.getUnenchantedId());
        for (Runes rune : type.getRunes().keySet()) {
            keep.add(rune.getItemId());
        }
        if (!AScriptBank.depositAllExcept(keep)) {
            log.warn("[BoltEnchant] contained-inventory sweep failed — retrying next cycle");
            return false;
        }

        // Fresh batch scope: the next empty state earns its break only after a batch runs.
        batchRanSinceBank = false;
        // A restock must not wait out a stale stall window before its first batch.
        lastSeenRaw = -1;
        lastProgressAt = 0L;
        return true;
    }

    /**
     * Close the bank window so a staff can be worn — with it open, an inventory left-click is a
     * deposit, never a Wear. Bounded wait; {@code false} when it stayed open so the cycle retries.
     */
    private boolean closeBankForWear() {
        if (!Rs2Bank.isOpen()) return true;
        Rs2Bank.closeBank();
        if (sleepUntil(() -> !Rs2Bank.isOpen(), 2000)) return true;
        log.warn("[BoltEnchant] bank did not close for the staff wear — retrying next cycle");
        return false;
    }

    // ── Action (enchant) ──────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested || Rs2Bank.isOpen()) return;
        BoltEnchantType type = config.boltEnchantBoltType();
        if (type == null || type == BoltEnchantType.NONE) return;

        Microbot.status = "ENCHANTING — " + type.getDisplayName();
        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        int raw = Rs2Inventory.itemQuantity(type.getUnenchantedId());
        if (raw < BoltEnchantType.BOLTS_PER_CAST) {
            consecutiveFailures = 0;
            postBatchBreak(config);
            return;
        }

        // Progress: every cast consumes 10 bolts. A drop means the repeat is running — never
        // touch the interface while it does (a mid-batch row click cancels it).
        long now = System.currentTimeMillis();
        if (lastSeenRaw >= 0 && raw < lastSeenRaw) {
            lastSeenRaw = raw;
            lastProgressAt = now;
            consecutiveFailures = 0;
            return;
        }
        lastSeenRaw = raw;
        if (now - lastProgressAt < STALL_MS) return;

        // The count is stale: the 10-cast repeat finished, or it never started. Runes are checked
        // BEFORE the click so "the batch stopped because the runes ran out" routes to the bank
        // cycle instead of spending a failure life.
        if (!ensureStaffWorn(config, type)) return;
        if (!Rs2Inventory.hasItem(Runes.COSMIC.getItemId())) return;
        if (!runesNeededInInventory(config, type).isEmpty()) return;

        if (!interfaceOpen() && !openInterface()) {
            failOnce("could not open the bolt enchant interface");
            return;
        }
        Rectangle row = resolveRowBounds(type);
        if (row == null) {
            // A SKILLMULTI window from another activity (fletching, smelting) is a different
            // interface that happens to share the group: close it so the next tick reopens the
            // bolt panel instead of repeatedly clicking rows that are not ours.
            if (interfaceOpen()) closeForeignInterface();
            long sinceWarn = now - lastRowWarnAt;
            if (sinceWarn > ROW_WARN_INTERVAL_MS) {
                lastRowWarnAt = now;
                log.warn("[BoltEnchant] no interface row for {}", type.getDisplayName());
            }
            failOnce("no row for " + type.getDisplayName() + " in the bolt interface");
            return;
        }

        batchRanSinceBank = true;
        postBatchBreakDone = false;
        Microbot.getMouse().click(row);

        // Start confirmation: the first cast lands within ~2 ticks, or the cast animation runs.
        int rawAtStart = raw;
        boolean started = sleepUntil(
                () -> Rs2Inventory.itemQuantity(type.getUnenchantedId()) < rawAtStart
                        || Rs2Player.isAnimating(),
                Rs2Random.logNormalBounded(2500, 4500, weatherMultiplier));
        if (!started) {
            failOnce("batch did not start for " + type.getDisplayName());
            return;
        }

        consecutiveFailures = 0;
        lastProgressAt = System.currentTimeMillis();
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(400, 900, weatherMultiplier));
    }

    // ── Interface ─────────────────────────────────────────

    /** True when the bolt interface (SKILLMULTI) is on screen. */
    private boolean interfaceOpen() {
        return Rs2Widget.isProductionWidgetOpen();
    }

    /**
     * Dismiss an open SKILLMULTI window that does not belong to this module (another activity's
     * production interface shares the group id). ESC is the game's own close key; the caller
     * retries the bolt panel on a later tick.
     */
    private void closeForeignInterface() {
        Rs2Keyboard.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
        sleepUntil(() -> !interfaceOpen(), Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
    }

    /**
     * Open the bolt interface: switch to the magic tab and click the "Enchant Crossbow Bolt"
     * spell icon ({@code 218/13}, action "View"), which opens the SKILLMULTI bolt panel.
     * <p>
     * The spellbook may be parked on a sub-page (jewellery enchantments), which hides the bolt
     * icon: the back button ({@code 218/4}) returns to the main page first. One bounded re-click
     * covers a lost click; a stale cursor selection is cleared before clicking — an armed spell
     * or item would otherwise consume the click.
     */
    private boolean openInterface() {
        if (Rs2Inventory.isItemSelected() && !clearCursorSelection()) {
            log.warn("[BoltEnchant] could not clear a stale cursor selection before the spell");
            return false;
        }
        if (!Rs2Tab.switchTo(InterfaceTab.MAGIC)) {
            log.warn("[BoltEnchant] could not open the magic tab");
            return false;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            Rectangle icon = boltSpellBounds();
            if (icon == null && clickSpellbookBack()) {
                sleepUntil(() -> boltSpellBounds() != null, 1500);
                icon = boltSpellBounds();
            }
            if (icon == null) {
                log.warn("[BoltEnchant] Enchant Crossbow Bolt icon not visible in the spellbook");
                return false;
            }
            Microbot.getMouse().click(icon);
            if (sleepUntil(this::interfaceOpen,
                    Rs2Random.logNormalBounded(1500, 2500, weatherMultiplier))) {
                return true;
            }
        }
        log.warn("[BoltEnchant] bolt interface did not open");
        return false;
    }

    /**
     * Leave a spellbook sub-page (jewellery enchantments, bolt panel) by clicking its back
     * button. Returns {@code false} when no back button is visible — there is nothing to leave.
     */
    private boolean clickSpellbookBack() {
        Rectangle back = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget button = Rs2Widget.getWidget(SPELLBOOK_PARENT_ID, SPELLBOOK_BACK_CHILD_ID);
            if (button == null || button.isHidden()) return null;
            Rectangle bounds = button.getBounds();
            return bounds == null || bounds.width <= 0 || bounds.height <= 0 ? null : bounds;
        }).orElse(null);
        if (back == null) return false;
        Microbot.getMouse().click(back);
        return true;
    }

    /** Bounds of the {@code 218/13} spell icon, or {@code null} when the page is closed. */
    private Rectangle boltSpellBounds() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget icon = Rs2Widget.getWidget(SPELLBOOK_PARENT_ID, ENCHANT_BOLT_SPELL_CHILD_ID);
            if (icon == null || icon.isHidden()) return null;
            Rectangle bounds = icon.getBounds();
            return bounds == null || bounds.width <= 0 || bounds.height <= 0 ? null : bounds;
        }).orElse(null);
    }

    /**
     * Resolve the clickable row of {@code type} inside the bolt interface, immediately before the
     * click. The row is matched by <b>item id first</b> (the interface's own identity) and by the
     * tag-stripped label second; zero-area rows are rejected rather than clicked blindly.
     */
    private Rectangle resolveRowBounds(BoltEnchantType type) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget root = Microbot.getClient().getWidget(InterfaceID.SKILLMULTI, 0);
            if (root == null) return null;
            Widget row = findWidget(root, w -> !w.isHidden() && w.getItemId() == type.getEnchantedId());
            if (row == null) {
                String label = type.interfaceLabel();
                row = findWidget(root, w -> !w.isHidden() && label.equalsIgnoreCase(stripTags(w.getText())));
            }
            return usableBounds(row, root);
        }).orElse(null);
    }

    /**
     * Cancel a stale cursor selection by clicking the selected widget itself: an inventory item's
     * own slot cancels the item, and clicking the selected spell icon again deselects the spell.
     * Never click an arbitrary inventory item here — an armed spell would be cast on it.
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

    private static Widget findWidget(Widget root, Predicate<Widget> predicate) {
        if (root == null) return null;
        if (predicate.test(root)) return root;
        Widget[][] groups = {
                root.getChildren(), root.getNestedChildren(),
                root.getDynamicChildren(), root.getStaticChildren()
        };
        for (Widget[] group : groups) {
            if (group == null) continue;
            for (Widget child : group) {
                Widget found = findWidget(child, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Rectangle usableBounds(Widget target, Widget root) {
        if (target == null) return null;
        Rectangle bounds = usable(target.getBounds());
        if (bounds != null) return bounds;
        bounds = firstUsable(target.getDynamicChildren());
        if (bounds != null) return bounds;
        bounds = firstUsable(target.getStaticChildren());
        if (bounds != null) return bounds;
        bounds = firstUsable(target.getChildren());
        if (bounds != null) return bounds;
        bounds = firstUsable(target.getNestedChildren());
        if (bounds != null) return bounds;
        Widget parent = target.getParent();
        if (parent == null || parent == root) return null;
        return firstUsable(new Widget[]{parent});
    }

    private static Rectangle firstUsable(Widget[] widgets) {
        if (widgets == null) return null;
        for (Widget widget : widgets) {
            if (widget == null) continue;
            Rectangle bounds = usable(widget.getBounds());
            if (bounds != null) return bounds;
            bounds = firstUsable(widget.getDynamicChildren());
            if (bounds != null) return bounds;
            bounds = firstUsable(widget.getStaticChildren());
            if (bounds != null) return bounds;
        }
        return null;
    }

    private static Rectangle usable(Rectangle bounds) {
        return bounds != null && bounds.width > 0 && bounds.height > 0 ? bounds : null;
    }

    private static String stripTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
    }

    // ── Staff ─────────────────────────────────────────────

    /** True when the worn staff supplies at least one rune of {@code type}. */
    private boolean isWearingStaffFor(BoltEnchantType type) {
        return bestStaffFor(id -> Rs2Equipment.isWearing(id), type) != null;
    }

    /** Number of {@code type}'s staff-coverable runes {@code staff} supplies. */
    private int coverageOf(Rs2Staff staff, BoltEnchantType type) {
        int covered = 0;
        for (Runes rune : type.getRunes().keySet()) {
            if (staff.provides(rune)) covered++;
        }
        return covered;
    }

    /**
     * The staff covering the most of {@code type}'s runes among the ids {@code present} accepts,
     * or {@code null} when none covers one (which keeps a useless staff — an air staff for an
     * onyx bolt — out of every "is a staff available?" answer). Ties keep the enum order.
     */
    private Rs2Staff bestStaffFor(IntPredicate present, BoltEnchantType type) {
        Rs2Staff best = null;
        int bestCovered = 0;
        for (Rs2Staff staff : Rs2Staff.values()) {
            if (staff == Rs2Staff.NONE || !present.test(staff.getItemID())) continue;
            int covered = coverageOf(staff, type);
            if (covered > bestCovered) {
                bestCovered = covered;
                best = staff;
            }
        }
        return best;
    }

    private Rs2Staff bestStaffInInventory(BoltEnchantType type) {
        return bestStaffFor(Rs2Inventory::hasItem, type);
    }

    /** Best staff in the bank snapshot. The snapshot is only fresh with the bank open. */
    private Rs2Staff bestStaffInBank(BoltEnchantType type) {
        Rs2Staff best = bankStaffFromMirror(type);
        if (best == null) {
            Rs2Random.waitEx(1200, 300);
            best = bankStaffFromMirror(type);
        }
        return best;
    }

    /** Mirror-only variant — safe from per-tick callers ({@link #needsBank}) that must not sleep. */
    private Rs2Staff bankStaffFromMirror(BoltEnchantType type) {
        Set<Integer> ids = Rs2Bank.getAll().map(Rs2ItemModel::getId).collect(Collectors.toSet());
        return bestStaffFor(ids::contains, type);
    }

    /**
     * The staff to wear this cycle: the WORN one (kept as-is), else the better of the held one and
     * — when {@code includeBank} — the bank one. Ties go to the item already at hand.
     */
    private Rs2Staff staffToWear(BoltEnchantType type, boolean includeBank) {
        Rs2Staff worn = bestStaffFor(id -> Rs2Equipment.isWearing(id), type);
        if (worn != null) return worn;
        Rs2Staff held = bestStaffInInventory(type);
        Rs2Staff bank = includeBank ? bankStaffFromMirror(type) : null;
        return bankStaffWins(held, bank, type) ? bank : held;
    }

    private boolean bankStaffWins(Rs2Staff held, Rs2Staff bankStaff, BoltEnchantType type) {
        return bankStaff != null
                && (held == null || coverageOf(bankStaff, type) > coverageOf(held, type));
    }

    /**
     * Runes of {@code type} still missing from the inventory, given what the staff the module WILL
     * wear supplies for free. Cosmic is included here like any other rune (no staff supplies it).
     */
    private Map<Runes, Integer> runesNeededInInventory(AScriptConfig config, BoltEnchantType type) {
        Set<Runes> covered = EnumSet.noneOf(Runes.class);
        if (config.boltEnchantUseStaff()) {
            Rs2Staff staff = staffToWear(type, Rs2Bank.isOpen());
            if (staff != null) {
                for (Runes rune : type.getRunes().keySet()) {
                    if (staff.provides(rune)) covered.add(rune);
                }
            }
        }
        Map<Runes, Integer> needed = new LinkedHashMap<>();
        for (Map.Entry<Runes, Integer> entry : type.getRunes().entrySet()) {
            Runes rune = entry.getKey();
            if (covered.contains(rune)) continue;
            if (!Rs2Inventory.hasItemAmount(rune.getItemId(), entry.getValue())) {
                needed.put(rune, entry.getValue());
            }
        }
        return needed;
    }

    /**
     * Wear the staff the module resolved, with the BANK CLOSED (with it open an inventory
     * left-click is a deposit, never a Wear). One bounded re-click covers a lost click; a cast
     * armed on the cursor defers the wear one tick instead of wearing the staff into a cast.
     *
     * @return false only on a failed wear, which counts as a failed attempt
     */
    private boolean ensureStaffWorn(AScriptConfig config, BoltEnchantType type) {
        if (!config.boltEnchantUseStaff() || !type.hasStaffCoverableRune()) return true;
        if (isWearingStaffFor(type)) return true;

        Rs2Staff staff = bestStaffInInventory(type);
        if (staff == null) return true; // nothing usable in hand — banking owns that state

        if (Rs2Inventory.isItemSelected()) return true;

        Microbot.status = "WEARING — " + staff.name();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!Rs2Tab.switchTo(InterfaceTab.INVENTORY)) {
                failOnce("could not open the inventory tab to wear " + staff.name());
                return false;
            }
            Rs2ItemModel held = Rs2Inventory.get(staff.getItemID());
            Rectangle bounds = held == null ? null : Rs2Inventory.itemBounds(held);
            if (bounds == null) {
                failOnce("no clickable inventory slot for " + staff.name());
                return false;
            }
            Microbot.getMouse().click(bounds);
            if (sleepUntil(() -> isWearingStaffFor(type),
                    Rs2Random.logNormalBounded(1200, 2200, weatherMultiplier))) {
                log.debug("[BoltEnchant] wore the held {}", staff.name());
                return true;
            }
        }
        failOnce("could not wear " + staff.name());
        return false;
    }

    // ── Randomness / stops ────────────────────────────────

    /**
     * Post-batch break — a short randomized pause, then the optional random AFK (log-normal,
     * weather-modulated, interruptible so blocking events still fire). Runs while
     * {@link #needsBank} yields, i.e. with the bank still closed.
     */
    private void postBatchBreak(AScriptConfig config) {
        if (postBatchBreakDone) return;
        postBatchBreakDone = true;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.boltEnchantAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3_000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    /**
     * Register a failed batch start: log it, back off, and stop the module (with a Discord
     * notification) after {@link #MAX_FAILURES} in a row — a data problem must never spin.
     */
    private void failOnce(String reason) {
        consecutiveFailures++;
        log.warn("[BoltEnchant] attempt failed ({}/{}): {}",
                consecutiveFailures, MAX_FAILURES, reason);
        if (consecutiveFailures >= MAX_FAILURES) {
            exitRequested = true;
            stopWithMessage(reason);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }

    /** Stop the module: status, log, Discord notification and config reset — never silent. */
    private void stopWithMessage(String message) {
        Microbot.status = "STOPPED — " + message;
        log.warn("[BoltEnchant] Stopping: {}", message);
        AScriptNotify.notify("aScript Stopped — Bolt Enchant", message);
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection",
                ScriptType.IDLE);
    }
}
