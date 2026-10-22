package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.plugins.microbot.ascript.crafting.CraftingScript;
import net.runelite.client.plugins.microbot.ascript.firemaking.FiremakingScript;
import net.runelite.client.plugins.microbot.ascript.mining.MiningScript;
import net.runelite.client.plugins.microbot.ascript.motherloadmine.MotherloadMineScript;
import net.runelite.client.plugins.microbot.ascript.woodcutting.WoodcuttingScript;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guardrail for the module selection dispatch: a module selected in the config but not yet
 * configured (activity enum still at its NONE default) must report a visible configuration
 * message instead of idling, and every other module must stay out of the way. These are the
 * "selected but inactive" branches of {@code validateSelection}; the active-phase capability
 * guards read live skill levels and are out of scope for a client-free test.
 */
public class ModuleSelectionTest {

    private static AScriptConfig selection(ScriptType type) {
        AScriptConfig config = mock(AScriptConfig.class);
        when(config.scriptSelection()).thenReturn(type);
        return config;
    }

    @Test
    public void selectedButInactiveModulesReportAConfigurationMessage() {
        assertEquals("no ore selected",
                new MiningScript().validateSelection(selection(ScriptType.MINING)));
        assertEquals("no log type selected",
                new FiremakingScript().validateSelection(selection(ScriptType.FIREMAKING)));
        assertEquals("no tree type selected",
                new WoodcuttingScript().validateSelection(selection(ScriptType.WOODCUTTING)));
        assertEquals("motherload mine selected but inactive",
                new MotherloadMineScript().validateSelection(selection(ScriptType.MOTHERLOAD_MINE)));
        assertEquals("no crafting activity selected",
                new CraftingScript().validateSelection(selection(ScriptType.CRAFTING)));
    }

    @Test
    public void unselectedModulesDoNotBlockTheRun() {
        assertNull(new MiningScript().validateSelection(selection(ScriptType.NONE)));
        assertNull(new FiremakingScript().validateSelection(selection(ScriptType.CRAFTING)));
        assertNull(new WoodcuttingScript().validateSelection(selection(ScriptType.MINING)));
        assertNull(new MotherloadMineScript().validateSelection(selection(ScriptType.WOODCUTTING)));
        assertNull(new CraftingScript().validateSelection(selection(ScriptType.FIREMAKING)));
    }
}
