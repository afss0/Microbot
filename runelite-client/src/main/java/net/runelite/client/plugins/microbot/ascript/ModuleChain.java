package net.runelite.client.plugins.microbot.ascript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

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
 * A step may carry config overrides in parentheses or brackets:
 * {@code FLETCHING(fletchingActivity=BOLTS,fletchingBoltType=MITHRIL)}.
 * {@link ScriptType#IDLE} is not a step.
 */
public final class ModuleChain {

    /** One configured chain step: a module plus optional config overrides. */
    public static final class Step {
        private final ScriptType type;
        private final Map<String, String> settings;

        public Step(ScriptType type, Map<String, String> settings) {
            if (type == null || type == ScriptType.IDLE) {
                throw new IllegalArgumentException("chain step must be a non-idle module");
            }
            this.type = type;
            this.settings = settings == null || settings.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        }

        public ScriptType getType() {
            return type;
        }

        /** Raw override values, still in their text form; the config layer validates types. */
        public Map<String, String> getSettings() {
            return settings;
        }

        /** Round-trip representation used by the sidebar field and preview. */
        public String toConfigText() {
            if (settings.isEmpty()) {
                return type.name();
            }
            StringJoiner overrides = new StringJoiner(",", "(", ")");
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                overrides.add(entry.getKey() + "=" + quote(entry.getValue()));
            }
            return type.name() + overrides;
        }

        @Override
        public String toString() {
            return toConfigText();
        }
    }

    private final List<Step> steps;
    private int index = -1;
    private boolean finished;

    /** Compatibility constructor for callers that only need module names. */
    public ModuleChain(List<ScriptType> steps) {
        this(toSteps(steps), true);
    }

    private ModuleChain(List<Step> steps, boolean ignored) {
        if (steps == null) {
            throw new IllegalArgumentException("steps");
        }
        this.steps = List.copyOf(steps);
    }

    private static List<Step> toSteps(List<ScriptType> types) {
        if (types == null) {
            throw new IllegalArgumentException("steps");
        }
        List<Step> result = new ArrayList<>();
        for (ScriptType type : types) {
            result.add(new Step(type, Map.of()));
        }
        return result;
    }

    static ModuleChain ofSteps(List<Step> steps) {
        return new ModuleChain(steps, true);
    }

    /**
     * Parse a configured chain. Blank text is an empty chain, not an error; an unknown token,
     * {@code IDLE}, or an unbalanced/duplicate override is rejected so a typo cannot silently
     * start the wrong module or apply the wrong setting.
     *
     * @throws IllegalArgumentException when the chain or an override is syntactically invalid
     */
    public static ModuleChain parse(String spec) {
        List<Step> parsed = new ArrayList<>();
        if (spec != null) {
            for (String token : splitTopLevel(spec, true)) {
                String raw = token.trim();
                if (raw.isEmpty()) {
                    continue;
                }
                parsed.add(parseStep(raw));
            }
        }
        return ofSteps(parsed);
    }

    private static Step parseStep(String raw) {
        int paren = raw.indexOf('(');
        int bracket = raw.indexOf('[');
        int open;
        char openChar;
        char closeChar;
        if (paren >= 0 && (bracket < 0 || paren < bracket)) {
            open = paren;
            openChar = '(';
            closeChar = ')';
        } else if (bracket >= 0) {
            open = bracket;
            openChar = '[';
            closeChar = ']';
        } else {
            if (raw.indexOf(')') >= 0 || raw.indexOf(']') >= 0) {
                throw new IllegalArgumentException("unbalanced chain step '" + raw + "'");
            }
            return new Step(parseType(raw), Map.of());
        }

        int close = findClosing(raw, open, openChar, closeChar);
        if (close < 0 || !raw.substring(close + 1).trim().isEmpty()) {
            throw new IllegalArgumentException("unbalanced chain step '" + raw + "'");
        }
        String module = raw.substring(0, open).trim();
        if (module.isEmpty()) {
            throw new IllegalArgumentException("missing module before override in '" + raw + "'");
        }
        String inside = raw.substring(open + 1, close);
        return new Step(parseType(module), parseSettings(inside));
    }

    private static int findClosing(String raw, int open, char openChar, char closeChar) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == openChar) {
                depth++;
            } else if (c == closeChar) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static Map<String, String> parseSettings(String inside) {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String token : splitTopLevel(inside, false)) {
            String raw = token.trim();
            if (raw.isEmpty()) {
                continue;
            }
            int equals = raw.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("override must be key=value: '" + raw + "'");
            }
            String key = raw.substring(0, equals).trim();
            String value = unquote(raw.substring(equals + 1).trim());
            if (key.isEmpty() || key.contains(" ")) {
                throw new IllegalArgumentException("invalid override key '" + key + "'");
            }
            if (settings.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate override '" + key + "'");
            }
        }
        return settings;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            return value.substring(1, value.length() - 1);
        }
        if ((!value.isEmpty() && (value.charAt(0) == '"' || value.charAt(0) == '\''))
                || (!value.isEmpty() && (value.charAt(value.length() - 1) == '"'
                || value.charAt(value.length() - 1) == '\''))) {
            throw new IllegalArgumentException("unbalanced quotes in override value '" + value + "'");
        }
        return value;
    }

    private static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        if (value.matches("[A-Za-z0-9_.:/+@-]+")) {
            return value;
        }
        return "\"" + value.replace("\"", "") + "\"";
    }

    private static List<String> splitTopLevel(String input, boolean splitArrow) {
        List<String> result = new ArrayList<>();
        if (input == null) {
            return result;
        }
        StringBuilder token = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote != 0) {
                token.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                token.append(c);
            } else if (c == '(' || c == '[') {
                depth++;
                token.append(c);
            } else if (c == ')' || c == ']') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("unbalanced chain text");
                }
                token.append(c);
            } else if (depth == 0 && (c == ',' || c == ';' || c == '\n'
                    || (splitArrow && c == '>'))) {
                addToken(result, token);
            } else {
                token.append(c);
            }
        }
        if (quote != 0 || depth != 0) {
            throw new IllegalArgumentException("unbalanced chain text");
        }
        addToken(result, token);
        return result;
    }

    private static void addToken(List<String> result, StringBuilder token) {
        String value = token.toString().trim();
        if (!value.isEmpty()) {
            result.add(value);
        }
        token.setLength(0);
    }

    private static ScriptType parseType(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT)
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
        return type;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public int size() {
        return steps.size();
    }

    /** Module types only; use {@link #entries()} when the overrides matter. */
    public List<ScriptType> steps() {
        return steps.stream().map(Step::getType).collect(Collectors.toUnmodifiableList());
    }

    public List<Step> entries() {
        return steps;
    }

    public boolean contains(ScriptType type) {
        return steps.stream().anyMatch(step -> step.getType() == type);
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

    /** The current module, or {@code null} before the first start. */
    public ScriptType current() {
        Step step = currentStep();
        return step == null ? null : step.getType();
    }

    /** The full current step, including its overrides, or {@code null}. */
    public Step currentStep() {
        return index < 0 ? null : steps.get(index);
    }

    /** Start at the first step; returns {@code null} for an empty chain. */
    public ScriptType start() {
        Step step = startStep();
        return step == null ? null : step.getType();
    }

    public Step startStep() {
        if (steps.isEmpty()) {
            return null;
        }
        index = 0;
        finished = false;
        return currentStep();
    }

    /** Start at an explicit module, for a manual selection inside the configured chain. */
    public ScriptType startAt(ScriptType type) {
        Step step = startAtStep(type);
        return step == null ? null : step.getType();
    }

    public Step startAtStep(ScriptType type) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getType() == type) {
                index = i;
                finished = false;
                return currentStep();
            }
        }
        return null;
    }

    /**
     * Advance to the next step. At the end the chain either wraps to the first step
     * ({@code loop}) or becomes finished and returns {@code null}.
     */
    public ScriptType advance(boolean loop) {
        Step step = advanceStep(loop);
        return step == null ? null : step.getType();
    }

    public Step advanceStep(boolean loop) {
        if (steps.isEmpty() || finished) {
            return null;
        }
        if (index < 0) {
            return startStep();
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
        return currentStep();
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
