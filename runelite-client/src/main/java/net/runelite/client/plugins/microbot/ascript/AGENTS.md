# aScript — AIO Multi-Script Orchestrator

AIO (All-In-One) script that hosts multiple automation scripts and QOL features under a single plugin. Each feature lives in its own sub-package under `ascript/`.

## Structure

```
ascript/
├── AScript.java              # Orchestrator — extends Script, MODULES registry + tick loop
├── AModule.java              # The contract every module implements
├── AScriptConfig.java        # Config with Automation / <Module> / QOL sections
├── AScriptPlugin.java        # Plugin descriptor, Guice wiring, start/stop
├── AScriptOverlay.java       # HUD overlay — shows the orchestrator phase + status
├── ScriptType.java           # Dropdown enum — which module to run
├── util/                     # Shared banking / notify / sleep helpers
└── <module>/                 # Each module is a sub-package
    ├── <Module>Script.java   # implements AModule — phase resolution, bank, action
    └── <enums>.java          # Module-specific enums (activities, items, locations)
```

## How it works

1. **AScript** extends `Script`. Its `Phase` enum (`DISABLED, IDLE, BANKING, CRAFTING, ERROR`) describes the *orchestrator* state for the overlay — modules keep their own private phase enums.
2. All modules live in one registry, in dispatch priority order: `AScript.MODULES = List.of(new CraftingScript(), ..., new <Module>Script())`.
3. Each tick: config check → `resolvePhase(config)` on every module (each stores its own phase) → `validateSelection` (first error stops the script) → find the single module that `needsBank` (open bank, stop when `isBankMissingMaterials`, otherwise `doBank`) → otherwise `doAction` on the first active module.
4. Each module is a plain class implementing `AModule` (not a `Script` subclass, no Guice, no lifecycle). The registry holds one instance for the whole session, so per-run mutable state (resolved phase, exit-once flags, failure counters) lives on that instance — keep it minimal and clear it in `resetExitFlag()`, which the orchestrator calls on every NONE → active transition.
5. The orchestrator never sees module-specific phase values: every `AModule` method takes the config and reads the module's own stored phase.

## Adding a new module

Follow the Crafting/Fletching packages as the reference template. Steps:

1. Create `ascript/<module>/` package.
2. Create `<Module>Script.java` — a **plain class implementing `AModule`** (no `Script` subclass, no Guice). The interface is the contract:
   - `enum Phase { NONE, ... }` — the module's private phases.
   - `void resolvePhase(AScriptConfig config)` — store the resolved phase on the instance; `NONE` when the module isn't selected or its activity is unset.
   - `boolean isActive()` — stored phase != `NONE`.
   - `String validateSelection(AScriptConfig config)` — an error string for invalid sub-selections (activity set but sub-type `NONE`), for missing capabilities (e.g. a Magic level below the spell requirement), or `null` when valid. Also return `"no <module> activity selected"` when the module is selected with no activity, so it surfaces a visible error instead of going silently IDLE (see `FletchingScript`). The orchestrator stops on the first error — this is the place for capability guards, not `doBank()`.
   - `boolean needsBank(AScriptConfig config)` — true when the inventory is missing a material/tool.
   - `boolean isBankMissingMaterials(AScriptConfig config)` — true only when the item is missing from **both** inventory and bank (see "Checking bank materials" below). `tick()` uses this to decide bank-vs-stop; `describeMissing` builds the stop message.
   - `String describeMissing(AScriptConfig config)` — human-readable list of what is missing (for the Discord stop message).
   - `boolean doBank(AScriptConfig config)` — banking; `false` on any failure (bank didn't open, withdraw didn't land, the configured tool is unavailable). If it returns `true`, `tick()` proceeds to `doAction()`, so returning `true` with an empty inventory creates an infinite loop.
   - `void doAction(AScriptConfig config)` — the action. Guard with an `exitRequested` flag so stop/notify fires once, verify the action actually landed, and count consecutive failures so a broken run stops instead of spinning forever.
   - `void resetExitFlag()` — clear `exitRequested`/counters when the module is re-enabled. AScript calls this automatically when it observes the module's phase transition NONE → active, so a module that self-stopped (e.g. "furnace not found") un-sticks on re-select without extra wiring.
   - `boolean isPrecisionModule()` — see step 7.
