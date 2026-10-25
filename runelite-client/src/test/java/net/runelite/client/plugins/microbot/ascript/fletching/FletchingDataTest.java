package net.runelite.client.plugins.microbot.ascript.fletching;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.skillcalculator.skills.FletchingAction;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the AutoFletching id tables to the two external sources of truth:
 * <ul>
 *   <li>{@code skillcalculator/skills/FletchingAction.java} for product ids, levels and xp;</li>
 *   <li>the OSRS wiki for the entries RuneLite does not enumerate (blurite bolts, jade/topaz
 *       gem-tipped bolts, dragon gem-tipped bolts, and the per-log arrow-shaft tiers).</li>
 * </ul>
 * The two known RuneLite data quirks are explicit here:
 * <ul>
 *   <li>{@code BROAD_ARROWS} points at the Slayer-guide dummy id 4150, not the real broad arrow
 *       4160;</li>
 *   <li>{@code DRAGON_BOLTS} points at the unfeathered input 21930, not the finished product
 *       21905.</li>
 * </ul>
 * A silent change to any of these tables does not fail a build — it makes a withdraw or a
 * production click miss — so the tables are asserted instead of trusted.
 */
public class FletchingDataTest {

    private static FletchingAction actionFor(int itemId) {
        for (FletchingAction action : FletchingAction.values()) {
            if (action.getItemId() == itemId) {
                return action;
            }
        }
        return null;
    }

    // ── darts ────────────────────────────────────────────────

    @Test
    public void dartMaterialsAndLevelsArePinned() {
        assertDart(FletchingDartType.BRONZE, ItemID.BRONZE_DART_TIP, ItemID.BRONZE_DART, 10);
        assertDart(FletchingDartType.IRON, ItemID.IRON_DART_TIP, ItemID.IRON_DART, 22);
        assertDart(FletchingDartType.STEEL, ItemID.STEEL_DART_TIP, ItemID.STEEL_DART, 37);
        assertDart(FletchingDartType.MITHRIL, ItemID.MITHRIL_DART_TIP, ItemID.MITHRIL_DART, 52);
        assertDart(FletchingDartType.ADAMANT, ItemID.ADAMANT_DART_TIP, ItemID.ADAMANT_DART, 67);
        assertDart(FletchingDartType.RUNE, ItemID.RUNE_DART_TIP, ItemID.RUNE_DART, 81);
        assertDart(FletchingDartType.DRAGON, ItemID.DRAGON_DART_TIP, ItemID.DRAGON_DART, 95);
        assertDart(FletchingDartType.AMETHYST, ItemID.AMETHYST_DART_TIP, ItemID.AMETHYST_DART, 90);
    }

    private static void assertDart(FletchingDartType dart, int materialId, int productId, int level) {
        assertEquals(dart + " material", materialId, dart.getMaterialId());
        assertEquals(dart + " product", productId, dart.getProductId());
        assertEquals(dart + " level", level, dart.getLevelRequirement());
        FletchingAction action = actionFor(productId);
        assertNotNull(dart + " has no FletchingAction entry", action);
        assertEquals(dart + " FletchingAction level", action.getLevel(), dart.getLevelRequirement());
    }

    // ── bolts ────────────────────────────────────────────────

    @Test
    public void boltMaterialsAndLevelsArePinned() {
        assertBolt(FletchingBoltType.BRONZE,
                ItemID.XBOWS_CROSSBOW_BOLTS_BRONZE_UNFEATHERED, ItemID.BOLT, 9);
        assertBolt(FletchingBoltType.BLURITE,
                ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE, 24);
        assertBolt(FletchingBoltType.SILVER,
                ItemID.XBOWS_CROSSBOW_BOLTS_SILVER_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_SILVER, 43);
        assertBolt(FletchingBoltType.IRON,
                ItemID.XBOWS_CROSSBOW_BOLTS_IRON_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_IRON, 39);
        assertBolt(FletchingBoltType.STEEL,
                ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_STEEL, 46);
        assertBolt(FletchingBoltType.MITHRIL,
                ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL, 54);
        assertBolt(FletchingBoltType.BROAD,
                ItemID.SLAYER_BROAD_BOLT_UNFINISHED, ItemID.SLAYER_BROAD_BOLT, 55);
        assertBolt(FletchingBoltType.ADAMANT,
                ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_UNFEATHERED,
                ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE, 61);
        assertBolt(FletchingBoltType.RUNITE,
                ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_UNFEATHERED, ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE, 69);
        assertBolt(FletchingBoltType.DRAGON,
                ItemID.DRAGON_BOLTS_UNFEATHERED, ItemID.DRAGON_BOLTS, 84);
    }

