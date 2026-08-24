package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.TileObject;
import net.runelite.client.plugins.microbot.Microbot;
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
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;


import java.awt.event.KeyEvent;

/**
 * Crafting sub-script — stateless helper called by AScript's CRAFTING / BANKING states.
 * <p>
 * Adapted from Microbot Hub CraftingPlugin.
 */
@Slf4j
public class CraftingScript {

    private long lastAfkTime;
    /** Set once per doCraft() call; used by all craft methods for weather-modulated timing. */
    private double weatherMultiplier = 1.0;
    /** Prevent repeated exit attempts / Discord spam when furnace not found. */
    private boolean exitRequested = false;

    public enum Phase {
        NONE, GEMS, GLASS, STAFFS, FLAX, DRAGON_LEATHER, JEWELRY
    }

    /** Reset exit flag when script is re-enabled. Call from AScript on state enter. */
    public void resetExitFlag() {
        exitRequested = false;
    }

    // ── Phase resolution ────────────────────────────────────────

    public Phase resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.CRAFTING
                || config.craftingActivity() == null) return Phase.NONE;
        switch (config.craftingActivity()) {
            case GEM_CUTTING:    return Phase.GEMS;
            case GLASSBLOWING:   return Phase.GLASS;
            case STAFF_MAKING:   return Phase.STAFFS;
            case FLAX_SPINNING:  return Phase.FLAX;
            case DRAGON_LEATHER: return Phase.DRAGON_LEATHER;
            case JEWELRY:        return Phase.JEWELRY;
            default:             return Phase.NONE;
        }
    }

    // ── Selection validation ────────────────────────────────────

    /**
     * Returns an error description when the configured activity has an invalid
     * sub-selection (e.g. GEM_CUTTING without a gem type), or null when valid.
     * Checked by AScript.tick() before any bank interaction.
     */
    public String validateSelection(AScriptConfig config, Phase phase) {
        switch (phase) {
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
            default:
                return null;
        }
    }

    // ── Bank check ──────────────────────────────────────────────

    public boolean needsBank(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        switch (phase) {
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
            return !Rs2Inventory.hasItem(21347) || !Rs2Inventory.hasItem("chisel");
        }
        return !Rs2Inventory.hasItem("uncut " + gem.getName()) || !Rs2Inventory.hasItem("chisel");
    }

    private boolean needsBankGlass() {
        return !Rs2Inventory.hasItem("molten glass") || !Rs2Inventory.hasItem("glassblowing pipe");
    }

    private boolean needsBankStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        if (staff == CraftingStaff.NONE) return false;
        return !Rs2Inventory.hasItem(staff.getItemName()) || !Rs2Inventory.hasItem(staff.getOrb());
    }

    private boolean needsBankFlax() {
        return !Rs2Inventory.hasItem("flax");
    }

    private boolean needsBankDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return false;
        boolean hasNeedle = config.useCostumeNeedle()
                ? Rs2Inventory.hasItem("costume needle")
                : Rs2Inventory.hasItem("needle");
        boolean hasThread = config.useCostumeNeedle() || Rs2Inventory.hasItem("thread");
        return !Rs2Inventory.hasItem(armour.getLeatherId()) || !hasNeedle || !hasThread;
    }

    private boolean needsBankJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        // Need the bar
        if (!Rs2Inventory.hasItem(item.getJewelryType().getMetalBarId())) return true;
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
    public boolean isBankMissingMaterials(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config, phase)) return false; // inventory has everything
        switch (phase) {
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
        // Only check bank for items missing from inventory
        if (!Rs2Inventory.hasItem("chisel") && !Rs2Bank.hasItem("chisel")) return true;
        if (amethyst) {
            return !Rs2Inventory.hasItem(21347) && !Rs2Bank.hasItem(21347);
        }
        return !Rs2Inventory.hasItem("uncut " + gem.getName()) && !Rs2Bank.hasItem("uncut " + gem.getName());
    }

    private boolean bankMissingGlass() {
        if (!Rs2Inventory.hasItem("glassblowing pipe") && !Rs2Bank.hasItem("glassblowing pipe")) return true;
        return !Rs2Inventory.hasItem("molten glass") && !Rs2Bank.hasItem("molten glass");
    }

    private boolean bankMissingStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        if (staff == CraftingStaff.NONE) return false;
        if (!Rs2Inventory.hasItem(staff.getItemName()) && !Rs2Bank.hasItem(staff.getItemName())) return true;
        return !Rs2Inventory.hasItem(staff.getOrb()) && !Rs2Bank.hasItem(staff.getOrb());
    }

    private boolean bankMissingFlax() {
        return !Rs2Bank.hasItem("flax");
    }

    private boolean bankMissingDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return false;
        String needle = config.useCostumeNeedle() ? "costume needle" : "needle";
        if (!Rs2Inventory.hasItem(armour.getLeatherId()) && !Rs2Bank.hasItem(armour.getLeatherId())) return true;
        if (!Rs2Inventory.hasItem(needle) && !Rs2Bank.hasItem(needle)) return true;
        if (!config.useCostumeNeedle() && !Rs2Inventory.hasItem("thread") && !Rs2Bank.hasItem("thread")) return true;
        return false;
    }

    private boolean bankMissingJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        // Check ONLY items that are missing from inventory
        // If mould is already in inventory, don't require it in bank
        if (!Rs2Inventory.hasItem(item.getJewelryType().getMetalBarId())
                && !Rs2Bank.hasItem(item.getJewelryType().getMetalBarId())) {
            return true; // need bar, bank doesn't have it
        }
        if (!Rs2Inventory.hasItem(item.getToolItemID())
                && !Rs2Bank.hasItem(item.getToolItemID())) {
            return true; // need mould, bank doesn't have it
        }
        if (item.hasGem()
                && !Rs2Inventory.hasItem(item.getGem().getCutItemID())
                && !Rs2Bank.hasItem(item.getGem().getCutItemID())) {
            return true; // need cut gem, bank doesn't have it
        }
        return false;
    }

    // ── Missing-materials description ────────────────────────────

    public String describeMissing(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE) return "";
        switch (phase) {
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
            if (!Rs2Inventory.hasItem(21347)) sb.append("Amethyst block, ");
            if (!Rs2Inventory.hasItem("chisel")) sb.append("chisel, ");
        } else {
            if (!Rs2Inventory.hasItem("uncut " + gem.getName())) sb.append("uncut ").append(gem.getName()).append(", ");
            if (!Rs2Inventory.hasItem("chisel")) sb.append("chisel, ");
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingGlass() {
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem("molten glass")) sb.append("molten glass, ");
        if (!Rs2Inventory.hasItem("glassblowing pipe")) sb.append("glassblowing pipe, ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        if (staff == CraftingStaff.NONE) return "";
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(staff.getItemName())) sb.append(staff.getItemName()).append(", ");
        if (!Rs2Inventory.hasItem(staff.getOrb())) sb.append(staff.getOrb()).append(", ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingFlax() {
        return !Rs2Inventory.hasItem("flax") ? "flax" : "";
    }

    private String describeMissingDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return "";
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(armour.getLeatherId())) sb.append(armour.getName()).append(", ");
        boolean hasNeedle = config.useCostumeNeedle()
                ? Rs2Inventory.hasItem("costume needle")
                : Rs2Inventory.hasItem("needle");
        if (!hasNeedle) sb.append(config.useCostumeNeedle() ? "costume needle" : "needle").append(", ");
        boolean hasThread = config.useCostumeNeedle() || Rs2Inventory.hasItem("thread");
        if (!hasThread) sb.append("thread").append(", ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(item.getJewelryType().getMetalBarId()))
            sb.append(item.getJewelryType().getLabel()).append(", ");
        if (!Rs2Inventory.hasItem(item.getToolItemID()))
            sb.append("mould, ");
        if (item.hasGem() && !Rs2Inventory.hasItem(item.getGem().getCutItemID()))
            sb.append(item.getGem().getCutItemName()).append(", ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    // ── Banking actions ─────────────────────────────────────────

    public boolean doBank(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn()) return true;

        Microbot.status = "BANKING";

        // Only open bank if not already open (IDLE may have opened it)
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            if (!Rs2Bank.isOpen()) return false;
        }

        boolean success = false;
        switch (phase) {
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
                ? Rs2Inventory.hasItem("costume needle")
                : Rs2Inventory.hasItem("needle");
        boolean hasThread = config.useCostumeNeedle() || Rs2Inventory.hasItem("thread");

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

        // Deposit everything via the bank's "Deposit inventory" toolbar button
        // (Rs2Bank.depositAll() -> raw click on the BUTTON widget).
        // Item-GRID interactions die silently on some machines — injected CC_OP
        // entries AND raw slot clicks both fail there (verified live via Agent
        // Server); the toolbar button click works. Crafted jewelry and leftover
        // gems go together.
        Microbot.status = "Depositing inventory";
        if (!AScriptBank.depositAndWaitEmpty()) {
            return false; // waitForInventoryChanges timed out — retry next tick
        }

        // Withdraw ONE mould and lock its inventory slot (tool stays across deposits;
        // any previously locked slot holding a DIFFERENT tool is released + deposited).
        if (!AScriptBank.ensureToolLocked(Integer.toString(item.getToolItemID()))) {
            AScriptNotify.notify("Banking Failed", "Missing mould in bank for " + item.getName());
            return false;
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

        return true;
    }

    // ── Crafting actions ────────────────────────────────────────

    public void doCraft(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn()) return;

        // Ensure fresh weather data (cached for 30 min, safe to call every tick)
        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        switch (phase) {
            case GEMS:            craftGems(config); break;
            case GLASS:           craftGlass(config); break;
            case STAFFS:          craftStaffs(config); break;
            case FLAX:            craftFlax(config); break;
            case DRAGON_LEATHER:  craftDragonLeather(config); break;
            case JEWELRY:         craftJewelry(config); break;
            default: break;
        }

        sleep(1200);

        // Random AFK — log-normal distribution, weather-modulated
        if (config.craftingAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3000, 120000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            sleep(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    private void craftGems(AScriptConfig config) {
        CraftingGem gem = config.gemType();
        boolean amethyst = gem.getName().contains("Amethyst");
        String uncutGemName = "uncut " + gem.getName();

        Microbot.status = "CUTTING " + gem.getName().toUpperCase();

        if (amethyst) {
            Rs2Inventory.use("chisel");
            Rs2Inventory.use(21347);
            // Gate on the amount dialog like the other branches — clicking blind
            // when the dialog never opened just burns the craft-wait timeout.
            boolean amountDialogOpen = Rs2Widget.sleepUntilHasWidgetText(
                    "How many do you wish to make?", 270, 5, false, 5000);
            if (!amountDialogOpen) return;
            Rs2Widget.clickWidget(gem.getName(), true);
            Rs2Widget.sleepUntilHasNotWidgetText("How many do you wish to make?", 270, 5, false, 5000);
            sleepUntil(() -> !Microbot.isGainingExp || !Rs2Inventory.hasItem(21347),
                    Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));
        } else {
            Rs2Inventory.use("chisel");
            Rs2Inventory.use(uncutGemName);
            boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                    Rs2Widget.isProductionWidgetOpen(), 300, 20000);
            if (!craftingInterfaceOpen) return;
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            sleepUntil(() -> !Microbot.isGainingExp || !Rs2Inventory.hasItem(uncutGemName),
                    Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

            if (config.fletchIntoBoltTips() && Rs2Inventory.hasItem(gem.getName())
                    && Rs2Inventory.hasItem("chisel")) {
                Microbot.status = "FLETCHING BOLT TIPS";
                Rs2Inventory.use("chisel");
                Rs2Inventory.use(gem.getName());
                boolean boltTipInterfaceOpen = sleepUntilTrue(() ->
                        Rs2Widget.isProductionWidgetOpen()
                                || Rs2Widget.isGoldCraftingWidgetOpen()
                                || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 20000);
                if (!boltTipInterfaceOpen) return;
                Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                sleepUntil(() -> !Microbot.isGainingExp || !Rs2Inventory.hasItem(gem.getName()),
                        Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));
            }
        }

        Microbot.status = "IDLE";
    }

    private void craftGlass(AScriptConfig config) {
        CraftingGlass glass = config.glassType();
        Microbot.status = "BLOWING " + glass.getLabel().toUpperCase();

        Rs2Inventory.use("glassblowing pipe");
        Rs2Inventory.use("molten glass");
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) return;
        Rs2Keyboard.keyPress(glass.getMenuEntry());
        sleepUntil(() -> !Microbot.isGainingExp || !Rs2Inventory.hasItem("molten glass"),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));
        Microbot.status = "IDLE";
    }

    private void craftStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        Microbot.status = "MAKING " + staff.getLabel().toUpperCase();
        Rs2Inventory.use(staff.getOrb());
        Rs2Inventory.use(staff.getItemName());
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) return;
        Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        sleepUntil(() -> !Microbot.isGainingExp
                || !Rs2Inventory.hasItem(staff.getItemName()),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

        Microbot.status = "IDLE";
    }

    private void craftFlax(AScriptConfig config) {
        if (exitRequested) return; // already tried to exit, don't spam

        CraftingFlaxLocation location = config.flaxSpinLocation();

        // A wheel cannot be located without a configured location
        if (location == null || location == CraftingFlaxLocation.NONE) {
            exitRequested = true;
            Microbot.status = "NO FLAX LOCATION — STOPPING";
            AScriptNotify.notify("Flax Script Stopped",
                    "No spinning wheel location selected. Set Flax Location in the config.");
            Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            return;
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
            return;
        }

        // Turn camera if wheel not on screen
        if (!Rs2Camera.isTileOnScreen(wheelObject.getLocalLocation())) {
            Rs2Camera.turnTo(wheelObject.getLocalLocation());
            return;
        }

        // Click the wheel with Spin action
        Rs2GameObject.interact(wheelObject, "Spin");

        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) return;
        Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        sleepUntil(() -> !Microbot.isGainingExp || !Rs2Inventory.hasItem("flax"),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

        Microbot.status = "IDLE";
    }

    private void craftDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        Microbot.status = "CRAFTING " + armour.getName().toUpperCase();

        Rs2Inventory.use(armour.getLeatherId());
        Rs2Inventory.use(config.useCostumeNeedle() ? "costume needle" : "needle");
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) return;
        Rs2Keyboard.keyPress(armour.getMenuEntry());
        sleepUntil(() -> !Microbot.isGainingExp
                || !Rs2Inventory.hasItem(armour.getLeatherId()),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

        Microbot.status = "IDLE";
    }

    private void craftJewelry(AScriptConfig config) {
        if (exitRequested) return; // already tried to exit, don't spam

        JewelryItem item = config.jewelryItem();
        JewelryLocation location = config.jewelryLocation();
        Microbot.status = "SMELTING " + item.getName().toUpperCase();

        // Find the furnace game object by ID 16469
        TileObject furnaceObject = Rs2GameObject.findObjectById(16469);

        // If not found, walk to furnace location once
        if (furnaceObject == null && location != null && location.getFurnaceLocation() != null) {
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
                    "Furnace (ID 16469) not found at " + (location != null ? location.getLabel() : "unknown")
                            + ". Deactivating script.");
            Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            return;
        }

        // Turn camera if furnace not on screen
        if (!Rs2Camera.isTileOnScreen(furnaceObject.getLocalLocation())) {
            Rs2Camera.turnTo(furnaceObject.getLocalLocation());
            return;
        }

        // Click furnace with Smelt action
        Rs2GameObject.interact(furnaceObject, "Smelt");

        // Wait for crafting interface to open
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen()
                        || Rs2Widget.isGoldCraftingWidgetOpen()
                        || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 30000);
        if (!craftingInterfaceOpen) return;

        // Click the correct widget by (group, child) from the JewelryItem enum
        Rs2Widget.clickWidget(item.getName(), java.util.Optional.of(item.getWidgetGroup()), item.getWidgetChild(), false);

        sleepUntil(() -> Microbot.isGainingExp, Rs2Random.logNormalBounded(3000, 12000, weatherMultiplier));

        sleepUntil(() -> !Microbot.isGainingExp
                || !Rs2Inventory.hasItem(item.getJewelryType().getMetalBarId()),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

        Microbot.status = "IDLE";
    }
}
