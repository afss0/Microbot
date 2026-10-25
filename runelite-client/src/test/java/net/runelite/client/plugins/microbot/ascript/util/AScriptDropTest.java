package net.runelite.client.plugins.microbot.ascript.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guardrail for the drop slicer: an empty inventory must never produce a slice (that is the
 * "false failed drop" case), a slice never exceeds what is held, the default band stays 5–9, and
 * {@code dropAll} really means "all of them".
 */
public class AScriptDropTest {

    @Test
    public void emptyOrNegativeBeforeYieldsNoSlice() {
        assertEquals(0, AScriptDrop.sliceLimit(0, 5, 9));
        assertEquals(0, AScriptDrop.sliceLimit(-3, 5, 9));
    }

    @Test
    public void sliceIsCappedByWhatIsHeld() {
        assertEquals(1, AScriptDrop.sliceLimit(1, 5, 9));
        assertEquals(3, AScriptDrop.sliceLimit(3, 5, 9));
        assertEquals(5, AScriptDrop.sliceLimit(5, 5, 9));
    }

    @Test
    public void sliceStaysInsideTheConfiguredBand() {
        for (int i = 0; i < 20_000; i++) {
            int limit = AScriptDrop.sliceLimit(28, 5, 9);
            assertTrue("slice out of band: " + limit, limit >= 5 && limit <= 9);
        }
    }

    @Test
    public void dropAllTakesEverything() {
        assertEquals(28, AScriptDrop.sliceLimit(28, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(4, AScriptDrop.sliceLimit(4, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, AScriptDrop.sliceLimit(0, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
