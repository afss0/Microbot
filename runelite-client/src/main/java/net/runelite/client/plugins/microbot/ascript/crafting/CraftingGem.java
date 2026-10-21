package net.runelite.client.plugins.microbot.ascript.crafting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CraftingGem {
    NONE(" ", 1, false),
    OPAL("Opal", 1, true),
    JADE("Jade", 13, true),
    RED_TOPAZ("Red Topaz", 16, true),
    SAPPHIRE("Sapphire", 20, true),
    EMERALD("Emerald", 27, true),
    RUBY("Ruby", 34, true),
    DIAMOND("Diamond", 43, true),
    DRAGONSTONE("Dragonstone", 55, true),
    ONYX("Onyx", 67, true),
    AMETHYST_BOLT_TIPS("Amethyst bolt tips", 83, false),
    AMETHYST_ARROWTIPS("Amethyst arrowtips", 85, false),
    AMETHYST_JAVELIN_HEADS("Amethyst javelin heads", 87, false),
    AMETHYST_DART_TIPS("Amethyst dart tips", 89, false),
    ZENYTE("Zenyte", 89, false);

    private final String name;
    private final int levelRequired;
    /**
     * Whether the cut gem can be chiselled into bolt tips. The wiki's bolt-tip table lists
     * opal, jade, red topaz, sapphire, emerald, ruby, diamond, dragonstone and onyx only; a cut
     * zenyte is jewellery stock (furnace + gold bar), and the amethyst entries are already the
     * finished chisel product, so neither has a bolt-tip recipe.
     */
    private final boolean boltTips;

    @Override
    public String toString() {
        return name;
    }
}
