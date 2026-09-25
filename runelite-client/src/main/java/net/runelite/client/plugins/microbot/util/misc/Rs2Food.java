package net.runelite.client.plugins.microbot.util.misc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Edible items and their per-bite heal.
 * <p>
 * Format: {@code (id, heal, name, tickdelay)} — {@code heal} is the hitpoints restored per
 * BITE (a 2-bite pie heals {@code heal} twice), and {@code tickdelay} is the FIRST bite's eat
 * delay in ticks: {@code 1} marks the fast foods ({@link #getFastFoodIds()}), non-standard
 * values like {@code 2} are kept as reported so the table stays faithful, standard food is
 * {@code 3}.
 * <p>
 * Source: the wiki's {@code Food/All food} table (heal per bite) and {@code Food/Fast foods}
 * table (per-bite attack/eat delays). Foods whose heal varies by level or is random
 * (anglerfish, kebabs, brews, snail meat, ...) are deliberately NOT listed — the format has no
 * way to express a range. Item ids are the gameval {@code ItemID} values.
 * <p>
 * <b>Holiday, quest and premade items are excluded by policy.</b> They are not training food, and
 * listing them lets {@code Rs2InventorySetup.handleHealing()} (which scans the bank for these ids)
 * withdraw and eat a rare event item or a quest item the player is carrying on purpose. The
 * {@code osrs-wiki-item-tables} skill applies the same filter: {@code Premade } name prefix, the
 * wiki's {@code |quest} field, and holiday items (page prose / holiday-event category).
 */
@Getter
@RequiredArgsConstructor
public enum Rs2Food {
    // Format: (id, heal, name, tickdelay)
    Dark_Crab(11936, 22, "Dark Crab", 3),
    ROCKTAIL(15272, 23, "Rocktail", 3),
    MANTA(391, 22, "Manta Ray",3),
    SHARK(385, 20, "Shark",3),
    KARAMBWAN(3144, 18, "Cooked karambwan",3),
    LOBSTER(379, 12, "Lobster",3),
    TROUT(333, 7, "Trout",3 ),
    SALMON(329, 9, "Salmon", 3),
    SWORDFISH(373, 14, "Swordfish",3),
    TUNA(361, 10, "Tuna",3),
    MONKFISH(7946, 16, "Monkfish",3),
    SEA_TURTLE(397, 21, "Sea Turtle",3),
    CAKE(1891, 4, "Cake",2),
    BASS(365, 13, "Bass",3),
    COD(339, 7, "Cod",3),
    POTATO(1942, 1, "Potato",3),
    BAKED_POTATO(6701, 4, "Baked Potato",3),
    POTATO_WITH_CHEESE(6705, 16, "Potato with Cheese",3),
    EGG_POTATO(7056, 16, "Egg Potato",3),
    CHILLI_POTATO(7054, 14, "Chilli Potato",3),
    MUSHROOM_POTATO(7058, 20, "Mushroom Potato",3),
    TUNA_POTATO(7060, 22, "Tuna Potato",3),
    SHRIMPS(315, 3, "Shrimps",3),
    HERRING(347, 5, "Herring",3),
    SARDINE(325, 4, "Sardine",3),
    CHOCOLATE_CAKE(1897, 5, "Chocolate Cake",2),
    ANCHOVIES(319, 1, "Anchovies",3),
    PLAIN_PIZZA(2289, 7, "Plain Pizza",1),
    MEAT_PIZZA(2293, 8, "Meat Pizza",1),
    ANCHOVY_PIZZA(2297, 9, "Anchovy Pizza",1),
    PINEAPPLE_PIZZA(2301, 11, "Pineapple Pizza",1),
    BREAD(2309, 5, "Bread",3),
    APPLE_PIE(2323, 7, "Apple Pie",1),
    REDBERRY_PIE(2325, 5, "Redberry Pie",1),
    MEAT_PIE(2327, 6, "Meat Pie",1),
    PIKE(351, 8, "Pike",3),
    POTATO_WITH_BUTTER(6703, 14, "Potato with Butter",3),
    BANANA(1963, 2, "Banana",3),
    PEACH(6883, 8, "Peach",3),
    ORANGE(2108, 2, "Orange",3),
    PINEAPPLE_RINGS(2118, 2, "Pineapple Rings",3),
    PINEAPPLE_CHUNKS(2116, 2, "Pineapple Chunks",3),
    JUG_OF_WINE(1993, 11, "Jug of wine",3),
    COOKED_LARUPIA(29146, 11, "Cooked larupia",3),
    COOKED_BARBTAILED_KEBBIT(29131, 12, "Cooked barb-tailed kebbit",3),
    COOKED_GRAAHK(29149, 14, "Cooked graahk",3),
    COOKED_KYATT(29152, 17, "Cooked kyatt",3),
    COOKED_PYRE_FOX(29137, 19, "Cooked pyre fox",3),
    COOKED_SUNLIGHT_ANTELOPE(29140, 21, "Cooked sunlight antelope",3),
    COOKED_DASHING_KEBBIT(29134, 23, "Cooked dashing kebbit",3),
    COOKED_MOONLIGHT_ANTELOPE(29143, 26, "Cooked moonlight antelope",3),
    PURPLE_SWEETS(10476, 3, "Purple Sweets",3),
    CABBAGE(ItemID.CABBAGE, 1, "Cabbage",3),
    BLIGHTED_MANTA_RAY(24589, 22, "Blighted manta ray", 3),
    BLIGHTED_ANGLERFISH(24592, 22, "Blighted anglerfish", 3),
    BLIGHTED_KARAMBWAN(24595, 18, "Blighted karambwan", 3),

    // ── Remaining edible items (wiki Food/All food + Food/Fast foods) ──────────────
    ADMIRAL_PIE(7198, 8, "Admiral pie", 1),
    BAGUETTE(6961, 6, "Baguette", 3),
    BANANA_STEW(4016, 11, "Banana stew", 3),
    BAT_SHISH(10964, 2, "Bat shish", 3),
    BLUE_CRAB_MEAT(31695, 14, "Blue crab meat", 3),
    BLUEBERRY_MUFFIN(31128, 4, "Blueberry muffin", 3),
    BLUEFIN(32344, 22, "Bluefin", 3),
    BOTANICAL_PIE(19662, 7, "Botanical pie", 1),
    BRAWK_FISH_3(20862, 14, "Brawk fish (3)", 3),
    CAERULA_BERRIES(30937, 2, "Caerula berries", 3),
    CAVIAR(11326, 5, "Caviar", 3),
    CHEESE(1985, 2, "Cheese", 3),
    CHEESETOM_BATTA(2259, 11, "Cheese+tom batta", 3),
    CHILLI_CON_CARNE(7062, 5, "Chilli con carne", 3),
    CHOC_ICE(6794, 7, "Choc-ice", 3),
    CHOCCHIP_CRUNCHIES(2209, 7, "Chocchip crunchies", 3),
    CHOCOLATE_BAR(1973, 3, "Chocolate bar", 3),
    CHOCOLATE_BOMB(2185, 15, "Chocolate bomb", 3),
    CHOPPED_ONION(1871, 1, "Chopped onion", 3),
    CHOPPED_TOMATO(1869, 2, "Chopped tomato", 3),
    CHOPPED_TUNA(7086, 10, "Chopped tuna", 3),
    COATED_FROGS_LEGS(10963, 2, "Coated frogs' legs", 3),
    COOKED_CHICKEN(2140, 3, "Cooked chicken", 3),
    COOKED_CHOMPY(2878, 11, "Cooked chompy", 3),
    COOKED_MEAT(2142, 3, "Cooked meat", 3),
    COOKED_MYSTERY_MEAT(24785, 5, "Cooked mystery meat", 3),
    COOKED_RABBIT(3228, 5, "Cooked rabbit", 3),
    COOKED_T_BONE_STEAK(33109, 9, "Cooked t-bone steak", 3),
    CORRUPTED_PADDLEFISH(25958, 16, "Corrupted paddlefish", 3),
    CRYSTAL_PADDLEFISH(25960, 16, "Crystal paddlefish", 3),
    CURRY(2011, 19, "Curry", 3),
    DRAGONFRUIT(22929, 10, "Dragonfruit", 3),
    DRAGONFRUIT_PIE(22795, 10, "Dragonfruit pie", 1),
    EDIBLE_SEAWEED(403, 4, "Edible seaweed", 3),
    EEL_SUSHI(10971, 10, "Eel sushi", 3),
    EGG_AND_TOMATO(7064, 8, "Egg and tomato", 3),
    EQUA_LEAVES(2128, 1, "Equa leaves", 3),
    FIELD_RATION(7934, 10, "Field ration", 3),
    FILLETS(10969, 2, "Fillets", 3),
    FINGERS(10965, 2, "Fingers", 3),
    FISH_PIE(7188, 6, "Fish pie", 1),
    FRIED_MUSHROOMS(7082, 5, "Fried mushrooms", 3),
    FRIED_ONIONS(7084, 5, "Fried onions", 3),
    FROGBURGER(10962, 2, "Frogburger", 3),
    FROGSPAWN_GUMBO(10961, 2, "Frogspawn gumbo", 3),
    FRUIT_BATTA(2277, 11, "Fruit batta", 3),
    GARDEN_PIE(7178, 6, "Garden pie", 1),
    GIANT_FROG_LEGS(4517, 6, "Giant frog legs", 3),
    GIANT_KRILL(32312, 17, "Giant krill", 3),
    GIRAL_BAT_2(20875, 11, "Giral bat (2)", 3),
    GOUT_TUBER(6311, 12, "Gout tuber", 3),
    GREEN_GLOOP_SOUP(10960, 2, "Green gloop soup", 3),
    GRUBS_LA_MODE(10966, 2, "Grubs à la mode", 3),
    GUANIC_BAT_0(20871, 5, "Guanic bat (0)", 3),
    HADDOCK(32320, 18, "Haddock", 3),
    HALIBUT(32336, 20, "Halibut", 3),
    HONEY_LOCUST(27351, 20, "Honey locust", 3),
    JANGERBERRIES(247, 2, "Jangerberries", 3),
    JUMBO_SQUID(31564, 17, "Jumbo squid", 3),
    KING_WORM(2162, 2, "King worm", 3),
    KRYKET_BAT_4(20879, 17, "Kryket bat (4)", 3),
    KYREN_FISH_6(20868, 23, "Kyren fish (6)", 3),
    LECKISH_FISH_2(20860, 11, "Leckish fish (2)", 3),
    LEMON(2102, 2, "Lemon", 3),
    LEMON_CHUNKS(2104, 2, "Lemon chunks", 3),
    LEMON_SLICES(2106, 2, "Lemon slices", 3),
    LIME(2120, 2, "Lime", 3),
    LIME_CHUNKS(2122, 2, "Lime chunks", 3),
    LIME_SLICES(2124, 2, "Lime slices", 3),
    LOACH(10970, 3, "Loach", 3),
    LOCUST_MEAT(9052, 3, "Locust meat", 3),
    MACKEREL(355, 6, "Mackerel", 3),
    MARLIN(32352, 24, "Marlin", 3),
    MINCED_MEAT(7070, 13, "Minced meat", 3),
    MONKEY_BAR(4014, 5, "Monkey bar", 3),
    MURNG_BAT_5(20881, 20, "Murng bat (5)", 3),
    MUSHROOM_ONION(7066, 11, "Mushroom & onion", 3),
    MUSHROOM_PIE(21690, 8, "Mushroom pie", 1),
    MUSHROOMS(10968, 2, "Mushrooms", 3),
    MYCIL_FISH_4(20864, 17, "Mycil fish (4)", 3),
    ONION(1957, 1, "Onion", 3),
    ONION_TOMATO(1875, 3, "Onion & tomato", 3),
    ORANGE_HAT(31117, 2, "Orange (hat)", 3),
    ORANGE_CHUNKS(2110, 2, "Orange chunks", 3),
    ORANGE_SLICES(2112, 2, "Orange slices", 3),
    PADDLEFISH(23874, 20, "Paddlefish", 3),
    PAPAYA_FRUIT(5972, 8, "Papaya fruit", 3),
    PHLUXIA_BAT_3(20877, 14, "Phluxia bat (3)", 3),
    PINEAPPLE(2114, 2, "Pineapple", 3),
    POT_OF_CREAM(2130, 1, "Pot of cream", 3),
    PRAEL_BAT_1(20873, 8, "Prael bat (1)", 3),
    PSYKK_BAT_6(20883, 23, "Psykk bat (6)", 3),
    PYSK_FISH_0(20856, 5, "Pysk fish (0)", 3),
    RAINBOW_CRAB_MEAT(31703, 19, "Rainbow crab meat", 3),
    RAINBOW_FISH(10136, 11, "Rainbow fish", 3),
    RED_CRAB_MEAT(31689, 8, "Red crab meat", 3),
    ROAST_BEAST_MEAT(9988, 8, "Roast beast meat", 3),
    ROAST_BIRD_MEAT(9980, 6, "Roast bird meat", 3),
    ROAST_FROG(10967, 2, "Roast frog", 3),
    ROAST_RABBIT(7223, 7, "Roast rabbit", 3),
    ROE(11324, 3, "Roe", 3),
    ROLL(6963, 6, "Roll", 3),
    ROQED_FISH_5(20866, 20, "Roqed fish (5)", 3),
    SCRAMBLED_EGG(30970, 5, "Scrambled egg", 3),
    SLICED_BANANA(3162, 2, "Sliced banana", 3),
    SPICY_CRUNCHIES(2213, 7, "Spicy crunchies", 3),
    SPICY_MINCED_MEAT(9996, 3, "Spicy minced meat", 3),
    SPICY_SAUCE(7072, 2, "Spicy sauce", 3),
    SPICY_TOMATO(9994, 2, "Spicy tomato", 3),
    SPINACH_ROLL(1969, 2, "Spinach roll", 3),
    SQUARE_SANDWICH(6965, 6, "Square sandwich", 3),
    STEW(2003, 11, "Stew", 3),
    STYMPHIKE_TARTARE(33628, 26, "Stymphike tartare", 3),
    SUMMER_PIE(7218, 11, "Summer pie", 1),
    SUPHI_FISH_1(20858, 8, "Suphi fish (1)", 3),
    SWORDTIP_SQUID(31556, 15, "Swordtip squid", 3),
    TANGLED_TOADS_LEGS(2187, 15, "Tangled toad's legs", 3),
    TOAD_BATTA(2255, 11, "Toad batta", 3),
    TOAD_CRUNCHIES(2217, 8, "Toad crunchies", 3),
    TOADS_LEGS(2152, 3, "Toad's legs", 3),
    TOMATO(1982, 2, "Tomato", 3),
    TRIANGLE_SANDWICH(6962, 6, "Triangle sandwich", 3),
    TUNA_AND_CORN(7068, 13, "Tuna and corn", 3),
    UGTHANKI_KEBAB(1885, 19, "Ugthanki kebab", 3),
    UGTHANKI_MEAT(1861, 3, "Ugthanki meat", 3),
    VEG_BALL(2195, 12, "Veg ball", 3),
    VEGETABLE_BATTA(2281, 11, "Vegetable batta", 3),
    WILD_PIE(7208, 11, "Wild pie", 1),
    WORM_BATTA(2253, 11, "Worm batta", 3),
    WORM_CRUNCHIES(2205, 8, "Worm crunchies", 3),
    WORM_HOLE(2191, 12, "Worm hole", 3),
    YELLOWFIN(32328, 19, "Yellowfin", 3);

    private int id;
    private int heal;
    private String name;
    private final int tickdelay;

    Rs2Food(int id, int heal, String name, int tickdelay) {
        this.id = id;
        this.heal = heal;
        this.name = name;
        this.tickdelay = tickdelay;
    }

    @Override
    public String toString() {
        return name + " (+" + getHeal() + ")";
    }

    public int getId() {
        return id;
    }

    public int getHeal() {
        return heal;
    }

    public String getName() {
        return name;
    }

    // get all ids as a set
    public static Set<Integer> getIds() {
        return Arrays.stream(values()).map(Rs2Food::getId).collect(Collectors.toSet());
    }

	public static Set<Integer> getFastFoodIds() {
		return Arrays.stream(values()).filter(f -> f.getTickdelay() == 1).map(Rs2Food::getId).collect(Collectors.toSet());
	}

}
