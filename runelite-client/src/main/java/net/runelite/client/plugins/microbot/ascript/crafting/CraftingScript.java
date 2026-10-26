package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.TileObject;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntilTrue;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptPick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;


import java.awt.event.KeyEvent;

/**
 * Crafting sub-script — stateless helper called by AScript's CRAFTING / BANKING states.
 * <p>
 * Adapted from Microbot Hub CraftingPlugin.
 */
@Slf4j
public class CraftingScript implements AModule {

    private Phase currentPhase = Phase.NONE;
    private long lastAfkTime;
    /** Set once per doCraft() call; used by all craft methods for weather-modulated timing. */
    private double weatherMultiplier = 1.0;

    // ── Jewelry batch tracking ──────────────────────────────────
    /** Bar count at the last observed change; 0 = fresh batch (the action tick resolves it). */
    private int jewelryLastBarCount = 0;
    /** Timestamp of the last jewelry batch progress (bar consumed / batch confirmed started). */
    private long jewelryLastProgressAt = 0L;
    /** Set while the post-batch break is pending/done — see {@link #needsBankJewelry}. */
    private boolean jewelryPostBatchBreakDone = false;
    /**
     * True once a batch has actually run since the last bank cycle (set in {@link #doAction},
     * cleared by a completed bank cycle and on reset). Gates the bank-closed yield in
     * {@link #needsBankJewelry}: with nothing yet worked, the first bank trip happens
     * immediately — never a post-batch break before it.
     */
    private boolean batchRanSinceBank = false;
    /** No bar consumed and no animation for this long = the batch is not running anymore. */
    private static final long JEWELRY_STALL_WINDOW_MS = 8_000L;

    // ── Make-batch latch (gems / glass / staffs / flax / dragon leather) ──
    /**
     * Cross-tick latch for the non-jewelry make batches. The material reference (a name or a
     * numeric id as a string) identifies the batch; the count and progress timestamp prove it
     * is still running, so a tick never re-dispatches a batch that is already in flight.
     */
    private String batchMaterialRef = null;
    private int batchLastCount = 0;
    private long batchLastProgressAt = 0L;
    /** No material consumed and no animation for this long = the batch is not running anymore. */
    private static final long CRAFT_STALL_WINDOW_MS = 8_000L;

    /** Prevent repeated exit attempts / Discord spam when furnace not found. */
    private boolean exitRequested = false;
    /** Track consecutive craft failures to trigger exit after persistent failures. */
    private int consecutiveCraftFailures = 0;
    private static final int MAX_CONSECUTIVE_CRAFT_FAILURES = 3;
    /**
     * Plain needle (1733) and costume needle (29920) as IDs. By NAME the plain needle is a
     * substring of "Costume needle" (and of anything else ending in "needle"), so a name
     * lookup could report the wrong tool as present, lock the wrong slot, or Use the wrong
     * needle on the leather.
     */
    private static final int NEEDLE_ID = ItemID.NEEDLE;
    private static final int COSTUME_NEEDLE_ID = ItemID.COSTUME_NEEDLE;

    public enum Phase {
        NONE, GEMS, GLASS, STAFFS, FLAX, DRAGON_LEATHER, JEWELRY
    }

