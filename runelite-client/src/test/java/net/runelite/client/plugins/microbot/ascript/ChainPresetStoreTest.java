package net.runelite.client.plugins.microbot.ascript;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChainPresetStoreTest {

    @Test
    public void roundTripsNamedChainsInOrder() {
        Map<String, String> presets = new LinkedHashMap<>();
        presets.put("Dragon bolts", "FLETCHING(fletchingActivity=BOLTS,fletchingBoltType=DRAGON)");
        presets.put("Ruby tips", "FLETCHING(fletchingActivity=BOLT_TIPS,fletchingBoltTipType=DRAGON_RUBY)");
        presets.put("Ruby enchant", "BOLT_ENCHANT(boltEnchantBoltType=RUBY_DRAGON)");

        Map<String, String> parsed = ChainPresetStore.parse(ChainPresetStore.serialize(presets));

        assertEquals(presets, parsed);
        assertEquals(3, parsed.size());
    }

    @Test
    public void blankStoreIsEmpty() {
        assertTrue(ChainPresetStore.parse(null).isEmpty());
        assertTrue(ChainPresetStore.parse("  ").isEmpty());
        assertEquals("", ChainPresetStore.serialize(Map.of()));
    }

    @Test
    public void preservesNamesWithQuotesAndUnicode() {
        Map<String, String> presets = new LinkedHashMap<>();
        presets.put("Ruby \"dragon\" bolts — teste", "FLETCHING(fletchingActivity=BOLTS,note=\"two words\")");

        assertEquals(presets, ChainPresetStore.parse(ChainPresetStore.serialize(presets)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedJson() {
        ChainPresetStore.parse("not json");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankNames() {
        ChainPresetStore.parse("{\" \":\"MINING\"}");
    }
}
