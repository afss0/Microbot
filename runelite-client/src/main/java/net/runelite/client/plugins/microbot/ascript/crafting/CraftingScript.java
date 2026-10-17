package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.extern.slf4j.Slf4j;
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

    /** Prevent repeated exit attempts / Discord spam when furnace not found. */
    private boolean exitRequested = false;
    /** Track consecutive craft failures to trigger exit after persistent failures. */
    private int consecutiveCraftFailures = 0;
    private static final int MAX_CONSECUTIVE_CRAFT_FAILURES = 3;

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

    // ── Selection validation ────────────────────────────────────

    /**
     * Returns an error description when the configured activity has an invalid
     * sub-selection (e.g. GEM_CUTTING without a gem type), or null when valid.
     * Checked by AScript.tick() before any bank interaction.
     */
    @Override
    public String validateSelection(AScriptConfig config) {
        switch (currentPhase) {
            case GEMS:
                if (config.gemType() == CraftingGem.NONE) return "no gem selected";
                return null;
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
                return null;
            default:
                return null;
        }
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

    private boolean needsBankDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return false;
        boolean hasNeedle = config.useCostumeNeedle()
                ? AScriptBank.hasUnnotedItem("costume needle")
                : AScriptBank.hasUnnotedItem("needle");
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
        // Only check bank for items missing from inventory (retrying bank reads — the bare
        // mirror read can report an item the bank no longer lists)
        if (!AScriptBank.hasUnnotedItem("chisel") && !Rs2Bank.hasBankItem("chisel", 1)) return true;
        if (amethyst) {
            return !Rs2Inventory.hasItem(21347) && !Rs2Bank.hasBankItem(21347, 1);
        }
        return !AScriptBank.hasUnnotedItem("uncut " + gem.getName()) && !Rs2Bank.hasBankItem("uncut " + gem.getName(), 1);
    }

    private boolean bankMissingGlass() {
        if (!AScriptBank.hasUnnotedItem("glassblowing pipe") && !Rs2Bank.hasBankItem("glassblowing pipe", 1)) return true;
        return !AScriptBank.hasUnnotedItem("molten glass") && !Rs2Bank.hasBankItem("molten glass", 1);
    }

    private boolean bankMissingStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        if (staff == CraftingStaff.NONE) return false;
        if (!AScriptBank.hasUnnotedItem(staff.getItemName()) && !Rs2Bank.hasBankItem(staff.getItemName(), 1)) return true;
        return !AScriptBank.hasUnnotedItem(staff.getOrb()) && !Rs2Bank.hasBankItem(staff.getOrb(), 1);
    }

    private boolean bankMissingFlax() {
        return !AScriptBank.hasUnnotedItem("flax") && !Rs2Bank.hasBankItem("flax", 1);
    }

    private boolean bankMissingDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return false;
        String needle = config.useCostumeNeedle() ? "costume needle" : "needle";
        if (!Rs2Inventory.hasItem(armour.getLeatherId()) && !Rs2Bank.hasBankItem(armour.getLeatherId(), 1)) return true;
        if (!Rs2Inventory.hasItem(needle) && !Rs2Bank.hasBankItem(needle, 1)) return true;
        if (!config.useCostumeNeedle() && !AScriptBank.hasUnnotedItem("thread") && !Rs2Bank.hasBankItem("thread", 1)) return true;
        return false;
    }

    private boolean bankMissingJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        if (item == JewelryItem.NONE) return false;
        // Check ONLY items that are missing from inventory
        // If mould is already in inventory, don't require it in bank
        if (!Rs2Inventory.hasItem(item.getJewelryType().getMetalBarId())
                && !Rs2Bank.hasBankItem(item.getJewelryType().getMetalBarId(), 1)) {
            return true; // need bar, bank doesn't have it
        }
        if (!Rs2Inventory.hasItem(item.getToolItemID())
                && !Rs2Bank.hasBankItem(item.getToolItemID(), 1)) {
            return true; // need mould, bank doesn't have it
        }
        if (item.hasGem()
                && !Rs2Inventory.hasItem(item.getGem().getCutItemID())
                && !Rs2Bank.hasBankItem(item.getGem().getCutItemID(), 1)) {
            return true; // need cut gem, bank doesn't have it
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
            if (!Rs2Inventory.hasItem(21347) && !Rs2Bank.hasBankItem(21347, 1)) sb.append("Amethyst block (not in bank), ");
            if (!AScriptBank.hasUnnotedItem("chisel") && !Rs2Bank.hasBankItem("chisel", 1)) sb.append("chisel (not in bank), ");
        } else {
            if (!AScriptBank.hasUnnotedItem("uncut " + gem.getName()) && !Rs2Bank.hasBankItem("uncut " + gem.getName(), 1))
                sb.append("uncut ").append(gem.getName()).append(" (not in bank), ");
            if (!AScriptBank.hasUnnotedItem("chisel") && !Rs2Bank.hasBankItem("chisel", 1)) sb.append("chisel (not in bank), ");
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingGlass() {
        StringBuilder sb = new StringBuilder();
        if (!AScriptBank.hasUnnotedItem("molten glass") && !Rs2Bank.hasBankItem("molten glass", 1))
            sb.append("molten glass (not in bank), ");
        if (!AScriptBank.hasUnnotedItem("glassblowing pipe") && !Rs2Bank.hasBankItem("glassblowing pipe", 1))
            sb.append("glassblowing pipe (not in bank), ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        if (staff == CraftingStaff.NONE) return "";
        StringBuilder sb = new StringBuilder();
        if (!AScriptBank.hasUnnotedItem(staff.getItemName()) && !Rs2Bank.hasBankItem(staff.getItemName(), 1))
            sb.append(staff.getItemName()).append(" (not in bank), ");
        if (!AScriptBank.hasUnnotedItem(staff.getOrb()) && !Rs2Bank.hasBankItem(staff.getOrb(), 1))
            sb.append(staff.getOrb()).append(" (not in bank), ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingFlax() {
        return !AScriptBank.hasUnnotedItem("flax") && !Rs2Bank.hasBankItem("flax", 1) ? "flax (not in bank)" : "";
    }

    private String describeMissingDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return "";
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(armour.getLeatherId()) && !Rs2Bank.hasBankItem(armour.getLeatherId(), 1))
            sb.append(armour.getName()).append(" (not in bank), ");
        boolean hasNeedle = config.useCostumeNeedle()
                ? AScriptBank.hasUnnotedItem("costume needle") || Rs2Bank.hasBankItem("costume needle", 1)
                : AScriptBank.hasUnnotedItem("needle") || Rs2Bank.hasBankItem("needle", 1);
        if (!hasNeedle) sb.append(config.useCostumeNeedle() ? "costume needle" : "needle").append(" (not in bank), ");
        boolean hasThread = config.useCostumeNeedle()
                || AScriptBank.hasUnnotedItem("thread") || Rs2Bank.hasBankItem("thread", 1);
        if (!hasThread) sb.append("thread (not in bank), ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        if (item == JewelryItem.NONE) return "";
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(item.getJewelryType().getMetalBarId())
                && !Rs2Bank.hasBankItem(item.getJewelryType().getMetalBarId(), 1))
            sb.append(item.getJewelryType().getLabel()).append(" (not in bank), ");
        if (!Rs2Inventory.hasItem(item.getToolItemID())
                && !Rs2Bank.hasBankItem(item.getToolItemID(), 1))
            sb.append("mould (not in bank), ");
        if (item.hasGem() && !Rs2Inventory.hasItem(item.getGem().getCutItemID())
                && !Rs2Bank.hasBankItem(item.getGem().getCutItemID(), 1))
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
                AScriptNotify.notify("Banking Failed", "No amethyst blocks in bank");
                return false;
            }
        } else {
            if (!AScriptBank.ensureToolLocked("chisel")) return false;
            if (!AScriptBank.withdrawVerified(uncutGemName)) {
                AScriptNotify.notify("Banking Failed", "No " + uncutGemName + " in bank");
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
            AScriptNotify.notify("Banking Failed", "No molten glass in bank");
            return false;
        }
        return true;
    }

    private boolean bankStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();

        // Deposit via toolbar button
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        if (!AScriptBank.withdrawVerified(staff.getItemName())) {
            AScriptNotify.notify("Banking Failed", "No " + staff.getItemName() + " in bank");
            return false;
        }
        if (!AScriptBank.withdrawVerified(staff.getOrb())) {
            AScriptNotify.notify("Banking Failed", "No " + staff.getOrb() + " in bank");
            return false;
        }
        return true;
    }

    private boolean bankFlax() {
        // Deposit via toolbar button
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        if (!AScriptBank.withdrawVerified("flax")) {
            AScriptNotify.notify("Banking Failed", "No flax in bank");
            return false;
        }
        return true;
    }

    private boolean bankDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();

        // Deposit via toolbar button
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        boolean hasNeedle = config.useCostumeNeedle()
                ? AScriptBank.hasUnnotedItem("costume needle")
                : AScriptBank.hasUnnotedItem("needle");
        boolean hasThread = config.useCostumeNeedle() || AScriptBank.hasUnnotedItem("thread");

        if (!hasNeedle && !AScriptBank.ensureToolLocked(config.useCostumeNeedle() ? "costume needle" : "needle"))
            return false;
        if (!hasThread && !config.useCostumeNeedle() && !AScriptBank.withdrawVerified("thread"))
            return false;

        if (!AScriptBank.withdrawVerified(Integer.toString(armour.getLeatherId()))) {
            AScriptNotify.notify("Banking Failed", "No dragon leather in bank");
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
            AScriptNotify.notify("Banking Failed", "Missing mould in bank for " + item.getName());
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

        // Withdraw bar
        if (!AScriptBank.withdrawVerified(Integer.toString(item.getJewelryType().getMetalBarId()))) {
            AScriptNotify.notify("Banking Failed", "No " + item.getJewelryType().getLabel() + " in bank");
            return false;
        }

        // If gem jewelry, withdraw cut gems
        if (item.hasGem()) {
            if (!AScriptBank.withdrawVerified(Integer.toString(item.getGem().getCutItemID()))) {
                AScriptNotify.notify("Banking Failed", "No " + item.getGem().getCutItemName() + " in bank");
                return false;
            }
        }

        // Fresh batch scope — the next empty state only earns its closing break after a
        // batch runs on these materials.
        batchRanSinceBank = false;
        return true;
    }

    // ── Crafting actions ────────────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return;

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
        // fire on every tick of a running batch instead of once per batch.
        if (currentPhase != Phase.JEWELRY) {
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
        String uncutGemName = "uncut " + gem.getName();

        Microbot.status = "CUTTING " + gem.getName().toUpperCase();

        if (amethyst) {
            Rs2ItemModel chiselItem = Rs2Inventory.get("chisel");
            Rs2ItemModel amethystItem = AScriptPick.pick(21347);
            if (!AScriptClick.useItemOnItem(chiselItem, amethystItem)) {
                Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
                return false;
            }
            boolean amountDialogOpen = Rs2Widget.sleepUntilHasWidgetText(
                    "How many do you wish to make?", 270, 5, false, 5000);
            if (!amountDialogOpen) { Microbot.status = "IDLE"; return false; }
            Rs2Widget.clickWidget(gem.getName(), true);
            Rs2Widget.sleepUntilHasNotWidgetText("How many do you wish to make?", 270, 5, false, 5000);
            sleepUntil(() -> !Microbot.isGainingExp || !Rs2Inventory.hasItem(21347),
                    Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));
        } else {
            Rs2ItemModel chiselItem = Rs2Inventory.get("chisel");
            Rs2ItemModel uncutGemItem = AScriptPick.pick(uncutGemName);
            if (!AScriptClick.useItemOnItem(chiselItem, uncutGemItem)) {
                Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
                return false;
            }
            boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                    Rs2Widget.isProductionWidgetOpen(), 300, 20000);
            if (!craftingInterfaceOpen) { Microbot.status = "IDLE"; return false; }
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            sleepUntil(() -> !Microbot.isGainingExp || !Rs2Inventory.hasItem(uncutGemName),
                    Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

            if (config.fletchIntoBoltTips() && Rs2Inventory.hasItem(gem.getName())
                    && AScriptBank.hasUnnotedItem("chisel")) {
                Microbot.status = "FLETCHING BOLT TIPS";
                // Same chisel as above (a locked tool, still in the inventory); the cut gem
                // is the new use target.
                Rs2ItemModel cutGemItem = AScriptPick.pick(gem.getName());
                if (!AScriptClick.useItemOnItem(chiselItem, cutGemItem)) {
                    Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
                    return false;
                }
                boolean boltTipInterfaceOpen = sleepUntilTrue(() ->
                        Rs2Widget.isProductionWidgetOpen()
                                || Rs2Widget.isGoldCraftingWidgetOpen()
                                || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 20000);
                if (!boltTipInterfaceOpen) { Microbot.status = "IDLE"; return false; }
                Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                sleepUntil(() -> !Microbot.isGainingExp || !Rs2Inventory.hasItem(gem.getName()),
                        Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));
            }
        }

        Microbot.status = "IDLE";
        return true;
    }

    private boolean craftGlass(AScriptConfig config) {
        CraftingGlass glass = config.glassType();
        Microbot.status = "BLOWING " + glass.getLabel().toUpperCase();

        Rs2ItemModel pipeItem = Rs2Inventory.get("glassblowing pipe");
        Rs2ItemModel moltenGlassItem = AScriptPick.pick("molten glass");
        if (!AScriptClick.useItemOnItem(pipeItem, moltenGlassItem)) {
            Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
            return false;
        }
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) { Microbot.status = "IDLE"; return false; }
        Rs2Keyboard.keyPress(glass.getMenuEntry());
        sleepUntil(() -> !Microbot.isGainingExp || !AScriptBank.hasUnnotedItem("molten glass"),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));
        Microbot.status = "IDLE";
        return true;
    }

    private boolean craftStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        Microbot.status = "MAKING " + staff.getLabel().toUpperCase();
        Rs2ItemModel orbItem = AScriptPick.pick(staff.getOrb());
        Rs2ItemModel staffItem = AScriptPick.pick(staff.getItemName());
        if (!AScriptClick.useItemOnItem(orbItem, staffItem)) {
            Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
            return false;
        }
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) { Microbot.status = "IDLE"; return false; }
        Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        sleepUntil(() -> !Microbot.isGainingExp
                || !Rs2Inventory.hasItem(staff.getItemName()),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

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
        sleepUntil(() -> !Microbot.isGainingExp || !AScriptBank.hasUnnotedItem("flax"),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

        Microbot.status = "IDLE";
        return true;
    }

    private boolean craftDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        Microbot.status = "CRAFTING " + armour.getName().toUpperCase();

        Rs2ItemModel leatherItem = AScriptPick.pick(armour.getLeatherId());
        // The costume-needle variant picks its own name string; resolution stays the old
        // substring match Rs2Inventory.use(name) used, so the dispatch picks the same
        // needle the pre-recipe code would have.
        Rs2ItemModel needleItem = Rs2Inventory.get(
                config.useCostumeNeedle() ? "costume needle" : "needle");
        if (!AScriptClick.useItemOnItem(leatherItem, needleItem)) {
            Microbot.status = "IDLE"; // recipe logged + cancelled its own selection
            return false;
        }
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) { Microbot.status = "IDLE"; return false; }
        Rs2Keyboard.keyPress(armour.getMenuEntry());
        sleepUntil(() -> !Microbot.isGainingExp
                || !Rs2Inventory.hasItem(armour.getLeatherId()),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

        Microbot.status = "IDLE";
        return true;
    }

    private boolean craftJewelry(AScriptConfig config) {
        if (exitRequested) return false; // already tried to exit, don't spam

        JewelryItem item = config.jewelryItem();
        JewelryLocation location = config.jewelryLocation();
        if (item == JewelryItem.NONE || location == JewelryLocation.NONE) return false;

        // Level requirement check
        int craftLevel = Rs2Player.getRealSkillLevel(Skill.CRAFTING);
        if (craftLevel < item.getLevelRequired()) {
            exitRequested = true;
            Microbot.status = "STOPPED — level too low";
            AScriptNotify.notify("Jewelry Script Stopped",
                    "Crafting level " + craftLevel + " < required " + item.getLevelRequired()
                            + " for " + item.getName());
            Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            return false;
        }

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
            // Find the furnace game object by ID 16469
            TileObject furnaceObject = Rs2GameObject.findObjectById(16469);

            // If not found, walk to furnace location once
            if (furnaceObject == null && location.getFurnaceLocation() != null) {
                Microbot.status = "WALKING TO " + location.getLabel().toUpperCase();
                Rs2Walker.walkTo(location.getFurnaceLocation());
                sleep(Rs2Random.logNormalBounded(2000, 4000)); // randomized wait for walker

                // Try finding furnace again after walking
                furnaceObject = Rs2GameObject.findObjectById(16469);
            }

            // If still not found, deactivate script + notify Discord (once)
            if (furnaceObject == null) {
                exitRequested = true;
                Microbot.status = "NO FURNACE — STOPPING";
                AScriptNotify.notify("Jewelry Script Stopped",
                        "Furnace (ID 16469) not found at " + location.getLabel()
                                + ". Deactivating script.");
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
                () -> Rs2Widget.clickWidget(item.getName(), java.util.Optional.of(item.getWidgetGroup()),
                        item.getWidgetChild(), false),
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

    @Override
    public boolean isActive() { return currentPhase != Phase.NONE; }
}
