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
2. Each tick: check config → resolve module phase → validate selection → check bank → dispatch to module.
3. Each module is a plain class (not a Script subclass) — no Guice, no lifecycle. Prefer stateless methods; if per-run mutable state is unavoidable (e.g. AFK timers, exit-once flags), keep it in the module instance and document it here.
4. The orchestrator delegates to the module: `module.resolvePhase()`, `module.validateSelection()`, `module.needsBank()`, `module.doBank()`, `module.doCraft()`.

## Adding a new module

Follow the Crafting/Fletching packages as the reference template. Steps:

1. Create `ascript/<module>/` package.
2. Create `<Module>Script.java` — a **plain stateless helper** (no `Script` subclass, no Guice). It must expose:
   - `enum Phase { NONE, ... }` — internal phases for the module's activities.
   - `Phase resolvePhase(AScriptConfig config)` — map `config.<module>Activity()` to a `Phase`; return `NONE` when the module isn't selected or activity is unset.
   - `String validateSelection(AScriptConfig config, Phase phase)` — return an error string for invalid sub-selections (e.g. activity set but sub-type `NONE`), or `null` when valid. Also return `"no <module> activity selected"` when `phase == NONE && config.scriptSelection() == ScriptType.<MODULE>` (so a module picked with no activity surfaces a visible error instead of going silently IDLE — see `FletchingScript`).
   - `boolean needsBank(AScriptConfig config, Phase phase)` — true when inventory is missing a material/tool.
   - `boolean isBankMissingMaterials(AScriptConfig config, Phase phase)` — true only when the item is missing from **both** inventory and bank (see "Checking bank materials" below). `tick()` uses this to decide stop-vs-bank.
   - `String describeMissing(AScriptConfig config, Phase phase)` — human-readable list of what's missing (for the Discord stop message).
   - `boolean doBank(AScriptConfig config, Phase phase)` — banking; return `false` on any failure (bank didn't open, withdraw didn't land). If it returns `true`, `tick()` proceeds to `doCraft()`, so returning `true` with an empty inventory creates an infinite loop.
   - `void doCraft(AScriptConfig config, Phase phase)` — the action. Guard with an `exitRequested` flag so stop/notify fires once.
   - `void resetExitFlag()` — clear `exitRequested`/counters when the module is re-enabled.
3. Add module-specific enums in the same package (activities, items, locations). Every enum value shown in a RuneLite config dropdown needs a `toString()`.
4. Add a `ScriptType` entry in `ScriptType.java` (e.g. `MYMODULE("My Module")`).
5. Add config items in `AScriptConfig.java` under a new `@ConfigSection(closedByDefault = true)`:
   - `ScriptType <module>Activity()` (default can be the `NONE` enum value)
   - sub-type configs (`<module>FooType()`, `<module>Afk()`, locations, etc.)
6. In `AScript.java`:
   - Add a field `private final <Module>Script <module>Script = new <Module>Script();` and a `private <Module>Script.Phase <module>Activity = <Module>Script.Phase.NONE;`
   - In `tick()`: resolve the phase, fold its `validateSelection` into `selectionError`, compute `needsBank`/`isBankMissingMaterials`, and add dispatch branches in the existing `if/else if` chain (reuse `IDLE/BANKING/CRAFTING`).
   - On stop paths, call `if (<module>Activity != <Module>Script.Phase.NONE) <module>Script.resetExitFlag();`
7. If the module does precise widget/combine clicks (like Crafting jewelry and Fletching), extend the `precisionModuleActive` condition in `AScript.manageCraftingMouseSpeed()` to include `ScriptType.<MODULE>` so it gets `VERY_LOW` mouse speed.
8. **Update this AGENTS.md** — add the module to the "Existing modules" table and note any module-specific invariants.

### Banking / notify must use the shared utils

Do **not** re-implement deposit/withdraw/Discord logic. Use:

- `AScriptBank.depositAll()` or `AScriptBank.depositAndWaitEmpty()` for deposits (toolbar button — grid dies silently on some machines).
- `AScriptBank.withdrawVerified(name)` for every withdraw (verifies it landed in inventory, returns `false` on miss/timeout). `name` may be an item name **or** a numeric id as a string.
- `AScriptBank.withdrawOneVerified(name)` for tools where exactly one is needed (chisel, knife, mould, needle, ...) — `withdrawOne` + verify.
- `AScriptBank.ensureToolLocked(toolName)` — the tool-lock pattern (see below).
- `AScriptNotify.notify(title, message)` for any Discord notification.

