package net.runelite.client.plugins.microbot.ascript.mining;

import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Known mines per ore type, ported from the hub's {@code MiningRockLocations}.
 * <p>
 * Transformations from the hub (plan §Task 2): the never-used varbit/varplayer maps are
 * gone (slimmed {@link MiningLocation}); the dead {@code ResourceLocationOption} subsystem
 * and its {@code getResourceLocationsForRock} family are dropped; the populated
 * requirements are kept verbatim; every entry was checked against the OSRS wiki
 * ({@code /w/Mines} + per-mine infoboxes) — corrections applied: the Al Kharid gem-rock
 * entry removed (Al Kharid mine has no gem rocks), the Mining Guild and Bandit Camp Mine
 * marked free-to-play (both are F2P; the Mining Guild keeps its 60 Mining gate), the
 * Heroes' Guild mine moved to its actual basement tile, and the Lava Maze / Coal Trucks /
 * Ardougne South East / Crafting Guild / Weiss basalt tiles aligned with the wiki map pins.
 * <p>
 * Salts have no entries — Progressive mode plus a salt ore stops loudly through the
 * null-location path.
 */
public final class MiningLocations {

    private static final Map<MiningOre, List<MiningLocation>> LOCATIONS = new HashMap<>();

    static {
        LOCATIONS.put(MiningOre.TIN, List.of(
                new MiningLocation(new WorldPoint(3149, 3148, 0), "Lumbridge Swamp West Mine", false),
                new MiningLocation(new WorldPoint(3181, 3377, 0), "Varrock South West Mine", false),
                new MiningLocation(new WorldPoint(3296, 3315, 0), "Al Kharid Mine", false),
                new MiningLocation(new WorldPoint(3034, 9822, 0), "Dwarven Mine", false)));

        LOCATIONS.put(MiningOre.COPPER, List.of(
                new MiningLocation(new WorldPoint(3296, 3315, 0), "Al Kharid Mine", false),
                new MiningLocation(new WorldPoint(3149, 3148, 0), "Lumbridge Swamp West Mine", false),
                new MiningLocation(new WorldPoint(3181, 3377, 0), "Varrock South West Mine", false),
                new MiningLocation(new WorldPoint(3034, 9822, 0), "Dwarven Mine", false)));

        LOCATIONS.put(MiningOre.CLAY, List.of(
                new MiningLocation(new WorldPoint(3181, 3377, 0), "Varrock South West Mine", false),
                new MiningLocation(new WorldPoint(3034, 9822, 0), "Dwarven Mine", false)));

        LOCATIONS.put(MiningOre.IRON, List.of(
                new MiningLocation(new WorldPoint(3046, 9756, 0), "Mining Guild", false,
                        new HashMap<>(), miningGuildSkills(), new HashMap<>()),
                new MiningLocation(new WorldPoint(3296, 3315, 0), "Al Kharid Mine", false),
                new MiningLocation(new WorldPoint(3285, 3363, 0), "Varrock South East Mine", false),
                new MiningLocation(new WorldPoint(3034, 9822, 0), "Dwarven Mine", false),
                new MiningLocation(new WorldPoint(2600, 3232, 0), "Ardougne South East Mine", true),
                new MiningLocation(new WorldPoint(3087, 3762, 0), "Bandit Camp Mine", false)));

        LOCATIONS.put(MiningOre.SILVER, List.of(
                new MiningLocation(new WorldPoint(3285, 3363, 0), "Varrock South East Mine", false),
                new MiningLocation(new WorldPoint(3034, 9822, 0), "Dwarven Mine", false),
                new MiningLocation(new WorldPoint(3296, 3315, 0), "Al Kharid Mine", false)));

        LOCATIONS.put(MiningOre.COAL, List.of(
                new MiningLocation(new WorldPoint(3046, 9756, 0), "Mining Guild", false,
                        new HashMap<>(), miningGuildSkills(), new HashMap<>()),
                new MiningLocation(new WorldPoint(3034, 9822, 0), "Dwarven Mine", false),
                new MiningLocation(new WorldPoint(3081, 3421, 0), "Barbarian Village Mine", false),
                new MiningLocation(new WorldPoint(2598, 3490, 0), "Coal Trucks", true)));

        LOCATIONS.put(MiningOre.GOLD, List.of(
                new MiningLocation(new WorldPoint(3034, 9822, 0), "Dwarven Mine", false),
                new MiningLocation(new WorldPoint(3296, 3315, 0), "Al Kharid Mine", false),
                new MiningLocation(new WorldPoint(2932, 3283, 0), "Crafting Guild", false,
                        new HashMap<>(), craftingGuildSkills(), craftingGuildItems())));

        LOCATIONS.put(MiningOre.GEM, List.of(
                new MiningLocation(new WorldPoint(2824, 2997, 0), "Shilo Village Gem Mine", true,
                        shiloQuests(), new HashMap<>(), new HashMap<>())));

        LOCATIONS.put(MiningOre.MITHRIL, List.of(
                new MiningLocation(new WorldPoint(3046, 9756, 0), "Mining Guild", false,
                        new HashMap<>(), miningGuildSkills(), new HashMap<>()),
                new MiningLocation(new WorldPoint(3296, 3315, 0), "Al Kharid Mine", false),
                new MiningLocation(new WorldPoint(3034, 9822, 0), "Dwarven Mine", false),
                new MiningLocation(new WorldPoint(3229, 3148, 0), "Lumbridge Swamp East Mine", false)));

        LOCATIONS.put(MiningOre.ADAMANTITE, List.of(
                new MiningLocation(new WorldPoint(3046, 9756, 0), "Mining Guild", false,
                        new HashMap<>(), miningGuildSkills(), new HashMap<>()),
                new MiningLocation(new WorldPoint(3296, 3315, 0), "Al Kharid Mine", false),
                new MiningLocation(new WorldPoint(3229, 3148, 0), "Lumbridge Swamp East Mine", false),
                new MiningLocation(new WorldPoint(2335, 3808, 0), "Neitiznot Mine", true,
                        fremennikIslesQuests(), new HashMap<>(), new HashMap<>())));

        LOCATIONS.put(MiningOre.RUNITE, List.of(
                new MiningLocation(new WorldPoint(3046, 9756, 0), "Mining Guild", false,
                        new HashMap<>(), miningGuildSkills(), new HashMap<>()),
                new MiningLocation(new WorldPoint(2917, 9907, 0), "Heroes' Guild Mine", true,
                        heroesQuests(), new HashMap<>(), new HashMap<>()),
                new MiningLocation(new WorldPoint(2335, 3808, 0), "Neitiznot Mine", true,
                        fremennikIslesQuests(), new HashMap<>(), new HashMap<>()),
                new MiningLocation(new WorldPoint(3060, 3884, 0), "Lava Maze Runite Mine (Wilderness)", false)));

        LOCATIONS.put(MiningOre.BASALT, List.of(
                new MiningLocation(new WorldPoint(2844, 10340, 0), "Salt Mine (Weiss)", true,
                        makingFriendsQuests(), new HashMap<>(), new HashMap<>())));
    }

