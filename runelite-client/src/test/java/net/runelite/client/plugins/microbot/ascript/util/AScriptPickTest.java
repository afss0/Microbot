package net.runelite.client.plugins.microbot.ascript.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guardrail for the aScript slot pick: the rank must stay inside {@code [1, n]} for every
 * candidate count an inventory can produce (a rank above the candidate list would throw, a
 * rank below 1 silently), and the shape must stay "first occurrences carry most of the
 * mass, the last ones stay reachable but rare" — the whole point of the helper.
 */
public class AScriptPickTest {

    @Test
    public void rankStaysInRangeForEveryInventorySize() {
        for (int n = 1; n <= 28; n++) {
            for (int i = 0; i < 20_000; i++) {
                int rank = AScriptPick.rank(n);
                assertTrue("rank " + rank + " out of range for n=" + n, rank >= 1 && rank <= n);
            }
        }
    }

    @Test
    public void degenerateCandidateCountsReturnRankOne() {
        assertEquals(1, AScriptPick.rank(0));
        assertEquals(1, AScriptPick.rank(1));
    }

    @Test
    public void firstOccurrencesCarryMostOfTheMassAndTheLastRankStaysReachable() {
        int n = 14;
        int samples = 100_000;
        int[] hits = new int[n + 1];
        for (int i = 0; i < samples; i++) {
            hits[AScriptPick.rank(n)]++;
        }

        int firstThree = hits[1] + hits[2] + hits[3];
        assertTrue("first three ranks should hold most of the mass, got " + firstThree,
                firstThree > samples * 0.4);
        assertTrue("the last rank must stay reachable", hits[n] > 0);
        assertTrue("the last rank must stay rare, got " + hits[n], hits[n] < samples * 0.1);
        assertTrue("the last rank must not dominate the tail, got " + hits[n] + " vs " + hits[n - 1],
                hits[n] < samples * 0.05);
    }
}
