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
