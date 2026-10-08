package net.runelite.client.plugins.microbot.ascript.cannonballsmelter;

import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;

/**
 * Furnace locations supported by the cannonball smelter.
 * Migrated from the Microbot-Hub CannonballSmelter plugin (Furnace enum).
 * <p>
 * Each site carries the bank it smelts from. The sites were chosen because the bank
 * and the furnace sit in the same region, so that bank doubles as the module's walk
 * anchor: the module walks to {@link #getBankLocation()} and the furnace then resolves
 * from the loaded scene — no furnace coordinates are needed here.
 */
public enum CannonballSmelterFurnace {
    EDGEVILLE("Edgeville", ObjectID.VARROCK_DIARY_FURNACE, BankLocation.EDGEVILLE),
    SHILO_VILLAGE("Shilo Village", ObjectID.ZQFURNACE_LIT, BankLocation.SHILO_VILLAGE),
    PRIFDDINAS("Prifddinas", ObjectID.PRIF_FURNACE, BankLocation.PRIFDDINAS_NORTH),
    PORT_PHASMATYS("Port Phasmatys", ObjectID.FAI_FALADOR_FURNACE, BankLocation.PORT_PHASMATYS);

    private final String name;
    private final int objectId;
    private final BankLocation bankLocation;

    CannonballSmelterFurnace(String name, int objectId, BankLocation bankLocation) {
        this.name = name;
        this.objectId = objectId;
        this.bankLocation = bankLocation;
    }

    public String getName() {
        return name;
    }

    public int getObjectId() {
        return objectId;
    }

    /** The bank this site smelts from — also the module's walk anchor. */
    public BankLocation getBankLocation() {
        return bankLocation;
    }

    @Override
    public String toString() {
        return name;
    }
}
