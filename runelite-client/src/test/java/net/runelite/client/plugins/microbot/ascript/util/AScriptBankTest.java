package net.runelite.client.plugins.microbot.ascript.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