    private static void assertBolt(FletchingBoltType bolt, int unfinishedId, int finishedId, int level) {
        assertEquals(bolt + " unfinished", unfinishedId, bolt.getUnfinishedId());
        assertEquals(bolt + " product", finishedId, bolt.getFinishedId());
        assertEquals(bolt + " level", level, bolt.getLevelRequirement());

        if (bolt == FletchingBoltType.BLURITE) {
            // RuneLite has no BLURITE_BOLTS entry; the wiki level is 24.
            return;
        }
        int actionId = bolt == FletchingBoltType.DRAGON ? ItemID.DRAGON_BOLTS_UNFEATHERED : finishedId;
        FletchingAction action = actionFor(actionId);
        assertNotNull(bolt + " has no FletchingAction entry", action);
        assertEquals(bolt + " FletchingAction level", action.getLevel(), bolt.getLevelRequirement());
    }

    // ── arrows ───────────────────────────────────────────────

    @Test
    public void arrowMaterialsAndLevelsArePinned() {
        assertArrow(FletchingArrowType.BRONZE, ItemID.BRONZE_ARROWHEADS, ItemID.BRONZE_ARROW, 1);
        assertArrow(FletchingArrowType.IRON, ItemID.IRON_ARROWHEADS, ItemID.IRON_ARROW, 15);
        assertArrow(FletchingArrowType.STEEL, ItemID.STEEL_ARROWHEADS, ItemID.STEEL_ARROW, 30);
        assertArrow(FletchingArrowType.MITHRIL, ItemID.MITHRIL_ARROWHEADS, ItemID.MITHRIL_ARROW, 45);
        assertArrow(FletchingArrowType.BROAD, ItemID.SLAYER_BROAD_ARROWHEAD, ItemID.SLAYER_BROAD_ARROWS, 52);
        assertArrow(FletchingArrowType.ADAMANT, ItemID.ADAMANT_ARROWHEADS, ItemID.ADAMANT_ARROW, 60);
        assertArrow(FletchingArrowType.RUNE, ItemID.RUNE_ARROWHEADS, ItemID.RUNE_ARROW, 75);
        assertArrow(FletchingArrowType.AMETHYST, ItemID.AMETHYST_ARROWHEADS, ItemID.AMETHYST_ARROW, 82);
        assertArrow(FletchingArrowType.DRAGON, ItemID.DRAGON_ARROWHEADS, ItemID.DRAGON_ARROW, 90);
        assertEquals(ItemID.HEADLESS_ARROW, FletchingArrowType.HEADLESS_ARROW_ID);
    }

    private static void assertArrow(FletchingArrowType arrow, int tipId, int productId, int level) {
        assertEquals(arrow + " tip", tipId, arrow.getTipId());
        assertEquals(arrow + " product", productId, arrow.getFinishedId());
        assertEquals(arrow + " level", level, arrow.getLevelRequirement());

        // FletchingAction's BROAD_ARROWS entry points at the Slayer-guide dummy 4150.
        int actionId = arrow == FletchingArrowType.BROAD
                ? ItemID.SLAYERGUIDE_BROAD_ARROWS : productId;
        FletchingAction action = actionFor(actionId);
        assertNotNull(arrow + " has no FletchingAction entry", action);
        assertEquals(arrow + " FletchingAction level", action.getLevel(), arrow.getLevelRequirement());
    }

    // ── bows ─────────────────────────────────────────────────