3. Add module-specific enums in the same package (activities, items, locations). Every enum value shown in a RuneLite config dropdown needs a `toString()`.
4. Add a `ScriptType` entry in `ScriptType.java` (e.g. `MYMODULE("My Module")`).
5. Add config items in `AScriptConfig.java` under a new `@ConfigSection(closedByDefault = true)`:
   - `ScriptType <module>Activity()` (default can be the `NONE` enum value)
   - sub-type configs (`<module>FooType()`, `<module>Afk()`, locations, etc.)
6. In `AScript.java`: add the module to the `MODULES` list — that is the entire wiring, `tick()` does not change. The orchestrator already handles per-module phase resolution, the NONE → active `resetExitFlag()` call, the `validateSelection` stop, opening the bank, the `isBankMissingMaterials` stop, the `consecutiveBankFailures` counter and the `doBank`/`doAction` dispatch. Stop paths go through `AScript.stopWithMessage(title, message)` — it handles the one-shot guard, Discord notify, bank close, exit-flag reset and config reset.
7. Return `true` from `AModule.isPrecisionModule()` when the module does precise widget/combine clicks (Crafting jewelry, Fletching darts/bolts) so the orchestrator forces `ActivityIntensity.VERY_LOW` while it is active. Modules whose work is inventory-slot clicking that tolerates imprecision (Jewel Enchant) return `false`.
8. **Update this AGENTS.md** — add the module to the "Existing modules" table and note any module-specific invariants.

### Banking / notify must use the shared utils

Do **not** re-implement deposit/withdraw/Discord logic. Use:

- `AScriptBank.depositAll()` or `AScriptBank.depositAndWaitEmpty()` for deposits (toolbar button — grid dies silently on some machines). `depositAndWaitEmpty()` waits until the inventory is empty **except for locked (tool) slots** — a held tool is never removed by the toolbar deposit, so "empty" means "no items in non-locked slots". When the bank is open and the inventory holds **only** locked-slot items, `depositAll()` skips the click and returns `true`: the toolbar button is a no-op there, and `Rs2Bank.depositAll()`'s trailing `waitForInventoryChanges(10_000)` would stall 10 s and report `false`.
- `AScriptBank.withdrawVerified(name)` for every withdraw (verifies it landed in inventory, returns `false` on miss/timeout). `name` may be an item name **or** a numeric id as a string.
- `AScriptBank.withdrawOneVerified(name)` for tools where exactly one is needed (chisel, knife, mould, needle, ...) — `withdrawOne` + verify.
- `AScriptBank.ensureToolLocked(toolName)` — the tool-lock pattern (see below).
- `AScriptBank.ensureStackLocked(name)` — the stack variant: locks the item's inventory slot like `ensureToolLocked`, but withdraws the WHOLE stack instead of one (the lock/unlock-stray rules are identical). Use for stackable *consumables* that must survive the blanket deposit as a persistent inventory anchor (Jewel Enchant locks the cosmic rune this way, mirroring the Crafting mould).
- `AScriptNotify.notify(title, message)` for any Discord notification.
- `AScriptSleep.sleepInterruptibly(ms)` for AFK/long delays instead of plain `sleep(ms)`. Blocking events are normally only dispatched between ticks from `Script.run()`, so a plain 120s AFK sleep delays their handling by the full duration; `sleepInterruptibly` polls `shouldBlockAndProcess()` (public manager API — no shared-code changes needed) and returns as soon as an event is pending/executing, with total duration unchanged when nothing happens.

### Tool locking (shared tools persist across modules)

Tools (chisel, glassblowing pipe, knife, mould, needle, costume needle, ...) must be held as a **single locked slot** in the inventory so blanket deposits never remove them. Use `AScriptBank.ensureToolLocked(toolName)`:

1. If the tool is already in the inventory, (re)lock its slot and return. **No extra withdraw, no deposit.**
2. Otherwise, for every currently locked slot that does **not** contain this tool, unlock it (the old tool from a previous module — e.g. switching Crafting mould → Fletching knife), then `depositAll()` (toolbar button, which ignores still-locked slots) to return the stray, `withdrawOneVerified(toolName)`, and lock the new tool's slot.

This guarantees **at most one tool** is ever withdrawn, and a tool switch (crafting→fletching) cleanly releases the old tool back to the bank before the new one is taken. Materials (unstrung bows, dart tips, molten glass, bars, gems, leather, thread, feathers, bow string, …) are NOT tools — withdraw them with `withdrawVerified` (all).

