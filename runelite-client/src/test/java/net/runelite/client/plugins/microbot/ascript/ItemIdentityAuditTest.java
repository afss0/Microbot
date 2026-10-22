package net.runelite.client.plugins.microbot.ascript;

import net.runelite.api.ItemID;
import net.runelite.client.plugins.microbot.ascript.crafting.JewelryItem;
import net.runelite.client.plugins.microbot.ascript.jewellenchant.JewelEnchantItem;
import net.runelite.client.plugins.microbot.ascript.motherloadmine.Pickaxe;
import net.runelite.client.plugins.microbot.ascript.woodcutting.Axe;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the item identity tables of the aScript modules: names the production interface is
 * searched by, item ids (the {@code ItemID} bracelet constants are the cache's dummy
 * entries), and the tool catalogues whose level gates decide whether a module banks for a
 * tool. A silent change here does not fail a build — it makes a batch click miss or a bank
 * cycle loop — so the tables are asserted instead of trusted.
 */
public class ItemIdentityAuditTest {

    // ── jewellery (crafting) ────────────────────────────────

    @Test
    public void dragonstoneBraceletUsesTheRenamedLabel() {
        // Renamed "Dragon bracelet" -> "Dragonstone bracelet" in 2015; the production
        // interface is searched by text, so the old label can never match.
        assertEquals("Dragonstone bracelet", JewelryItem.DRAGONSTONE_BRACELET.getName());
        assertEquals(11115, JewelryItem.DRAGONSTONE_BRACELET.getItemID());
    }

    @Test
    public void braceletIdsAreTheRealItemsNotTheDummyEntries() {
        assertEquals("gold bracelet is 11069 (JEWL_GOLD_BRACELET), not 11068",
                11069, JewelryItem.GOLD_BRACELET.getItemID());
        assertEquals("sapphire bracelet is 11072 (JEWL_SAPPHIRE_BRACELET), not 11071",
                11072, JewelryItem.SAPPHIRE_BRACELET.getItemID());
        assertEquals(11072, JewelEnchantItem.SAPPHIRE_BRACELET.getUnenchantedId());
    }

    @Test
    public void unstrungSymbolIsNamedForTheItemAndAcceptsBothInterfaceLabels() {
        assertEquals("Unstrung symbol", JewelryItem.UNSTRUNG_SYMBOL.getName());
        assertEquals(ItemID.UNSTRUNG_SYMBOL, JewelryItem.UNSTRUNG_SYMBOL.getItemID());
        assertTrue(Stream.of(JewelryItem.UNSTRUNG_SYMBOL.productLabels())
                .anyMatch("Unstrung symbol"::equals));
        assertTrue("legacy interface label must stay reachable",
                Stream.of(JewelryItem.UNSTRUNG_SYMBOL.productLabels()).anyMatch("Holy symbol"::equals));
    }

    @Test
    public void everyJewelleryEntryCarriesItsOwnNameAsThePrimaryLabel() {
        for (JewelryItem item : JewelryItem.values()) {
            if (item == JewelryItem.NONE) continue;
            assertEquals(item.name() + " must offer its own name first",
                    item.getName(), item.productLabels()[0]);
        }
    }

    @Test
    public void craftableAmuletsAreTheUnstrungItems() {
        assertEquals(ItemID.GOLD_AMULET_U, JewelryItem.GOLD_AMULET.getItemID());
        assertEquals(ItemID.SAPPHIRE_AMULET_U, JewelryItem.SAPPHIRE_AMULET.getItemID());
        assertEquals(ItemID.EMERALD_AMULET_U, JewelryItem.EMERALD_AMULET.getItemID());
        assertEquals(ItemID.RUBY_AMULET_U, JewelryItem.RUBY_AMULET.getItemID());
        assertEquals(ItemID.DIAMOND_AMULET_U, JewelryItem.DIAMOND_AMULET.getItemID());
        assertEquals(ItemID.DRAGONSTONE_AMULET_U, JewelryItem.DRAGONSTONE_AMULET.getItemID());
        assertEquals(ItemID.ONYX_AMULET_U, JewelryItem.ONYX_AMULET.getItemID());
        assertEquals(ItemID.ZENYTE_AMULET_U, JewelryItem.ZENYTE_AMULET.getItemID());
        assertEquals(ItemID.OPAL_AMULET_U, JewelryItem.OPAL_AMULET.getItemID());
        assertEquals(ItemID.JADE_AMULET_U, JewelryItem.JADE_AMULET.getItemID());
        assertEquals(ItemID.TOPAZ_AMULET_U, JewelryItem.TOPAZ_AMULET.getItemID());
    }