### Tool locking (shared tools persist across modules)

Tools (chisel, glassblowing pipe, knife, mould, needle, costume needle, ...) must be held as a **single locked slot** in the inventory so blanket deposits never remove them. Use `AScriptBank.ensureToolLocked(toolName)`:

1. If the tool is already in the inventory, (re)lock its slot and return. **No extra withdraw, no deposit.**
2. Otherwise, for every currently locked slot that does **not** contain this tool, unlock it (the old tool from a previous module — e.g. switching Crafting mould → Fletching knife), then `depositAll()` (toolbar button, which ignores still-locked slots) to return the stray, `withdrawOneVerified(toolName)`, and lock the new tool's slot.

This guarantees **at most one tool** is ever withdrawn, and a tool switch (crafting→fletching) cleanly releases the old tool back to the bank before the new one is taken. Materials (unstrung bows, dart tips, molten glass, bars, gems, leather, thread, feathers, bow string, …) are NOT tools — withdraw them with `withdrawVerified` (all).

**Do not** call `Rs2Bank.depositAll(name)` (grid-targeted) to remove a stray tool — grid interactions die silently on some machines. Unlock the slot and let the toolbar `depositAll()` button handle it.

### Reference skeleton

```java
package net.runelite.client.plugins.microbot.ascript.mymodule;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;

@Slf4j
public class MyModuleScript {

    private boolean exitRequested = false;

    public enum Phase { NONE, FOO, BAR }

    public void resetExitFlag() { exitRequested = false; }

    public Phase resolvePhase(AScriptConfig config) {
        if (config == null || config.scriptSelection() != ScriptType.MYMODULE
                || config.myModuleActivity() == null) return Phase.NONE;
        return switch (config.myModuleActivity()) {
            case FOO -> Phase.FOO;
            case BAR -> Phase.BAR;
            default  -> Phase.NONE;
        };
    }

    public String validateSelection(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE && config.scriptSelection() == ScriptType.MYMODULE)
            return "no mymodule activity selected";
        // add sub-type NONE checks here
        return null;
    }

    public boolean needsBank(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        // check inventory for materials; return true if missing
        return false;
    }

    public boolean isBankMissingMaterials(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config, phase)) return false;
        // check inventory AND bank; return true only if missing from both
        return false;
    }

    public String describeMissing(AScriptConfig config, Phase phase) {
        return ""; // list missing items for the stop message
    }

    public boolean doBank(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING";
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            if (!Rs2Bank.isOpen()) return false;
        }
        // deposit via toolbar button, then withdrawVerified each item
        if (!AScriptBank.depositAndWaitEmpty()) return false;
        if (!AScriptBank.withdrawVerified("some item")) {
            AScriptNotify.notify("Banking Failed", "No some item in bank");
            return false;
        }
        return true;
    }

    public void doCraft(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn() || exitRequested) return;
        WeatherModulation.ensureFresh();
        double weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();
        // ... perform the action using AScriptBank / Rs2* helpers ...
        sleep(Rs2Random.logNormalBounded(800, 1600));
    }
}
```

(Minish the skeleton — it omits `Rs2Bank` import and per-phase switch bodies; copy a real method from `CraftingScript`/`FletchingScript` as the starting point.)

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

### openBank() return value

`Rs2Bank.openBank()` returns `false` when it fails (no bank in range, lag, blocked). **Never ignore the return value before checking stock** — `Rs2Bank.hasItem()` reads a cached snapshot (`rs2BankData`) that may be empty/stale when the bank never opened, causing false "missing materials" stops. Pattern:

```java
if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
    // retry next tick — do NOT run material checks against an unopened bank
    return;
}
```

### doBank() return value

