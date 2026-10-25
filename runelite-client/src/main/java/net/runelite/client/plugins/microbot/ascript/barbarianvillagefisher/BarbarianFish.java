package net.runelite.client.plugins.microbot.ascript.barbarianvillagefisher;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import java.util.Arrays;

/**
 * The three fish the Barbarian Village river yields, with the id each processing stage uses.
 * <p>
 * One source of truth for the cook path: a cook dispatch may only ever select a
 * {@link #getRawId() raw} id. The cooked ids are the {@code CookingAction} entries
 * ({@code skillcalculator/skills/CookingAction.java}) and carry the same level/XP here, so
 * {@code BarbarianFishTest} can pin this table to that one; the raw counterparts come from
 * {@link ItemID} ({@code RAW_TROUT} 335 vs {@code TROUT} 333, {@code RAW_SALMON} 331 vs
 * {@code SALMON} 329, {@code RAW_PIKE} 349 vs {@code PIKE} 351).
 * <p>
 * Burnt fish are matched by <b>name</b>: every burnt variant (323/343/357/367/369) shares the
 * display name "Burnt fish", so the drop phase covers all of them instead of one hardcoded id —
 * and no burnt id can ever reach a cook dispatch through an id table.
 */
@Getter
@RequiredArgsConstructor
enum BarbarianFish {
    TROUT(ItemID.RAW_TROUT, ItemID.TROUT, 15, 70),
    SALMON(ItemID.RAW_SALMON, ItemID.SALMON, 25, 90),
    PIKE(ItemID.RAW_PIKE, ItemID.PIKE, 20, 80);

    /** Display name every burnt fish variant shares. */
    static final String BURNT_NAME = "Burnt fish";

    private final int rawId;
    private final int cookedId;
    private final int level;
    private final int xp;

    /** True when {@code id} is a raw fish this module may select for cooking. */
    static boolean isRaw(int id) {
        return Arrays.stream(values()).anyMatch(fish -> fish.rawId == id);
    }

    /** True when {@code id} is a raw or cooked fish (the burn-free drop set). */
    static boolean isRawOrCooked(int id) {
        return Arrays.stream(values()).anyMatch(fish -> fish.rawId == id || fish.cookedId == id);
    }

    /** True when {@code item} is any "Burnt fish" variant (name-matched; never cookable). */
    static boolean isBurnt(Rs2ItemModel item) {
        return item != null && item.getName() != null
                && BURNT_NAME.equalsIgnoreCase(item.getName());
    }

    /** Every raw id, in enum order — the only ids a cook dispatch may iterate. */
    static int[] rawIds() {
        return Arrays.stream(values()).mapToInt(BarbarianFish::getRawId).toArray();
    }
}
