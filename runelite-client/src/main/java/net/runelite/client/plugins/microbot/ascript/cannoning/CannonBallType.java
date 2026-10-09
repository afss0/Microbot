package net.runelite.client.plugins.microbot.ascript.cannoning;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;

/**
 * Cannonball types the Cannoning module can load.
 * <p>
 * The cannon accepts both, and they are separate stacks: the module always works with
 * exactly the configured type — never a mix — so its inventory count and its "out of ammo"
 * verdict all describe one item.
 */
@Getter
@RequiredArgsConstructor
public enum CannonBallType {

    CANNONBALL("Cannonball", ItemID.STEEL_CANNONBALL),
    GRANITE_CANNONBALL("Granite cannonball", ItemID.GRANITE_CANNONBALL);

    private final String itemName;
    private final int itemId;

    @Override
    public String toString() {
        return itemName;
    }
}
