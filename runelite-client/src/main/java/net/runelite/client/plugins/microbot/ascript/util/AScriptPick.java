package net.runelite.client.plugins.microbot.ascript.util;

import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;

import java.awt.Point;
import java.awt.Rectangle;
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

    // ── Drop-target decisions (the drop model) ─────────────────────

    /**
     * The FIRST draw of a drop batch — no cursor anchor yet, so the item is drawn UNIFORMLY
     * (never the lowest slot). Same as {@link #nextDropTarget(List, Point)} with
     * {@code cursor == null}.
     */
    public static Rs2ItemModel nextDropTarget(List<Rs2ItemModel> candidates) {
        return nextDropTarget(candidates, null);
    }

    /**
     * One drop-target decision for a batch being emptied.
     *
     * <p>Drops use a different model from the Use chain: every drop REMOVES its instance, so the
     * sequence — not the front-loading of a stack — is what carries the signature, and the draw
     * is uniform. {@code cursor == null} (the first draw of a batch), a cursor nowhere near the
     * batch, or a roll under {@link #dropHopChancePercent()} draws uniformly again; otherwise the
     * target is one of the {@link #dropNeighborCount()} candidates NEAREST the cursor — the human
     * "sweep the neighbours, then jump" rhythm. A uniform draw alone would break it: 27
     * corner-to-corner teleports in a row is its own tell.</p>
     *
     * @param candidates the instances still to drop (the caller prunes the returned one)
     * @param cursor     the current virtual-mouse position, or {@code null} with no anchor
     * @return the next target, or {@code null} when {@code candidates} is empty
     */
    public static Rs2ItemModel nextDropTarget(List<Rs2ItemModel> candidates, Point cursor) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        List<DropAnchor> anchors = anchors(candidates); // one slot-bounds read per candidate
        if (cursor == null || !anchorUsable(anchors, cursor) || rollDropHop()) {
            return candidates.get(Rs2Random.between(0, candidates.size()));
        }
        anchors.sort(Comparator.comparingDouble(anchor -> anchor.distanceSquared(cursor)));
        return anchors.get(Rs2Random.between(0, Math.min(dropNeighborCount(), anchors.size()))).item;
    }

    /**
     * Chance (%) that the next drop hops to a uniform pick instead of the candidate nearest the
     * cursor: 15% base plus the weather's erratic-click term
     * ({@link WeatherModulation#mistakeProbabilityOffset()} — rain up to 3 points, gusts above
     * 30 km/h up to 6), i.e. bad weather moves more erratically and hops more often. This is not
     * an input-latency term, so it uses the mistake offset, not the speed factor.
     */
    static double dropHopChancePercent() {
        final double baseHopPercent = 15.0;
        return baseHopPercent + 100.0 * WeatherModulation.mistakeProbabilityOffset();
    }

    /**
     * How many of the nearest candidates the "nearest" branch draws from: a 2..4 window whose
     * tendency sits on 4. {@link Rs2Random#logNormalBounded(int, int, double)} puts its mass at its
     * min, so the window is MIRRORED to move the mass to the far end — mostly 4, occasionally 3,
     * rarely 2.
     */
    static int dropNeighborCount() {
        final int nearestMin = 2;
        final int nearestMax = 4;
        return nearestMin + nearestMax
                - Rs2Random.logNormalBounded(nearestMin, nearestMax, 1.0);
    }

    private static boolean rollDropHop() {
        return Rs2Random.betweenInclusive(1, 100) <= dropHopChancePercent();
    }

    /** Slot anchors — one {@link Rs2Inventory#itemBounds} read per candidate, unreadable ones dropped. */
    private static List<DropAnchor> anchors(List<Rs2ItemModel> candidates) {
        List<DropAnchor> anchors = new java.util.ArrayList<>(candidates.size());
        for (Rs2ItemModel candidate : candidates) {
            Rectangle bounds = Rs2Inventory.itemBounds(candidate);
            if (bounds != null) {
                anchors.add(new DropAnchor(candidate, bounds));
            }
        }
        return anchors;
    }

    /** True when the cursor sits within roughly two slot widths of a candidate. */
    private static boolean anchorUsable(List<DropAnchor> anchors, Point cursor) {
        final double reachSlots = 2.0;
        for (DropAnchor anchor : anchors) {
            double reach = reachSlots * anchor.extent;
            if (anchor.distanceSquared(cursor) <= reach * reach) return true;
        }
        return false;
    }

    /** A candidate's slot geometry, resolved once per drop decision. */
    private static final class DropAnchor {

        private final Rs2ItemModel item;
        private final double centreX;
        private final double centreY;
        private final double extent;

        private DropAnchor(Rs2ItemModel item, Rectangle bounds) {
            this.item = item;
            this.centreX = bounds.getCenterX();
            this.centreY = bounds.getCenterY();
            this.extent = Math.max(bounds.getWidth(), bounds.getHeight());
        }

        private double distanceSquared(Point point) {
            double dx = centreX - point.getX();
            double dy = centreY - point.getY();
            return dx * dx + dy * dy;
        }
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
