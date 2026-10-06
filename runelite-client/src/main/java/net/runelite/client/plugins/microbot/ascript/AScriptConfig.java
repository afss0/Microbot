package net.runelite.client.plugins.microbot.ascript;

import net.runelite.api.Skill;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.plugins.microbot.ascript.ammonitecrabs.AmmoniteCrabFood;
import net.runelite.client.plugins.microbot.ascript.ammonitecrabs.AmmoniteCrabLocation;
import net.runelite.client.plugins.microbot.ascript.ammonitecrabs.AmmoniteCrabPotion;
import net.runelite.client.plugins.microbot.ascript.barbarianfishing.BarbarianFishingActivity;
import net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher.BarbarianFishingFunctions;
import net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher.BarbarianFishingType;
import net.runelite.client.plugins.microbot.ascript.cannonballsmelter.CannonballSmelterFurnace;
import net.runelite.client.plugins.microbot.ascript.crafting.*;
import net.runelite.client.plugins.microbot.ascript.eventdismiss.EventAction;
import net.runelite.client.plugins.microbot.ascript.firemaking.FiremakingLocation;
import net.runelite.client.plugins.microbot.ascript.firemaking.FiremakingLog;
import net.runelite.client.plugins.microbot.ascript.fletching.*;
import net.runelite.client.plugins.microbot.ascript.jewellenchant.JewelEnchantActivity;
import net.runelite.client.plugins.microbot.ascript.jewellenchant.JewelEnchantItem;
import net.runelite.client.plugins.microbot.ascript.motherloadmine.MLMMiningSpotList;
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
        return JewelryItem.NONE;
    }

    @ConfigItem(
            keyName = "jewelryLocation",
            name = "Furnace Location",
            description = "Choose furnace location to craft jewelry",
            position = 1,
            section = jewelrySection
    )
    default JewelryLocation jewelryLocation() {
        return JewelryLocation.NONE;
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

    // ── Motherload Mine ────────────────────────────────────────

    @ConfigSection(
            name = "Motherload Mine",
            description = "Motherload Mine settings",
            position = 4,
            closedByDefault = true
    )
    String motherloadMineSection = "motherloadmine";

    @ConfigItem(
            keyName = "mlmMiningArea",
            name = "Mining Area",
            description = "Choose mining area",
            position = 0,
            section = motherloadMineSection
    )
    default MLMMiningSpotList mlmMiningArea() {
        return MLMMiningSpotList.ANY;
    }

    @ConfigItem(
            keyName = "mlmDropGems",
            name = "Drop Gems",
            description = "Drop gems while mining",
            position = 1,
            section = motherloadMineSection
    )
    default boolean mlmDropGems() {
        return false;
    }

    @ConfigItem(
            keyName = "mlmUseUpstairsHopper",
            name = "Use Upstairs Hopper",
            description = "Use upstairs hopper if unlocked",
            position = 2,
            section = motherloadMineSection
    )
    default boolean mlmUseUpstairsHopper() {
        return false;
    }

    @ConfigItem(
            keyName = "mlmAntiCrash",
            name = "Anti Crash",
            description = "Avoid other players when mining",
            position = 3,
            section = motherloadMineSection
    )
    default boolean mlmAntiCrash() {
        return false;
    }

    // ── Gem Crab Killer ──────────────────────────────────────

    @ConfigSection(
            name = "Gem Crab Killer",
            description = "Gem Crab Killer settings",
            position = 5,
            closedByDefault = true
    )
    String gemCrabKillerSection = "gemcrabkiller";

    @ConfigItem(
            keyName = "gemCrabLootCrab",
            name = "Loot Crab",
            description = "Mine the dead crab for loot",
            position = 0,
            section = gemCrabKillerSection
    )
    default boolean gemCrabLootCrab() {
        return true;
    }

    @ConfigItem(
            keyName = "gemCrabDharokMode",
            name = "Dharok Mode",
            description = "Low HP for Dharok set effect — uses locator orb/rock cake, rapid heal prayer flick",
            position = 1,
            section = gemCrabKillerSection
    )
    default boolean gemCrabDharokMode() {
        return false;
    }

    @ConfigItem(
            keyName = "gemCrabUseFood",
            name = "Use Food",
            description = "Eat food during combat and withdraw from bank when low. Disabled in Dharok mode (rock-cake to 10 HP instead).",
            position = 2,
            section = gemCrabKillerSection
    )
    default boolean gemCrabUseFood() {
        return true;
    }

    @ConfigItem(
            keyName = "gemCrabUseOffensivePotions",
            name = "Use Offensive Potions",
            description = "Drink combat potions during fight (ranged, magic, strength, attack, defence)",
            position = 3,
            section = gemCrabKillerSection
    )
    default boolean gemCrabUseOffensivePotions() {
        return false;
    }

    // ── Jewel Enchant ─────────────────────────────────────

    @ConfigSection(
            name = "Jewel Enchant",
            description = "Jewellery enchanting settings",
            position = 5,
            closedByDefault = true
    )
    String jewelEnchantSection = "jewelenchant";

    @ConfigItem(
            keyName = "jewelEnchantActivity",
            name = "Activity",
            description = "Choose the enchanting activity to perform",
            position = 0,
            section = jewelEnchantSection
    )
    default JewelEnchantActivity jewelEnchantActivity() {
        return JewelEnchantActivity.NONE;
    }

    @ConfigItem(
            keyName = "jewelEnchantItem",
            name = "Jewellery",
            description = "Choose the jewellery item to enchant",
            position = 1,
            section = jewelEnchantSection
    )
    default JewelEnchantItem jewelEnchantItem() {
        return JewelEnchantItem.NONE;
    }

    @ConfigItem(
            keyName = "jewelEnchantUseStaff",
            name = "Use Elemental Staff",
            description = "Equip an elemental staff instead of withdrawing elemental runes",
            position = 2,
            section = jewelEnchantSection
    )
    default boolean jewelEnchantUseStaff() {
        return false;
    }

    // ── Cannonball Smelter ─────────────────────────────────────

    @ConfigSection(
            name = "Cannonball Smelter",
            description = "Cannonball smelting settings",
            position = 7,
            closedByDefault = true
    )
    String cannonballSmelterSection = "cannonballsmelter";

    @ConfigItem(
            keyName = "cannonballFurnace",
            name = "Furnace",
            description = "Furnace location to smelt cannonballs at (start at the bank next to it; keep ammo mould and steel bars in the bank)",
            position = 0,
            section = cannonballSmelterSection
    )
    default CannonballSmelterFurnace cannonballFurnace() {
        return CannonballSmelterFurnace.EDGEVILLE;
    }

    @ConfigItem(
            keyName = "cannonballAfk",
            name = "Random AFKs",
            description = "Randomly AFKs between 3 and 120 seconds between smelting batches",
            position = 1,
            section = cannonballSmelterSection
    )
    default boolean cannonballAfk() {
        return true;
    }

    // ── Barbarian Village Fisher ────────────────────────────────

    @ConfigSection(
            name = "Barbarian Village Fisher",
            description = "Barbarian Village Fisher settings",
            position = 6,
            closedByDefault = true
    )
    String barbarianVillageFisherSection = "barbarianvillagefisher";

    @ConfigItem(
            keyName = "barbarianVillageFisherType",
            name = "Fishing Type",
            description = "Choose fly fishing or bait fishing",
            position = 0,
            section = barbarianVillageFisherSection
    )
    default BarbarianFishingType barbarianVillageFisherType() {
        return BarbarianFishingType.FLY_FISHING;
    }

    @ConfigItem(
            keyName = "barbarianVillageFisherFunction",
            name = "Function",
            description = "What to do with caught fish",
            position = 1,
            section = barbarianVillageFisherSection
    )
    default BarbarianFishingFunctions barbarianVillageFisherFunction() {
        return BarbarianFishingFunctions.DROP_RAW;
    }

    // ── Ammonite Crabs ──────────────────────────────────────────

    @ConfigSection(
            name = "Ammonite Crabs",
            description = "Ammonite crab killer settings",
            position = 7,
            closedByDefault = true
    )
    String ammoniteCrabsSection = "ammonitecrabs";

    @ConfigItem(
            keyName = "ammoniteCrabLocation",
            name = "Crab Location",
            description = "Choose the ammonite crab spot",
            position = 0,
            section = ammoniteCrabsSection
    )
    default AmmoniteCrabLocation ammoniteCrabLocation() {
        return AmmoniteCrabLocation.NONE;
    }

    @ConfigItem(
            keyName = "ammoniteCrabUseFood",
            name = "Use Food",
            description = "Eat food at 50% HP and restock at the bank when the food runs out",
            position = 1,
            section = ammoniteCrabsSection
    )
    default boolean ammoniteCrabUseFood() {
        return true;
    }

    @ConfigItem(
            keyName = "ammoniteCrabFood",
            name = "Food",
            description = "Food to withdraw at the bank",
            position = 2,
            section = ammoniteCrabsSection
    )
    default AmmoniteCrabFood ammoniteCrabFood() {
        return AmmoniteCrabFood.SHARK;
    }

    @ConfigItem(
            keyName = "ammoniteCrabUsePotions",
            name = "Use Potions",
            description = "Drink offensive potions in combat and restock at the bank when out",
            position = 3,
            section = ammoniteCrabsSection
    )
    default boolean ammoniteCrabUsePotions() {
        return false;
    }

    @ConfigItem(
            keyName = "ammoniteCrabPotion",
            name = "Potion",
            description = "Offensive potion to withdraw (4-dose)",
            position = 4,
            section = ammoniteCrabsSection
    )
    default AmmoniteCrabPotion ammoniteCrabPotion() {
        return AmmoniteCrabPotion.COMBAT;
    }

    @Range(min = 1, max = 28)
    @ConfigItem(
            keyName = "ammoniteCrabPotionAmount",
            name = "Potions to withdraw",
            description = "Number of 4-dose potions to withdraw per bank trip",
            position = 5,
            section = ammoniteCrabsSection
    )
    default int ammoniteCrabPotionAmount() {
        return 4;
    }

    @ConfigItem(
            keyName = "ammoniteCrabLootSpores",
            name = "Loot Seaweed Spores",
            description = "Pick up seaweed spores dropped near the spot",
            position = 6,
            section = ammoniteCrabsSection
    )
    default boolean ammoniteCrabLootSpores() {
        return false;
    }

    @ConfigItem(
            keyName = "ammoniteCrabHopOnCrash",
            name = "Hop On Crash",
            description = "Hop worlds when another player camps the spot",
            position = 7,
            section = ammoniteCrabsSection
    )
    default boolean ammoniteCrabHopOnCrash() {
        return true;
    }

    // ── Firemaking ──────────────────────────────────────────────

    @ConfigSection(
            name = "Firemaking",
            description = "Firemaking settings",
            position = 8,
            closedByDefault = true
    )
    String firemakingSection = "firemaking";

    @ConfigItem(
            keyName = "firemakingLog",
            name = "Log Type",
            description = "Logs to burn on fires/campfires (keep them in the bank; a tinderbox is required to light a fire when none is nearby)",
            position = 0,
            section = firemakingSection
    )
    default FiremakingLog firemakingLog() {
        return FiremakingLog.NONE;
    }

    @ConfigItem(
            keyName = "firemakingLocation",
            name = "Fire Location",
            description = "Where to light a fire when no campfire/fire is within range (start at the bank next to it)",
            position = 1,
            section = firemakingSection
    )
    default FiremakingLocation firemakingLocation() {
        return FiremakingLocation.GRAND_EXCHANGE_NORTH_EAST;
    }

    @ConfigItem(
            keyName = "firemakingAfk",
            name = "Random AFKs",
            description = "Randomly AFKs between 3 and 120 seconds between batches",
            position = 2,
            section = firemakingSection
    )
    default boolean firemakingAfk() {
        return true;
    }

    // ── Barbarian Fishing ───────────────────────────────────────

    @ConfigSection(
            name = "Barbarian Fishing",
            description = "Barbarian fishing settings",
            position = 9,
            closedByDefault = true
    )
    String barbarianFishingSection = "barbarianfishing";

    @ConfigItem(
            keyName = "barbarianFishingActivity",
            name = "Activity",
            description = "Choose the barbarian fishing activity to perform",
            position = 0,
            section = barbarianFishingSection
    )
    default BarbarianFishingActivity barbarianFishingActivity() {
        return BarbarianFishingActivity.NONE;
    }

    @ConfigItem(
            keyName = "barbarianFishingAfk",
            name = "Random AFKs",
            description = "Randomly AFKs between 3 and 120 seconds after every dropped batch",
            position = 1,
            section = barbarianFishingSection
    )
    default boolean barbarianFishingAfk() {
        return true;
    }

    @ConfigItem(
            keyName = "barbarianFishingHarpoonSpec",
            name = "Dragon Harpoon Spec",
            description = "Activate the dragon harpoon special attack when worn and its energy is full",
            position = 2,
            section = barbarianFishingSection
    )
    default boolean barbarianFishingHarpoonSpec() {
        return true;
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

    @ConfigItem(
            keyName = "eventDismissGenieAction",
            name = "Genie",
            description = "Accept the lamp from the Genie or dismiss the event",
            position = 4,
            section = qolSection
    )
    default EventAction eventDismissGenieAction() {
        return EventAction.ACCEPT;
    }

    @ConfigItem(
            keyName = "eventDismissCountCheckAction",
            name = "Count Check",
            description = "Accept the lamp from Count Check or dismiss the event",
            position = 5,
            section = qolSection
    )
    default EventAction eventDismissCountCheckAction() {
        return EventAction.ACCEPT;
    }

    @ConfigItem(
            keyName = "eventDismissLampSkill",
            name = "Lamp skill",
            description = "Skill to use experience lamps on",
            position = 6,
            section = qolSection
    )
    default Skill eventDismissLampSkill() {
        return Skill.HERBLORE;
    }

    @ConfigItem(
            keyName = "eventDismissStrayLamps",
            name = "Use stray lamps",
            description = "Automatically use lamps found in the inventory from any source",
            position = 7,
            section = qolSection
    )
    default boolean eventDismissStrayLamps() {
        return false;
    }
}
