package net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.skillcalculator.skills.CookingAction;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the module's cookable-fish table to the two external sources of truth: the raw ids must be
 * {@code ItemID.RAW_*} ids (never a cooked/burnt id), and each cooked id must be a
 * {@code CookingAction} entry with the same level and XP. The cook dispatch may only ever select
 * a raw id — a mixed-up table is how a script ends up trying to cook burnt fish.
 */
public class BarbarianFishTest {

    @Test
    public void everyFishMapsToACookingActionWithTheSameLevelAndXp() {
        for (BarbarianFish fish : BarbarianFish.values()) {
            CookingAction action = Arrays.stream(CookingAction.values())
                    .filter(candidate -> candidate.getItemId() == fish.getCookedId())
                    .findFirst()
                    .orElse(null);
            assertNotNull(fish + "'s cooked id " + fish.getCookedId()
                    + " is not enumerated in CookingAction", action);
            assertEquals(fish + " level", fish.getLevel(), action.getLevel());
            assertEquals(fish + " xp", fish.getXp(), action.getXp(), 0.001f);
        }
    }

    @Test
    public void rawIdsAreNeverCookingActionEntries() {
        for (BarbarianFish fish : BarbarianFish.values()) {
            assertTrue("raw id must not be a cookable entry: " + fish,
                    Arrays.stream(CookingAction.values())
                            .noneMatch(action -> action.getItemId() == fish.getRawId()));
        }
    }

    @Test
    public void cookedAndBurntIdsAreNotCookable() {
        for (BarbarianFish fish : BarbarianFish.values()) {
            assertTrue("raw id must classify as raw: " + fish, BarbarianFish.isRaw(fish.getRawId()));
            assertFalse("cooked id must not classify as raw: " + fish,
                    BarbarianFish.isRaw(fish.getCookedId()));
        }
        for (int burnt : new int[]{ItemID.BURNTFISH1, ItemID.BURNTFISH2, ItemID.BURNTFISH3,
                ItemID.BURNTFISH4, ItemID.BURNTFISH5}) {
            assertFalse("burnt fish " + burnt + " must not classify as raw", BarbarianFish.isRaw(burnt));
            assertFalse("burnt fish " + burnt + " must not classify as raw/cooked",
                    BarbarianFish.isRawOrCooked(burnt));
        }
    }

    @Test
    public void burntVariantsAreMatchedByNameForDropping() {
        for (String name : new String[]{"Burnt fish", "burnt fish", "BURNT FISH"}) {
            Rs2ItemModel burnt = mock(Rs2ItemModel.class);
            when(burnt.getName()).thenReturn(name);
            when(burnt.getId()).thenReturn(ItemID.BURNTFISH2);
            assertTrue("variant '" + name + "' must be droppable", BarbarianFish.isBurnt(burnt));
            assertFalse("variant '" + name + "' must not be raw", BarbarianFish.isRaw(burnt.getId()));
        }

        Rs2ItemModel raw = mock(Rs2ItemModel.class);
        when(raw.getName()).thenReturn("Raw trout");
        when(raw.getId()).thenReturn(ItemID.RAW_TROUT);
        assertFalse(BarbarianFish.isBurnt(raw));
        assertFalse("null items are never burnt", BarbarianFish.isBurnt(null));
    }
}
