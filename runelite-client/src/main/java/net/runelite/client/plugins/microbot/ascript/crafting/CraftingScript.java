package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntilTrue;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

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

    public enum Phase {
        NONE, GEMS, GLASS, STAFFS, FLAX, DRAGON_LEATHER, JEWELRY
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
        if (staff == CraftingStaff.NONE || staff == CraftingStaff.PROGRESSIVE) return false;
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
        if (!Rs2Inventory.hasItem(item.getJewelryType().getItemID())) return true;
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
        boolean bankHasChisel = Rs2Bank.hasItem("chisel");
        if (amethyst) {
            return !Rs2Bank.hasItem(21347) || !bankHasChisel;
        }
        return !Rs2Bank.hasItem("uncut " + gem.getName()) || !bankHasChisel;
    }

    private boolean bankMissingGlass() {
        return !Rs2Bank.hasItem("molten glass") || !Rs2Bank.hasItem("glassblowing pipe");
    }

    private boolean bankMissingStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        if (staff == CraftingStaff.NONE || staff == CraftingStaff.PROGRESSIVE) return false;
        return !Rs2Bank.hasItem(staff.getItemName()) || !Rs2Bank.hasItem(staff.getOrb());
    }

    private boolean bankMissingFlax() {
        return !Rs2Bank.hasItem("flax");
    }

    private boolean bankMissingDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        if (armour == CraftingDragonLeather.NONE) return false;
        boolean bankHasNeedle = Rs2Bank.hasItem(config.useCostumeNeedle() ? "costume needle" : "needle");
        boolean bankHasThread = Rs2Bank.hasItem("thread");
        return !Rs2Bank.hasItem(armour.getLeatherId()) || !bankHasNeedle || !bankHasThread;
    }

    private boolean bankMissingJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        boolean bankHasBar = Rs2Bank.hasItem(item.getJewelryType().getItemID());
        boolean bankHasMould = Rs2Bank.hasItem(item.getToolItemID());
        if (!bankHasBar || !bankHasMould) return true;
        if (item.hasGem()) {
            return !Rs2Bank.hasItem(item.getGem().getCutItemID());
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
        if (staff == CraftingStaff.NONE || staff == CraftingStaff.PROGRESSIVE) return "";
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
        if (!Rs2Inventory.hasItem(item.getJewelryType().getItemID()))
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
        Rs2Bank.openBank();
        if (!Rs2Bank.isOpen()) return false;

        switch (phase) {
            case GEMS:            bankGems(config); break;
            case GLASS:           bankGlass(); break;
            case STAFFS:          bankStaffs(config); break;
            case FLAX:            bankFlax(); break;
            case DRAGON_LEATHER:  bankDragonLeather(config); break;
            case JEWELRY:         bankJewelry(config); break;
            default: break;
        }

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
        return true;
    }

    private void bankGems(AScriptConfig config) {
        CraftingGem gem = config.gemType();
        boolean amethyst = gem.getName().contains("Amethyst");
        String uncutGemName = "uncut " + gem.getName();

        Rs2Bank.depositAll(gem.getName());
        Rs2Bank.depositAll("crushed gem");
        sleepUntil(() -> !Rs2Inventory.hasItem(gem.getName())
                && !Rs2Inventory.hasItem("crushed gem"), 3000);

        if (amethyst) {
            if (Rs2Bank.hasItem(21347)) {
                Rs2Bank.withdrawItem(true, "chisel");
                Rs2Bank.withdrawAll(21347);
            } else {
                Microbot.showMessage("You've run out of materials!");
            }
        } else {
            if (Rs2Bank.hasItem(uncutGemName)) {
                Rs2Bank.withdrawItem(true, "chisel");
                Rs2Bank.withdrawAll(true, uncutGemName);
            } else {
                Microbot.showMessage("You've run out of materials!");
            }
        }
    }

    private void bankGlass() {
        Rs2Bank.depositAll("molten glass");
        sleepUntil(() -> !Rs2Inventory.hasItem("molten glass"), 3000);

        if (Rs2Bank.hasItem("molten glass")) {
            Rs2Bank.withdrawItem(true, "glassblowing pipe");
            Rs2Bank.withdrawAll(true, "molten glass");
        } else {
            Microbot.showMessage("You've run out of molten glass!");
        }
    }

    private void bankStaffs(AScriptConfig config) {
        CraftingStaff staff = config.staffType();
        Rs2Bank.depositAll(staff.getItemName());
        Rs2Bank.depositAll(staff.getOrb());
        sleepUntil(() -> !Rs2Inventory.hasItem(staff.getItemName())
                && !Rs2Inventory.hasItem(staff.getOrb()), 3000);

        if (Rs2Bank.hasItem(staff.getItemName()) && Rs2Bank.hasItem(staff.getOrb())) {
            Rs2Bank.withdrawAll(true, staff.getItemName());
            Rs2Bank.withdrawAll(true, staff.getOrb());
        } else {
            Microbot.showMessage("You've run out of materials!");
        }
    }

    private void bankFlax() {
        Rs2Bank.depositAll("bow string");
        Rs2Bank.depositAll("flax");
        sleepUntil(() -> !Rs2Inventory.hasItem("bow string")
                && !Rs2Inventory.hasItem("flax"), 3000);

        if (Rs2Bank.hasItem("flax")) {
            Rs2Bank.withdrawAll(true, "flax");
        } else {
            Microbot.showMessage("You've run out of flax!");
        }
    }

    private void bankDragonLeather(AScriptConfig config) {
        CraftingDragonLeather armour = config.dragonLeatherType();
        Rs2Bank.depositAll(armour.getItemId());
        sleepUntil(() -> !Rs2Inventory.hasItem(armour.getItemId()), 3000);

        boolean hasNeedle = config.useCostumeNeedle()
                ? Rs2Inventory.hasItem("costume needle")
                : Rs2Inventory.hasItem("needle");
        boolean hasThread = config.useCostumeNeedle() || Rs2Inventory.hasItem("thread");

        if (!hasNeedle) {
            Rs2Bank.withdrawItem(true, config.useCostumeNeedle() ? "costume needle" : "needle");
        }
        if (!hasThread && !config.useCostumeNeedle()) {
            Rs2Bank.withdrawItem(true, "thread");
        }

        if (Rs2Bank.hasItem(armour.getLeatherId())) {
            Rs2Bank.withdrawAll(armour.getLeatherId());
        } else {
            Microbot.showMessage("You've run out of dragon leather!");
        }
    }

    private void bankJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();

        // Deposit any finished jewelry
        Rs2Bank.depositAll(item.getItemID());
        // Deposit cut gems if any leftover
        if (item.hasGem()) {
            Rs2Bank.depositAll(item.getGem().getCutItemID());
        }
        sleepUntil(() -> !Rs2Inventory.hasItem(item.getItemID()), 3000);

        // Withdraw mould if needed
        if (!Rs2Inventory.hasItem(item.getToolItemID())) {
            if (Rs2Bank.hasItem(item.getToolItemID())) {
                Rs2Bank.withdrawOne(item.getToolItemID());
            } else {
                Microbot.showMessage("Missing mould in bank!");
                return;
            }
        }

        // Withdraw bar
        Rs2Bank.withdrawAll(item.getJewelryType().getItemID());
        sleepUntil(() -> Rs2Inventory.hasItem(item.getJewelryType().getItemID()), 3000);

        // If gem jewelry, withdraw cut gems
        if (item.hasGem()) {
            Rs2Bank.withdrawAll(item.getGem().getCutItemID());
            sleepUntil(() -> Rs2Inventory.hasItem(item.getGem().getCutItemID()), 3000);
        }
    }

    // ── Crafting actions ────────────────────────────────────────

    public void doCraft(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn()) return;

        // Ensure fresh weather data (cached for 30 min, safe to call every tick)
        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // Random AFK — log-normal distribution, weather-modulated
        if (config.craftingAfk() && System.currentTimeMillis() - lastAfkTime > 120_000) {
            int afkMs = Rs2Random.logNormalBounded(3000, 60000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            sleep(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }

        switch (phase) {
            case GEMS:            craftGems(config); break;
            case GLASS:           craftGlass(config); break;
            case STAFFS:          craftStaffs(config); break;
            case FLAX:            craftFlax(); break;
            case DRAGON_LEATHER:  craftDragonLeather(config); break;
            case JEWELRY:         craftJewelry(config); break;
            default: break;
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
            Rs2Widget.sleepUntilHasWidgetText("How many do you wish to make?", 270, 5, false, 5000);
            Rs2Widget.clickWidget(gem.getName(), true);
            Rs2Widget.sleepUntilHasNotWidgetText("How many do you wish to make?", 270, 5, false, 5000);
            sleepUntil(() -> !Microbot.isGainingExp || !Rs2Inventory.hasItem(21347),
                    Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));
        } else {
            Rs2Inventory.use("chisel");
            Rs2Inventory.use(uncutGemName);
            boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                    Rs2Widget.isProductionWidgetOpen()
                            || Rs2Widget.isGoldCraftingWidgetOpen()
                            || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 20000);
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
                Rs2Widget.isProductionWidgetOpen()
                        || Rs2Widget.isGoldCraftingWidgetOpen()
                        || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 20000);
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
                Rs2Widget.isProductionWidgetOpen()
                        || Rs2Widget.isGoldCraftingWidgetOpen()
                        || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) return;
        Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        sleepUntil(() -> !Microbot.isGainingExp
                || !Rs2Inventory.hasItem(staff.getItemName()),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

        Microbot.status = "IDLE";
    }

    private void craftFlax() {
        Microbot.status = "SPINNING FLAX";

        Rs2Inventory.use("flax");
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen()
                        || Rs2Widget.isGoldCraftingWidgetOpen()
                        || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 20000);
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
                Rs2Widget.isProductionWidgetOpen()
                        || Rs2Widget.isGoldCraftingWidgetOpen()
                        || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) return;
        Rs2Keyboard.keyPress(armour.getMenuEntry());
        sleepUntil(() -> !Microbot.isGainingExp
                || !Rs2Inventory.hasItem(armour.getLeatherId()),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

        Microbot.status = "IDLE";
    }

    private void craftJewelry(AScriptConfig config) {
        JewelryItem item = config.jewelryItem();
        Microbot.status = "SMELTING " + item.getName().toUpperCase();

        // Click furnace and select jewelry
        // Use mould on furnace (simplified — assumes player near furnace)
        Rs2Inventory.use(item.getToolItemID());
        boolean craftingInterfaceOpen = sleepUntilTrue(() ->
                Rs2Widget.isProductionWidgetOpen()
                        || Rs2Widget.isGoldCraftingWidgetOpen()
                        || Rs2Widget.isSilverCraftingWidgetOpen(), 300, 20000);
        if (!craftingInterfaceOpen) return;
        // Select from the make interface
        Rs2Widget.sleepUntilHasWidgetText("What would you like to make?", 270, 13, false, 5000);
        Rs2Widget.clickWidget(item.getName(), true);
        sleepUntil(() -> !Microbot.isGainingExp
                || !Rs2Inventory.hasItem(item.getJewelryType().getItemID()),
                Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier));

        Microbot.status = "IDLE";
    }

    // ── Helpers (delegate to Script base) ───────────────────────
    // These are called from Script context; CraftingScript doesn't
    // extend Script itself, so we use static utility methods.

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void sleepUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep(100);
        }
    }
}
