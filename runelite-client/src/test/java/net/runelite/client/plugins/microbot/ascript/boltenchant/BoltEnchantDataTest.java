package net.runelite.client.plugins.microbot.ascript.boltenchant;

import net.runelite.client.plugins.microbot.util.magic.Runes;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the bolt-enchant data table: ids, levels and per-cast rune costs.
 * <p>
 * A silent change here does not fail a build — it makes the batch click miss, the count signal
 * read the wrong item, or a bank cycle withdraw a stack that cannot cast. The table is the wiki's
 * "Enchant Crossbow Bolt" data (levels, runes, 10 bolts per cast) and the ids are the legacy
 * {@code ItemID} constants; both are asserted instead of trusted.
 */
public class BoltEnchantDataTest {

    private static final List<String> GEMS = List.of(
            "OPAL", "SAPPHIRE", "JADE", "PEARL", "EMERALD",
            "TOPAZ", "RUBY", "DIAMOND", "DRAGONSTONE", "ONYX");

    @Test
    public void everyGemHasARegularAndADragonEntry() {
        for (String gem : GEMS) {
            assertNotNull(gem, BoltEnchantType.valueOf(gem));
            assertNotNull(gem + "_DRAGON", BoltEnchantType.valueOf(gem + "_DRAGON"));
        }
        assertEquals("20 bolt types + NONE", 21, BoltEnchantType.values().length);
    }

    @Test
    public void idsAreUniqueAndAreNotTheGamevalPlaceholders() {
        Set<Integer> seen = new HashSet<>();
        for (BoltEnchantType type : BoltEnchantType.values()) {
            if (type == BoltEnchantType.NONE) continue;
            assertTrue(type.name() + " input id must be unique", seen.add(type.getUnenchantedId()));
            assertTrue(type.name() + " output id must be unique", seen.add(type.getEnchantedId()));
            assertTrue(type.name() + " must not reuse its own input id",
                    type.getUnenchantedId() != type.getEnchantedId());
        }
        // Regular opal bolts: 879 -> 9236 (OPAL_BOLTS_E), not the 9246-9249 charge-variant ids.
        assertEquals(879, BoltEnchantType.OPAL.getUnenchantedId());
        assertEquals(9236, BoltEnchantType.OPAL.getEnchantedId());
        // Dragon opal bolts: 21955 -> 21932 (the 21956/21933 pair are the placeholder copies).
        assertEquals(21955, BoltEnchantType.OPAL_DRAGON.getUnenchantedId());
        assertEquals(21932, BoltEnchantType.OPAL_DRAGON.getEnchantedId());
    }

    @Test
    public void levelsMatchTheWikiTable() {
        assertEquals(4, BoltEnchantType.OPAL.getMagicLevel());
        assertEquals(7, BoltEnchantType.SAPPHIRE.getMagicLevel());
        assertEquals(14, BoltEnchantType.JADE.getMagicLevel());
        assertEquals(24, BoltEnchantType.PEARL.getMagicLevel());
        assertEquals(27, BoltEnchantType.EMERALD.getMagicLevel());
        assertEquals(29, BoltEnchantType.TOPAZ.getMagicLevel());
        assertEquals(49, BoltEnchantType.RUBY.getMagicLevel());
        assertEquals(57, BoltEnchantType.DIAMOND.getMagicLevel());
        assertEquals(68, BoltEnchantType.DRAGONSTONE.getMagicLevel());
        assertEquals(87, BoltEnchantType.ONYX.getMagicLevel());
    }

    @Test
    public void runeCostsMatchTheWikiTable() {
        assertRunes(BoltEnchantType.OPAL, Map.of(Runes.AIR, 2, Runes.COSMIC, 1));
        assertRunes(BoltEnchantType.SAPPHIRE, Map.of(Runes.WATER, 1, Runes.COSMIC, 1, Runes.MIND, 1));
        assertRunes(BoltEnchantType.JADE, Map.of(Runes.EARTH, 2, Runes.COSMIC, 1));
        assertRunes(BoltEnchantType.PEARL, Map.of(Runes.WATER, 2, Runes.COSMIC, 1));
        assertRunes(BoltEnchantType.EMERALD, Map.of(Runes.AIR, 3, Runes.COSMIC, 1, Runes.NATURE, 1));
        assertRunes(BoltEnchantType.TOPAZ, Map.of(Runes.FIRE, 2, Runes.COSMIC, 1));
        assertRunes(BoltEnchantType.RUBY, Map.of(Runes.FIRE, 5, Runes.BLOOD, 1, Runes.COSMIC, 1));
        assertRunes(BoltEnchantType.DIAMOND, Map.of(Runes.EARTH, 10, Runes.COSMIC, 1, Runes.LAW, 2));
        assertRunes(BoltEnchantType.DRAGONSTONE,
                Map.of(Runes.EARTH, 15, Runes.COSMIC, 1, Runes.SOUL, 1));
        assertRunes(BoltEnchantType.ONYX, Map.of(Runes.FIRE, 20, Runes.COSMIC, 1, Runes.DEATH, 1));
    }

    @Test
    public void dragonEntriesMirrorTheirRegularTwin() {
        for (String gem : GEMS) {
            BoltEnchantType regular = BoltEnchantType.valueOf(gem);
            BoltEnchantType dragon = BoltEnchantType.valueOf(gem + "_DRAGON");
            assertEquals(gem + " dragon level", regular.getMagicLevel(), dragon.getMagicLevel());
            assertEquals(gem + " dragon runes", regular.getRunes(), dragon.getRunes());
            assertTrue(gem + " dragon needs its own items",
                    regular.getUnenchantedId() != dragon.getUnenchantedId()
                            && regular.getEnchantedId() != dragon.getEnchantedId());
        }
    }

    @Test
    public void everyCastNeedsExactlyOneCosmicAndOnlyStaffRunesAreCoverable() {
        assertTrue("cosmic is supplied by no catalogue staff",
                net.runelite.client.plugins.microbot.util.magic.Rs2Staff
                        .itemIdsProviding(Runes.COSMIC).isEmpty());
        for (BoltEnchantType type : BoltEnchantType.values()) {
            if (type == BoltEnchantType.NONE) continue;
            assertEquals(type.name() + " needs one cosmic per cast",
                    Integer.valueOf(1), type.getRunes().get(Runes.COSMIC));
        }
        assertTrue("emerald carries a nature rune a staff can supply",
                BoltEnchantType.EMERALD.hasStaffCoverableRune());
        assertTrue("opal carries air, which every air staff supplies",
                BoltEnchantType.OPAL.hasStaffCoverableRune());
    }

    @Test
    public void labelAndDisplayNameStayInSync() {
        for (BoltEnchantType type : BoltEnchantType.values()) {
            if (type == BoltEnchantType.NONE) continue;
            assertEquals(type.getDisplayName() + " (e)", type.interfaceLabel());
            assertFalse(type.name() + " must render a human name",
                    type.toString().isEmpty() || type.toString().startsWith("NONE"));
        }
    }

    private static void assertRunes(BoltEnchantType type, Map<Runes, Integer> expected) {
        assertEquals(type.name() + " rune table", expected, type.getRunes());
    }
}