    @Test
    public void bowIdsLevelsAndLogsArePinned() {
        assertBow(FletchingBowType.SHORTBOW, ItemID.UNSTRUNG_SHORTBOW, ItemID.LOGS, 5);
        assertBow(FletchingBowType.LONGBOW, ItemID.UNSTRUNG_LONGBOW, ItemID.LOGS, 10);
        assertBow(FletchingBowType.OAK_SHORTBOW, ItemID.UNSTRUNG_OAK_SHORTBOW, ItemID.OAK_LOGS, 20);
        assertBow(FletchingBowType.OAK_LONGBOW, ItemID.UNSTRUNG_OAK_LONGBOW, ItemID.OAK_LOGS, 25);
        assertBow(FletchingBowType.WILLOW_SHORTBOW, ItemID.UNSTRUNG_WILLOW_SHORTBOW, ItemID.WILLOW_LOGS, 35);
        assertBow(FletchingBowType.WILLOW_LONGBOW, ItemID.UNSTRUNG_WILLOW_LONGBOW, ItemID.WILLOW_LOGS, 40);
        assertBow(FletchingBowType.MAPLE_SHORTBOW, ItemID.UNSTRUNG_MAPLE_SHORTBOW, ItemID.MAPLE_LOGS, 50);
        assertBow(FletchingBowType.MAPLE_LONGBOW, ItemID.UNSTRUNG_MAPLE_LONGBOW, ItemID.MAPLE_LOGS, 55);
        assertBow(FletchingBowType.YEW_SHORTBOW, ItemID.UNSTRUNG_YEW_SHORTBOW, ItemID.YEW_LOGS, 65);
        assertBow(FletchingBowType.YEW_LONGBOW, ItemID.UNSTRUNG_YEW_LONGBOW, ItemID.YEW_LOGS, 70);
        assertBow(FletchingBowType.MAGIC_SHORTBOW, ItemID.UNSTRUNG_MAGIC_SHORTBOW, ItemID.MAGIC_LOGS, 80);
        assertBow(FletchingBowType.MAGIC_LONGBOW, ItemID.UNSTRUNG_MAGIC_LONGBOW, ItemID.MAGIC_LOGS, 85);
        assertFalse("NONE must not be a configured product", FletchingBowType.NONE.isConfigured());
    }

    private static void assertBow(FletchingBowType bow, int unstrungId, int logId, int level) {
        assertEquals(bow + " unstrung", unstrungId, bow.getUnstrungId());
        assertEquals(bow + " log", logId, bow.getLogId());
        assertEquals(bow + " level", level, bow.getLevelRequirement());
        FletchingAction action = actionFor(unstrungId);
        assertNotNull(bow + " has no FletchingAction *_U entry", action);
        assertEquals(bow + " FletchingAction level", action.getLevel(), bow.getLevelRequirement());
    }

    // ── stocks and shields ───────────────────────────────────

    @Test
    public void stockIdsAndLevelsArePinned() {
        assertStock(FletchingStockType.WOODEN, ItemID.LOGS, ItemID.XBOWS_CROSSBOW_STOCK_WOOD, 9);
        assertStock(FletchingStockType.OAK, ItemID.OAK_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_OAK, 24);
        assertStock(FletchingStockType.WILLOW, ItemID.WILLOW_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_WILLOW, 39);
        assertStock(FletchingStockType.TEAK, ItemID.TEAK_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_TEAK, 46);
        assertStock(FletchingStockType.MAPLE, ItemID.MAPLE_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_MAPLE, 54);
        assertStock(FletchingStockType.MAHOGANY, ItemID.MAHOGANY_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_MAHOGANY, 61);
        assertStock(FletchingStockType.YEW, ItemID.YEW_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_YEW, 69);
        assertStock(FletchingStockType.MAGIC, ItemID.MAGIC_LOGS, ItemID.XBOWS_CROSSBOW_STOCK_MAGIC, 78);
    }

    private static void assertStock(FletchingStockType stock, int logId, int productId, int level) {
        assertEquals(stock + " log", logId, stock.getLogId());
        assertEquals(stock + " product", productId, stock.getProductId());
        assertEquals(stock + " level", level, stock.getLevelRequirement());
        assertEquals(1, stock.logsPerAction());
        FletchingAction action = actionFor(productId);
        assertNotNull(stock + " has no FletchingAction entry", action);
        assertEquals(stock + " FletchingAction level", action.getLevel(), stock.getLevelRequirement());
    }