    private MiningLocations() {
        // static data holder
    }

    private static Map<Skill, Integer> miningGuildSkills() {
        Map<Skill, Integer> skills = new HashMap<>();
        skills.put(Skill.MINING, 60);
        return skills;
    }

    private static Map<Skill, Integer> craftingGuildSkills() {
        Map<Skill, Integer> skills = new HashMap<>();
        skills.put(Skill.CRAFTING, 40);
        return skills;
    }

    private static Map<Integer, Integer> craftingGuildItems() {
        Map<Integer, Integer> items = new HashMap<>();
        items.put(ItemID.BROWN_APRON, 1);
        return items;
    }

    private static Map<Quest, QuestState> shiloQuests() {
        Map<Quest, QuestState> quests = new HashMap<>();
        quests.put(Quest.SHILO_VILLAGE, QuestState.FINISHED);
        return quests;
    }

    private static Map<Quest, QuestState> heroesQuests() {
        Map<Quest, QuestState> quests = new HashMap<>();
        quests.put(Quest.HEROES_QUEST, QuestState.FINISHED);
        return quests;
    }

    private static Map<Quest, QuestState> fremennikIslesQuests() {
        Map<Quest, QuestState> quests = new HashMap<>();
        quests.put(Quest.THE_FREMENNIK_ISLES, QuestState.FINISHED);
        return quests;
    }

    private static Map<Quest, QuestState> makingFriendsQuests() {
        Map<Quest, QuestState> quests = new HashMap<>();
        quests.put(Quest.MAKING_FRIENDS_WITH_MY_ARM, QuestState.FINISHED);
        return quests;
    }

    /**
     * Best accessible location for a rock type based on player position.
     * Returns {@code null} if no accessible locations are found.
     */
    public static MiningLocation getBestAccessibleLocation(MiningOre ore) {
        List<MiningLocation> accessible = LOCATIONS.getOrDefault(ore, List.of()).stream()
                .filter(MiningLocation::hasRequirements)
                .collect(Collectors.toList());
        if (accessible.isEmpty()) {
            return null;
        }
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) {
            return accessible.get(0);
        }
        return accessible.stream()
                .min(Comparator.comparingInt(loc -> player.distanceTo(loc.getWorldPoint())))
                .orElse(accessible.get(0));
    }
}