    // ── axes ────────────────────────────────────────────────

    @Test
    public void specialAttackAxesAreASubsetOfTheAxeCatalogue() {
        Set<Integer> catalogue = Stream.of(Axe.values()).map(Axe::getItemId).collect(Collectors.toSet());
        assertTrue("the spec set must come from the catalogue, never a copy",
                catalogue.containsAll(Axe.specialAttackAxeIds()));
        assertFalse(Axe.specialAttackAxeIds().isEmpty());
    }

    @Test
    public void axeCatalogueHasNoDuplicateIds() {
        Set<Integer> seen = new HashSet<>();
        for (Axe axe : Axe.values()) {
            assertTrue("duplicate axe id: " + axe, seen.add(axe.getItemId()));
        }
    }

    @Test
    public void axeCatalogueCoversTheTiersTheModuleWithdraws() {
        Set<Integer> ids = Stream.of(Axe.values()).map(Axe::getItemId).collect(Collectors.toSet());
        assertTrue("gilded axe missing", ids.contains(net.runelite.api.gameval.ItemID.TRAIL_GILDED_AXE));
        assertTrue("3rd age axe missing", ids.contains(net.runelite.api.gameval.ItemID._3A_AXE));
        assertTrue("2H dragon axe missing", ids.contains(net.runelite.api.gameval.ItemID.DRAGON_AXE_2H));
        assertTrue("2H crystal axe missing", ids.contains(net.runelite.api.gameval.ItemID.CRYSTAL_AXE_2H));
    }

    @Test
    public void axeLevelGateMatchesTheWikiRequirements() {
        assertEquals(1, Axe.BRONZE.getWoodcuttingLevel());
        assertEquals(11, Axe.BLACK.getWoodcuttingLevel());
        assertEquals(41, Axe.GILDED.getWoodcuttingLevel());
        assertEquals(61, Axe.DRAGON.getWoodcuttingLevel());
        assertEquals(71, Axe.CRYSTAL.getWoodcuttingLevel());

        assertFalse("a 60 Woodcutting player cannot use a dragon axe", Axe.DRAGON.isUsableAt(60));
        assertTrue(Axe.DRAGON.isUsableAt(61));
        assertTrue("an unknown level (0) must not filter", Axe.CRYSTAL.isUsableAt(0));
    }

    // ── pickaxes ────────────────────────────────────────────

    @Test
    public void pickaxeCatalogueHasNoDuplicateIdsAndCoversTheVariants() {
        Set<Integer> seen = new HashSet<>();
        for (Pickaxe pickaxe : Pickaxe.values()) {
            assertTrue("duplicate pickaxe id: " + pickaxe, seen.add(pickaxe.getItemId()));
        }
        assertTrue("upgraded dragon pickaxe missing",
                seen.contains(net.runelite.api.gameval.ItemID.DRAGON_PICKAXE_PRETTY));
        assertTrue("3rd age pickaxe missing",
                seen.contains(net.runelite.api.gameval.ItemID._3A_PICKAXE));
    }

    @Test
    public void pickaxeLevelGateMatchesTheWikiRequirements() {
        assertEquals("black pickaxe is Mining 11, not 6",
                11, Pickaxe.BLACK_PICKAXE.getMiningLevel());
        assertEquals(10, Pickaxe.BLACK_PICKAXE.getAttackLevel());
        assertEquals(61, Pickaxe.THIRD_AGE_PICKAXE.getMiningLevel());
        assertEquals(65, Pickaxe.THIRD_AGE_PICKAXE.getAttackLevel());

        assertFalse(Pickaxe.DRAGON_PICKAXE.isUsableAt(60));
        assertTrue(Pickaxe.DRAGON_PICKAXE.isUsableAt(61));
        assertTrue(Pickaxe.CRYSTAL_PICKAXE.isUsableAt(0));
    }

    @Test
    public void theRenamedAndDummyIdsAreNotInterchangeable() {
        // Guards the audit itself: the constants the tables used to carry are the dummies.
        assertNotEquals(ItemID.GOLD_BRACELET, JewelryItem.GOLD_BRACELET.getItemID());
        assertNotEquals(ItemID.SAPPHIRE_BRACELET, JewelryItem.SAPPHIRE_BRACELET.getItemID());
        assertNotEquals(ItemID.DRAGON_BRACELET, JewelryItem.DRAGONSTONE_BRACELET.getItemID());
    }
}
