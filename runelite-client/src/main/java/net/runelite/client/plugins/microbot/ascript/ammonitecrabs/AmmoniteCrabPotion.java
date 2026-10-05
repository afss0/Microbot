package net.runelite.client.plugins.microbot.ascript.ammonitecrabs;

import lombok.Getter;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * Offensive potion choices. {@code id} is the 4-dose item withdrawn at the bank;
 * {@code skill} drives Rs2Player.drinkCombatPotionAt(skill) in combat.
 */
@Getter
public enum AmmoniteCrabPotion {
    COMBAT("Combat potion", Skill.STRENGTH, ItemID._4DOSECOMBAT),
    SUPER_COMBAT("Super combat potion", Skill.STRENGTH, ItemID._4DOSE2COMBAT),
    SUPER_STRENGTH("Super strength", Skill.STRENGTH, ItemID._4DOSE2STRENGTH),
    RANGING("Ranging potion", Skill.RANGED, ItemID._4DOSERANGERSPOTION),
    MAGIC("Magic potion", Skill.MAGIC, ItemID._4DOSE1MAGIC);

    private final String potionName;
    private final Skill skill;
    private final int id;

    AmmoniteCrabPotion(String potionName, Skill skill, int id) {
        this.potionName = potionName;
        this.skill = skill;
        this.id = id;
    }

    @Override
    public String toString() {
        return potionName;
    }
}
