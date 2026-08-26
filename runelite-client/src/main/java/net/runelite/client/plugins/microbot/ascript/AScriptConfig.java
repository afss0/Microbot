package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.plugins.microbot.ascript.crafting.*;
import net.runelite.client.plugins.microbot.ascript.fletching.*;
import net.runelite.client.plugins.microbot.util.skills.fletching.data.FletchingArrow;
import net.runelite.client.plugins.microbot.util.skills.fletching.data.FletchingBolt;
import net.runelite.client.plugins.microbot.util.skills.fletching.data.FletchingDart;

@ConfigGroup(AScriptConfig.GROUP)
public interface AScriptConfig extends Config {

    String GROUP = "aScript";

    // ── Automation ──────────────────────────────────────────────

    @ConfigSection(
            name = "Automation",
            description = "Automation settings",
            position = 0
    )
    String automationSection = "automation";

    @ConfigItem(
            keyName = "enabled",
            name = "Enabled",
            description = "Enable / disable the script",
            position = 0,
            section = automationSection
    )
    default boolean enabled() {
        return false;
    }

    @ConfigItem(
            keyName = "scriptSelection",
            name = "Script",
            description = "Select which automation script to run",
            position = 1,
            section = automationSection
    )
    default ScriptType scriptSelection() {
        return ScriptType.NONE;
    }

    // ── Crafting ────────────────────────────────────────────────

    @ConfigSection(
            name = "AutoCrafting",
            description = "AutoCrafting settings",
            position = 1,
            closedByDefault = true
    )
    String craftingSection = "crafting";

    @ConfigItem(
            keyName = "craftingActivity",
            name = "Activity",
            description = "Choose the type of crafting activity to perform",
            position = 0,
            section = craftingSection
    )
    default CraftingActivity craftingActivity() {
        return CraftingActivity.NONE;
    }

    @ConfigItem(
            keyName = "craftingAfk",
            name = "Random AFKs",
            description = "Randomly AFKs between 3 and 60 seconds",
            position = 1,
            section = craftingSection
    )
    default boolean craftingAfk() {
        return false;
    }

    @ConfigItem(
            keyName = "gemType",
            name = "Gem",
            description = "Choose the type of gem to cut",
            position = 2,
            section = craftingSection
    )
    default CraftingGem gemType() {
        return CraftingGem.NONE;
    }

    @ConfigItem(
            keyName = "fletchIntoBoltTips",
            name = "Fletch into Bolt Tips",
            description = "Fletch cut gems into bolt tips if possible",
            position = 3,
            section = craftingSection
    )
    default boolean fletchIntoBoltTips() {
        return false;
    }

    @ConfigItem(
            keyName = "glassType",
            name = "Glass",
            description = "Choose the type of glass item to blow",
            position = 4,
            section = craftingSection
    )
    default CraftingGlass glassType() {
        return CraftingGlass.NONE;
    }

    @ConfigItem(
            keyName = "staffType",
            name = "Staffs",
            description = "Choose the type of battlestaff to make",
            position = 5,
            section = craftingSection
    )
    default CraftingStaff staffType() {
        return CraftingStaff.NONE;
    }

    @ConfigItem(
            keyName = "flaxSpinLocation",
            name = "Flax Location",
            description = "Choose location where to spin flax",
            position = 6,
            section = craftingSection
    )
    default CraftingFlaxLocation flaxSpinLocation() {
        return CraftingFlaxLocation.NONE;
    }

    @ConfigItem(
            keyName = "dragonLeatherArmour",
            name = "Dragon Leather Armour",
            description = "Choose type of dragon leather armour",
            position = 7,
            section = craftingSection
    )
    default CraftingDragonLeather dragonLeatherType() {
        return CraftingDragonLeather.NONE;
    }

    @ConfigItem(
            keyName = "useCostumeNeedle",
            name = "Use Costume Needle",
            description = "Use costume needle instead of regular needle + thread",
            position = 8,
            section = craftingSection
    )
    default boolean useCostumeNeedle() {
        return false;
    }

    // ── Jewelry ─────────────────────────────────────────────────

