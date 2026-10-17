package net.runelite.client.plugins.microbot.ascript.util;

import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Inventory-slot selection for the item a module is about to click — either end of a Use
 * chain (the selected item, or its target) and any other inventory item a click resolves to.
 *
 * <p>{@link Rs2Inventory#get} always returns the <b>lowest</b> matching slot, so a module
 * working a 14-item batch clicks the same screen position every cycle — a constant, not a
 * human pattern. {@code pick} returns the same item <i>type</i> with a randomized
 * <i>instance</i>: the candidate rank (rank 1 = lowest slot) is drawn with a log-normal
 * shape, so most picks land on the first occurrences with a tail that occasionally reaches
 * the last ones (n = 14: ranks 1-3 hold ~54% of the mass, the last rank ~2.5%).</p>
 *
 * <p><b>Contract:</b> this helper changes WHICH candidate is clicked, never WHICH candidates
 * qualify. The type is resolved with the plain {@link Rs2Inventory#get} rules — fuzzy name
 * matching, shortest-name tie-break and all — and only the instance is drawn, so a
 * converted call site cannot silently retarget a different item. Candidates are one item
 * type (one id); a weighted pick ACROSS types is not supported.</p>
 *
 * <p>Resolve the pick at the click site (immediately before the recipe), never at the top
 * of {@code doAction}: item models come from the event-refreshed container cache.</p>
 *
 * <p><b>Opt-outs are explicit and code-level</b> — {@link #first(int...)} / {@link #last(int)}
 * plus a comment giving the reason. A slot that is load-bearing for correctness must stay
 * deterministic and say so; a config toggle would let a user silently restore the constant
 * pattern. See {@code ascript/AGENTS.md}.</p>
 */
public final class AScriptPick {

    private AScriptPick() {
    }

    // ── Weighted pick (the module default) ─────────────────────────

    /**
     * Weighted pick among the items of the same type (same item id) as {@code reference}.
     *
     * @param reference the model whose TYPE is drawn from; {@code null} yields {@code null}
     * @return the picked model, or {@code null} when no candidate is in the inventory
     */
    public static Rs2ItemModel pick(Rs2ItemModel reference) {
        if (reference == null) return null;
        return pick(item -> item.getId() == reference.getId());
    }

    /**
     * Weighted pick among the items matching one of {@code ids} — the type resolution is
     * {@link Rs2Inventory#get(int...)}'s (first match wins), only the instance is drawn.
     */
    public static Rs2ItemModel pick(int... ids) {
        return pick(Rs2Inventory.get(ids));
    }

    /**
     * Weighted pick among the items matching {@code name} — the type resolution is
     * {@link Rs2Inventory#get(String)}'s (substring match, shortest name wins), only the
     * instance is drawn.
     */
    public static Rs2ItemModel pick(String name) {
        return pick(Rs2Inventory.get(name));
    }

    /**
     * Weighted pick among the items matching {@code name} exactly — the type resolution is
     * {@link Rs2Inventory#get(String, boolean)}'s, only the instance is drawn.
     */
    public static Rs2ItemModel pick(String name, boolean exact) {
        return pick(Rs2Inventory.get(name, exact));
    }

    /**
     * Weighted pick among the items matching {@code filter}. The caller guarantees the
     * filter selects a single item type.
     */
    public static Rs2ItemModel pick(Predicate<Rs2ItemModel> filter) {
        List<Rs2ItemModel> candidates = Rs2Inventory.getList(filter);
        if (candidates.isEmpty()) return null;
        // The rank weighting is only meaningful against slot order: rank 1 must be the
        // lowest slot. items() walks the container in ascending slot order today; the
        // explicit sort keeps the weighting self-contained if that ever changes.
        candidates.sort(Comparator.comparingInt(Rs2ItemModel::getSlot));
        return candidates.get(rank(candidates.size()) - 1);
    }

    // ── Documented opt-outs ────────────────────────────────────────

    /**
     * The lowest matching slot — the deterministic behaviour {@link Rs2Inventory#get} has.
     * Opt-out for a site where the FIRST occurrence is load-bearing; give the reason at the
     * call site.
     */
    public static Rs2ItemModel first(int... ids) {
        return Rs2Inventory.get(ids);
    }

    /**
     * The highest matching slot — opt-out for a site where the target must stay as late as
     * possible (Jewel Enchant: the armed spell consumes the first-of-type item, so an early
     * slot can already have been converted by the time the click lands). Give the reason at
     * the call site.
     */
    public static Rs2ItemModel last(int id) {
        return Rs2Inventory.getLast(id);
    }

    // ── Internals ──────────────────────────────────────────────────

    /**
     * 1-based rank of the candidate to click: a log-normal draw over {@code [1, n]}, which
     * concentrates on the first occurrences and keeps a tail into the last ones.
     *
     * <p>The three-argument {@link Rs2Random#logNormalBounded(int, int, double)} is
     * deliberate: the two-argument overload scales its ceiling by the weather multiplier and
     * can return a rank ABOVE {@code n}, and the raw body forces a non-positive minimum to 1
     * — so the index-0-based form ({@code logNormalBounded(0, n - 1)}) could never return
     * the first item. Slot choice is not timing: no weather term here. The clamp is
     * belt-and-braces against both.</p>
     */
    static int rank(int n) {
        if (n <= 1) return 1;
        return Math.min(n, Rs2Random.logNormalBounded(1, n, 1.0));
    }
}
