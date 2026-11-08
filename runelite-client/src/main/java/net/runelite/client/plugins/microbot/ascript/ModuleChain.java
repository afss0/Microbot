package net.runelite.client.plugins.microbot.ascript;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ordered list of aScript modules plus the cursor needed to walk it.
 * <p>
 * The chain is deliberately pure: it knows nothing about RuneLite, bank state or module
 * phases. {@link AScript} owns the session and supplies the current selection; this class only
 * answers "what is first", "what is next" and "has the run finished". Keeping the walk here
 * makes the edge cases (empty list, manual selection, loop, end of chain) unit-testable
 * without a client.
 * <p>
 * A chain is configured as a comma/semicolon/arrow separated list of {@link ScriptType}
 * enum names or display names: {@code MINING, CRAFTING}, {@code Motherload Mine > Crafting}.
 * {@link ScriptType#IDLE} is not a step.
 */
public final class ModuleChain {

    private final List<ScriptType> steps;
    private int index = -1;
    private boolean finished;

    public ModuleChain(List<ScriptType> steps) {
        if (steps == null) {
            throw new IllegalArgumentException("steps");
        }
        this.steps = List.copyOf(steps);
    }

    /**
     * Parse a configured chain. Blank text is an empty chain, not an error; an unknown token or
     * {@code IDLE} is rejected so a typo cannot silently start the wrong module.
     *
     * @throws IllegalArgumentException when a token is not a valid non-idle {@link ScriptType}
     */
    public static ModuleChain parse(String spec) {
        List<ScriptType> parsed = new ArrayList<>();
        if (spec != null) {
            for (String token : spec.split("[,;>\\n]+")) {
                String raw = token.trim();
                if (raw.isEmpty()) {
                    continue;
                }
                String normalized = raw.toUpperCase(Locale.ROOT)
                        .replaceAll("[^A-Z0-9]+", "_");
                ScriptType type;
                try {
                    type = ScriptType.valueOf(normalized);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("unknown module '" + raw + "'", ex);
                }
                if (type == ScriptType.IDLE) {
                    throw new IllegalArgumentException("IDLE is not a chain step");
                }
                parsed.add(type);
            }
        }
        return new ModuleChain(parsed);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public int size() {
        return steps.size();
    }

    public List<ScriptType> steps() {
        return steps;
    }

    public boolean contains(ScriptType type) {
        return steps.contains(type);
    }

    /** One-based position of the current step, or 0 when the chain has not started. */
    public int position() {
        return index < 0 ? 0 : index + 1;
    }

    /** Whether a step is selected and the walk has not finished. */
    public boolean isStarted() {
        return index >= 0 && !finished;
    }

    public boolean isFinished() {
        return finished;
    }

    /** The current step, or {@code null} before the first start. */
    public ScriptType current() {
        return index < 0 ? null : steps.get(index);
    }

    /** Start at the first step; returns {@code null} for an empty chain. */
    public ScriptType start() {
        if (steps.isEmpty()) {
            return null;
        }
        index = 0;
        finished = false;
        return current();
    }

    /** Start at an explicit step, for a manual selection inside the configured chain. */
    public ScriptType startAt(ScriptType type) {
        int found = steps.indexOf(type);
        if (found < 0) {
            return null;
        }
        index = found;
        finished = false;
        return current();
    }

    /**
     * Advance to the next step. At the end the chain either wraps to the first step
     * ({@code loop}) or becomes finished and returns {@code null}.
     */
    public ScriptType advance(boolean loop) {
        if (steps.isEmpty() || finished) {
            return null;
        }
        if (index < 0) {
            return start();
        }
        int next = index + 1;
        if (next >= steps.size()) {
            if (!loop) {
                finished = true;
                return null;
            }
            next = 0;
        }
        index = next;
        return current();
    }

    /** Mark the chain as exhausted; a finished chain cannot auto-start again this session. */
    public void finish() {
        finished = true;
        index = -1;
    }

    /** Forget the current session, keeping the configured steps. */
    public void reset() {
        index = -1;
        finished = false;
    }
}
