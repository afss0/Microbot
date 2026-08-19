# aScript — AIO Multi-Script Orchestrator

AIO (All-In-One) script that hosts multiple automation scripts and QOL features under a single plugin. Each feature lives in its own sub-package under `ascript/`.

## Structure

```
ascript/
├── AScript.java              # Orchestrator — extends Script, phase-based loop
├── AScriptConfig.java        # Config with Automation / <Module> / QOL sections
├── AScriptPlugin.java        # Plugin descriptor, Guice wiring, start/stop
├── AScriptOverlay.java       # HUD overlay — shows phase, status
├── ScriptType.java           # Dropdown enum — which module to run
└── <module>/                 # Each module is a sub-package
    ├── <Module>Script.java   # Stateless helper: phase resolution, bank, craft/action
    └── <enums>.java          # Module-specific enums (activities, items, locations)
```

## How it works

1. **AScript** extends `Script` with a `Phase` enum: `DISABLED, IDLE, BANKING, CRAFTING, ERROR`.
2. Each tick: check config → resolve module phase → check bank → dispatch to module.
3. Each module is a plain class (not a Script subclass) with stateless methods — no Guice, no lifecycle.
4. The orchestrator delegates to the module: `module.resolvePhase()`, `module.needsBank()`, `module.doBank()`, `module.doCraft()`.

## Adding a new module

1. Create `ascript/<module>/` package.
2. Create `<Module>Script.java` with these methods:
   - `Phase resolvePhase(AScriptConfig config)` — maps config activity to internal phase enum
   - `boolean needsBank(AScriptConfig config, Phase phase)` — true when materials are missing
   - `boolean doBank(AScriptConfig config, Phase phase)` — banking logic; return true when done
   - `void doCraft(AScriptConfig config, Phase phase)` — the main action
3. Add module-specific enums in the same package (activities, items, etc.).
4. Add a new `ScriptType` entry in `ScriptType.java`.
5. Add config items in `AScriptConfig.java` under a new `@ConfigSection`.
6. In `AScript.java`:
   - Add a field for the new sub-script instance
   - Add phase handling in `tick()` (reuse `IDLE/BANKING/CRAFTING`)
   - Add dispatch logic
7. **Update this AGENTS.md** with the new module's name, package, and methods.

## Config layout

```java
@ConfigGroup("aScript")
public interface AScriptConfig {
    // Automation section
    boolean enabled();           // Master on/off
    ScriptType scriptSelection(); // Dropdown — which module to run

    // <Module> section (one per module, closedByDefault = true)
    // ... module-specific settings ...

    // QOL section (placeholder)
}
```

When a module's section is `closedByDefault = true`, the user expands it manually in the config panel. The orchestrator reads the config and dispatches to the correct module.

## Rules

- **Modules must be stateless.** All mutable state lives in AScript or on the executor thread. Modules are helpers, not lifecycle participants.
- **Modules must not extend Script.** They are plain classes called by the orchestrator.
- **Modules must not use Guice.** No `@Inject` — they receive config as a method parameter.
**Tick order matters.** High-priority checks (config null, missing materials) go first in `tick()`.
- **Use `Microbot.status`** for user-visible status updates inside module actions.
- **Use `sleepUntil(condition, timeoutMs)`** — never fixed `sleep()` to wait on game state.
- **Use `Rs2Random` for all timing.** Never use `Random.nextInt()` or fixed `sleep()` for delays.

## Banking pitfalls

### Checking bank materials: inventory + bank, not just bank

**WRONG — checks only bank, ignores inventory:**
```java
boolean bankHasBar = Rs2Bank.hasItem(barId);
boolean bankHasMould = Rs2Bank.hasItem(mouldId);
if (!bankHasBar || !bankHasMould) return true; // BUG: mould in inventory but not bank -> false positive
```

**RIGHT — check inventory first, only fail if missing from BOTH:**
```java
// Only report "bank missing" if item is needed AND absent from both sources
if (!Rs2Inventory.hasItem(barId) && !Rs2Bank.hasItem(barId)) return true;
if (!Rs2Inventory.hasItem(mouldId) && !Rs2Bank.hasItem(mouldId)) return true;
```

**Why:** A tool (mould, chisel, needle) is often kept in inventory permanently. Checking only the bank reports "missing" when the tool is safe in inventory — causing false stops or infinite bank loops.

### Bank state management

- **IDLE** opens the bank to check stock. If materials exist, bank stays open for BANKING.
- **BANKING** uses the already-open bank. Does NOT re-open. Does NOT close on success.
- **IDLE** closes the bank only when transitioning to CRAFTING (inventory full) or stopping (no materials).
- **Never close the bank between IDLE <-> BANKING** — this causes open/close loops.

### doBank() return value

