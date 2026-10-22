package net.runelite.client.plugins.microbot.ascript.motherloadmine;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guardrail for the MLM pickaxe catalogue: {@code isUsableAt} gates both the bank's best
 * pick and the "no pickaxe anywhere" stop. Level 0 is a timed-out client read ("unknown")
 * and must not filter; a real level must be honoured exactly at the boundary.
 */
public class PickaxeTest {

    @Test
    public void usableAtRespectsTheBoundaryAndTreatsZeroAsUnknown() {
        for (Pickaxe pickaxe : Pickaxe.values()) {
            int required = pickaxe.getMiningLevel();
            assertTrue(pickaxe.name() + ": level 0 is unknown and must not filter",
                    pickaxe.isUsableAt(0));
            assertTrue(pickaxe.name() + " must be usable at its own requirement",
                    pickaxe.isUsableAt(required));
            if (required > 1) {
                assertFalse(pickaxe.name() + " must not be usable below its requirement",
                        pickaxe.isUsableAt(required - 1));
            }
            assertTrue(pickaxe.name() + " must be usable above its requirement",
                    pickaxe.isUsableAt(required + 1));
        }
    }

    @Test
    public void itemIdsAreUniqueAndNamesAreUsableInStatusLines() {
        Set<Integer> seen = new HashSet<>();
        for (Pickaxe pickaxe : Pickaxe.values()) {
            assertTrue("duplicate pickaxe item id " + pickaxe.getItemId() + " (" + pickaxe.name() + ")",
                    seen.add(pickaxe.getItemId()));
            assertTrue(pickaxe.name() + " has a blank item name",
                    pickaxe.getItemName() != null && !pickaxe.getItemName().trim().isEmpty());
        }
    }

    @Test
    public void catalogueLevelsArePositive() {
        for (Pickaxe pickaxe : Pickaxe.values()) {
            assertTrue(pickaxe.name() + " has no mining requirement", pickaxe.getMiningLevel() > 0);
            assertTrue(pickaxe.name() + " has no attack requirement", pickaxe.getAttackLevel() > 0);
        }
    }
}
