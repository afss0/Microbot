package net.runelite.client.plugins.microbot.ascript;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ScriptType {
    IDLE("Idle"),
    /**
     * Virtual selection: run the configured module chain. It is never a module step and is
     * resolved to the first step by the orchestrator before dispatch.
     */
    MODULE_CHAIN("Module Chain"),
    CRAFTING("Crafting"),
    FLETCHING("Fletching"),
    MOTHERLOAD_MINE("Motherload Mine"),
    GEM_CRAB_KILLER("Gem Crab Killer"),
    BARBARIAN_VILLAGE_FISHER("Barbarian Village Fisher"),
    JEWEL_ENCHANT("Jewel Enchant"),
    BOLT_ENCHANT("Bolt Enchant"),
    CANNONBALL_SMELTER("Cannonball Smelter"),
    CANNONING("Cannoning"),
    AMMONITE_CRABS("Ammonite Crabs"),
    FIREMAKING("Firemaking"),
    BARBARIAN_FISHING("Barbarian Fishing"),
    WOODCUTTING("Woodcutting"),
    MINING("Mining");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
