package net.runelite.client.plugins.microbot.ascript.mining;

import lombok.Getter;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.HashMap;
import java.util.Map;

/**
 * A known mine for one ore type, with the requirements its access gate implies.
 * Port of the hub's LocationOption with three corrections (plan §1.2 #5/#6):
 * membership IS enforced; item requirements count INVENTORY + WORN only (the hub
 * counted the bank — an apron in the bank passed the check and the module walked
 * to a gate it could not enter); the never-used varbit/varplayer maps are gone.
 */
@Getter
public class MiningLocation {
    private final WorldPoint worldPoint;
    private final String name;
    private final boolean membersOnly;
    private final Map<Quest, QuestState> requiredQuests;
    private final Map<Skill, Integer> requiredSkills;
    private final Map<Integer, Integer> requiredItems; // itemId -> amount; must be worn or carried

    /** Aprons the Crafting Guild gate accepts — and only when WORN. */
    private static final java.util.Set<Integer> APRON_IDS =
            java.util.Set.of(ItemID.BROWN_APRON, ItemID.GOLDEN_APRON);

    public MiningLocation(WorldPoint worldPoint, String name, boolean membersOnly) {
        this(worldPoint, name, membersOnly, new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    public MiningLocation(WorldPoint worldPoint, String name, boolean membersOnly,
                          Map<Quest, QuestState> requiredQuests,
                          Map<Skill, Integer> requiredSkills,
                          Map<Integer, Integer> requiredItems) {
        this.worldPoint = worldPoint;
        this.name = name;
        this.membersOnly = membersOnly;
        this.requiredQuests = requiredQuests != null ? new HashMap<>(requiredQuests) : new HashMap<>();
        this.requiredSkills = requiredSkills != null ? new HashMap<>(requiredSkills) : new HashMap<>();
        this.requiredItems = requiredItems != null ? new HashMap<>(requiredItems) : new HashMap<>();
    }

    /** Requirement check: membership + quests + skills + carried/worn items. */
    public boolean hasRequirements() {
        if (Microbot.getClient() == null || !Microbot.isLoggedIn()) {
            return false;
        }
        if (membersOnly && !Rs2Player.isMember()) {
            return false;
        }
        boolean questsMet = requiredQuests.entrySet().stream().allMatch(entry -> {
            QuestState current = Rs2Player.getQuestState(entry.getKey());
            if (entry.getValue() == QuestState.FINISHED) {
                return current == QuestState.FINISHED;
            }
            if (entry.getValue() == QuestState.IN_PROGRESS) {
                return current == QuestState.IN_PROGRESS || current == QuestState.FINISHED;
            }
            return true;
        });
        if (!questsMet) {
            return false;
        }
        boolean skillsMet = requiredSkills.entrySet().stream()
                .allMatch(entry -> Rs2Player.getSkillRequirement(entry.getKey(), entry.getValue()));
        if (!skillsMet) {
            return false;
        }
        return requiredItems.entrySet().stream().allMatch(entry -> {
            // Aprons open the Crafting Guild gate only when WORN: a carried apron is not
            // accepted, and the golden apron works as well as the brown one.
            if (APRON_IDS.contains(entry.getKey())) {
                return APRON_IDS.stream().anyMatch(id -> Rs2Equipment.isWearing(id));
            }
            return Rs2Inventory.count(entry.getKey())
                    + (Rs2Equipment.isWearing(entry.getKey()) ? 1 : 0) >= entry.getValue();
        });
    }

    @Override
    public String toString() {
        return name;
    }
}