`AScriptBank.ensureStackLocked(name)` is the same pattern for a stackable **consumable** anchor: identical lock/unlock-stray handling, but the withdraw takes the whole stack (`withdrawVerified`) instead of one. Jewel Enchant uses it for cosmic runes, which behave like a tool slot for banking purposes but are consumed per cast — locking one rune would be pointless.

**Do not** call `Rs2Bank.depositAll(name)` (grid-targeted) to remove a stray tool — grid interactions die silently on some machines. Unlock the slot and let the toolbar `depositAll()` button handle it.

### Reference skeleton

```java
package net.runelite.client.plugins.microbot.ascript.mymodule;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;

import java.awt.Rectangle;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
public class MyModuleScript implements AModule {

    private static final int MAX_ACTION_FAILURES = 3;

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private int consecutiveFailures = 0;
    private double weatherMultiplier = 1.0;

    public enum Phase { NONE, FOO, BAR }

    // ── Contract ──────────────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null || config.scriptSelection() != ScriptType.MYMODULE
                || config.myModuleActivity() == null) {
            currentPhase = Phase.NONE;
            return;
        }
        switch (config.myModuleActivity()) {
            case FOO: currentPhase = Phase.FOO; return;
            default:  currentPhase = Phase.NONE;
        }
    }

    @Override
    public boolean isActive() { return currentPhase != Phase.NONE; }

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFailures = 0;
    }

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.MYMODULE)
            return "no mymodule activity selected";
        // sub-type checks and capability guards (skill levels, spellbook, ...) here
        return null;
    }

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        // inventory-only checks. For name-based material checks use
        // AScriptBank.hasUnnotedItem(name) so banknotes don't count as materials.
        return false;
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        // true only when missing from BOTH inventory and bank, and use the
        // AMOUNT overload for anything consumed per action:
        // Rs2Bank.hasBankItem(id, amount) — a partial stack is a stop, not a loop.
        return false;
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        return ""; // what is missing, for the Discord stop message
    }

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING";
        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) return false;
        if (!AScriptBank.depositAll()) return false;       // toolbar button, never grid
        if (!AScriptBank.withdrawVerified("some item")) {
            AScriptNotify.notify("Banking Failed", "No some item in bank");
            return false;
        }
        return true;                                        // false = never advance to doAction
    }

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;
        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();
        Microbot.status = "WORKING";

        // Clicks go through the client mouse: resolve the target bounds, click with
        // the mouse. Never inject menu entries (doInvoke / NewMenuEntry).
        Rs2ItemModel target = Rs2Inventory.get(/* configured item id */ 0);
        Rectangle bounds = target == null ? null : Rs2Inventory.itemBounds(target);
        if (bounds == null) {
            fail("no clickable slot for the target");
            return;
        }
        Microbot.getMouse().click(bounds);

        // Verify the action actually landed before treating it as success.
        boolean landed = sleepUntil(
                /* the inventory count dropped / the player animates */ () -> false,
                Rs2Random.logNormalBounded(1500, 3000, weatherMultiplier));
        if (!landed) {
            fail("action did not land");
            return;
        }
        consecutiveFailures = 0;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(300, 700, weatherMultiplier));
    }

    @Override
    public boolean isPrecisionModule() { return false; }

    // ── Helpers ───────────────────────────────────────────

    /** Counts failed attempts, backs off, and stops the module (with a notify) after MAX_ACTION_FAILURES. */
    private void fail(String reason) {
        consecutiveFailures++;
        log.warn("[MyModule] Attempt failed ({}/{}): {}", consecutiveFailures, MAX_ACTION_FAILURES, reason);
        if (consecutiveFailures >= MAX_ACTION_FAILURES) {
            exitRequested = true;
            Microbot.status = "STOPPED — " + reason;
            AScriptNotify.notify("aScript Stopped — My Module", reason);
            Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }
}
```

(Trimmed skeleton — it omits some imports (`Widget`, `Optional`) and the per-phase switch bodies; copy a real method from `CraftingScript` or `JewelEnchantScript` as the starting point.)

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

