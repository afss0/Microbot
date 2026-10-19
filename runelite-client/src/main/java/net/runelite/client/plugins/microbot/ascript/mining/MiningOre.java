package net.runelite.client.plugins.microbot.ascript.mining;

import lombok.Getter;
import net.runelite.api.gameval.ItemID;

/**
 * Mineable ore types with their rock name, product item ids and level requirement.
 * Full hub parity (16 values incl. the Varlamore salts). Every value needs a
 * {@code toString()} — it is what the RuneLite config dropdown shows.
 * <p>
 * {@link #NONE} is the config default; {@code validateSelection} turns a
 * selected-but-unset module into a visible "no ore selected" stop. Products do not
 * stack (ores/gems); salts may — see the plan's Risks.
 */
@Getter
public enum MiningOre {
    NONE("None", "None", 1, new int[0]),
    TIN("tin rocks", "Tin ore", 1, new int[]{ItemID.TIN_ORE}),
    COPPER("copper rocks", "Copper ore", 1, new int[]{ItemID.COPPER_ORE}),
    CLAY("clay rocks", "Clay", 1, new int[]{ItemID.CLAY}),
    IRON("iron rocks", "Iron ore", 15, new int[]{ItemID.IRON_ORE}),
    SILVER("silver rocks", "Silver ore", 20, new int[]{ItemID.SILVER_ORE}),
    COAL("coal rocks", "Coal", 30, new int[]{ItemID.COAL}),
    GOLD("gold rocks", "Gold ore", 40, new int[]{ItemID.GOLD_ORE}),
    GEM("gem rocks", "Uncut gems", 40, new int[]{
            ItemID.UNCUT_OPAL, ItemID.UNCUT_JADE, ItemID.UNCUT_RED_TOPAZ,
            ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND}),
    MITHRIL("mithril rocks", "Mithril ore", 55, new int[]{ItemID.MITHRIL_ORE}),
    ADAMANTITE("adamantite rocks", "Adamantite ore", 70, new int[]{ItemID.ADAMANTITE_ORE}),
    BASALT("Basalt rocks", "Basalt", 72, new int[]{ItemID.BASALT}),
    // Salts (Salt Mine beneath Weiss, Varlamore): the ROCK names are hub-parity; the product
    // ids are wiki-verified — Urt salt = GREEN_SALT (22597), Te salt = RED_SALT (22593),
    // Efh salt = BLUE_SALT (22595). The colour follows the salt's examine, not the rock name.
    URT_SALT("Urt salt rocks", "Urt salt", 72, new int[]{ItemID.GREEN_SALT}),
    EFH_SALT("Efh salt rocks", "Efh salt", 72, new int[]{ItemID.BLUE_SALT}),
    TE_SALT("Te salt rocks", "Te salt", 72, new int[]{ItemID.RED_SALT}),
    RUNITE("runite rocks", "Runite ore", 85, new int[]{ItemID.RUNITE_ORE});

    private final String rockName;    // object name for the query
    private final String displayName; // status line / stop messages
    private final int miningLevel;
    private final int[] productIds;   // inventory product(s) — GEM spans the uncut family

    MiningOre(String rockName, String displayName, int miningLevel, int[] productIds) {
        this.rockName = rockName;
        this.displayName = displayName;
        this.miningLevel = miningLevel;
        this.productIds = productIds;
    }

    /** True when {@code itemId} is one of this ore's inventory products. */
    public boolean isProduct(int itemId) {
        for (int id : productIds) {
            if (id == itemId) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
