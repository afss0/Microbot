package net.runelite.client.plugins.microbot.ascript.cannonballsmelter;

import net.runelite.api.gameval.ObjectID;

/**
 * Furnace locations supported by the cannonball smelter.
 * Migrated from the Microbot-Hub CannonballSmelter plugin (Furnace enum).
 */
public enum CannonballSmelterFurnace {
    EDGEVILLE("Edgeville", ObjectID.VARROCK_DIARY_FURNACE),
    SHILO_VILLAGE("Shilo Village", ObjectID.ZQFURNACE_LIT),
    PRIFDDINAS("Prifddinas", ObjectID.PRIF_FURNACE),
    PORT_PHASMATYS("Port Phasmatys", ObjectID.FAI_FALADOR_FURNACE);

    private final String name;
    private final int objectId;

    CannonballSmelterFurnace(String name, int objectId) {
        this.name = name;
        this.objectId = objectId;
    }

    public String getName() {
        return name;
    }

    public int getObjectId() {
        return objectId;
    }

    @Override
    public String toString() {
        return name;
    }
}