    @Test
    public void shieldIdsLevelsAndTwoLogCostArePinned() {
        assertShield(FletchingShieldType.OAK, ItemID.OAK_LOGS, ItemID.OAK_SHIELD, 27);
        assertShield(FletchingShieldType.WILLOW, ItemID.WILLOW_LOGS, ItemID.WILLOW_SHIELD, 42);
        assertShield(FletchingShieldType.MAPLE, ItemID.MAPLE_LOGS, ItemID.MAPLE_SHIELD, 57);
        assertShield(FletchingShieldType.YEW, ItemID.YEW_LOGS, ItemID.YEW_SHIELD, 72);
        assertShield(FletchingShieldType.MAGIC, ItemID.MAGIC_LOGS, ItemID.MAGIC_SHIELD, 87);
        assertShield(FletchingShieldType.REDWOOD, ItemID.REDWOOD_LOGS, ItemID.REDWOOD_SHIELD, 92);
    }

    private static void assertShield(FletchingShieldType shield, int logId, int productId, int level) {
        assertEquals(shield + " log", logId, shield.getLogId());
        assertEquals(shield + " product", productId, shield.getProductId());
        assertEquals(shield + " level", level, shield.getLevelRequirement());
        assertEquals(shield + " consumes two logs", 2, shield.logsPerAction());
        FletchingAction action = actionFor(productId);
        assertNotNull(shield + " has no FletchingAction entry", action);
        assertEquals(shield + " FletchingAction level", action.getLevel(), shield.getLevelRequirement());
    }

    // ── arrow shafts ─────────────────────────────────────────

    @Test
    public void shaftTierTableMatchesTheWiki() {
        assertShaft(FletchingShaftType.LOGS, ItemID.LOGS, 1, 15, 5);
        assertShaft(FletchingShaftType.OAK, ItemID.OAK_LOGS, 15, 30, 10);
        assertShaft(FletchingShaftType.WILLOW, ItemID.WILLOW_LOGS, 30, 45, 15);
        assertShaft(FletchingShaftType.MAPLE, ItemID.MAPLE_LOGS, 45, 60, 20);
        assertShaft(FletchingShaftType.YEW, ItemID.YEW_LOGS, 60, 75, 25);
        assertShaft(FletchingShaftType.MAGIC, ItemID.MAGIC_LOGS, 75, 90, 30);
        assertShaft(FletchingShaftType.REDWOOD, ItemID.REDWOOD_LOGS, 90, 105, 35);
    }

    private static void assertShaft(FletchingShaftType shaft, int logId, int level,
                                    int shaftsPerLog, int xpPerAction) {
        assertEquals(shaft + " log", logId, shaft.getLogId());
        assertEquals(shaft + " product", ItemID.ARROW_SHAFT, shaft.productId());
        assertEquals(shaft + " level", level, shaft.getLevelRequirement());
        assertEquals(shaft + " shafts/log", shaftsPerLog, shaft.getShaftsPerLog());
        assertEquals(shaft + " xp", xpPerAction, shaft.getXpPerAction());
        assertEquals(1, shaft.logsPerAction());
        assertEquals(shaftsPerLog, shaft.productsPerAction());
    }

    // ── gem-tipped bolts ─────────────────────────────────────

