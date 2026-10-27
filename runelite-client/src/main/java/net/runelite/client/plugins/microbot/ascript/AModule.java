package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.plugins.microbot.ascript.AScriptConfig;

/**
 * Common interface for all aScript modules.
 * <p>
 * Each module manages its own Phase enum internally — the orchestrator never
 * sees module-specific phase values. After {@link #resolvePhase(AScriptConfig)}
 * is called each tick, the module stores the resolved phase and all subsequent
 * methods use it implicitly.
 * <p>
 * Adding a new module = implement this interface + register in {@code AScript}.
 * The orchestrator's tick logic does not change.
 */
public interface AModule {

    /**
     * Resolve the current phase from config and store it internally.
     * Must be called before any other method each tick.
     */
    void resolvePhase(AScriptConfig config);

    /** Whether the module is currently active (resolved phase != NONE). */
    boolean isActive();

    /**
     * Validate the current sub-selection.
     *
     * @return error string if invalid, or {@code null} when valid.
     */
    String validateSelection(AScriptConfig config);

    /** Whether the module needs bank access this tick. */
    boolean needsBank(AScriptConfig config);

    /**
     * Identifies the module's current sub-selection (activity, item, location, ...).
     * <p>
     * A change between ticks while the module stays active starts a NEW run scope: the
     * orchestrator calls {@link #resetExitFlag()} so a finished batch latch, a previous
     * activity's anchors or failure streak cannot leak into the newly selected activity.
     * Modules without sub-selections keep the default (selection-level) key.
     */
    default String runKey(AScriptConfig config) {
        return "";
    }

    /**
     * True only when the needed item cannot be supplied for the bank cycle: missing from the
     * inventory (unnoted <b>and</b> noted — a banknote is un-noted by depositing it) and from
     * the bank. Used by the orchestrator to decide between banking and stopping. Prefer
     * {@code AScriptBank.bankableAtLeast(...)} over hand-written inventory/bank pairs.
     */
    boolean isBankMissingMaterials(AScriptConfig config);

    /** Human-readable description of missing items for the stop message. */
    String describeMissing(AScriptConfig config);

    /**
     * Execute banking. Returns {@code true} on success (inventory filled),
     * {@code false} on any failure (bank didn't open, withdraw failed).
     * <p>
     * <b>Must return {@code false}</b> on failure — the orchestrator counts
     * consecutive failures and stops after a threshold.
     */
    boolean doBank(AScriptConfig config);

    /** Execute the module's main action (craft, mine, fight, etc.). */
    void doAction(AScriptConfig config);

    /** Clear self-stop flags on IDLE → active transition. */
    void resetExitFlag();

    /**
     * Whether the orchestrator may run the bank open/cache cycle for this module on
     * this tick.
     * <p>
     * The first bank phase exists to open the bank and populate the cached contents
     * that {@link #isBankMissingMaterials} and the module's own bank queries read.
     * A module that must prepare first — walk to the bank area, let a blocking event
     * finish — returns {@code false} until it is ready; the orchestrator then skips
     * the bank phase and dispatches {@link #doAction(AScriptConfig)} instead, so the
     * module can do that preparation.
     * <p>
     * A module returning {@code false} while {@link #needsBank} is true owns that
     * preparation: it must keep it bounded and stop loudly on stagnation — it must
     * never idle silently. Returning {@code false} forever is the same defect class as
     * {@link #needsBank} being wrong.
     * <p>
     * Default {@code true}: open the bank on the first tick (historic behaviour).
     */
    default boolean readyForBank(AScriptConfig config) {
        return true;
    }
}
