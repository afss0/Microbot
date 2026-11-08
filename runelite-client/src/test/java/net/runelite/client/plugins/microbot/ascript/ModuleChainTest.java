package net.runelite.client.plugins.microbot.ascript;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModuleChainTest {

    @Test
    public void parsesEnumAndDisplayNamesInOrder() {
        ModuleChain chain = ModuleChain.parse("MINING, Crafting > Motherload Mine");
        assertEquals(List.of(ScriptType.MINING, ScriptType.CRAFTING, ScriptType.MOTHERLOAD_MINE),
                chain.steps());
    }

    @Test
    public void blankSpecIsAnEmptyChain() {
        assertTrue(ModuleChain.parse(null).isEmpty());
        assertTrue(ModuleChain.parse("  ").isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownModule() {
        ModuleChain.parse("MINING,NOT_A_MODULE");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIdleAsAStep() {
        ModuleChain.parse("MINING,IDLE");
    }

    @Test
    public void parsesPerStepConfigOverrides() {
        ModuleChain chain = ModuleChain.parse(
                "FLETCHING(fletchingActivity=BOLTS,fletchingBoltType=MITHRIL);"
                        + "FLETCHING(fletchingActivity=BOLT_TIPS,fletchingBoltTipType=OPAL)");
        assertEquals(2, chain.size());
        assertEquals(ScriptType.FLETCHING, chain.entries().get(0).getType());
        assertEquals("BOLTS", chain.entries().get(0).getSettings().get("fletchingActivity"));
        assertEquals("MITHRIL", chain.entries().get(0).getSettings().get("fletchingBoltType"));
        assertEquals("BOLT_TIPS", chain.entries().get(1).getSettings().get("fletchingActivity"));
        assertEquals("OPAL", chain.entries().get(1).getSettings().get("fletchingBoltTipType"));
        assertEquals("FLETCHING(fletchingActivity=BOLTS,fletchingBoltType=MITHRIL)",
                chain.entries().get(0).toConfigText());
    }

    @Test
    public void acceptsQuotedStringValuesAndLegacyCommaSteps() {
        ModuleChain chain = ModuleChain.parse(
                "MINING,FLETCHING(fletchingActivity=BOLTS,note=\"two words\")");
        assertEquals(2, chain.size());
        assertEquals("two words", chain.entries().get(1).getSettings().get("note"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateOverride() {
        ModuleChain.parse("FLETCHING(fletchingActivity=BOLTS,fletchingActivity=ARROWS)");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnbalancedOverride() {
        ModuleChain.parse("FLETCHING(fletchingActivity=BOLTS");
    }

    @Test
    public void repeatedModuleKeepsDistinctStepSettings() {
        ModuleChain chain = ModuleChain.parse(
                "FLETCHING(fletchingActivity=BOLTS);FLETCHING(fletchingActivity=BOLT_TIPS)");
        ModuleChain.Step first = chain.startStep();
        ModuleChain.Step second = chain.advanceStep(false);
        assertEquals(ScriptType.FLETCHING, first.getType());
        assertEquals(ScriptType.FLETCHING, second.getType());
        assertEquals("BOLTS", first.getSettings().get("fletchingActivity"));
        assertEquals("BOLT_TIPS", second.getSettings().get("fletchingActivity"));
    }

    @Test
    public void startsAtFirstAndAdvances() {
        ModuleChain chain = ModuleChain.parse("MINING,CRAFTING");
        assertEquals(ScriptType.MINING, chain.start());
        assertTrue(chain.isStarted());
        assertEquals(1, chain.position());
        assertEquals(ScriptType.CRAFTING, chain.advance(false));
        assertEquals(2, chain.position());
    }

    @Test
    public void endStopsOrLoops() {
        ModuleChain noLoop = ModuleChain.parse("MINING,CRAFTING");
        noLoop.start();
        noLoop.advance(false);
        assertNull(noLoop.advance(false));
        assertTrue(noLoop.isFinished());

        ModuleChain loop = ModuleChain.parse("MINING,CRAFTING");
        loop.start();
        loop.advance(false);
        assertEquals(ScriptType.MINING, loop.advance(true));
        assertTrue(loop.isStarted());
    }

    @Test
    public void manualSelectionSeedsTheCursor() {
        ModuleChain chain = ModuleChain.parse("MINING,CRAFTING,FLETCHING");
        assertEquals(ScriptType.CRAFTING, chain.startAt(ScriptType.CRAFTING));
        assertEquals(2, chain.position());
        assertEquals(ScriptType.FLETCHING, chain.advance(false));
    }

    @Test
    public void finishedChainDoesNotAutoStartAgain() {
        ModuleChain chain = ModuleChain.parse("MINING");
        chain.start();
        assertNull(chain.advance(false));
        assertTrue(chain.isFinished());
        assertNull(chain.advance(true));
        chain.reset();
        assertFalse(chain.isFinished());
        assertEquals(ScriptType.MINING, chain.start());
    }
}