    @Test
    public void gemTippedBoltTableMatchesSources() {
        assertEquals(10, FletchingBoltTipType.MATERIALS_PER_ACTION);

        assertBoltTip(FletchingBoltTipType.OPAL, ItemID.BOLT, ItemID.OPAL_BOLTTIPS,
                ItemID.OPAL_BOLT, 11, 1.6f, true);
        assertBoltTip(FletchingBoltTipType.JADE, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE,
                ItemID.XBOWS_BOLT_TIPS_JADE, ItemID.XBOWS_CROSSBOW_BOLTS_BLURITE_TIPPED_JADE,
                26, 2.4f, false);
        assertBoltTip(FletchingBoltTipType.PEARL, ItemID.XBOWS_CROSSBOW_BOLTS_IRON,
                ItemID.PEARL_BOLTTIPS, ItemID.PEARL_BOLT, 41, 3.2f, true);
        assertBoltTip(FletchingBoltTipType.TOPAZ, ItemID.XBOWS_CROSSBOW_BOLTS_STEEL,
                ItemID.XBOWS_BOLT_TIPS_REDTOPAZ,
                ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_TIPPED_REDTOPAZ, 48, 3.9f, false);
        assertBoltTip(FletchingBoltTipType.SAPPHIRE, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL,
                ItemID.XBOWS_BOLT_TIPS_SAPPHIRE,
                ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_TIPPED_SAPPHIRE, 56, 4.7f, true);
        assertBoltTip(FletchingBoltTipType.EMERALD, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL,
                ItemID.XBOWS_BOLT_TIPS_EMERALD,
                ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_TIPPED_EMERALD, 58, 5.5f, true);
        assertBoltTip(FletchingBoltTipType.RUBY, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE,
                ItemID.XBOWS_BOLT_TIPS_RUBY,
                ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_TIPPED_RUBY, 63, 6.3f, true);
        assertBoltTip(FletchingBoltTipType.DIAMOND, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE,
                ItemID.XBOWS_BOLT_TIPS_DIAMOND,
                ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_TIPPED_DIAMOND, 65, 7.0f, true);
        assertBoltTip(FletchingBoltTipType.DRAGONSTONE, ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE,
                ItemID.XBOWS_BOLT_TIPS_DRAGONSTONE,
                ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_TIPPED_DRAGONSTONE, 71, 8.2f, true);
        assertBoltTip(FletchingBoltTipType.ONYX, ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE,
                ItemID.XBOWS_BOLT_TIPS_ONYX,
                ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_TIPPED_ONYX, 73, 9.4f, true);
        assertBoltTip(FletchingBoltTipType.AMETHYST_BROAD, ItemID.SLAYER_BROAD_BOLT,
                ItemID.XBOWS_BOLT_TIPS_AMETHYST, ItemID.SLAYER_BROAD_BOLT_AMETHYST,
                76, 10.6f, true);

        assertDragonBoltTip(FletchingBoltTipType.DRAGON_OPAL, ItemID.OPAL_BOLTTIPS,
                ItemID.DRAGON_BOLTS_UNENCHANTED_OPAL, 1.6f);
        assertDragonBoltTip(FletchingBoltTipType.DRAGON_JADE, ItemID.XBOWS_BOLT_TIPS_JADE,
                ItemID.DRAGON_BOLTS_UNENCHANTED_JADE, 2.4f);
        assertDragonBoltTip(FletchingBoltTipType.DRAGON_PEARL, ItemID.PEARL_BOLTTIPS,
                ItemID.DRAGON_BOLTS_UNENCHANTED_PEARL, 3.2f);
        assertDragonBoltTip(FletchingBoltTipType.DRAGON_TOPAZ, ItemID.XBOWS_BOLT_TIPS_REDTOPAZ,
                ItemID.DRAGON_BOLTS_UNENCHANTED_TOPAZ, 3.9f);
        assertDragonBoltTip(FletchingBoltTipType.DRAGON_SAPPHIRE, ItemID.XBOWS_BOLT_TIPS_SAPPHIRE,
                ItemID.DRAGON_BOLTS_UNENCHANTED_SAPPHIRE, 4.7f);
        assertDragonBoltTip(FletchingBoltTipType.DRAGON_EMERALD, ItemID.XBOWS_BOLT_TIPS_EMERALD,
                ItemID.DRAGON_BOLTS_UNENCHANTED_EMERALD, 5.5f);
        assertDragonBoltTip(FletchingBoltTipType.DRAGON_RUBY, ItemID.XBOWS_BOLT_TIPS_RUBY,
                ItemID.DRAGON_BOLTS_UNENCHANTED_RUBY, 6.3f);
        assertDragonBoltTip(FletchingBoltTipType.DRAGON_DIAMOND, ItemID.XBOWS_BOLT_TIPS_DIAMOND,
                ItemID.DRAGON_BOLTS_UNENCHANTED_DIAMOND, 7.0f);
        assertDragonBoltTip(FletchingBoltTipType.DRAGON_DRAGONSTONE, ItemID.XBOWS_BOLT_TIPS_DRAGONSTONE,
                ItemID.DRAGON_BOLTS_UNENCHANTED_DRAGONSTONE, 8.2f);
        assertDragonBoltTip(FletchingBoltTipType.DRAGON_ONYX, ItemID.XBOWS_BOLT_TIPS_ONYX,
                ItemID.DRAGON_BOLTS_UNENCHANTED_ONYX, 9.4f);
    }

