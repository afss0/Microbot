package net.runelite.client.plugins.microbot.ascript.util;

import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared eating decisions for aScript and the modules that own their food.
 * <p>
 * The priority is the auto-eat rule: the biggest per-bite heal wins ({@link AScriptPick#pickFood}),
 * food with no heal entry ranks last, and only the INSTANCE inside the top-heal group is drawn.
 * The dispatch mirrors {@code Rs2Player.useFood()}: only unnoted food, blighted food first in
 * the Wilderness, and "jug of wine" is drunk rather than eaten. Callers own the HP threshold and
 * the confirmation/failure policy.
 */
public final class AScriptFood {

    /**
     * Outcome of a consume dispatch. Only {@link #ISSUED} means a press was made; the bite
     * itself is confirmed asynchronously by the caller.
     */
    public enum Dispatch {
        /** The Eat/Drink press was issued (effect unverified). */
        ISSUED,
        /** No edible item was supplied. */
        NO_FOOD,
        /** The visible inventory container exposes no Eat/Drink row for this item. */
        NO_ACTION,
        /** A pending cursor selection or an open menu prevented the press. */
        BLOCKED
    }

    private AScriptFood() {
    }

    /**
     * The food instance a module should eat right now, or {@code null} when no edible food is
     * held. Never returns a banknote.
     */
    public static Rs2ItemModel bestFood() {
        List<Rs2ItemModel> foods = Rs2Inventory.getInventoryFood().stream()
                .filter(food -> food != null && !food.isNoted())
                .collect(Collectors.toList());
        if (foods.isEmpty()) {
            return null;
        }
        if (Microbot.getVarbitValue(Varbits.IN_WILDERNESS) == 1) {
            List<Rs2ItemModel> blighted = foods.stream()
                    .filter(food -> food.getName() != null
                            && food.getName().toLowerCase().contains("blighted"))
                    .collect(Collectors.toList());
            if (!blighted.isEmpty()) {
                foods = blighted;
            }
        }
        return AScriptPick.pickFood(foods);
    }

    /**
     * Dispatch the Eat/Drink op for {@code food} through the shared client-mouse recipe
     * ({@link AScriptClick#inventoryOptionAtSlot}): hover the real slot, promote the item's
     * own {@code Eat}/{@code Drink} row with the MES-style swap, verify it is on top, then
     * press. No fabricated menu entry and no {@code Rs2Inventory.invokeMenu}.
     * <p>
     * With the bank open the item's default left click is {@code Deposit}, so the swap is
     * mandatory there — a plain slot click would bank the food instead of eating it.
     * <p>
     * A {@link Dispatch#NO_ACTION} answer means the visible container exposed no consume row
     * (for example a shop or the Grand Exchange); the caller decides whether to close the
     * interface. The bite itself is never confirmed here — count/HP tracking stays with the
     * caller.
     */
    public static Dispatch dispatch(Rs2ItemModel food) {
        if (food == null || food.getName() == null) {
            return Dispatch.NO_FOOD;
        }
        // A pending spell/item selection turns the next inventory op into "use the selected
        // thing on the food" (casting at it) instead of an Eat/Drink.
        if (Rs2Inventory.isItemSelected() && !AScriptClick.clearSelection()) {
            return Dispatch.BLOCKED;
        }
        String option = food.getName().toLowerCase().contains("jug of wine") ? "Drink" : "Eat";
        AScriptClick.ItemClickResult result =
                AScriptClick.inventoryOptionAtSlot(food.getSlot(), food.getId(), option);
        if (result == AScriptClick.ItemClickResult.ISSUED) {
            return Dispatch.ISSUED;
        }
        if (result == AScriptClick.ItemClickResult.NO_ACTION) {
            return Dispatch.NO_ACTION;
        }
        return Dispatch.BLOCKED;
    }

    /** Total quantity of unnoted food in the inventory (banknotes cannot be eaten). */
    public static int edibleCount() {
        return Rs2Inventory.getInventoryFood().stream()
                .filter(food -> food != null && !food.isNoted())
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();
    }

    /**
     * NaN-safe HP-percentage check: a real-level read of 0 means skill data has not loaded
     * (or the client-thread read timed out) — that is "retry", not "below threshold".
     */
    public static boolean belowHpPercent(int percent) {
        int real = Rs2Player.getRealSkillLevel(Skill.HITPOINTS);
        if (real <= 0) {
            return false;
        }
        return (double) Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS) * 100 / real <= percent;
    }
}