- **Modules must not extend Script.** They are plain `AModule` implementations called by the orchestrator.
- **Modules must not use Guice.** No `@Inject` — they receive config as a method parameter.
- **Keep module state minimal, per-run, and cleared.** The registry holds one instance for the whole session; mutable state (resolved phase, exit-once flag, failure counter) must be reset in `resetExitFlag()`.
- **Clicks go through the client mouse.** Use `Microbot.getMouse().click(bounds)` (VirtualMouse: natural-mouse movement + weather/error hooks), or a helper that already clicks that way (`Rs2Widget.clickWidget(...)`, `Rs2Inventory.itemBounds(...)` + mouse). Never inject menu entries (`Microbot.doInvoke` / `NewMenuEntry`) or call `Rs2Inventory.interact(...)` for a module's own action: injections skip the mouse and the mouse-speed/antiban modulation, and they cannot fail visibly — `Rs2Inventory.interact` returns `true` even when nothing happened. Bank *withdrawals* are the documented exception (see "Bank item-GRID interactions").
**Tick order matters.** High-priority checks (config null, missing materials) go first in `tick()`.
- **Use `Microbot.status`** for user-visible status updates inside module actions.
- **Use `sleepUntil(condition, timeoutMs)`** — never fixed `sleep()` to wait on game state.
- **Verify the action landed.** A click that returned `true` is not a completed action: confirm the state change (inventory count, animation, widget) and count consecutive failures, so a broken run stops with a message instead of spinning.
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

**Exception — nothing to deposit.** `AScriptBank.depositAll()` returns `true` *without clicking* when the bank is open and every occupied inventory slot is locked: the toolbar button ignores locked slots, so the click would be a no-op and `Rs2Bank.depositAll()`'s `waitForInventoryChanges(10_000)` would stall 10 s and then report `false` (which makes `doBank()` return `false` and retry forever — the MLM loop). The guard requires `Rs2Bank.isOpen()` because `isLockedSlot()` reads the `BANK_INVENTORY_ITEM_CONTAINER` widget, which only exists with the bank open; in the `ensureToolLocked` stray-release path the stray was just unlocked, so the guard does not fire and the toolbar deposit still runs.

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
| Motherload Mine | `ascript/motherloadmine/` | Mining veins, depositing hopper, emptying sack, fixing waterwheel |
| Gem Crab Killer | `ascript/gemcrabkiller/` | Combat — kill gem crabs, mine loot, bank at Tal Teklan |
| Barbarian Village Fisher | `ascript/barbarianvillagefisher/` | Fly/Bait fishing, Cook/Drop/Bank fish |
| Jewel Enchant | `ascript/jewellenchant/` | Enchant Jewellery (Lvl-1 through Lvl-7 Enchant spells) |

**GCK special case:** GemCrabKiller handles its own banking internally — the bank (Tal Teklan) is too far from the cave for the orchestrator's open-check-withdraw cycle. `needsBank()` returns false; the module's internal state machine transitions to BANKING when food runs out. The orchestrator calls `doAction()` every tick and GCK routes internally (WALKING→FIGHTING→BANKING→WAITING).

**Jewel Enchant notes:** The cosmic rune stack is held as a **locked inventory slot** (the mould pattern, `AScriptBank.ensureStackLocked`): `doBank()` locks it *before* the blanket deposit, so the deposit never removes it; when the stack is consumed empty the slot lock is gone and the next bank cycle withdraws the whole stack and re-locks it. Lvl-7 Enchant consumes blood + soul runes — no staff supplies those, so `staffCoverableRunes()` is empty for that level and they are always withdrawn; staff mode only skips the runes the *equipped* staff actually provides (per rune, not per level). A level that needs a staff with none worn and none in the bank stops with a message instead of looping. The action is two client-mouse clicks — the spellbook icon (bounds from widget 218/3 filtered by `MagicAction.getSprite()`, after opening the "Jewellery Enchantments" sub-tab) and the inventory slot (`Rs2Inventory.itemBounds`) — and success is confirmed by the jewellery count dropping or `Rs2Player.isAnimating()`, with a 3-attempt stop (`MAX_ENCHANT_FAILURES`).

**Enchant target slot:** the click targets the **last** occupied jewellery slot (`Rs2Inventory.getLast(id)` — highest slot index, since `items()` walks the container in ascending slot order), not the first match, and the slot is resolved **immediately before the click** (after the spellbook click and the tab switch), never at the top of `doAction`. The enchant spell consumes the **first** inventory item of the type it finds regardless of which item was selected, so a target resolved earlier is stale: by the time the bot's virtual-mouse click lands the chosen piece may already be enchanted, and casting on an already-enchanted item does nothing. Resolving late leaves only the mouse travel time in the race window.