    @ConfigSection(
            name = "Jewelry",
            description = "Jewelry crafting settings",
            position = 2,
            closedByDefault = true
    )
    String jewelrySection = "jewelry";

    @ConfigItem(
            keyName = "jewelryItem",
            name = "Jewelry",
            description = "Choose the jewelry item to craft",
            position = 0,
            section = jewelrySection
    )
    default JewelryItem jewelryItem() {
        return JewelryItem.GOLD_RING;
    }

    @ConfigItem(
            keyName = "jewelryLocation",
            name = "Furnace Location",
            description = "Choose furnace location to craft jewelry",
            position = 1,
            section = jewelrySection
    )
    default JewelryLocation jewelryLocation() {
        return JewelryLocation.EDGEVILLE;
    }

    // ── Fletching ──────────────────────────────────────────────

    @ConfigSection(
            name = "AutoFletching",
            description = "AutoFletching settings",
            position = 3,
            closedByDefault = true
    )
    String fletchingSection = "fletching";

    @ConfigItem(
            keyName = "fletchingActivity",
            name = "Activity",
            description = "Choose the type of fletching activity to perform",
            position = 0,
            section = fletchingSection
    )
    default FletchingActivity fletchingActivity() {
        return FletchingActivity.NONE;
    }

    @ConfigItem(
            keyName = "fletchingAfk",
            name = "Random AFKs",
            description = "Randomly AFKs between 3 and 60 seconds",
            position = 1,
            section = fletchingSection
    )
    default boolean fletchingAfk() {
        return false;
    }

    @ConfigItem(
            keyName = "fletchingDartType",
            name = "Dart Type",
            description = "Choose the type of dart to make",
            position = 2,
            section = fletchingSection
    )
    default FletchingDart fletchingDartType() {
        return FletchingDart.BRONZE;
    }

    @ConfigItem(
            keyName = "fletchingBoltType",
            name = "Bolt Type",
            description = "Choose the type of bolt to make",
            position = 3,
            section = fletchingSection
    )
    default FletchingBolt fletchingBoltType() {
        return FletchingBolt.BRONZE;
    }

    @ConfigItem(
            keyName = "fletchingArrowType",
            name = "Arrow Type",
            description = "Choose the type of arrow to make",
            position = 4,
            section = fletchingSection
    )
    default FletchingArrow fletchingArrowType() {
        return FletchingArrow.BRONZE;
    }

    @ConfigItem(
            keyName = "fletchingBowType",
            name = "Bow Type",
            description = "Choose the type of bow to string",
            position = 5,
            section = fletchingSection
    )
    default FletchingBowType fletchingBowType() {
        return FletchingBowType.NONE;
    }

    // ── QOL ─────────────────────────────────────────────────────

    @ConfigSection(
            name = "QOL",
            description = "Quality of Life settings",
            position = 4
    )
    String qolSection = "qol";

    @ConfigItem(
            keyName = "autoZoomOut",
            name = "Auto zoom out",
            description = "Periodically zooms the camera fully out (respects the Camera plugin's expanded outer limit)",
            position = 0,
            section = qolSection
    )
    default boolean autoZoomOut() {
        return false;
    }

    @ConfigItem(
            keyName = "autoEat",
            name = "Auto eat",
            description = "Automatically eats food from the inventory when hitpoints fall below a randomly rolled threshold (re-rolled after every bite)",
            position = 1,
            section = qolSection
    )
    default boolean autoEat() {
        return false;
    }

    @Range(min = 1, max = 99)
    @ConfigItem(
            keyName = "autoEatMinHpPercent",
            name = "Min eat HP %",
            description = "Lower bound of the random eat threshold roll",
            position = 2,
            section = qolSection
    )
    default int autoEatMinHpPercent() {
        return 40;
    }

    @Range(min = 1, max = 99)
    @ConfigItem(
            keyName = "autoEatMaxHpPercent",
            name = "Max eat HP %",
            description = "Upper bound of the random eat threshold roll",
            position = 3,
            section = qolSection
    )
    default int autoEatMaxHpPercent() {
        return 60;
    }
}
