package net.runelite.client.plugins.microbot.ascript.fletching;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * One "knife on logs" fletching product.
 * <p>
 * The four log activities (unstrung bows, arrow shafts, crossbow stocks and shields) share the
 * same recipe — a knife used on a log — and differ only in the product id, the level gate, how
 * many logs one action consumes, and how many products one action yields (arrow shafts yield
 * 15–105 per log; everything else yields one).
 * <p>
 * Implementing this interface keeps {@code FletchingScript}'s bank/craft/verify logic generic
 * and keeps every material/product id in one place per enum.
 */
public interface LogFletchProduct {

    /** The log consumed by the action. */
    int logId();

    /** The item the production interface creates. */
    int productId();

    /** Fletching level required for the action. */
    int levelRequirement();

    /** Logs consumed per action (2 for shields, 1 for everything else). */
    int logsPerAction();

    /** Products created per action (15–105 for arrow shafts, 1 for everything else). */
    int productsPerAction();

    /** Config/status label. Display only — behaviour is keyed by the ids above. */
    String label();

    /** True when this value is a real, selected product (the NONE placeholder returns false). */
    default boolean isConfigured() {
        return logId() > 0 && productId() > 0;
    }

    /** Level gate; an unloaded level (0) passes and the next tick re-checks. */
    default boolean meetsLevelRequirement() {
        int level = Rs2Player.getRealSkillLevel(Skill.FLETCHING);
        return level <= 0 || level >= levelRequirement();
    }
}