**Barbarian Village Fisher notes:** Cooking never latches on an unconfirmed dispatch. `doCookingDispatch()` waits for the make-X box (`How many would you like to cook?`) and only then presses SPACE and sets `currentlyCookingID`/`cookingConfirmed`; if the box never appeared it returns `false` and the next tick retries. The XP wait itself has **no** animation precondition — cooking has no modelled animation, and `isAnimating(600)` misses the ~2.4 s per-fish cycle, which would re-open the make-X box every 1-2 ticks. The in-flight signals are used only to *interpret* a failed wait (`isCookingInFlight()` = `isInteracting()` — the direct one — or `isAnimating()`): in flight ⇒ re-wait without counting a failure, not in flight ⇒ release the latch and re-dispatch. Escalation is a **stagnation** deadline (60 s, reset on every XP received, because a full 26-fish batch is already ~62 s) and it only fires when nothing is in flight. Drop verification counts what is actually droppable — `countDroppableItems()` = raw + cooked (333/329/351) + burnt (343), never the rod/bait — because `countRawFish() == 0` is vacuously true in `COOK_AND_DROP` once the raw fish are cooked. The fishing click keeps a bounded transient-retry budget (`SPOT_CLICK_RETRIES`, backoff per attempt) that escalates to `fail()` at the ceiling, so a click that never lands stops the run instead of spinning. **Open:** the module's own interactions still go through menu injection (`Rs2Inventory.useItemOnObject`, `Rs2NpcModel.click`) contrary to the client-mouse rule above — the refactor to `Microbot.getMouse().click(...)` is pending, and until it lands an unreliable injection surfaces as `useItemOnObject did not open make-X dialog` instead of a silent stall.

### Module conventions (enforced for all modules)

Shared banking/notify logic lives in `ascript/util/`:
- `AScriptBank.depositAll()` / `depositAndWaitEmpty()` — toolbar button deposit (+ wait empty).
- `AScriptBank.withdrawVerified(name)` — withdraw-all + verify in inventory, `false` on miss/timeout.
- `AScriptNotify.notify(title, message)` — ORANGE Discord embed tagged "aScript".

Modules must call these instead of re-implementing them.

- **Banking must use the toolbar "Deposit inventory" button** (`AScriptBank.depositAll()`, no args) — never grid-targeted `depositAll(String/id)`. Grid interactions die silently on some machines (see "Bank item-GRID interactions" below).
- **Every withdraw must be verified** with `AScriptBank.withdrawVerified(name)` so a failed withdraw never advances to CRAFTING with an empty inventory.
- **Precision modules get `VERY_LOW` mouse speed.** `AScript.managePrecisionMouseSpeed()` applies `ActivityIntensity.VERY_LOW` while any active module returns `true` from `AModule.isPrecisionModule()` (Crafting and Fletching do widget/combine interactions) and restores the previous intensity on switch/stop. Flag a new module through that method — there is no `ScriptType` condition to extend any more.
- **Missing-materials / invalid-selection stops** must call `Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE)` with a `stopRequested`/`exitRequested` guard and a one-shot Discord notify (see "Stopping the script"). Fletching also self-escalates after 3 consecutive craft failures.

## Adding QOL features

QOL features don't run as automation scripts. Add config items under the QOL `@ConfigSection` in `AScriptConfig.java`. Implement them as event listeners or overlays registered in `AScriptPlugin.startUp()`.

### Implemented QOL features

| Feature | Config keys | Where |
|---------|-------------|-------|
| Auto zoom out | `autoZoomOut` | `tick()` step 1b — rate-limited to 60 s |
| Auto eat | `autoEat`, `autoEatMinHpPercent`, `autoEatMaxHpPercent` | `tick()` step 1c + `rollEatThreshold()` |

