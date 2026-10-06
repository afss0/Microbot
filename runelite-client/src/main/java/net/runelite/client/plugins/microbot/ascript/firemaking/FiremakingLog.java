package net.runelite.client.plugins.microbot.ascript.firemaking;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * Log tiers burnable on a fire/forester's campfire, with the Firemaking level each tier needs.
 * Every value needs a {@code toString()} — it is what the RuneLite config dropdown shows.
 * <p>
 * {@link #NONE} is the config default; {@link FiremakingScript#validateSelection} turns a
 * selected-but-unset module into a visible "no log type selected" stop.
 */
@Getter
@RequiredArgsConstructor
public enum FiremakingLog {
    NONE("None", -1, 1),
    LOGS("Logs", ItemID.LOGS, 1),
    OAK_LOGS("Oak logs", ItemID.OAK_LOGS, 15),
    WILLOW_LOGS("Willow logs", ItemID.WILLOW_LOGS, 30),
    TEAK_LOGS("Teak logs", ItemID.TEAK_LOGS, 35),
    ARCTIC_PINE_LOG("Arctic pine logs", ItemID.ARCTIC_PINE_LOG, 42),
    MAPLE_LOGS("Maple logs", ItemID.MAPLE_LOGS, 45),
    MAHOGANY_LOGS("Mahogany logs", ItemID.MAHOGANY_LOGS, 50),
    YEW_LOGS("Yew logs", ItemID.YEW_LOGS, 60),
    BLISTERWOOD_LOGS("Blisterwood logs", ItemID.BLISTERWOOD_LOGS, 62),
    CAMPHOR_LOGS("Camphor logs", ItemID.CAMPHOR_LOGS, 66),
    MAGIC_LOGS("Magic logs", ItemID.MAGIC_LOGS, 75),
    IRONWOOD_LOGS("Ironwood logs", ItemID.IRONWOOD_LOGS, 80),
    REDWOOD_LOGS("Redwood logs", ItemID.REDWOOD_LOGS, 90),
    ROSEWOOD_LOGS("Rosewood logs", ItemID.ROSEWOOD_LOGS, 92);

    private final String name;
    private final int itemId;
    private final int levelRequired;

    @Override
    public String toString() {
        return name;
    }
}
