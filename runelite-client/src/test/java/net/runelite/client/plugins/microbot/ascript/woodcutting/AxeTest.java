package net.runelite.client.plugins.microbot.ascript.woodcutting;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guardrail for the axe catalogue's level policy: {@code isUsableAt} decides both the
 * bank's best-axe pick and the held-axe equip path. A level read of 0 means "skill data
 * not loaded yet" and must never filter (a false "no axe" verdict is worse than
 * attempting the action), while a real level must be honoured exactly at the boundary.
 */
public class AxeTest {

    @Test
    public void usableAtRespectsTheBoundaryAndTreatsZeroAsUnknown() {
        for (Axe axe : Axe.values()) {
            int required = axe.getWoodcuttingLevel();
            assertTrue(axe.name() + ": level 0 is unknown and must not filter", axe.isUsableAt(0));
            assertTrue(axe.name() + " must be usable at its own requirement",
                    axe.isUsableAt(required));
            if (required > 1) {
                assertFalse(axe.name() + " must not be usable below its requirement",
                        axe.isUsableAt(required - 1));
            }
            assertTrue(axe.name() + " must be usable above its requirement",
                    axe.isUsableAt(required + 1));
        }
    }

    @Test
    public void specialAttackIdsAreDerivedFromTheCatalogue() {
        Set<Integer> expected = new HashSet<>();
        for (Axe axe : Axe.values()) {
            if (axe.isSpecialAttack()) expected.add(axe.getItemId());
        }
        assertEquals(expected, Axe.specialAttackAxeIds());
    }

    @Test
    public void itemIdsAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (Axe axe : Axe.values()) {
            assertTrue("duplicate axe item id " + axe.getItemId() + " (" + axe.name() + ")",
                    seen.add(axe.getItemId()));
        }
    }
}
