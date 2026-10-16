package net.runelite.client.plugins.microbot.ascript.woodcutting;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;

/**
 * Chopped tree types with their log item, level requirement and the composition action
 * that marks them choppable ("Chop down" / "Chop" / "Cut"). Every value needs a
 * {@code toString()} — it is what the RuneLite config dropdown shows.
 * <p>
 * {@link #NONE} is the config default; {@code validateSelection} turns a
 * selected-but-unset module into a visible "no tree type selected" stop.
 * <p>
 * Imports the plain {@code net.runelite.api.ItemID} (like the hub's own
 * {@code woodcutting.enums.WoodcuttingTree}): the gameval copy lacks
 * {@code ARCTIC_PINE_LOGS} (only the {@code _PYRE} variants), {@code THATCH_SPAR_*},
 * {@code BARK} and {@code SULLIUSCEP_CAP}. Both classes carry identical values for
 * the shared constants (verified 2026-10-15).
 */
@Getter
@RequiredArgsConstructor
public enum WoodcuttingTree {
    NONE("None", null, -1, 1, null),
    TREE("tree", "Logs", ItemID.LOGS, 1, "Chop down"),
    TIRANNWN_TREE("tree", "Logs", ItemID.LOGS, 1, "Chop down"),
    DYING_TREE("dying tree", "Logs", ItemID.LOGS, 1, "Chop down"),
    BURNT_TREE("burnt tree", "Charcoal", ItemID.CHARCOAL, 1, "Chop down"),
    JUNGLE_TREE("jungle tree", "Logs", ItemID.LOGS, 1, "Chop down"),
    ACHEY_TREE("achey tree", "Achey tree logs", ItemID.ACHEY_TREE_LOGS, 1, "Chop down"),
    LIGHT_JUNGLE("light jungle", "Thatch spar light", ItemID.THATCH_SPAR_LIGHT, 10, "Chop down"),
    OAK("oak tree", "Oak logs", ItemID.OAK_LOGS, 15, "Chop down"),
    MEDIUM_JUNGLE("medium jungle", "Thatch spar med", ItemID.THATCH_SPAR_MED, 20, "Chop down"),
    WILLOW("willow tree", "Willow logs", ItemID.WILLOW_LOGS, 30, "Chop down"),
    TEAK_TREE("teak tree", "Teak logs", ItemID.TEAK_LOGS, 35, "Chop down"),
    DENSE_JUNGLE("dense jungle", "Thatch spar dense", ItemID.THATCH_SPAR_DENSE, 35, "Chop down"),
    JATOBA_TREE("jatoba tree", "Jatoba logs", ItemID.JATOBA_LOGS, 40, "Chop down"),
    MATURE_JUNIPER("mature juniper tree", "Juniper logs", ItemID.JUNIPER_LOGS, 42, "Chop down"),
    MAPLE("maple tree", "Maple logs", ItemID.MAPLE_LOGS, 45, "Chop down"),
    HOLLOW_TREE("hollow tree", "Bark", ItemID.BARK, 45, "Chop down"),
    MAHOGANY("mahogany tree", "Mahogany logs", ItemID.MAHOGANY_LOGS, 50, "Chop down"),
    ARCTIC_PINE("arctic pine", "Arctic pine logs", ItemID.ARCTIC_PINE_LOGS, 54, "Chop down"),
    YEW("yew tree", "Yew logs", ItemID.YEW_LOGS, 60, "Chop down"),
    BLISTERWOOD("blisterwood tree", "Blisterwood logs", ItemID.BLISTERWOOD_LOGS, 62, "Chop"),
    SULLIUSCEP("sulliuscep", "Sulliuscep cap", ItemID.SULLIUSCEP_CAP, 65, "Chop down"),
    CAMPHOR_TREE("camphor tree", "Camphor logs", ItemID.CAMPHOR_LOGS, 66, "Chop down"),
    MAGIC("magic tree", "Magic logs", ItemID.MAGIC_LOGS, 75, "Chop down"),
    IRONWOOD_TREE("ironwood tree", "Ironwood logs", ItemID.IRONWOOD_LOGS, 80, "Chop down"),
    REDWOOD("redwood tree", "Redwood logs", ItemID.REDWOOD_LOGS, 90, "Cut"),
    ROSEWOOD_TREE("rosewood tree", "Rosewood logs", ItemID.ROSEWOOD_LOGS, 92, "Chop down"),
    EVERGREEN_TREE("evergreen tree", "Logs", ItemID.LOGS, 1, "Chop down"),
    DEAD_TREE("dead tree", "Logs", ItemID.LOGS, 1, "Chop down"),
    INFECTED_ROOT("infected root", "Logs", ItemID.LOGS, 80, "Chop");

    private final String name;      // object name for the query
    private final String logName;   // display name of the log item
    private final int logId;        // ItemID of the log
    private final int levelRequired;
    private final String action;    // composition action — the choppability discriminator

    @Override
    public String toString() {
        if (this == TIRANNWN_TREE) {
            return "tree (tirannwn)";
        }
        return name;
    }
}