    private static void assertBoltTip(FletchingBoltTipType tip, int boltId, int tipId,
                                       int productId, int level, float xp, boolean actionExists) {
        assertEquals(tip + " bolt", boltId, tip.getBoltId());
        assertEquals(tip + " tip", tipId, tip.getTipId());
        assertEquals(tip + " product", productId, tip.getProductId());
        assertEquals(tip + " level", level, tip.getLevelRequirement());
        assertEquals(tip + " xp", xp, tip.getXpPerBolt(), 0.001f);

        FletchingAction action = actionFor(productId);
        if (actionExists) {
            assertNotNull(tip + " has no FletchingAction entry", action);
            assertEquals(tip + " FletchingAction level", action.getLevel(), level);
            assertEquals(tip + " FletchingAction xp", action.getXp(), xp, 0.001f);
        }
    }

    private static void assertDragonBoltTip(FletchingBoltTipType tip, int tipId,
                                             int productId, float xp) {
        assertEquals(ItemID.DRAGON_BOLTS, tip.getBoltId());
        assertEquals(tip + " tip", tipId, tip.getTipId());
        assertEquals(tip + " product", productId, tip.getProductId());
        assertEquals(84, tip.getLevelRequirement());
        assertEquals(tip + " xp", xp, tip.getXpPerBolt(), 0.001f);
    }

    @Test
    public void dragonBoltsNeverAcceptAmethyst() {
        for (FletchingBoltTipType tip : FletchingBoltTipType.values()) {
            boolean dragonAmethyst = tip.getBoltId() == ItemID.DRAGON_BOLTS
                    && tip.getTipId() == ItemID.XBOWS_BOLT_TIPS_AMETHYST;
            assertFalse("dragon bolts do not accept amethyst tips: " + tip, dragonAmethyst);
        }
    }

    // ── poison / enchantment guard ───────────────────────────

    @Test
    public void noPoisonedOrEnchantedAmmunitionIsReferenced() {
        Set<Integer> forbidden = new HashSet<>();
        forbidden.addAll(itemIdsMatching(".*(_P|_P_|_P__|_POISONED(_\\d+)?)$"));
        forbidden.addAll(itemIdsMatching(".*_ENCHANTED(_\\d+)?$"));
        assertFalse("the guard must actually find poison/enchanted ids", forbidden.isEmpty());

        Set<Integer> referenced = referencedIds();
        Set<Integer> overlap = new HashSet<>(referenced);
        overlap.retainAll(forbidden);
        assertTrue("poisoned/enchanted ids referenced by fletching tables: " + overlap, overlap.isEmpty());
    }