**Auto-eat invariants:**
- Skipped entirely for `ScriptType.GEM_CRAB_KILLER`: that module owns HP management (50% normal eat, 2% emergency, banks for food at 25%, Dharok mode holds HP at 10). A global 35–60% eat would double-eat in normal mode and break Dharok mode. `autoEatEnabled()` is the single gate.
- The threshold is rolled once (`rollEatThreshold()`), held across ticks, and **only re-rolled after a bite is CONFIRMED** — never per tick, and never on an unconfirmed dispatch.
- The bite goes through `eatBestFood()`, NOT `Rs2Player.eatAt(pct)`: `eatAt()` → `useFood()` eats the FIRST food in slot order (a shark in slot 3 always beats a summer pie in slot 20, whatever the heal). `eatBestFood()` keeps `useFood()`'s rules (unnoted only, blighted food first in the Wilderness, "jug of wine" is drunk) and picks the **largest per-bite heal** from `Rs2Food`; ties break towards the lowest slot, and food with no heal in the table (level-scaled/random heals) sorts last. `Rs2Food` is therefore the heal source of truth for the auto-eat priority — keep it in sync with the wiki's `Food/All food` (heal per bite) and `Food/Fast foods` (eat delays) tables.
- The Eat/Drink op being *dispatched* proves nothing: the dispatch ends in `Rs2Inventory.interact()`, which returns true unconditionally and can bail silently inside `invokeMenu()`. `waitForBite()` is the confirmation step — inventory food consumed (unnoted quantity) or hitpoints rose — and it is **not** `Rs2Player.waitForAnimation()`, which waits for ANY animation (the module's own action animation is normally already running) and can hold the 600 ms loop for ~10 s.
- `MAX_EAT_FAILURES` consecutive unconfirmed bites stop the script via `stopWithMessage()` — a broken safety net must be loud, not a silent wait every tick while hitpoints drain.
- A spell/item left selected on the cursor makes `Rs2Inventory.invokeMenu()` send the Eat as `WIDGET_TARGET_ON_WIDGET` (the spell is cast at the food, nothing is eaten). `eatReady()` cancels the pending selection the way the bank/deposit-box/GE utils do (`isItemSelected()` → `mouse.click()` at the current position, then wait for it to clear) and skips the attempt — without counting a strike — when it cannot be cleared. `Rs2Inventory.deselect()` does NOT work for this: it re-uses the selected widget's item id, which is -1 for a spell.
- The hitpoint check is `belowEatThreshold()` (level comparison), NOT `Rs2Player.getHealthPercentage()`: that helper divides by the real level, which reads 0 while skill data is still loading (each `getRealSkillLevel`/`getBoostedSkillLevel` call also falls back to 0 when the client-thread read times out), so the ratio comes back NaN (0/0) or Infinity — every comparison against it is false and the auto-eat silently does nothing. An unloaded level returns false here and the next tick retries.
- The hitpoint check runs before `eatReady()`, so the cancel-click never fires on a tick where no bite is due.
- Roll is normal-distributed between the min/max sliders (`Rs2Random.fancyNormalSample`), then shifted down by `(WeatherModulation.combinedSpeedFactor() − 1) × 15` HP% points (bad real-world weather → slower reactions → eats later; no-op when weather modulation is disabled globally).
- Result clamps to `[max(1, min−5), max]` — the floor must stay ≥ 1 or the threshold can silently disable eating.
- No food in the inventory is a cheap no-op (`eatAt` returns false before any wait).
- The `Eat` op does force the inventory tab (`Rs2Inventory.invokeMenu` → `Rs2Tab.switchToInventoryTab`). Not a concern for the tab-sensitive modules: only Gem Crab Killer carries food and it is excluded above, and the crafting/fletching/MLM/Jewel-Enchant modules have no damage source, so the block never dispatches an eat.
- `eatThreshold` and the strike count reset whenever auto-eat is inactive (`autoEatEnabled()` false — unchecked, or a food-owning module), so toggling the checkbox mid-run re-rolls from the current slider values.

## Checklist when modifying this module

- [ ] Code compiles: `./gradlew :client:compileJava`
- [ ] New enums/config items have `toString()` for RuneLite config UI
- [ ] Tick logic is clear: config check → phase resolve → validation → bank check → dispatch
- [ ] Module clicks use the client mouse — no `doInvoke` / `NewMenuEntry` / `interact(...)`
- [ ] `isPrecisionModule()` returns the right value, and `isBankMissingMaterials()` checks bank *amounts* for anything consumed per action
- [ ] Every stop path notifies (status + log + `AScriptNotify` + config reset), and repeated action failures escalate to a stop
- [ ] This AGENTS.md is updated with new modules, states, or config items
