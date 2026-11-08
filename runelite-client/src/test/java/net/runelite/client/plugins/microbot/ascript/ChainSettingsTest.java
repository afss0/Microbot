package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.ascript.fletching.FletchingActivity;
import net.runelite.client.plugins.microbot.ascript.fletching.FletchingBoltType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class ChainSettingsTest {

    @Test
    public void userFletchingSequenceIsValid() {
        ModuleChain chain = ModuleChain.parse(
                "FLETCHING(fletchingActivity=BOLTS,fletchingBoltType=MITHRIL);"
                        + "FLETCHING(fletchingActivity=BOLT_TIPS,fletchingBoltTipType=OPAL)");
        assertNull(ChainSettings.validate(chain));
        assertEquals(ScriptType.FLETCHING, chain.entries().get(0).getType());
        assertEquals(ScriptType.FLETCHING, chain.entries().get(1).getType());
    }

    @Test
    public void appliesTypedOverridesThroughConfigManager() {
        ModuleChain.Step step = ModuleChain.parse(
                "FLETCHING(fletchingActivity=BOLTS,fletchingBoltType=MITHRIL)")
                .entries().get(0);
        ConfigManager configManager = mock(ConfigManager.class);

        assertNull(ChainSettings.apply(configManager, step));
        verify(configManager).setConfiguration(AScriptConfig.GROUP, "fletchingActivity",
                FletchingActivity.BOLTS);
        verify(configManager).setConfiguration(AScriptConfig.GROUP, "fletchingBoltType",
                FletchingBoltType.MITHRIL);
    }

    @Test
    public void unknownSettingIsRejectedBeforeAnyWrite() {
        ModuleChain.Step step = ModuleChain.parse("FLETCHING(notASetting=BOLTS)")
                .entries().get(0);
        ConfigManager configManager = mock(ConfigManager.class);

        assertNotNull(ChainSettings.apply(configManager, step));
        verifyNoInteractions(configManager);
    }

    @Test
    public void invalidEnumIsRejected() {
        ModuleChain.Step step = ModuleChain.parse("FLETCHING(fletchingActivity=NOT_A_REAL_ACTIVITY)")
                .entries().get(0);
        ConfigManager configManager = mock(ConfigManager.class);

        assertNotNull(ChainSettings.apply(configManager, step));
        verifyNoInteractions(configManager);
    }

    @Test
    public void chainManagedKeyIsReserved() {
        ModuleChain.Step step = ModuleChain.parse("FLETCHING(chainEnabled=false)")
                .entries().get(0);
        assertNotNull(ChainSettings.validate(step));
    }

    @Test
    public void parserRoundTripsEnumAndBooleanValues() {
        ModuleChain chain = ModuleChain.parse(
                "FLETCHING(fletchingActivity=BOLTS,fletchingAfk=false)");
        assertNull(ChainSettings.validate(chain));
        assertEquals(FletchingActivity.BOLTS,
                FletchingActivity.valueOf(chain.entries().get(0).getSettings().get("fletchingActivity")));
    }
}