`doBank()` must return `false` if banking failed (bank didn't open, materials missing, withdraw failed). If it returns `true`, the tick proceeds to CRAFTING. Returning `true` when inventory is empty creates an infinite loop.

### Stopping the script

Use `Microbot.getConfigManager().setConfiguration("ascript", "scriptSelection", ScriptType.NONE)` to stop. Add a `stopRequested` flag to prevent repeated exit attempts while the config change propagates.

## Anti-detection with Rs2Random

All timing must use `Rs2Random` to produce human-like distributions. Anti-cheat systems flag uniform or fixed patterns.

### Required patterns

| What | Wrong | Right |
|------|-------|-------|
| AFK delay | `RANDOM.nextInt(57000)` | `Rs2Random.logNormalBounded(3000, 60000)` |
| Wait for animation | `sleep(3000); sleepUntil(...)` | `sleepUntil(..., Rs2Random.logNormalBounded(15000, 45000))` |
| Action cooldown | `sleep(800)` | `Rs2Random.waitEx(800, 200)` |
| Click imprecision | exact widget coords | `Rs2Random.randomPoint(center, 5, 2.0)` |
| Random decision | `Math.random() < 0.1` | `Rs2Random.diceFractional(0.1)` |

### Key methods

- **`Rs2Random.logNormalBounded(min, max)`** — right-skewed: mostly short delays, occasional long. Use for AFK and animation waits.
- **`Rs2Random.waitEx(mean, dev)`** — Gaussian-distributed wait. Use for action cooldowns.
- **`Rs2Random.truncatedGauss(min, max, cutoff)`** — bounded normal. Use for general randomness within range.
- **`Rs2Random.reactionTime()`** — log-normal human reaction time (120ms–2200ms). Use for input delays.
- **`Rs2Random.diceFractional(chance)`** — random boolean with probability. Use for AFK triggers, break decisions.

### Why log-normal for AFK?

Uniform `RANDOM.nextInt(60000)` produces a flat histogram — every duration equally likely. Humans cluster around short AFKs with a long tail of longer ones. `logNormalBounded(3000, 60000)` matches this shape and is harder to fingerprint.

### Weather modulation (Rs2Random + WeatherModulation)

All timing and mouse behavior is modulated by real-world weather data via `WeatherModulation` (Open-Meteo API, 30-min cache).

**What's weather-modulated:**
- **Timing** — AFK delays, animation waits via `logNormalBounded(min, max, multiplier)`
- **Mouse speed** — `NaturalMouse.getFactory()` applies `combinedSpeedFactor()` to movement time
- **Click position** — `Rs2UiHelper.getClickingPoint()` adds wind-based jitter via `windGustFactor()`
- **Click errors** — `VirtualMouse.click()` adds off-target offset via `mistakeProbabilityOffset()`
- **Overshoots** — `FactoryTemplates` adjusts overshoot count via `mistakeProbabilityOffset()`

**How it works:**
1. `WeatherModulation.ensureFresh()` — refreshes cache if stale (safe to call every tick)
2. `WeatherModulation.combinedSpeedFactor()` — returns ≤ 1.0 (slower in bad weather)
3. Invert to get a multiplier: `1.0 / combinedSpeedFactor()` = 1.0+ (longer waits)
4. Pass to `Rs2Random.logNormalBounded(min, max, multiplier)`

**Example:**
```java
WeatherModulation.ensureFresh();
double weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();
int afkMs = Rs2Random.logNormalBounded(3000, 60000, weatherMultiplier);
// Clear: ~3000–60000, Storm: ~3000–81000 (25% longer)
```

**API safety:** `ensureFresh()` has a 30-minute TTL cache. Calling it every tick is safe — it only hits the API when the cache expires.

**Factors available:**
- `combinedSpeedFactor()` — temp × wind × weather mood (use for wait times AND mouse speed)
- `breakLengthFactor()` — cold + rain → longer breaks
- `microBreakChanceOffset()` — heat + rain + gust → more micro-breaks
- `mistakeProbabilityOffset()` — rain + wind → more click errors AND overshoots
- `windGustFactor()` — wind → click position jitter

## Existing modules

| Module | Package | Activities |
|--------|---------|------------|
| Crafting | `ascript/crafting/` | Gem Cutting, Glassblowing, Staff Making, Flax Spinning, Dragon Leather, Jewelry |

## Adding QOL features

QOL features don't run as automation scripts. Add config items under the QOL `@ConfigSection` in `AScriptConfig.java`. Implement them as event listeners or overlays registered in `AScriptPlugin.startUp()`.

## Checklist when modifying this module

- [ ] Code compiles: `./gradlew :client:compileJava`
- [ ] New enums/config items have `toString()` for RuneLite config UI
- [ ] Tick logic is clear: config check → phase resolve → bank check → dispatch
- [ ] This AGENTS.md is updated with new modules, states, or config items
