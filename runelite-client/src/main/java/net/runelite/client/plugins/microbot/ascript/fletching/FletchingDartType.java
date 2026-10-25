package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Dart tip + feather = dart. Ids are the gameval {@link ItemID} constants; the display names are
 * only used for status/stop messages (the live name is resolved through
 * {@code AScriptFletching.itemName(...)} at runtime).
 * <p>
 * Levels are pinned to {@code skillcalculator/skills/FletchingAction.java} by
 * {@code FletchingDataTest}.
 */
@Getter
@RequiredArgsConstructor
public enum FletchingDartType {
    BRONZE("Bronze dart", "Bronze dart tip", ItemID.BRONZE_DART_TIP, ItemID.BRONZE_DART, 10),
    IRON("Iron dart", "Iron dart tip", ItemID.IRON_DART_TIP, ItemID.IRON_DART, 22),
    STEEL("Steel dart", "Steel dart tip", ItemID.STEEL_DART_TIP, ItemID.STEEL_DART, 37),
    MITHRIL("Mithril dart", "Mithril dart tip", ItemID.MITHRIL_DART_TIP, ItemID.MITHRIL_DART, 52),
    ADAMANT("Adamant dart", "Adamant dart tip", ItemID.ADAMANT_DART_TIP, ItemID.ADAMANT_DART, 67),
    RUNE("Rune dart", "Rune dart tip", ItemID.RUNE_DART_TIP, ItemID.RUNE_DART, 81),
    DRAGON("Dragon dart", "Dragon dart tip", ItemID.DRAGON_DART_TIP, ItemID.DRAGON_DART, 95),
    AMETHYST("Amethyst dart", "Amethyst dart tip", ItemID.AMETHYST_DART_TIP, ItemID.AMETHYST_DART, 90);

    private final String label;
    private final String materialName;
    private final int materialId;
    private final int productId;
    private final int levelRequirement;

    /** Level gate; an unloaded level (0) passes and the next tick re-checks. */
    public boolean meetsLevelRequirement() {
        int level = Rs2Player.getRealSkillLevel(Skill.FLETCHING);
        return level <= 0 || level >= levelRequirement;
    }

    @Override
    public String toString() {
        return label;
    }
}
