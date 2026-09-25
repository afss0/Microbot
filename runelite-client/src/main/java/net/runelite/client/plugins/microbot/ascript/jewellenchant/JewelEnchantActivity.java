package net.runelite.client.plugins.microbot.ascript.jewellenchant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum JewelEnchantActivity {
    NONE(" "),
    ENCHANT_JEWELLERY("Enchant Jewellery");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