`doBank()` must return `false` if banking failed (bank didn't open, materials missing, withdraw failed). If it returns `true`, the tick proceeds to CRAFTING. Returning `true` when inventory is empty creates an infinite loop.

### Bank item-GRID interactions can die silently — deposit via the toolbar button

On some machines, **any** interaction targeting the bank's item grids fails silently: injected CC_OP entries (`Rs2Bank.depositAll(id)` op 8, `depositX` ops 2/6/7) AND raw physical clicks on the slot widget all do nothing — mouse fires, no deposit, no exception, no `MenuOptionClicked`. Verified live via Agent Server (menu entry logging on): withdraw-side grid entries work fine, toolbar BUTTON clicks work fine, only grid targets die. Suspected coordinate/scaling issue with dynamic item widgets on that display setup.

Symptom when it hits a full inventory (crafted jewelry + mould): every withdraw also fails, unchecked `sleepUntil`s time out invisibly, module returns `true` → infinite BANKING↔CRAFTING loop with zero messages.

**Rule:** in aScript modules, deposit with `Rs2Bank.depositAll()` (no args) — it raw-clicks the "Deposit inventory" toolbar button (a static button widget, proven to work) and waits for inventory changes. Protect tool slots (mould/chisel/pipe) from the blanket deposit by locking them first: `Rs2ItemModel tool = Rs2Inventory.get(toolId); if (tool != null && !Rs2Bank.isLockedSlot(tool.getSlot())) Rs2Bank.toggleItemLock(tool.getName(), true);` — locks persist account-wide and the check makes it a no-op after the first cycle. Locking needs "bank slot locking" enabled in the player's bank settings; if the injected lock op dies on grid-silent machines, keep going — re-withdraw the tool next cycle. Check every bank-step `sleepUntil(...)` result and return `false` on timeout so the tick retries instead of looping blind.

### Furnace lookup — hardcoded ID 16469

`craftJewelry()` finds the furnace via `Rs2GameObject.findObjectById(16469)` — a hardcoded ID covering the supported jewelry locations, not a name search. Consequences:
- A game update that changes the furnace object ID breaks jewelry silently: `findObjectById` returns null → walk-to-location retry → still null → script deactivates itself once with a Discord notification ("Furnace (ID 16469) not found"). Check Discord/status before assuming a code bug.
- New jewelry locations must either share this furnace ID or the lookup needs extending (ID set or per-location IDs in `JewelryLocation`).

### Stopping the script

Use `Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE)` to stop. The group key is `"aScript"` (capital S) — use the `AScriptConfig.GROUP` constant, never a string literal: a wrong-case group writes to a nonexistent config and fails silently. Add a `stopRequested` flag to prevent repeated exit attempts while the config change propagates.

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
| Fletching | `ascript/fletching/` | Darts, Bolts, Arrows, Bows (string) |

### Module conventions (enforced for all modules)

Shared banking/notify logic lives in `ascript/util/`:
- `AScriptBank.depositAll()` / `depositAndWaitEmpty()` — toolbar button deposit (+ wait empty).
- `AScriptBank.withdrawVerified(name)` — withdraw-all + verify in inventory, `false` on miss/timeout.
- `AScriptNotify.notify(title, message)` — ORANGE Discord embed tagged "aScript".

Modules must call these instead of re-implementing them.

- **Banking must use the toolbar "Deposit inventory" button** (`AScriptBank.depositAll()`, no args) — never grid-targeted `depositAll(String/id)`. Grid interactions die silently on some machines (see "Bank item-GRID interactions" below).
- **Every withdraw must be verified** with `AScriptBank.withdrawVerified(name)` so a failed withdraw never advances to CRAFTING with an empty inventory.
- **Precision modules get `VERY_LOW` mouse speed.** `AScript.manageCraftingMouseSpeed()` applies `ActivityIntensity.VERY_LOW` while `scriptSelection` is `CRAFTING` or `FLETCHING` (both do widget/combine interactions) and restores the previous intensity on switch/stop. Extend the `precisionModuleActive` condition when adding a module that also needs precise clicks.
- **Missing-materials / invalid-selection stops** must call `Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE)` with a `stopRequested`/`exitRequested` guard and a one-shot Discord notify (see "Stopping the script"). Fletching also self-escalates after 3 consecutive craft failures.

## Adding QOL features

QOL features don't run as automation scripts. Add config items under the QOL `@ConfigSection` in `AScriptConfig.java`. Implement them as event listeners or overlays registered in `AScriptPlugin.startUp()`.

## Checklist when modifying this module

- [ ] Code compiles: `./gradlew :client:compileJava`
- [ ] New enums/config items have `toString()` for RuneLite config UI
- [ ] Tick logic is clear: config check → phase resolve → bank check → dispatch
- [ ] This AGENTS.md is updated with new modules, states, or config items
