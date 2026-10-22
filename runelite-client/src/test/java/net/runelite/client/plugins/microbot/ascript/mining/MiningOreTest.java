package net.runelite.client.plugins.microbot.ascript.mining;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guardrail for {@link MiningOre}'s inventory-product policy: the drop, deposit and
 * basalt-noting paths classify inventory items with {@code isProduct}, so an ore whose
 * product list is empty (or that claims another ore's product) silently breaks those
 * decisions — the item is never dropped, deposited or noted.
 */
public class MiningOreTest {

    @Test
    public void noneHasNoProductsAndMatchesNothing() {
        MiningOre none = MiningOre.NONE;
        assertEquals(0, none.getProductIds().length);
        for (MiningOre ore : MiningOre.values()) {
            for (int id : ore.getProductIds()) {
                assertFalse("NONE must not claim id " + id, none.isProduct(id));
            }
        }
    }

    @Test
    public void everyMineableOreOwnsItsProducts() {
        for (MiningOre ore : MiningOre.values()) {
            if (ore == MiningOre.NONE) continue;
            int[] products = ore.getProductIds();
            assertTrue(ore.name() + " has no product ids", products.length > 0);
            for (int id : products) {
                assertTrue(ore.name() + " does not recognise its own product " + id,
                        ore.isProduct(id));
            }
        }
    }

    @Test
    public void oreDoesNotClaimAnotherOresProduct() {
        for (MiningOre ore : MiningOre.values()) {
            if (ore == MiningOre.NONE) continue;
            for (MiningOre other : MiningOre.values()) {
                if (other == ore || other == MiningOre.NONE) continue;
                for (int foreign : other.getProductIds()) {
                    if (contains(ore.getProductIds(), foreign)) continue; // shared family (salts/gems)
                    assertFalse(ore.name() + " wrongly claims " + other.name() + "'s product " + foreign,
                            ore.isProduct(foreign));
                }
            }
        }
    }

    @Test
    public void gemOreCoversTheUncutFamily() {
        MiningOre gems = MiningOre.GEM;
        int[] expected = {
                ItemID.UNCUT_OPAL, ItemID.UNCUT_JADE, ItemID.UNCUT_RED_TOPAZ,
                ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND
        };
        for (int id : expected) {
            assertTrue("gem ore is missing uncut id " + id, gems.isProduct(id));
        }
    }

    @Test
    public void productIdsAreUniqueAcrossTheCatalogue() {
        Set<Integer> seen = new HashSet<>();
        for (MiningOre ore : MiningOre.values()) {
            for (int id : ore.getProductIds()) {
                assertTrue("product id " + id + " reused by " + ore.name(), seen.add(id));
            }
        }
    }

    @Test
    public void displayNamesAreUsableInStatusLines() {
        for (MiningOre ore : MiningOre.values()) {
            assertNotNull(ore.getDisplayName());
            assertFalse(ore.name() + " has a blank display name", ore.getDisplayName().trim().isEmpty());
            assertEquals(ore.getDisplayName(), ore.toString());
        }
    }

    private static boolean contains(int[] ids, int wanted) {
        for (int id : ids) {
            if (id == wanted) return true;
        }
        return false;
    }
}
