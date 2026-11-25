package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.plugins.microbot.ascript.barbarianfishing.BarbarianFishingActivity;
import net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher.BarbarianFishingFunctions;
import net.runelite.client.plugins.microbot.ascript.motherloadmine.MLMStatus;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Pins the user-facing defaults to the bank-only policy: no mode remains that can be
 * interpreted as a request to drop a produced item.
 */
public class AScriptNoDropPolicyTest {

    @Test
    public void barbarianVillageFisherHasOnlyBankModes() {
        assertEquals(Arrays.asList(BarbarianFishingFunctions.COOK_AND_BANK,
                BarbarianFishingFunctions.BANK_RAW), Arrays.asList(BarbarianFishingFunctions.values()));
    }

    @Test
    public void barbarianFishingLabelAndMlmStatusHaveNoDropMode() {
        assertFalse(BarbarianFishingActivity.FISH.toString().toLowerCase().contains("drop"));
        assertFalse(Arrays.stream(MLMStatus.values())
                .anyMatch(status -> status.name().toLowerCase().contains("drop")));
    }
}