    /** Reset exit flag and failure counter when script is re-enabled. Call from AScript on state enter. */
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveCraftFailures = 0;
        jewelryLastBarCount = 0;
        jewelryLastProgressAt = 0L;
        jewelryPostBatchBreakDone = false;
        batchRanSinceBank = false;
        batchMaterialRef = null;
        batchLastCount = 0;
        batchLastProgressAt = 0L;
        lastAfkTime = 0L;
    }

    // ── Phase resolution ────────────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.CRAFTING
                || config.craftingActivity() == null) { this.currentPhase = Phase.NONE; return; }
        switch (config.craftingActivity()) {
            case GEM_CUTTING:    this.currentPhase = Phase.GEMS; return;
            case GLASSBLOWING:   this.currentPhase = Phase.GLASS; return;
            case STAFF_MAKING:   this.currentPhase = Phase.STAFFS; return;
            case FLAX_SPINNING:  this.currentPhase = Phase.FLAX; return;
            case DRAGON_LEATHER: this.currentPhase = Phase.DRAGON_LEATHER; return;
            case JEWELRY:        this.currentPhase = Phase.JEWELRY; return;
            default:             this.currentPhase = Phase.NONE; return;
        }
    }

    @Override
    public String runKey(AScriptConfig config) {
        return "CRAFTING:" + currentPhase
                + ":" + config.craftingActivity()
                + ":" + config.gemType()
                + ":" + config.glassType()
                + ":" + config.staffType()
                + ":" + config.flaxSpinLocation()
                + ":" + config.dragonLeatherType()
                + ":" + config.jewelryItem()
                + ":" + config.jewelryLocation()
                + ":" + config.useCostumeNeedle();
    }

    // ── Selection validation ────────────────────────────────────

    /**
     * Returns an error description when the configured activity has an invalid
     * sub-selection (e.g. GEM_CUTTING without a gem type), or null when valid.
     * Checked by AScript.tick() before any bank interaction.
     */
    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE) {
            // Selected with no activity: the activity enum defaults to NONE, so this is the
            // first-run state of the module — report it instead of idling silently.
            return config.scriptSelection() == ScriptType.CRAFTING
                    ? "no crafting activity selected" : null;
        }
        switch (currentPhase) {
            case GEMS:
                if (config.gemType() == CraftingGem.NONE) return "no gem selected";
                return craftingLevelError(config.gemType().getLevelRequired(), config.gemType().getName());
            case GLASS:
                if (config.glassType() == CraftingGlass.NONE) return "no glass item selected";
                return null;
            case STAFFS:
                if (config.staffType() == CraftingStaff.NONE) return "no staff type selected";
                return null;
            case FLAX:
                if (config.flaxSpinLocation() == CraftingFlaxLocation.NONE) return "no flax location selected";
                return null;
            case DRAGON_LEATHER:
                if (config.dragonLeatherType() == CraftingDragonLeather.NONE) return "no dragon leather armour selected";
                return null;
            case JEWELRY:
                if (config.jewelryItem() == JewelryItem.NONE) return "no jewelry item selected";
                if (config.jewelryLocation() == JewelryLocation.NONE) return "no furnace location selected";
                return craftingLevelError(config.jewelryItem().getLevelRequired(), config.jewelryItem().getName());
            default:
                return null;
        }
    }

    /**
     * Capability guard for the configured item's Crafting level — run from
     * {@link #validateSelection} so a locked selection stops with a message BEFORE the
     * module banks, instead of burning a bank trip first.
     * <p>
     * A real-level read of 0 means the skill data has not loaded yet (or the client-thread
     * read timed out) — that is "skill data loading", not a locked item, so it passes and
     * the next tick re-checks.
     */
    private String craftingLevelError(int required, String itemName) {
        int level = Rs2Player.getRealSkillLevel(Skill.CRAFTING);
        if (level <= 0) return null;
        return level < required
                ? "need level " + required + " Crafting for " + itemName + " (current " + level + ")"
                : null;
    }

    // ── Bank check ──────────────────────────────────────────────

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        switch (currentPhase) {
            case GEMS:            return needsBankGems(config);
            case GLASS:           return needsBankGlass();
            case STAFFS:          return needsBankStaffs(config);
            case FLAX:            return needsBankFlax();
            case DRAGON_LEATHER:  return needsBankDragonLeather(config);
            case JEWELRY:         return needsBankJewelry(config);
            default:              return false;
        }
    }

    private boolean needsBankGems(AScriptConfig config) {
        CraftingGem gem = config.gemType();
        boolean amethyst = gem.getName().contains("Amethyst");
        if (amethyst) {
            return !Rs2Inventory.hasItem(21347) || !AScriptBank.hasUnnotedItem("chisel");
        }
        // A finished cut stack that "Fletch into Bolt Tips" still has to process is work sitting in
        // the inventory, not a missing material. Reporting a bank need here would take the bank
        // branch, deposit the cut gems and return before doAction() — the option would silently do
        // nothing. The count is exact ("Ruby" must not resolve an "Uncut ruby" stack), and the gem
        // must actually have a bolt-tip recipe: without that gate a cut zenyte would hold the tick
        // in the IDLE path forever (never banking for more uncut) and then fail three times.
        if (config.fletchIntoBoltTips() && gem.isBoltTips() && countMaterial(gem.getName(), true) > 0
                && AScriptBank.hasUnnotedItem("chisel")) {
            return false;
        }
        return !AScriptBank.hasUnnotedItem("uncut " + gem.getName()) || !AScriptBank.hasUnnotedItem("chisel");
    }

    private boolean needsBankGlass() {
        return !AScriptBank.hasUnnotedItem("molten glass") || !AScriptBank.hasUnnotedItem("glassblowing pipe");
    }

    private boolean needsBankStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        if (staff == CraftingStaff.NONE) return false;
        return !AScriptBank.hasUnnotedItem(staff.getItemName()) || !AScriptBank.hasUnnotedItem(staff.getOrb());
    }

    private boolean needsBankFlax() {
        return !AScriptBank.hasUnnotedItem("flax");
    }

    /** The configured needle's id (plain 1733 or costume 29920). */
    private static int needleId(AScriptConfig config) {
        return config.useCostumeNeedle() ? COSTUME_NEEDLE_ID : NEEDLE_ID;
    }

    /** The configured needle's display name — status/notify text only, never item resolution. */
    private static String needleLabel(AScriptConfig config) {
        return config.useCostumeNeedle() ? "costume needle" : "needle";
    }

    private boolean needsBankDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return false;
        boolean hasNeedle = AScriptBank.hasUnnotedItem(needleId(config));
        boolean hasThread = config.useCostumeNeedle() || AScriptBank.hasUnnotedItem("thread");
        return !Rs2Inventory.hasItem(armour.getLeatherId()) || !hasNeedle || !hasThread;
    }

    private boolean needsBankJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        if (item == JewelryItem.NONE) return false;
        // Need the bar. When a batch just ran out, yield one tick so the post-batch break
        // runs with the bank still closed before banking (cannonball-smelter pattern).
        // Only a batch that actually ran earns that break — a fresh empty state banks
        // immediately.
        if (!Rs2Inventory.hasItem(item.getJewelryType().getMetalBarId())) {
            return !batchRanSinceBank || jewelryPostBatchBreakDone;
        }
        // Need the mould (tool)
        if (!Rs2Inventory.hasItem(item.getToolItemID())) return true;
        // Need cut gem if jewelry has a gem
        if (item.hasGem() && !Rs2Inventory.hasItem(item.getGem().getCutItemID())) return true;
        return false;
    }

    // ── Bank-level check (both bank AND inventory missing) ──────

    /**
     * Returns true when materials or tools are missing from BOTH the bank
     * and inventory — i.e. there is nothing to work with.
     */
    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false; // inventory has everything
        switch (currentPhase) {
            case GEMS:            return bankMissingGems(config);
            case GLASS:           return bankMissingGlass();
            case STAFFS:          return bankMissingStaffs(config);
            case FLAX:            return bankMissingFlax();
            case DRAGON_LEATHER:  return bankMissingDragonLeather(config);
            case JEWELRY:         return bankMissingJewelry(config);
            default:              return false;
        }
    }

    private boolean bankMissingGems(AScriptConfig config) {
        CraftingGem gem = config.gemType();
        boolean amethyst = gem.getName().contains("Amethyst");
        // Missing only when neither the inventory (unnoted or banknote) nor the bank can
        // supply it — a note counts because the bank cycle deposits it back to the base item.
        if (!AScriptBank.bankableAtLeast("chisel", 1)) return true;
        if (amethyst) {
            return !AScriptBank.bankableAtLeast(21347, 1);
        }
        return !AScriptBank.bankableAtLeast("uncut " + gem.getName(), 1);
    }

    private boolean bankMissingGlass() {
        if (!AScriptBank.bankableAtLeast("glassblowing pipe", 1)) return true;
        return !AScriptBank.bankableAtLeast("molten glass", 1);
    }

    private boolean bankMissingStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        if (staff == CraftingStaff.NONE) return false;
        if (!AScriptBank.bankableAtLeast(staff.getItemName(), 1)) return true;
        return !AScriptBank.bankableAtLeast(staff.getOrb(), 1);
    }

    private boolean bankMissingFlax() {
        return !AScriptBank.bankableAtLeast("flax", 1);
    }

    private boolean bankMissingDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return false;
        int needleId = needleId(config);
        if (!AScriptBank.bankableAtLeast(armour.getLeatherId(), 1)) return true;
        if (!AScriptBank.bankableAtLeast(needleId, 1)) return true;
        if (!config.useCostumeNeedle() && !AScriptBank.bankableAtLeast("thread", 1)) return true;
        return false;
    }

    private boolean bankMissingJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        if (item == JewelryItem.NONE) return false;
        if (!AScriptBank.bankableAtLeast(item.getJewelryType().getMetalBarId(), 1)) {
            return true; // need bar, neither inventory (incl. notes) nor bank has it
        }
        if (!AScriptBank.bankableAtLeast(item.getToolItemID(), 1)) {
            return true; // need mould
        }
        if (item.hasGem() && !AScriptBank.bankableAtLeast(item.getGem().getCutItemID(), 1)) {
            return true; // need cut gem
        }
        return false;
    }

    // ── Missing-materials description ────────────────────────────

    @Override
    public String describeMissing(AScriptConfig config) {
        if (currentPhase == Phase.NONE) return "";
        switch (currentPhase) {
            case GEMS:            return describeMissingGems(config);
            case GLASS:           return describeMissingGlass();
            case STAFFS:          return describeMissingStaffs(config);
            case FLAX:            return describeMissingFlax();
            case DRAGON_LEATHER:  return describeMissingDragonLeather(config);
            case JEWELRY:         return describeMissingJewelry(config);
            default:              return "";
        }
    }

    private String describeMissingGems(AScriptConfig config) {
        CraftingGem gem = config.gemType();
        boolean amethyst = gem.getName().contains("Amethyst");
        StringBuilder sb = new StringBuilder();
        if (amethyst) {
            if (!AScriptBank.bankableAtLeast(21347, 1)) sb.append("Amethyst block (not in bank), ");
            if (!AScriptBank.bankableAtLeast("chisel", 1)) sb.append("chisel (not in bank), ");
        } else {
            if (!AScriptBank.bankableAtLeast("uncut " + gem.getName(), 1))
                sb.append("uncut ").append(gem.getName()).append(" (not in bank), ");
            if (!AScriptBank.bankableAtLeast("chisel", 1)) sb.append("chisel (not in bank), ");
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingGlass() {
        StringBuilder sb = new StringBuilder();
        if (!AScriptBank.bankableAtLeast("molten glass", 1))
            sb.append("molten glass (not in bank), ");
        if (!AScriptBank.bankableAtLeast("glassblowing pipe", 1))
            sb.append("glassblowing pipe (not in bank), ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        if (staff == CraftingStaff.NONE) return "";
        StringBuilder sb = new StringBuilder();
        if (!AScriptBank.bankableAtLeast(staff.getItemName(), 1))
            sb.append(staff.getItemName()).append(" (not in bank), ");
        if (!AScriptBank.bankableAtLeast(staff.getOrb(), 1))
            sb.append(staff.getOrb()).append(" (not in bank), ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingFlax() {
        return !AScriptBank.bankableAtLeast("flax", 1) ? "flax (not in bank)" : "";
    }

    private String describeMissingDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return "";
        StringBuilder sb = new StringBuilder();
        if (!AScriptBank.bankableAtLeast(armour.getLeatherId(), 1))
            sb.append(armour.getName()).append(" (not in bank), ");
        int needleId = needleId(config);
        if (!AScriptBank.bankableAtLeast(needleId, 1))
            sb.append(needleLabel(config)).append(" (not in bank), ");
        if (!config.useCostumeNeedle() && !AScriptBank.bankableAtLeast("thread", 1))
            sb.append("thread (not in bank), ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        if (item == JewelryItem.NONE) return "";
        StringBuilder sb = new StringBuilder();
        if (!AScriptBank.bankableAtLeast(item.getJewelryType().getMetalBarId(), 1))
            sb.append(item.getJewelryType().getLabel()).append(" (not in bank), ");
        if (!AScriptBank.bankableAtLeast(item.getToolItemID(), 1))
            sb.append("mould (not in bank), ");
        if (item.hasGem() && !AScriptBank.bankableAtLeast(item.getGem().getCutItemID(), 1))
            sb.append(item.getGem().getCutItemName()).append(" (not in bank), ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    // ── Banking actions ─────────────────────────────────────────

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;

        Microbot.status = "BANKING";

        // Only open bank if not already open (IDLE may have opened it)
        if (!Rs2Bank.isOpen()) {
            if (!Rs2Bank.openBank()) return false;
            if (!sleepUntil(Rs2Bank::isOpen, Rs2Random.logNormalBounded(5000, 10000))) return false;
        }

        boolean success = false;
        switch (currentPhase) {
            case GEMS:            success = bankGems(config); break;
            case GLASS:           success = bankGlass(); break;
            case STAFFS:          success = bankStaffs(config); break;
            case FLAX:            success = bankFlax(); break;
            case DRAGON_LEATHER:  success = bankDragonLeather(config); break;
            case JEWELRY:         success = bankJewelry(config); break;
            default: break;
        }

        if (success) {
            // Don't close bank — keep it open for next IDLE check
            // Bank is only closed when script stops or materials run out
            // Fresh materials: drop the previous batch's latch so its progress stamps cannot
            // make the new batch look like it is still in flight.
            clearBatchLatch();
        }

        return success;
    }

    private boolean bankGems(AScriptConfig config) {
        CraftingGem gem = config.gemType();
        boolean amethyst = gem.getName().contains("Amethyst");
        String uncutGemName = "uncut " + gem.getName();

        // Deposit via toolbar button (safe on grid-silent machines)
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        if (amethyst) {
            if (!AScriptBank.ensureToolLocked("chisel")) return false;
            if (!AScriptBank.withdrawVerified(Integer.toString(21347))) {
                return false;
            }
        } else {
            if (!AScriptBank.ensureToolLocked("chisel")) return false;
            if (!AScriptBank.withdrawVerified(uncutGemName)) {
                return false;
            }
        }
        return true;
    }

    private boolean bankGlass() {
        // Deposit via toolbar button
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        if (!AScriptBank.ensureToolLocked("glassblowing pipe")) return false;
        if (!AScriptBank.withdrawVerified("molten glass")) {
            return false;
        }
        return true;
    }

    private boolean bankStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();

        // Deposit via toolbar button
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        // Reserve slots for BOTH materials before any click. Battlestaves and orbs are both
        // non-stackable, so one pair costs TWO inventory slots: with the old withdraw-all the
        // first material filled the bag and Rs2Bank.withdrawAll bailed on a full inventory for
        // the second. The batch is capped at half the free slots (13 = the classic half-bag
        // batch) so the same count of orbs always fits, and free slots already exclude a held
        // tool or the XP lamp — a locked slot shrinks the batch instead of failing the cycle.
        int freeSlots = AScriptBank.freeSlots();
        int staffs = AScriptBank.bankQuantity(staff.getItemName());
        int orbs = AScriptBank.bankQuantity(staff.getOrb());
        int batch = Math.min(13, freeSlots / 2);
        if (batch < 1) {
            // No Discord verdict here: a bank cycle that cannot proceed is counted by the
            // orchestrator (consecutiveBankFailures -> "Bank Cycle Failed"), and a notify from
            // this path would fire once per retry.
            log.warn("[Crafting] No staff-making batch — only {} free inventory slot(s)", freeSlots);
            Microbot.status = "No free inventory slots for staff making";
            return false;
        }

        // Never ask for more than the bank can serve on either side: the withdraw dispatch
        // resolves its bank row by the requested amount, so an over-request finds no row and
        // dispatches nothing. A short stack here means a smaller batch, not a failed cycle.
        batch = Math.min(batch, Math.min(staffs, orbs));
        if (batch < 1) {
            log.warn("[Crafting] No {} ({}) / {} ({}) pair in the bank — retrying next cycle",
                    staff.getItemName(), staffs, staff.getOrb(), orbs);
            Microbot.status = "No " + staff.getItemName() + " / " + staff.getOrb() + " pair in bank";
            return false;
        }

        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(staff.getItemName(), batch)) {
            log.warn("[Crafting] Could not withdraw {} x{} — retrying next cycle", staff.getItemName(), batch);
            Microbot.status = "Withdraw failed: " + staff.getItemName();
            return false;
        }
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(staff.getOrb(), batch)) {
            log.warn("[Crafting] Could not withdraw {} x{} — retrying next cycle", staff.getOrb(), batch);
            Microbot.status = "Withdraw failed: " + staff.getOrb();
            return false;
        }
        return true;
    }

    private boolean bankFlax() {
        // Deposit via toolbar button
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        if (!AScriptBank.withdrawVerified("flax")) {
            return false;
        }
        return true;
    }

    private boolean bankDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();

        // Deposit via toolbar button
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        int needleId = needleId(config);
        boolean hasNeedle = AScriptBank.hasUnnotedItem(needleId);
        boolean hasThread = config.useCostumeNeedle() || AScriptBank.hasUnnotedItem("thread");

        if (!hasNeedle && !AScriptBank.ensureToolLocked(String.valueOf(needleId)))
            return false;
        if (!hasThread && !config.useCostumeNeedle() && !AScriptBank.withdrawVerified("thread"))
            return false;

        if (!AScriptBank.withdrawVerified(Integer.toString(armour.getLeatherId()))) {
            return false;
        }
        return true;
    }

    private boolean bankJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        if (item == JewelryItem.NONE) return false;

        // Lock mould FIRST — if it's already in inventory, its slot gets locked
        // before deposit, so the blanket deposit preserves it. If not in inventory,
        // ensureToolLocked deposits strays + withdraws + locks (inventory ends up
        // with just the locked mould).
        if (!AScriptBank.ensureToolLocked(Integer.toString(item.getToolItemID()))) {
            return false;
        }

        // Deposit everything via the bank's "Deposit inventory" toolbar button.
        // Item-GRID interactions die silently on some machines — injected CC_OP
        // entries AND raw slot clicks both fail there (verified live via Agent
        // Server); the toolbar button click works. Crafted jewelry and leftover
        // gems go together. Locked slots (the mould) are preserved.
        Microbot.status = "Depositing inventory";
        if (!AScriptBank.depositAndWaitEmpty()) {
            return false; // waitForInventoryChanges timed out — retry next tick
        }

        // Withdraw bar. Bars are NOT stackable, so the bar count has to leave the gem stack a
        // slot: one slot is reserved for it up front (cut gems stack into a single slot). Free
        // slots already exclude the locked mould and any XP lamp, so a locked slot shrinks the
        // batch instead of filling the bag and making the gem withdraw impossible.
        String barRef = Integer.toString(item.getJewelryType().getMetalBarId());
        int freeSlots = AScriptBank.freeSlots();
        int batch = Math.min(AScriptBank.bankQuantity(barRef), Math.max(0, freeSlots - 1));
        if (item.hasGem()) {
            // One gem per bar is consumed — never withdraw bars the gems cannot match.
            batch = Math.min(batch, AScriptBank.bankQuantity(Integer.toString(item.getGem().getCutItemID())));
        }
        if (batch < 1) {
            // Bank-cycle failures are counted by the orchestrator ("Bank Cycle Failed"), so this
            // path logs + reports status instead of raising a per-retry Discord verdict.
            log.warn("[Crafting] No {} batch for {} — {} free slot(s)",
                    item.getJewelryType().getLabel(), item.getName(), freeSlots);
            Microbot.status = "No " + item.getJewelryType().getLabel() + " batch for " + item.getName();
            return false;
        }

        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(barRef, batch)) {
            log.warn("[Crafting] Could not withdraw {} bar x{} — retrying next cycle",
                    item.getJewelryType().getLabel(), batch);
            Microbot.status = "Withdraw failed: " + item.getJewelryType().getLabel();
            return false;
        }

        // If gem jewelry, withdraw cut gems — and verify the count, not just the presence:
        // withdrawVerified(name, batch) confirms the inventory count delta, so a gem stack that
        // only partly landed (or landed as banknotes) fails here instead of starting a batch
        // that stalls on its second bar.
        if (item.hasGem()) {
            if (!AScriptBank.ensureBankOpen()) return false;
            if (!AScriptBank.withdrawVerified(Integer.toString(item.getGem().getCutItemID()), batch)) {
                log.warn("[Crafting] Could not withdraw {} x{} — retrying next cycle",
                        item.getGem().getCutItemName(), batch);
                Microbot.status = "Withdraw failed: " + item.getGem().getCutItemName();
                return false;
            }
        }

        // Fresh batch scope — the next empty state only earns its closing break after a
        // batch runs on these materials. The progress stamps are part of the counter set:
        // leaving a stale timestamp makes the freshly withdrawn batch look like it is still
        // in flight and delays its first click.
        batchRanSinceBank = false;
        jewelryLastBarCount = 0;
        jewelryLastProgressAt = 0L;
        return true;
    }

    // ── Crafting actions ────────────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;

        // Ensure fresh weather data (cached for 30 min, safe to call every tick)
        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        boolean success = false;
        switch (currentPhase) {
            case GEMS:            success = craftGems(config); break;
            case GLASS:           success = craftGlass(config); break;
            case STAFFS:          success = craftStaffs(config); break;
            case FLAX:            success = craftFlax(config); break;
            case DRAGON_LEATHER:  success = craftDragonLeather(config); break;
            case JEWELRY:         success = craftJewelry(config); break;
            default: break;
        }

        // Track consecutive failures — exit after persistent failures
        if (success) {
            consecutiveCraftFailures = 0;
        } else {
            consecutiveCraftFailures++;
            if (consecutiveCraftFailures >= MAX_CONSECUTIVE_CRAFT_FAILURES) {
                exitRequested = true;
                Microbot.status = "STOPPED — persistent craft failures";
                log.warn("[CraftingScript] {} consecutive craft failures, stopping", consecutiveCraftFailures);
                AScriptNotify.notify("aScript Stopped", "Crafting: " + consecutiveCraftFailures
                        + " consecutive craft failures");
                Microbot.getConfigManager().setConfiguration(
                        AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            }
        }

        // Jewelry drives its own per-tick pacing (in-flight wait + post-batch break
        // with the randomness layer) — the shared trailing sleep/AFK would otherwise
        // fire on every tick of a running batch instead of once per batch. The make-batch
        // latch means the same for gems/glass/staffs/flax/dragon leather: while a batch is
        // latched, its own in-flight branch paces the loop.
        if (currentPhase != Phase.JEWELRY && batchMaterialRef == null) {
            sleep(Rs2Random.logNormalBounded(800, 1600));

            // Random AFK — log-normal distribution, weather-modulated.
            // Interruptible: returns early when a blocking event is pending so the
            // orchestrator's next tick handles it instead of after the full delay.
            if (config.craftingAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
                int afkMs = Rs2Random.logNormalBounded(3000, 120000, weatherMultiplier);
                Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
                AScriptSleep.sleepInterruptibly(afkMs);
                lastAfkTime = System.currentTimeMillis();
            }
        }
    }

    private boolean craftGems(AScriptConfig config) {
        CraftingGem gem = config.gemType();
        boolean amethyst = gem.getName().contains("Amethyst");
        final String materialRef = amethyst ? Integer.toString(21347) : "uncut " + gem.getName();

        Microbot.status = "CUTTING " + gem.getName().toUpperCase();

        if (countMaterial(materialRef) > 0) {
            if (batchInFlight(materialRef)) return true; // running: wait, never re-dispatch

            int before = countMaterial(materialRef);
            Rs2ItemModel chiselItem = AScriptPick.pick("chisel");
            if (amethyst) {
                Rs2ItemModel amethystItem = AScriptPick.pick(21347);
                if (!AScriptClick.useItemOnItem(chiselItem, amethystItem)) {
                    Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
                    return false;
                }
                // Chisel-on-block opens the multi-product production widget FIRST: the product
                // has to be chosen there, and only then does the quantity prompt become
                // answerable. Waiting for the prompt before the product exists is what used to
                // time out and leave the batch unstarted.
                if (!sleepUntilTrue(Rs2Widget::isProductionWidgetOpen, 300, 20000)) {
                    Microbot.status = "IDLE";
                    log.warn("[CraftingScript] Amethyst production interface did not open");
                    return false;
                }
                if (!sleepUntilTrue(() -> Rs2Widget.clickWidget(gem.getName(), true), 300, 5000)) {
                    Microbot.status = "IDLE";
                    log.warn("[CraftingScript] Could not click the {} product", gem.getName());
                    return false;
                }
                // Quantity is the second step of the same widget tree — answer it with "All".
                Rs2Widget.sleepUntilHasWidgetText("How many do you wish to make?", 270, 5, false, 5000);
                Rs2Widget.enableQuantityOption("All");
                if (!confirmBatchStarted(materialRef, before)) {
                    Microbot.status = "IDLE";
                    log.warn("[CraftingScript] Amethyst batch did not start");
                    return false;
                }
            } else {
                Rs2ItemModel uncutGemItem = AScriptPick.pick(materialRef);
                if (!AScriptClick.useItemOnItem(chiselItem, uncutGemItem)) {
                    Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
                    return false;
                }
                if (!sleepUntilTrue(Rs2Widget::isProductionWidgetOpen, 300, 20000)) {
                    Microbot.status = "IDLE";
                    return false;
                }
                Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                if (!confirmBatchStarted(materialRef, before)) {
                    Microbot.status = "IDLE";
                    return false;
                }
            }

            Microbot.status = "IDLE";
            return true;
        }

        // The uncut stack is gone: the cutting batch is over. The latch is NOT cleared here — the
        // bolt-tip continuation below keeps its own latch, and clearing it on every tick would make
        // that batch re-dispatch every tick (the P1-11 defect, in this sub-batch).
        if (amethyst) {
            // Amethyst products are the finished output — nothing to fletch afterwards.
            clearBatchLatch();
            Microbot.status = "IDLE";
            return true;
        }

        if (config.fletchIntoBoltTips() && gem.isBoltTips()) {
            final String cutRef = gem.getName();
            int cutCount = countMaterial(cutRef, true);
            if (cutCount > 0 && AScriptBank.hasUnnotedItem("chisel")) {
                if (batchInFlight(cutRef, cutCount)) return true; // running: wait, never re-dispatch

                Microbot.status = "FLETCHING BOLT TIPS";
                // Same chisel as above (a locked tool, still in the inventory); the cut gem
                // is the new use target.
                Rs2ItemModel chiselItem = AScriptPick.pick("chisel");
                // Exact match: the substring form resolves "Uncut ruby" for "Ruby".
                Rs2ItemModel cutGemItem = AScriptPick.pick(cutRef, true);
                if (!AScriptClick.useItemOnItem(chiselItem, cutGemItem)) {
                    Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
                    return false;
                }
                if (!sleepUntilTrue(() -> Rs2Widget.isProductionWidgetOpen()
                        || Rs2Widget.isGoldCraftingWidgetOpen()
                        || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 20000)) {
                    Microbot.status = "IDLE";
                    return false;
                }
                Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                if (!confirmBatchStarted(cutRef, cutCount, true)) {
                    Microbot.status = "IDLE";
                    return false;
                }
                Microbot.status = "IDLE";
                return true;
            }
        }

        // Nothing is left to process: neither a cutting batch nor a bolt-tip batch is in flight.
        clearBatchLatch();
        Microbot.status = "IDLE";
        return true;
    }

    private boolean craftGlass(AScriptConfig config) {
        CraftingGlass glass = config.glassType();
        final String materialRef = "molten glass";
        Microbot.status = "BLOWING " + glass.getLabel().toUpperCase();

        if (countMaterial(materialRef) <= 0) {
            clearBatchLatch();
            Microbot.status = "IDLE";
            return true;
        }
        if (batchInFlight(materialRef)) return true; // running: wait, never re-dispatch

        int before = countMaterial(materialRef);
        Rs2ItemModel pipeItem = AScriptPick.pick("glassblowing pipe");
        Rs2ItemModel moltenGlassItem = AScriptPick.pick(materialRef);
        if (!AScriptClick.useItemOnItem(pipeItem, moltenGlassItem)) {
            Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
            return false;
        }
        if (!sleepUntilTrue(Rs2Widget::isProductionWidgetOpen, 300, 20000)) {
            Microbot.status = "IDLE";
            return false;
        }
        Rs2Keyboard.keyPress(glass.getMenuEntry());
        if (!confirmBatchStarted(materialRef, before)) {
            Microbot.status = "IDLE";
            return false;
        }
        Microbot.status = "IDLE";
        return true;
    }

    private boolean craftStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        final String materialRef = staff.getItemName();
        Microbot.status = "MAKING " + staff.getLabel().toUpperCase();

        if (countMaterial(materialRef) <= 0) {
            clearBatchLatch();
            Microbot.status = "IDLE";
            return true;
        }
        if (batchInFlight(materialRef)) return true; // running: wait, never re-dispatch

        int before = countMaterial(materialRef);
        Rs2ItemModel orbItem = AScriptPick.pick(staff.getOrb());
        Rs2ItemModel staffItem = AScriptPick.pick(materialRef);
        if (!AScriptClick.useItemOnItem(orbItem, staffItem)) {
            Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
            return false;
        }
        if (!sleepUntilTrue(Rs2Widget::isProductionWidgetOpen, 300, 20000)) {
            Microbot.status = "IDLE";
            return false;
        }
        Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        if (!confirmBatchStarted(materialRef, before)) {
            Microbot.status = "IDLE";
            return false;
        }

        Microbot.status = "IDLE";
        return true;
    }

    private boolean craftFlax(AScriptConfig config) {
        if (exitRequested) return false; // already tried to exit, don't spam

        CraftingFlaxLocation location = config.flaxSpinLocation();

        // A wheel cannot be located without a configured location
        if (location == null || location == CraftingFlaxLocation.NONE) {
            exitRequested = true;
            Microbot.status = "NO FLAX LOCATION — STOPPING";
            AScriptNotify.notify("Flax Script Stopped",
                    "No spinning wheel location selected. Set Flax Location in the config.");
            Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            return false;
        }

        Microbot.status = "SPINNING FLAX";

        final String materialRef = "flax";

        if (countMaterial(materialRef) <= 0) {
            clearBatchLatch();
            Microbot.status = "IDLE";
            return true;
        }
        if (batchInFlight(materialRef)) return true; // running: wait, never re-dispatch
        int before = countMaterial(materialRef);

        // Find the spinning wheel by ID from the selected location
        TileObject wheelObject = Rs2GameObject.findObjectById(location.getObjectID());

        // If not found, walk to the wheel location
        if (wheelObject == null && location.getWorldPoint() != null) {
            Microbot.status = "WALKING TO " + location.getLabel().toUpperCase();
            Rs2Walker.walkTo(location.getWorldPoint());
            sleep(Rs2Random.logNormalBounded(2000, 4000)); // randomized wait for walker

            // Try finding the wheel again after walking
            wheelObject = Rs2GameObject.findObjectById(location.getObjectID());
        }

        // If still not found, deactivate script + notify Discord (once)
        if (wheelObject == null) {
            exitRequested = true;
            Microbot.status = "NO WHEEL — STOPPING";
            AScriptNotify.notify("Flax Script Stopped",
                    "Spinning wheel (ID " + location.getObjectID() + ") not found at "
                            + location.getLabel() + ". Deactivating script.");
            Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            return false;
        }

        // Turn camera if wheel not on screen
        if (!Rs2Camera.isTileOnScreen(wheelObject.getLocalLocation())) {
            Rs2Camera.turnTo(wheelObject.getLocalLocation());
            return false;
        }

        // Click the wheel for its "Spin" default op — the AScriptClick object recipe (a
        // physical click on the wheel's real clickbox, no menu injection). The module
        // wrapper absorbs a recipe camera-turn so it cannot spend a failure life; what is
        // still not clicked is a render race and retries next tick, as the camera pre-check
        // above does.
        AScriptClick.Target wheelClick = clickObject(wheelObject);
        if (!wheelClick.clicked()) {
            return false;
        }

        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) { Microbot.status = "IDLE"; return false; }
        Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        if (!confirmBatchStarted(materialRef, before)) {
            Microbot.status = "IDLE";
            return false;
        }

        Microbot.status = "IDLE";
        return true;
    }

    private boolean craftDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        final String materialRef = Integer.toString(armour.getLeatherId());
        Microbot.status = "CRAFTING " + armour.getName().toUpperCase();

        if (countMaterial(materialRef) <= 0) {
            clearBatchLatch();
            Microbot.status = "IDLE";
            return true;
        }
        if (batchInFlight(materialRef)) return true; // running: wait, never re-dispatch

        int before = countMaterial(materialRef);
        Rs2ItemModel leatherItem = AScriptPick.pick(armour.getLeatherId());
        // The configured needle, resolved by ID immediately before the click: "needle" also
        // matches "Costume needle", so a name lookup can hand back the other tool.
        Rs2ItemModel needleItem = Rs2Inventory.get(needleId(config));
        if (!AScriptClick.useItemOnItem(leatherItem, needleItem)) {
            Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
            return false;
        }
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) { Microbot.status = "IDLE"; return false; }
        Rs2Keyboard.keyPress(armour.getMenuEntry());
        if (!confirmBatchStarted(materialRef, before)) {
            Microbot.status = "IDLE";
            return false;
        }

        Microbot.status = "IDLE";
        return true;
    }

    // ── Batch latch helpers ────────────────────────────────────

    /**
     * Inventory count of a batch material. Foreign names and numeric ids both arrive as the
     * latch's reference string, so the numeric form resolves through the id overload.
     */
    private int countMaterial(String ref) {
        return countMaterial(ref, false);
    }

    /**
     * Inventory count of a batch material, optionally by exact name. Substring matching is the
     * legacy behaviour and stays for the raw materials (their names are distinctive), but a
     * product name such as {@code "Ruby"} also matches {@code "Uncut ruby"} — the bolt-tip
     * continuation counts those with {@code exact = true}.
     * <p>
     * Banknotes are excluded: a note shares the base item's display name but is a certificate
     * that cannot be cut/fletched, so counting it would make the module believe work is in hand
     * and then dispatch a Use click that can only fail.
     */
    private int countMaterial(String ref, boolean exact) {
        if (ref == null || ref.isEmpty()) return 0;
        boolean numeric = ref.chars().allMatch(Character::isDigit);
        if (numeric) {
            int id = Integer.parseInt(ref);
            return Rs2Inventory.count(item -> item != null && !item.isNoted() && item.getId() == id);
        }
        String needle = ref.toLowerCase();
        return Rs2Inventory.count(item -> item != null && item.getName() != null && !item.isNoted()
                && (exact ? item.getName().equalsIgnoreCase(ref)
                        : item.getName().toLowerCase().contains(needle)));
    }

    /**
     * Cross-tick batch latch: true while the latched batch is still running, i.e. the player is
     * animating or the material was consumed within the stall window. The previous tick's
     * dispatch is what sets the latch, so a running batch is never re-dispatched — the old
     * {@code !Microbot.isGainingExp || !hasItem(...)} wait was already true before the first XP
     * drop and returned immediately.
     */
    private boolean batchInFlight(String ref) {
        return batchInFlight(ref, countMaterial(ref));
    }

    /** {@link #batchInFlight(String)} with a count the caller already took (exact or id-based). */
    private boolean batchInFlight(String ref, int count) {
        if (batchMaterialRef == null || !batchMaterialRef.equals(ref)) return false;
        if (count <= 0) return false; // stack consumed — the batch ended with it

        long now = System.currentTimeMillis();
        if (count < batchLastCount) {
            batchLastCount = count;
            batchLastProgressAt = now;
            Microbot.status = batchStatus() + " — " + count + " left";
        }
        if (Rs2Player.isAnimating() || now - batchLastProgressAt < CRAFT_STALL_WINDOW_MS) {
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(400, 900, weatherMultiplier));
            return true;
        }
        return false; // stalled long enough that the batch is not running anymore
    }

    /**
     * Confirm a dispatched batch actually started (the player animates or the material count
     * dropped) and latch it for the following ticks. Returns false when nothing started, so the
     * caller reports a failed attempt instead of resetting the failure counter.
     */
    private boolean confirmBatchStarted(String ref, int before) {
        return confirmBatchStarted(ref, before, false);
    }

    /** {@link #confirmBatchStarted(String, int)} with the caller's own count semantics. */
    private boolean confirmBatchStarted(String ref, int before, boolean exact) {
        boolean started = sleepUntil(
                () -> Rs2Player.isAnimating() || countMaterial(ref, exact) < before,
                Rs2Random.logNormalBounded(1500, 3000, weatherMultiplier));
        if (!started) {
            batchMaterialRef = null;
            log.warn("[CraftingScript] Batch did not start for {}", ref);
            return false;
        }
        batchMaterialRef = ref;
        batchLastCount = countMaterial(ref, exact);
        batchLastProgressAt = System.currentTimeMillis();
        return true;
    }

    private void clearBatchLatch() {
        batchMaterialRef = null;
        batchLastCount = 0;
        batchLastProgressAt = 0L;
    }

    private String batchStatus() {
        switch (currentPhase) {
            case GEMS:           return "CUTTING";
            case GLASS:          return "BLOWING";
            case STAFFS:         return "MAKING";
            case FLAX:           return "SPINNING";
            case DRAGON_LEATHER: return "CRAFTING";
            default:             return "WORKING";
        }
    }

    private boolean craftJewelry(AScriptConfig config) {
        if (exitRequested) return false; // already tried to exit, don't spam

        JewelryItem item = config.jewelryItem();
        JewelryLocation location = config.jewelryLocation();
        if (item == JewelryItem.NONE || location == JewelryLocation.NONE) return false;

        // The Crafting-level capability guard lives in validateSelection(): it runs before the
        // bank trip and treats a 0 level read as "skill data loading" instead of a locked item.

        int bars = Rs2Inventory.count(item.getJewelryType().getMetalBarId());
        if (bars <= 0) {
            // Batch finished — run the post-batch break once (the orchestrator banks on a
            // later tick; needsBankJewelry yields while the break is pending).
            postJewelryBatchBreak(config);
            return true;
        }
        jewelryPostBatchBreakDone = false; // bars present again — the next empty state is a new batch end
        batchRanSinceBank = true;          // a batch is being worked — its end earns the closing break

        long now = System.currentTimeMillis();
        boolean progressed = bars < jewelryLastBarCount;
        if (progressed) {
            jewelryLastBarCount = bars;
            jewelryLastProgressAt = now;
        }

        // In flight: a bar was consumed since the last tick, the player is animating, or
        // the batch started less than the stall window ago (the gap between two items).
        // NEVER interact with the furnace in this state — a click on it while the make
        // runs resolves as a mid-batch "Cancel" menu entry and stops the batch.
        if (progressed || Rs2Player.isAnimating()
                || now - jewelryLastProgressAt < JEWELRY_STALL_WINDOW_MS) {
            Microbot.status = "SMELTING " + item.getName().toUpperCase() + " — " + bars + " bars left";
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(400, 900, weatherMultiplier));
            return true;
        }

        // Not in flight — (re)start the batch. The stall window above means this runs at
        // batch start or after a real stall (batch stopped early), never mid-craft.
        return startJewelryBatch(item, location);
    }

    /**
     * Start (or resume) a jewelry batch: open the make interface if needed, click the
     * item option, and confirm the batch actually started.
     */
    private boolean startJewelryBatch(JewelryItem item, JewelryLocation location) {
        Microbot.status = "SMELTING " + item.getName().toUpperCase();

        // The interface may already be open from an attempt whose wait timed out —
        // finish that sequence instead of re-clicking the furnace.
        if (!isJewelryInterfaceOpen()) {
            // Find this location's own furnace. Every jewelry site has a different scenery
            // object (Edgeville 16469, the general Falador/Port Phasmatys furnace 24009,
            // Mount Karuulm's volcanic furnace 34342, Zanaris 12100, Shilo Village's lit
            // furnace 30158) — a single hardcoded 16469 only ever resolves at Edgeville.
            TileObject furnaceObject = findFurnace(location);

            // If not found, walk to furnace location once
            if (furnaceObject == null && location.getFurnaceLocation() != null) {
                Microbot.status = "WALKING TO " + location.getLabel().toUpperCase();
                Rs2Walker.walkTo(location.getFurnaceLocation());
                sleep(Rs2Random.logNormalBounded(2000, 4000)); // randomized wait for walker

                // Try finding furnace again after walking
                furnaceObject = findFurnace(location);
            }

            // If still not found, deactivate script + notify Discord (once)
            if (furnaceObject == null) {
                exitRequested = true;
                Microbot.status = "NO FURNACE — STOPPING";
                AScriptNotify.notify("Jewelry Script Stopped",
                        "Furnace (ID " + location.getFurnaceObjectIdsText() + ") not found at "
                                + location.getLabel() + ". Deactivating script.");
                Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
                return false;
            }

            // Turn camera if furnace not on screen
            if (!Rs2Camera.isTileOnScreen(furnaceObject.getLocalLocation())) {
                Rs2Camera.turnTo(furnaceObject.getLocalLocation());
                return false; // retry next tick — transient, resolves in a tick or two
            }

            // Click the furnace for its "Smelt" default op — the AScriptClick object recipe
            // (a physical click on the furnace's real clickbox, no menu injection). The
            // module wrapper absorbs a recipe camera-turn so it cannot spend a failure life;
            // what is still not clicked is a render race and retries next tick, as the
            // camera pre-check above does.
            AScriptClick.Target furnaceClick = clickObject(furnaceObject);
            if (!furnaceClick.clicked()) {
                return false;
            }

            // Wait for the make interface — bounded; a blind long wait just delays the retry
            boolean craftingInterfaceOpen = sleepUntilTrue(this::isJewelryInterfaceOpen, 300,
                    Rs2Random.logNormalBounded(6000, 10000, weatherMultiplier));
            if (!craftingInterfaceOpen) {
                failJewelryAttempt("make interface did not open");
                return false;
            }
        }

        // Click our item. The option can populate a tick after the interface appears and the
        // name search can miss while the widget tree settles — RETRY until the click is
        // actually dispatched. A single silent miss here is what used to send the old loop
        // straight back to the furnace mid-batch.
        boolean clicked = sleepUntilTrue(
                () -> {
                    for (String label : item.productLabels()) {
                        if (Rs2Widget.clickWidget(label, java.util.Optional.of(item.getWidgetGroup()),
                                item.getWidgetChild(), false)) {
                            return true;
                        }
                    }
                    return false;
                },
                300, Rs2Random.logNormalBounded(3000, 5000, weatherMultiplier));
        if (!clicked) {
            failJewelryAttempt("could not click the " + item.getName() + " option");
            return false;
        }

        // Confirm the batch started: the player animates or a bar is consumed.
        int before = Rs2Inventory.count(item.getJewelryType().getMetalBarId());
        if (!sleepUntil(() -> Rs2Player.isAnimating()
                || Rs2Inventory.count(item.getJewelryType().getMetalBarId()) < before,
                Rs2Random.logNormalBounded(1500, 3000, weatherMultiplier))) {
            failJewelryAttempt("craft did not start");
            return false;
        }

        jewelryLastBarCount = Rs2Inventory.count(item.getJewelryType().getMetalBarId());
        jewelryLastProgressAt = System.currentTimeMillis();
        return true;
    }

    /** True while any of the jewelry make interfaces is up (gold 446, silver 6, production 270). */
    private boolean isJewelryInterfaceOpen() {
        return Rs2Widget.isProductionWidgetOpen()
                || Rs2Widget.isGoldCraftingWidgetOpen()
                || Rs2Widget.isSilverCraftingWidgetOpen();
    }

    /** Failed start attempt: log + backoff — the orchestrator's failure counter escalates to a stop. */
    private void failJewelryAttempt(String reason) {
        log.warn("[CraftingScript] Jewelry attempt failed: {}", reason);
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }

    /**
     * Post-batch break — the jewelry randomness layer: a short randomized pause after every
     * completed batch, then the optional random AFK (log-normal, weather-modulated,
     * interruptible so blocking events still fire). Runs while {@link #needsBankJewelry}
     * yields, i.e. with the bank still closed (cannonball-smelter pattern).
     */
    private void postJewelryBatchBreak(AScriptConfig config) {
        if (jewelryPostBatchBreakDone) return;
        jewelryPostBatchBreakDone = true;
        jewelryLastBarCount = 0;
        jewelryLastProgressAt = 0L;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.craftingAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3000, 120000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    // ── Clicks ────────────────────────────────────────────────

    /**
     * The shared object recipe ({@link AScriptClick#clickObject}) with the camera turn
     * absorbed inside the call — the F1 Motherload Mine wrapper: these boolean sub-routines
     * feed the module's consecutive-failure counter directly, so the recipe's camera-turn
     * outcome (a retry on a later tick, not a failure) must not spend a life. Wait one
     * frame for the turned view to render and resolve the click again here, as the
     * pre-recipe {@code Rs2GameObject.interact} call sites behaved (turn, then act on a
     * later tick — except these two blocks pre-check the camera themselves, so the turn
     * here only ever covers the race between that check and this call).
     * <p>
     * A physical click dispatches the object's left-click default only — both callers click
     * their own default op (wheel "Spin", furnace "Smelt").
     *
     * @return the recipe outcome — {@link AScriptClick.Target#clicked()} is the click issued
     */
    private AScriptClick.Target clickObject(TileObject object) {
        AScriptClick.Target target = AScriptClick.clickObject(object);
        if (!target.cameraTurned()) {
            return target;
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(200, 350));
        return AScriptClick.clickObject(object);
    }

    /**
     * Resolve the configured location's furnace scenery object. Locations can list more than one
     * id (Shilo Village ships a lit and an unlit furnace), so every id is tried before giving up.
     */
    private TileObject findFurnace(JewelryLocation location) {
        for (int objectId : location.getFurnaceObjectIds()) {
            TileObject furnace = Rs2GameObject.findObjectById(objectId);
            if (furnace != null) return furnace;
        }
        return null;
    }

    @Override
    public boolean isActive() { return currentPhase != Phase.NONE; }
}
