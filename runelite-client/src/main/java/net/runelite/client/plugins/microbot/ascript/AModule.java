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
     * True only when the needed item is missing from <em>both</em> inventory
     * and bank. Used by the orchestrator to decide between banking and stopping.
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

    /** Clear self-stop flags on NONE → active transition. */
    void resetExitFlag();

    /**
     * Whether this module needs VERY_LOW mouse speed for precise widget
     * interactions (jewelry, darts, bolts, etc.).
     * <p>
     * The orchestrator calls {@link net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban#setActivityIntensity}
     * with {@code ActivityIntensity.VERY_LOW} while any precision module is active.
     */
    boolean isPrecisionModule();
}
