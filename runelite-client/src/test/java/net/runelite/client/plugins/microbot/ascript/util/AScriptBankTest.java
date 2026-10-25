package net.runelite.client.plugins.microbot.ascript.util;

import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guardrail for the amount check's item-type resolution: the inventory count and the bank row
 * the withdraw dispatch clicks must resolve to the SAME display name, so a substring name can
 * never let one item type satisfy the check for another.
 */
public class AScriptBankTest {

    @Test
    public void shortestMatchingNamePinsOneTypeAmongSubstringSiblings() {
        List<String> names = Arrays.asList("Stripy feather", "Feather", "Red feather");
        assertEquals("Feather", AScriptBank.shortestMatchingName("feather", names));

        assertEquals("Logs", AScriptBank.shortestMatchingName("logs", Arrays.asList("Oak logs", "Logs")));
        assertEquals("Oak logs", AScriptBank.shortestMatchingName("oak logs", Arrays.asList("Oak logs", "Logs")));
        assertEquals("Necklace of passage", AScriptBank.shortestMatchingName(
                "necklace of passage",
                Arrays.asList("Necklace of passage(5)", "Necklace of passage(3)", "Necklace of passage")));
    }

    @Test
    public void matchingIsCaseInsensitiveAndPicksTheShortestName() {
        assertEquals("Bow string", AScriptBank.shortestMatchingName("BOW STRING", List.of("Bow string")));
        assertEquals("Chisel", AScriptBank.shortestMatchingName("chisel", List.of("Chisel", "Costume needle")));
    }

    @Test
    public void noMatchAndDegenerateInputsReturnNull() {
        assertNull(AScriptBank.shortestMatchingName("feather", List.of("Logs")));
        assertNull(AScriptBank.shortestMatchingName("feather", Collections.emptyList()));
        assertNull(AScriptBank.shortestMatchingName("feather", null));
        assertNull(AScriptBank.shortestMatchingName(null, List.of("Feather")));
    }

    @Test
    public void nullCandidateEntriesAreSkippedNotFatal() {
        assertEquals("Feather", AScriptBank.shortestMatchingName("feather", Arrays.asList(null, "Feather")));
    }

    @Test
    public void notedInventoryCountsAsBankable() {
        Rs2ItemModel noted = mock(Rs2ItemModel.class);
        when(noted.isNoted()).thenReturn(true);
        when(noted.getUnNotedId()).thenReturn(879);
        when(noted.getQuantity()).thenReturn(3);

        try (MockedStatic<Rs2Inventory> inventory = Mockito.mockStatic(Rs2Inventory.class)) {
            inventory.when(Rs2Inventory::items).thenAnswer(call -> Stream.of(noted));
            assertTrue(AScriptBank.hasNotedItem(879));
            assertFalse(AScriptBank.hasNotedItem(880));
            assertTrue("noted units become bank stock after the deposit",
                    AScriptBank.bankableAtLeast(879, 3));
        }
    }

    @Test
    public void unnotedInventoryAndBankCombineForAvailability() {
        Rs2ItemModel plain = mock(Rs2ItemModel.class);
        when(plain.isNoted()).thenReturn(false);
        when(plain.getId()).thenReturn(879);
        when(plain.getQuantity()).thenReturn(1);

        try (MockedStatic<Rs2Inventory> inventory = Mockito.mockStatic(Rs2Inventory.class);
             MockedStatic<Rs2Bank> bank = Mockito.mockStatic(Rs2Bank.class)) {
            inventory.when(Rs2Inventory::items).thenAnswer(call -> Stream.of(plain));
            bank.when(() -> Rs2Bank.hasBankItem(879, 2)).thenReturn(true);
            assertTrue(AScriptBank.bankableAtLeast(879, 3));
            assertFalse(AScriptBank.bankableAtLeast(880, 1));
        }
    }
}
