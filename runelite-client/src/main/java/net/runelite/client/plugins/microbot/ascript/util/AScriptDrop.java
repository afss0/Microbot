package net.runelite.client.plugins.microbot.ascript.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.NaturalMouse;

import java.util.function.Predicate;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Sliced inventory dropping for aScript modules.
 * <p>
 * A full inventory is 28 slots, and every drop is one physical click
 * ({@link AScriptClick#dropAtSlot(int, int)} + {@link AScriptPick#dropRoute(Predicate)}). Dropping
 * everything inside one {@code doAction} would hold the tick for 6–12 s: blocking events would not
 * be dispatched and the click burst reads as a machine. This helper therefore drops a
 * <b>slice</b> — a randomised 5–9 slots — per call, paced with log-normal gaps, and verifies the
 * matching count actually fell. Callers keep their own drop-phase latch and call this once per
 * tick until the filter matches nothing.
 * <p>
 * The helper is <b>stateless</b>: one call is one slice. It does not restart itself. A module that
 * wants "drop these until clean" keeps a {@code dropping} flag, returns before its action branch
 * while the flag is set, and clears it when {@link Result#getRemaining()} reaches 0 or the outcome
 * is {@link Outcome#NOTHING_TO_DROP}.
 * <p>
 * Counts are <b>units</b> (stacked quantities summed), never slots — the same measure the
 * verification uses, so a stale mirror cannot make the slice look failed. The filter is also the
 * route predicate, so "what is counted" and "what is clicked" can never diverge. One Drop op
 * clears a whole stackable slot; non-stackable targets (logs, ore, fish) occupy one slot each.
 */
@Slf4j
public final class AScriptDrop {

    /** Default per-slice slot budget: short enough for one tick, large enough to finish in ~4 passes. */
    private static final int SLICE_MIN = 5;
    private static final int SLICE_MAX = 9;

    private static final int CLICK_GAP_MIN_MS = 120;
    private static final int CLICK_GAP_MAX_MS = 320;
    private static final int VERIFY_MIN_MS = 1_500;
    private static final int VERIFY_MAX_MS = 3_000;

    /**
     * Drop gap bands, split a third of the way up the range (50–140 fast, 140–320 slow for the
     * default bounds). A drop slice is a burst of the same tiny hop, so most gaps come from the
     * fast band; the slow band supplies the occasional longer pause. Both bands are sampled
     * with the shared {@link Rs2Random#logNormalBounded(int, int, double)}.
     */
    private static final int DROP_GAP_MIN_MS = 50;
    private static final int DROP_GAP_MAX_MS = 320;
    private static final double DROP_GAP_SPLIT_FRACTION = 1.0 / 3.0;

    /** Chance of the slow band at neutral weather (weather only ever raises it). */
    private static final double DROP_GAP_SLOW_CHANCE = 0.15;

    /** Ceiling for the slow-band chance, so even the worst weather cannot make every gap long. */
    private static final double DROP_GAP_SLOW_CHANCE_MAX = 0.55;

    /**
     * Cursor-speed multiplier applied while a slice is being clicked. A drop's travel time is
     * pure overhead (the same 20–40 px hop, 5–9 times per slice), so it runs at 3x; the click
     * gaps and hold times are left alone because those carry the anti-ban pacing.
     */
    private static final double DROP_SPEED_MULTIPLIER = 3.0;

    private AScriptDrop() {
        // static utility
    }

    /** How one slice ended. */
    public enum Outcome {
        /** Nothing in the inventory matched the filter — an empty state, never a failure. */
        NOTHING_TO_DROP,
        /** Drops were clicked and the matching unit count fell. Call again next tick if needed. */
        DROPPED,
        /** Drops were clicked (or no slot could be clicked) but the count did not fall. */
        NO_PROGRESS
    }

    /** Slice summary; {@code before}/{@code clicked}/{@code remaining} are unit counts. */
    @Getter
    @RequiredArgsConstructor
    public static final class Result {
        private final Outcome outcome;
        private final int before;
        private final int clicked;
        private final int remaining;
    }

    /**
     * Drop one slice of the items matching {@code filter} (default 5–9 slots), pacing the
     * clicks with the drop gap mixture (50–140 ms fast band, 140–320 ms slow band at 15%).
     *
     * @param filter           item predicate; also the route/count predicate
     * @param status           {@link Microbot#status} label, or {@code null} to leave it alone
     * @param weatherMultiplier {@code 1 / WeatherModulation.combinedSpeedFactor()} for the pacing
     *                          and verify windows
     * @return the slice summary — see {@link Outcome}
     */
    public static Result drop(Predicate<Rs2ItemModel> filter, String status, double weatherMultiplier) {
        return drop(filter, status, weatherMultiplier, SLICE_MIN, SLICE_MAX,
                DROP_GAP_MIN_MS, DROP_GAP_MAX_MS);
    }

    /**
     * {@link #drop(Predicate, String, double)} with an explicit slice budget — use a larger
     * {@code maxPerTick} only for small sets (see {@link #dropAll}). Keeps the generic
     * {@value #CLICK_GAP_MIN_MS}–{@value #CLICK_GAP_MAX_MS} ms click pacing.
     */
    public static Result drop(Predicate<Rs2ItemModel> filter, String status, double weatherMultiplier,
                              int minPerTick, int maxPerTick) {
        return drop(filter, status, weatherMultiplier, minPerTick, maxPerTick,
                CLICK_GAP_MIN_MS, CLICK_GAP_MAX_MS);
    }

    /**
     * The full form: slice budget and <b>both</b> click-gap bounds are caller-chosen.
     * <p>
     * A gap is a two-band mixture over {@code [gapMinMs, gapMaxMs]}: the band boundary sits a
     * third of the way up, and the fast band is picked unless the slow-band roll wins. The slow
     * band has a {@value #DROP_GAP_SLOW_CHANCE} chance in neutral weather, and <b>worse weather
     * raises that chance</b> (up to {@value #DROP_GAP_SLOW_CHANCE_MAX}), so a slow connection
     * reads as more frequent pauses rather than as uniformly longer gaps. Both bands come from
     * {@link Rs2Random#logNormalBounded(int, int, double)}, which skews toward the low end of
     * its band without forbidding the high end.
     *
     * @param gapMinMs shortest pause between two drop clicks (ms, at least 1)
     * @param gapMaxMs longest pause between two drop clicks (ms, before the weather stretch)
     * @throws IllegalArgumentException when {@code gapMaxMs < gapMinMs}
     */
    public static Result drop(Predicate<Rs2ItemModel> filter, String status, double weatherMultiplier,
                              int minPerTick, int maxPerTick, int gapMinMs, int gapMaxMs) {
        if (filter == null) {
            throw new IllegalArgumentException("filter");
        }
        if (gapMaxMs < gapMinMs) {
            throw new IllegalArgumentException("gapMaxMs must be >= gapMinMs");
        }
        final String label = status == null || status.isBlank() ? "drop" : status;
        int before = count(filter);
        if (before <= 0) {
            log.debug("[AScriptDrop] nothing to drop for '{}'", label);
            return new Result(Outcome.NOTHING_TO_DROP, 0, 0, 0);
        }
        if (status != null && !status.isBlank()) {
            Microbot.status = status;
        }

        int limit = sliceLimit(before, minPerTick, maxPerTick);
        int clicked = 0;
        // Cursor travel runs at 3x for the duration of this slice only: a drop is a
        // mechanically repetitive click burst whose travel time is pure overhead, unlike its
        // pacing (gap/hold/verify), which stays untouched. Restored in the finally so an
        // early return, an interrupt or a throw can never leak the boost into other actions.
        double previousSpeed = NaturalMouse.setSpeedMultiplier(DROP_SPEED_MULTIPLIER);
        try {
            // Precomputed route over the matching slots, shuffled once: no slot is drawn twice and
            // dropAtSlot re-reads the slot, so one emptied by an earlier drop is skipped.
            for (int slot : AScriptPick.dropRoute(filter)) {
                if (clicked >= limit) {
                    break;
                }
                Rs2ItemModel item = Rs2Inventory.getItemInSlot(slot);
                if (item == null || !filter.test(item)) {
                    continue;
                }
                if (!AScriptClick.dropAtSlot(slot, item.getId())) {
                    continue;
                }
                clicked++;
                AScriptSleep.sleepInterruptibly(dropGapMs(gapMinMs, gapMaxMs, weatherMultiplier));
            }
        } finally {
            NaturalMouse.setSpeedMultiplier(previousSpeed);
        }

        if (clicked == 0) {
            log.debug("[AScriptDrop] '{}' matched {} unit(s) but no slot could be clicked",
                    label, before);
            return new Result(Outcome.NO_PROGRESS, before, 0, before);
        }

        boolean landed = sleepUntil(() -> count(filter) < before,
                (int) Rs2Random.logNormalBounded(VERIFY_MIN_MS, VERIFY_MAX_MS, weatherMultiplier));
        int remaining = count(filter);
        if (!landed) {
            log.warn("[AScriptDrop] '{}' clicked {} slot(s) but the count did not fall ({} left)",
                    label, clicked, remaining);
            return new Result(Outcome.NO_PROGRESS, before, clicked, remaining);
        }
        return new Result(Outcome.DROPPED, before, clicked, remaining);
    }

    /**
     * One full pass over every matching slot — for small sets only (e.g. the gems in a Motherload
     * Mine load). Not for bulk inventories: it removes the slicer, so a 28-slot bag would hold the
     * tick again. Callers that need a bounded tick use {@link #drop(Predicate, String, double)}.
     */
    public static Result dropAll(Predicate<Rs2ItemModel> filter, String status, double weatherMultiplier) {
        return drop(filter, status, weatherMultiplier, Integer.MAX_VALUE, Integer.MAX_VALUE,
                DROP_GAP_MIN_MS, DROP_GAP_MAX_MS);
    }

    /**
     * One click gap from the two-band mixture described on
     * {@link #drop(Predicate, String, double, int, int, int, int)}: the fast band first, the slow
     * band on a weather-scaled roll. Both bands are drawn with the shared
     * {@link Rs2Random#logNormalBounded(int, int, double)}, so the pacing code path stays the one
     * every other module already uses.
     * <p>
     * The weather multiplier is clamped at 1.0: it may stretch a band's ceiling, never shorten it.
     */
    static int dropGapMs(int minMs, int maxMs, double weatherMultiplier) {
        int lo = Math.max(1, minMs);
        int hi = Math.max(lo, maxMs);
        int split = lo + (int) Math.round((hi - lo) * DROP_GAP_SPLIT_FRACTION);
        double weather = Math.max(1.0, weatherMultiplier);
        boolean slow = Rs2Random.diceFractional(slowGapChance(weather));
        return slow
                ? Rs2Random.logNormalBounded(split, hi, weather)
                : Rs2Random.logNormalBounded(lo, Math.max(split, lo), weather);
    }

    /**
     * Chance of taking the slow band: {@value #DROP_GAP_SLOW_CHANCE} in neutral weather, rising
     * with the weather multiplier and capped at {@value #DROP_GAP_SLOW_CHANCE_MAX} so even the
     * worst weather cannot turn every gap into a pause. Pure, so the response curve is testable.
     */
    static double slowGapChance(double weatherMultiplier) {
        return Math.min(DROP_GAP_SLOW_CHANCE_MAX,
                DROP_GAP_SLOW_CHANCE * Math.max(1.0, weatherMultiplier));
    }

    /** Units held across the filtered items, stacked quantities summed (never slot count). */
    public static int count(Predicate<Rs2ItemModel> filter) {
        return Rs2Inventory.items()
                .filter(filter)
                .mapToInt(Rs2ItemModel::getQuantity)
                .sum();
    }

    /**
     * Slots to click in this slice: {@code min(before, fancyNormalSample(min, max))}, with
     * {@code max == Integer.MAX_VALUE} meaning "all of them" ({@link #dropAll}). Pure so the cap
     * semantics stay unit-testable.
     */
    static int sliceLimit(int before, int minPerTick, int maxPerTick) {
        if (before <= 0) {
            return 0;
        }
        if (maxPerTick >= Integer.MAX_VALUE) {
            return before;
        }
        return Math.min(before, Rs2Random.fancyNormalSample(minPerTick, maxPerTick));
    }
}
