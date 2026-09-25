package net.runelite.client.plugins.microbot.ascript.motherloadmine;

/**
 * Internal status tracking for the Motherload Mine mining loop.
 * Used by {@link MotherloadMineScript} to track which sub-phase
 * of the mining loop is currently active.
 */
public enum MLMStatus {
    IDLE,
    MINING,
    DEPOSIT_HOPPER,
    EMPTY_SACK,
    FIXING_WATERWHEEL,
    DROP_GEMS
}