    private static Set<Integer> itemIdsMatching(String regex) {
        Pattern pattern = Pattern.compile(regex);
        Set<Integer> ids = new HashSet<>();
        for (Field field : ItemID.class.getFields()) {
            if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!pattern.matcher(field.getName()).matches()) {
                continue;
            }
            try {
                ids.add(field.getInt(null));
            } catch (IllegalAccessException ignored) {
                // public static final int fields are accessible; ignore defensive failure
            }
        }
        return ids;
    }

    private static Set<Integer> referencedIds() {
        Set<Integer> ids = new HashSet<>();
        for (FletchingDartType dart : FletchingDartType.values()) {
            ids.add(dart.getMaterialId());
            ids.add(dart.getProductId());
        }
        for (FletchingBoltType bolt : FletchingBoltType.values()) {
            ids.add(bolt.getUnfinishedId());
            ids.add(bolt.getFinishedId());
        }
        for (FletchingArrowType arrow : FletchingArrowType.values()) {
            ids.add(arrow.getTipId());
            ids.add(arrow.getFinishedId());
        }
        ids.add(FletchingArrowType.HEADLESS_ARROW_ID);
        for (FletchingBoltTipType tip : FletchingBoltTipType.values()) {
            ids.add(tip.getBoltId());
            ids.add(tip.getTipId());
            ids.add(tip.getProductId());
        }
        for (FletchingBowType bow : FletchingBowType.values()) {
            ids.add(bow.getUnstrungId());
            ids.add(bow.getLogId());
        }
        for (FletchingShaftType shaft : FletchingShaftType.values()) {
            ids.add(shaft.getLogId());
            ids.add(shaft.productId());
        }
        for (FletchingStockType stock : FletchingStockType.values()) {
            ids.add(stock.getLogId());
            ids.add(stock.getProductId());
        }
        for (FletchingShieldType shield : FletchingShieldType.values()) {
            ids.add(shield.getLogId());
            ids.add(shield.getProductId());
        }
        ids.remove(0);
        return ids;
    }

    // ── duplicate / label sanity ─────────────────────────────

    @Test
    public void productIdsAreUniqueWithinEachCatalogue() {
        assertUniqueProducts("darts", FletchingDartType.values().length,
                idsOf(FletchingDartType.values(), FletchingDartType::getProductId));
        assertUniqueProducts("bolts", FletchingBoltType.values().length,
                idsOf(FletchingBoltType.values(), FletchingBoltType::getFinishedId));
        assertUniqueProducts("arrows", FletchingArrowType.values().length,
                idsOf(FletchingArrowType.values(), FletchingArrowType::getFinishedId));
        assertUniqueProducts("bolt tips", FletchingBoltTipType.values().length,
                idsOf(FletchingBoltTipType.values(), FletchingBoltTipType::getProductId));
        assertUniqueProducts("bows", FletchingBowType.values().length - 1,
                idsOf(FletchingBowType.values(), FletchingBowType::getUnstrungId));
        assertUniqueProducts("stocks", FletchingStockType.values().length,
                idsOf(FletchingStockType.values(), FletchingStockType::getProductId));
        assertUniqueProducts("shields", FletchingShieldType.values().length,
                idsOf(FletchingShieldType.values(), FletchingShieldType::getProductId));
    }

    private static <T> Set<Integer> idsOf(T[] values, java.util.function.ToIntFunction<T> id) {
        Set<Integer> ids = new HashSet<>();
        for (T value : values) {
            int itemId = id.applyAsInt(value);
            if (itemId > 0) {
                ids.add(itemId);
            }
        }
        return ids;
    }

    private static void assertUniqueProducts(String catalogue, int expectedSize, Set<Integer> ids) {
        assertEquals(catalogue + " product count", expectedSize, ids.size());
    }

    @Test
    public void everyConfigLabelIsNonEmpty() {
        for (FletchingDartType dart : FletchingDartType.values()) {
            assertFalse(dart.name(), dart.toString().isBlank());
        }
        for (FletchingBoltType bolt : FletchingBoltType.values()) {
            assertFalse(bolt.name(), bolt.toString().isBlank());
        }
        for (FletchingArrowType arrow : FletchingArrowType.values()) {
            assertFalse(arrow.name(), arrow.toString().isBlank());
        }
        for (FletchingBoltTipType tip : FletchingBoltTipType.values()) {
            assertFalse(tip.name(), tip.toString().isBlank());
        }
        for (FletchingShaftType shaft : FletchingShaftType.values()) {
            assertFalse(shaft.name(), shaft.toString().isBlank());
        }
        for (FletchingStockType stock : FletchingStockType.values()) {
            assertFalse(stock.name(), stock.toString().isBlank());
        }
        for (FletchingShieldType shield : FletchingShieldType.values()) {
            assertFalse(shield.name(), shield.toString().isBlank());
        }
        for (FletchingBowType bow : FletchingBowType.values()) {
            assertNotNull(bow.name(), bow.toString());
        }
        for (FletchingActivity activity : FletchingActivity.values()) {
            assertNotNull(activity.name(), activity.toString());
        }
    }
}
