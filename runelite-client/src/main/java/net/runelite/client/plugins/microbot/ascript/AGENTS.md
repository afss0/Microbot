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
   - `boolean readyForBank(AScriptConfig config)` — defaults to `true`; return `false` while the module still has preparation to do before banking is meaningful (typically: it must walk to the bank area). `tick()` then skips the bank open/cache cycle and dispatches `doAction()` so the module can prepare, and the bank phase runs on a later tick, once this reports ready. A module that returns `false` while `needsBank` is true owns that preparation: bound it and stop loudly on stagnation — returning `false` forever is the same defect class as a wrong `needsBank` (see "The bank phase gate" below).
   - `boolean isBankMissingMaterials(AScriptConfig config)` — true only when the item is missing from **both** inventory and bank (see "Checking bank materials" below). `tick()` uses this to decide bank-vs-stop; `describeMissing` builds the stop message.
   - `String describeMissing(AScriptConfig config)` — human-readable list of what is missing (for the Discord stop message).
   - `boolean doBank(AScriptConfig config)` — banking; `false` on any failure (bank didn't open, withdraw didn't land, the configured tool is unavailable). The tick dispatches `doAction()` on a later tick, never in the same bank-dispatch branch; returning `true` while `needsBank()` stays true creates a BANKING loop, so the state must actually change (materials landed, tool held).
   - `void doAction(AScriptConfig config)` — the action. Guard with an `exitRequested` flag so stop/notify fires once, verify the action actually landed, and count consecutive failures so a broken run stops instead of spinning forever.
   - `void resetExitFlag()` — clear `exitRequested`/counters when the module is re-enabled. AScript calls this automatically when it observes the module's phase transition NONE → active, so a module that self-stopped (e.g. "furnace not found") un-sticks on re-select without extra wiring. It is also called when `runKey(config)` changes while the module stays active — a different activity/item/location starts a new run scope, so batch latches, anchors and failure streaks from the old scope are cleared.
   - `String runKey(AScriptConfig config)` — optional (default stable). Return the activity/item/location values that define a batch scope for this module (e.g. activity + sub-type + location). Do NOT include runtime phases (walking/fighting/dropping): they change every tick and would reset the module continuously.
3. Add module-specific enums in the same package (activities, items, locations). Every enum value shown in a RuneLite config dropdown needs a `toString()`.
4. Add a `ScriptType` entry in `ScriptType.java` (e.g. `MYMODULE("My Module")`).
5. Add config items in `AScriptConfig.java` under a new `@ConfigSection(closedByDefault = true)`:
   - `ScriptType <module>Activity()` (default can be the `NONE` enum value)
   - sub-type configs (`<module>FooType()`, `<module>Afk()`, locations, etc.)
6. In `AScript.java`: add the module to the `MODULES` list — that is the entire wiring, `tick()` does not change. The orchestrator already handles per-module phase resolution, the NONE → active `resetExitFlag()` call, the `runKey` scope change, the `validateSelection` stop, the `readyForBank` gate on the bank phase, opening the bank, the `isBankMissingMaterials` stop, the `consecutiveBankFailures`/bank-open-failure counters and the `doBank`/`doAction` dispatch. The orchestrator's own stops use `AScript.stopWithMessage(title, message)`; a module self-stop sets `scriptSelection = NONE` through its local stop helper, and the orchestrator's disabled branch restores the bank withdraw mode and closes a bank the run left open.
7. Mouse speed is global (fork customization): `Rs2Antiban.activityIntensity` is pinned to `VERY_LOW` and `setActivityIntensity` is a no-op (`Rs2AntibanIntensityLockTest` guardrail). Modules carry no mouse-speed code — the old per-module `isPrecisionModule()` machinery was removed.
8. **Update this AGENTS.md** — add the module to the "Existing modules" table and note any module-specific invariants.

### Banking / notify must use the shared utils

Do **not** re-implement deposit/withdraw/Discord logic. Use:

- `AScriptBank.depositAll()` or `AScriptBank.depositAndWaitEmpty()` for deposits (toolbar button — grid dies silently on some machines). `depositAndWaitEmpty()` waits until the inventory is empty **except for locked (tool) slots** — a held tool is never removed by the toolbar deposit, so "empty" means "no items in non-locked slots". When the bank is open and the inventory holds **only** locked-slot items, `depositAll()` skips the click and returns `true`: the toolbar button is a no-op there, and `Rs2Bank.depositAll()`'s trailing `waitForInventoryChanges(10_000)` would stall 10 s and report `false`.
- `AScriptBank.withdrawVerified(name)` for every withdraw (verifies the inventory count ROSE, so a copy already held cannot mask a failed withdraw; returns `false` on miss/timeout). `name` may be an item name **or** a numeric id as a string.
- `AScriptBank.withdrawOneVerified(name)` for tools where exactly one is needed (chisel, knife, mould, needle, ...) — routes through the verified amount path (`withdrawX(name, 1)`), never `Rs2Bank.withdrawOne` (whose menu op 1/2 is `Withdraw-5` unless the bank quantity setting is "1", and which was a dead dispatch on at least one live client).
- **Withdraw verification is patient, not one-shot.** The `withdrawVerified*` helpers verify in two windows (3 s main + 2 s grace — a landing that is merely late under transient lag is caught by the grace window) and an unconfirmed dispatch is retried up to 3 attempts total with exponential backoff (≈0.8 s / 1.6 s, jittered). Each retry waits the backoff and re-checks the inventory **before** re-clicking, so a slow landing is confirmed instead of double-clicked; the bank window is never closed/re-opened across attempts. The `Failed to withdraw` WARN fires only after all attempts are exhausted (late-landing recoveries log at DEBUG).
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
import net.runelite.client.plugins.microbot.ascript.util.AScriptPick;
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
        // the mouse. Never inject menu entries (doInvoke / NewMenuEntry). The item comes
        // from AScriptPick.pick(...) — randomized instance, same type — never
        // Rs2Inventory.get(...), which always clicks the lowest matching slot.
        Rs2ItemModel target = AScriptPick.pick(/* configured item id */ 0);
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
- **Clicks go through the client mouse.** Use `Microbot.getMouse().click(bounds)` (VirtualMouse: natural-mouse movement + weather/error hooks), or a helper that already clicks that way (`Rs2Widget.clickWidget(...)`, `Rs2Inventory.itemBounds(...)` + mouse). Never inject menu entries (`Microbot.doInvoke` / `NewMenuEntry`) or call `Rs2Inventory.interact(...)` for a module's own action. The residue is not a missing cursor — the `doInvoke`-with-bounds path does move the cursor and click through `VirtualMouse.click(point, entry)` — it is the **fabricated menu entry** (the client's own menu is replaced, so the action can dispatch from a pixel that does not cover the target) plus the **silent failure**: the call returns `true` even when nothing happened, and it bypasses the mouse-speed/antiban modulation. Bank *withdrawals* are the documented exception (see "Bank item-GRID interactions").
**Tick order matters.** High-priority checks (config null, missing materials) go first in `tick()`.
- **Use `Microbot.status`** for user-visible status updates inside module actions.
- **Use `sleepUntil(condition, timeoutMs)`** — never fixed `sleep()` to wait on game state.
- **Verify the action landed.** A click that returned `true` is not a completed action: confirm the state change (inventory count, animation, widget) and count consecutive failures, so a broken run stops with a message instead of spinning.
- **Use `Rs2Random` for all timing.** Never use `Random.nextInt()` or fixed `sleep()` for delays.
- **Always use `...OnClientThread()` variants.** When an API exposes an `...OnClientThread()` overload, use it: game updates block client-state access outside the client thread, so the plain variant can compile yet fail in-game.

## Banking pitfalls

### Checking bank materials: inventory + bank + banknotes, not just bank

A banknote is a **certificate** for the base item with a different item id; it cannot be used, equipped, eaten, crafted or fletched with. `Rs2ItemModel.isNoted()` identifies it and `Rs2ItemModel.getUnNotedId()` maps it back to the base id (RuneLite's `ItemComposition.getLinkedNoteId()` relationship — never assume `id + 1`). Stackable items cannot be noted, so ammo, runes, feathers and bait never appear as banknotes.

- **`needsBank` counts only unnoted inventory** (`AScriptBank.hasUnnotedItem`, `AScriptFletching.inventoryQuantity`, or id checks): a banknote must trigger the bank trip.
- **The missing-materials verdict counts a banknote as available**, because the bank cycle's toolbar deposit stores its base item in the bank. Use `AScriptBank.bankableAtLeast(idOrName, amount)` = unnoted inventory + noted inventory (via `getUnNotedId`) + bank.
- **Name-based inventory checks match banknotes** (they share the display name): `Rs2Inventory.hasItem(name)` / `get(name)` / `count(name)` can report a note as usable. Use the id form, `AScriptBank.hasUnnotedItem`, or `AScriptPick` (which never returns a note).
- **Withdrawals must be unnoted.** `AScriptBank.ensureItemWithdrawMode()` switches the bank to "Withdraw as item" for the bank phase (and the orchestrator restores the player's note setting afterwards). `AScriptBank.getInventoryItem`/`hasInventoryItem` are note-blind, so a noted withdrawal cannot verify as success.
- **`doBank` must un-note before the withdraw**: materials/food deposit first (`depositAndWaitEmpty`), tools go through `ensureToolLocked`/`ensureStackLocked` (their internal deposit un-notes a certificate), and Woodcutting/Mining deposit once before the bank axe/pickaxe lookup when the only tool is noted.

**WRONG — checks only bank, ignores inventory and notes:**
```java
boolean bankHasBar = Rs2Bank.hasItem(barId);
boolean bankHasMould = Rs2Bank.hasItem(mouldId);
if (!bankHasBar || !bankHasMould) return true; // BUG: mould in inventory but not bank -> false positive
```

**RIGHT — one availability check that includes inventory, banknotes and bank:**
```java
if (!AScriptBank.bankableAtLeast(barId, 1)) return true;
if (!AScriptBank.bankableAtLeast(mouldId, 1)) return true;
```

**Why:** A tool (mould, chisel, needle) is often kept in inventory permanently, and a material may exist only as banknotes. Checking only the bank reports "missing" when the item is safe in the inventory or recoverable by banking — causing false stops or infinite bank loops.

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

### doBank ordering: withdraws before equips; re-verify the window before each step

- Do every withdraw that needs the bank window **before** any equip/wear step: equips interact with the bank UI, and with nothing left to withdraw a mid-equip state change cannot break the cycle (Jewel Enchant's staff equip used to run before the rune/jewellery withdraws).
- Re-verify `Rs2Bank.isOpen()` before each withdraw step (Jewel Enchant's `ensureBankOpen()`: reopen once, else return `false`). A human at the VNC closing the bank mid-cycle leaves the remaining withdraws dispatching into nothing — `withdrawOne`/`withdrawAll` return `false` with no click and no WARN — and the cycle died with a misleading "no materials" message (live 2026-10-14, Jewel Enchant: the user equipped the staff by hand and closed the bank, the ring withdraw failed 3× against the closed window, the module stopped saying "No Sapphire ring in bank" while the mirror said the ring was there). Treat human intervention mid-cycle as an expected condition, not an anomaly.
- Never raise an inline "No <item> in bank" notification from a failed withdraw: the withdraw can fail for non-missing reasons (closed window, lag). Return `false` (the orchestrator retries the cycle; three consecutive failures stop as "Bank Cycle Failed") and let the confirmed missing-materials verdict own that stop (see "Missing-materials verdicts are confirmed before they stop").

### Never scan a candidate list through the retrying bank reads

`Rs2Bank.hasBankItem` pays a 2-tick race-retry sleep for every item it MISSES (`bankItemRaceRetryWarranted` = count 0 && bank open), so scanning a 23-staff enum cost ~20 s of silent stall whenever the bank held just one of them — it reads as a frozen step, and a human watching took over mid-cycle (live 2026-10-14). Read ONE bank snapshot (`Rs2Bank.getAll()`) and match ids in memory; when the pass finds nothing, ONE re-read after ~2 ticks guards the stale-mirror case the retrying reads were built for. Same rule for any candidate-list lookup (tools, variants, runes) — reserve the retrying reads for single-item checks and stop verdicts.

### Bank item-GRID interactions can die silently — deposit via the toolbar button

On some machines, **any** interaction targeting the bank's item grids fails silently: injected CC_OP entries (`Rs2Bank.depositAll(id)` op 8, `depositX` ops 2/6/7) AND raw physical clicks on the slot widget all do nothing — mouse fires, no deposit, no exception, no `MenuOptionClicked`. Verified live via Agent Server (menu entry logging on): withdraw-side grid entries work fine, toolbar BUTTON clicks work fine, only grid targets die. Suspected coordinate/scaling issue with dynamic item widgets on that display setup.

Symptom when it hits a full inventory (crafted jewelry + mould): every withdraw also fails, unchecked `sleepUntil`s time out invisibly, module returns `true` → infinite BANKING↔CRAFTING loop with zero messages.

**Rule:** in aScript modules, deposit with `Rs2Bank.depositAll()` (no args) — it raw-clicks the "Deposit inventory" toolbar button (a static button widget, proven to work) and waits for inventory changes. Protect tool slots (mould/chisel/pipe) from the blanket deposit by locking them first: `Rs2ItemModel tool = Rs2Inventory.get(toolId); if (tool != null && !Rs2Bank.isLockedSlot(tool.getSlot())) Rs2Bank.toggleItemLock(tool.getName(), true);` — locks persist account-wide and the check makes it a no-op after the first cycle. Locking needs "bank slot locking" enabled in the player's bank settings; if the injected lock op dies on grid-silent machines, keep going — re-withdraw the tool next cycle. Check every bank-step `sleepUntil(...)` result and return `false` on timeout so the tick retries instead of looping blind.

**Exception — nothing to deposit.** `AScriptBank.depositAll()` returns `true` *without clicking* when the bank is open and every occupied inventory slot is locked: the toolbar button ignores locked slots, so the click would be a no-op and `Rs2Bank.depositAll()`'s `waitForInventoryChanges(10_000)` would stall 10 s and then report `false` (which makes `doBank()` return `false` and retry forever — the MLM loop). The guard requires `Rs2Bank.isOpen()` because `isLockedSlot()` reads the `BANK_INVENTORY_ITEM_CONTAINER` widget, which only exists with the bank open; in the `ensureToolLocked` stray-release path the stray was just unlocked, so the guard does not fire and the toolbar deposit still runs.

### Furnace lookup — per-location object id

`craftJewelry()` resolves the furnace from `JewelryLocation.getFurnaceObjectIds()`, trying each id in order — the furnace is not one shared object id across Gielinor:

| Location | Furnace object id |
|---|---|
| Edgeville | 16469 |
| Falador / Port Phasmatys | 24009 (general furnace scenery) |
| Mount Karuulm | 34342 (volcanic furnace) |
| Zanaris | 12100 |
| Shilo Village | 30157 (unlit) / 30158 (lit) |

Consequences:
- A game update that changes a furnace object id breaks that location only: `findObjectById` returns null for every id of the site → walk-to-location retry → still null → the script deactivates itself once with a Discord notification ("Furnace (ID <site ids>) not found"). Check Discord/status before assuming a code bug.
- Adding a jewelry location means adding its furnace id(s) to `JewelryLocation`, not reusing Edgeville's 16469.

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
| Inventory click target | `Rs2Inventory.get(id)` — always the lowest slot | `AScriptPick.pick(id)` — randomized instance, same type |

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
| Fletching | `ascript/fletching/` | Darts, Bolts, Gem-tipped Bolts, Arrows, String Bows, Carve Bows (u), Arrow Shafts, Crossbow Stocks, Shields |
| Motherload Mine | `ascript/motherloadmine/` | Mining veins, depositing hopper, emptying sack, fixing waterwheel |
| Gem Crab Killer | `ascript/gemcrabkiller/` | Combat — kill gem crabs, mine loot, bank at Tal Teklan |
| Barbarian Village Fisher | `ascript/barbarianvillagefisher/` | Fly/Bait fishing, Cook/Drop/Bank fish |
| Jewel Enchant | `ascript/jewellenchant/` | Enchant Jewellery (Lvl-1 through Lvl-7 Enchant spells) |
| Cannonball Smelter | `ascript/cannonballsmelter/` | Smelt steel bars into cannonballs (Edgeville, Shilo Village, Prifddinas, Port Phasmatys) |
| Ammonite Crabs | `ascript/ammonitecrabs/` | Combat — kill ammonite crabs at 4 Fossil Island beach spots |
| Firemaking | `ascript/firemaking/` | Burn logs on fires / forester's campfires (GE corners, Castle Wars) |
| Barbarian Fishing | `ascript/barbarianfishing/` | Fish & Drop at Otto's Grotto (leaping fish; rod + feathers as locked anchors) |
| Cannon | `ascript/cannoning/` | Dwarf multicannon maintenance — repair a broken cannon and reload it from the inventory (never banks) |
| Woodcutting | `ascript/woodcutting/` | Chop trees (Drop/powercut or Bank); best axe auto-equipped from the bank; Jewelry-style AFK layer |
| Mining | `ascript/mining/` | Mine configured ore (Drop or Bank); best pickaxe auto-equipped from the bank; gem-bag/deposit-box, basalt-noting and Crafting Guild apron edges |

**Crafting jewelry notes:** the batch is tracked **across ticks** by bar-count drops plus the player animation — while bars remain the module returns with a short in-flight wait (400–900 ms) and **never touches the furnace mid-batch** (a click on it while the make runs resolves as the "Cancel" menu entry and stops the batch); an 8 s stall window with no bar consumed and no animation re-issues the furnace click for the remaining bars. The item option click **retries until actually dispatched** (`sleepUntilTrue` over `Rs2Widget.clickWidget` — the option can populate a tick after the interface appears) and the start is confirmed by animation or a bar consumed; three consecutive failed starts escalate to the orchestrator's craft-failure stop. An already-open interface (production 270 / gold 446 / silver 6) skips the furnace click and finishes the pending make sequence instead. At batch end the post-batch break (short pause + `craftingAfk` AFK) runs once with the bank closed — and only after a batch that actually ran: `needsBankJewelry` yields a tick while `batchRanSinceBank` is set and the break is pending (a fresh empty state banks immediately) — then the bank cycle withdraws more bars (smelter parity). **F2 conversion (card t_5edf6f64):** every Crafting inventory/entity dispatch now goes through the `AScriptClick` recipes — the six use-chains (gems, bolt tips, glassblowing, staffs, dragon leather) are `useItemOnItem`, and the flax wheel ('Spin') and jewelry furnace ('Smelt') clicks are `clickObject` through a module-private wrapper that absorbs the recipe's camera-turn with one frame wait (Motherload Mine wrapper); both ops are the objects' left-click defaults. `Rs2Inventory.use` / `Rs2GameObject.interact` injection is gone from this module.

**Fletching notes:** The module is **ID-first**. Every material/product lookup, bank check and click uses the gameval `ItemID` constants in the aScript-owned enums (`FletchingDartType`, `FletchingBoltType`, `FletchingArrowType`, `FletchingBoltTipType`, `FletchingShaftType`, `FletchingStockType`, `FletchingShieldType`, `FletchingBowType`); display names are never used to decide presence or identity. The old shared `microbot/util/skills/fletching/data` enums and `Rs2Fletching.make*` helpers are **not** used by this module and are intentionally left untouched. `AScriptFletching` (ascript/util) provides the new helpers: `itemName(id, fallback)` via `ItemComposition.getMembersName()` (real name, no F2P " (Members)" suffix), `inventoryQuantity(id)` (unnoted units), `combinedAtLeast(id, amount)` (inventory + retrying bank read), `hasKnife()/heldKnifeId()/bankHasKnife()`. Runtime names are resolved through `getMembersName()`; config `toString()` keeps a static fallback label because it may be rendered outside the client thread. New config keys: `fletchingBoltTipType`, `fletchingShaftType`, `fletchingStockType`, `fletchingShieldType`; `fletchingBowType` is shared by `BOWS` (string) and `UNSTRUNG_BOWS` (carve).

**Fletching activities.**
- `DARTS`: dart tip + feather → dart.
- `BOLTS`: unfinished bolts + feather → bolts. Includes Blurite (24) and Silver (43) alongside the metal/broad/dragon tiers.
- `BOLT_TIPS`: finished base bolts + gem bolt tips → tipped bolts. One enum value per valid pair; each action consumes 10 bolts + 10 tips. Dragon bolts (`ItemID.DRAGON_BOLTS`, 21905) accept every gem tip **except amethyst**; the `DRAGON_*` entries use `DRAGON_BOLTS_UNENCHANTED_*` products. Barbed bolts are deliberately not supported. No poisoned (`p`/`p+`/`p++`) or enchanted (`e`) id is reachable; `FletchingDataTest` reflects over `ItemID` and rejects any referenced poison/enchanted constant.
- `ARROWS`: headless arrow + arrowtips → arrow. Broad uses the real finished id 4160, not FletchingAction's Slayer-guide dummy 4150.
- `BOWS`: bow string + unstrung bow → strung bow, `String` production action.
- `UNSTRUNG_BOWS`, `ARROW_SHAFTS`, `STOCKS`, `SHIELDS`: knife + logs. `LogFletchProduct` makes one generic bank/craft/verify path for all four. Unstrung bows/stocks consume 1 log per product; **shields consume 2 logs per product** (the batch planner withdraws a multiple of 2); arrow shafts produce 15/30/45/60/75/90/105 per log for logs/oak/willow/maple/yew/magic/redwood at levels 1/15/30/45/60/75/90. Logs are **not stackable**, so the log activities use the bow-style slot budget (`freeSlots / logsPerAction`), not `withdrawAll`. The knife is held as a **locked tool slot** (`AScriptBank.ensureToolLocked("knife")`, matching `ItemID.KNIFE` 946 or `FLETCHING_KNIFE` 31043); bank slot locking must be enabled or the orchestrator stops with the dedicated capability message.

**Fletching banking checks** use the combined inventory + bank amount (`AScriptFletching.combinedAtLeast`), not "bank has amount" alone: with 1 log in the inventory and 1 in the bank a shield is makeable, and the bank cycle deposits the inventory before withdrawing. Minimum actionable amounts are 2 logs per shield, 10 bolts + 10 tips per gem-tip action, and 1 of each other material. `needsBank` yields while a batch is in flight; the batch state is cleared in `resetExitFlag`.

**Fletching wearables.** Bolts are ammo and the knife is wieldable, so a plain inventory click on either dispatches **`Wield`**, not `Use` — the live gem-tip bug equipped the bolt stack, the recipe timed out, and the module stopped with its material on the player's body. The item-on-X selection therefore goes through `AScriptClick.select`, which moves the client's real **`Use`** row to the top before pressing (the same `PostMenuSort` swap as the drop recipe) and refuses to click when that row cannot be verified. `BOLT_TIPS` additionally selects the **gem tip first** and clicks the bolt as the target — the natural direction when the source's default op is already `Use` — while the knife activities keep "use knife on logs". If a material is found worn **and the inventory cannot supply the next batch** (the worn stack is the only copy at hand), `doAction` runs `recoverWornMaterials` before any bank/material check: `AScriptClick.unequip` physically clicks the equipment slot (no injection) and the module continues next tick. `readyForBank` is false while such a blocking worn item exists, so the orchestrator dispatches that recovery instead of opening a bank; a worn stack the inventory can already match is left on the player as ordinary equipment. A failed unequip goes through the normal 3-failure stop.

**Fletching batch tracking.** One recipe dispatch starts a batch (`batchInFlight`), then each tick polls the tracked material count. A drop is progress; the batch finishes when fewer than the per-action amount remain (1 log, 2 logs for shields, 10 bolts for gem tips). No progress for 12 s re-clicks the production row; a stall that cannot be re-clicked stops through the 3-failure budget. The post-batch break + `fletchingAfk` run **once per completed batch**, not after every dispatch. The start confirmation accepts "material count dropped OR production interface closed"; an interface still open after the 5 s window is left to the stall path rather than re-selecting the recipe behind it.

**Fletching production interface.** `FletchingProductionHelper.clickProductionProduct(int productId)` is ID-driven: it resolves the row by `Widget.getItemId() == productId` first, then by the exact `getMembersName()` label (tag-stripped), and clicks with the virtual mouse. `clickProductionAction("String")` remains for bow stringing. `enableQuantityOption("All")` runs before the row click; if it closes the interface the batch is treated as started. This replaces the old `Rs2Fletching` name-based flow and the keyboard-shortcut path. **Needs in-game validation** for each new activity (especially multi-product log interfaces and the dragon/amethyst exclusions).

**Fletching source of truth.** `FletchingAction` pins product ids, levels and xp in `FletchingDataTest`. Known gaps/quirks: no `BLURITE_BOLTS` entry (wiki 24), no jade/topaz or dragon gem-tipped entries (wiki 26/2.4, 48/3.9, dragon 84), only one `ARROW_SHAFT` entry (tier levels/quantities are the wiki table), `BROAD_ARROWS` uses dummy id 4150 while the real item is 4160, and `DRAGON_BOLTS` uses the unf input 21930 rather than the finished 21905. Do not "fix" these in RuneLite/Microbot shared files; the test documents them and the aScript tables use the real ids.

**Cannonball Smelter notes:** The mould (ammo or double ammo) is held as a **locked inventory slot** (the tool pattern, `AScriptBank.ensureToolLocked`); `doBank()` keeps whichever mould is already held and prefers the double one when withdrawing fresh. One smelt click runs the whole inventory (~6 s per bar); the batch is tracked across ticks by bar-count drops plus the smithing animation, and a 12 s stall window re-issues the furnace click if the batch stops early — never a blind long wait. The furnace click goes through the verified `AScriptClick.clickObject(furnace, "Smelt", RENDER_WAIT_STEPS)` recipe (the hover must rank the "Smelt" row top, or the frozen menu must expose it — no menu injection; the old `click("Smelt")` entry-click on the `api.tileobject.models.Rs2TileObjectModel` is gone — the deprecated `util/tileobject/Rs2TileObjectModel` is a different class and is used nowhere in aScript), after a bounded wait for a real hull (a canvas-sized clickbox means the object is not rendered yet — never clicked blindly; `Target.miss()` keeps that render budget separate from a hover/menu failure); the smelt interface is clicked with `Rs2Widget.clickWidget` (child 14; child 13 marks it open). The furnace is found via the tile-object query API, always with the `...OnClientThread()` variant (`withId(...).nearestOnClientThread()`; Shilo Village via `withName("Furnace").nearestOnClientThread()`). `doBank()` deposits the produced cannonballs with the toolbar deposit (product, not a tool). Between batches it runs the Jewelry/Crafting randomness layer: an unconditional short randomized pause plus the `cannonballAfk` random AFK (3–120 s log-normal, weather-modulated, interruptible, 5 s min-gap), with `needsBank()` — and the pool-empty `doAction` branch — honouring the break only when a batch actually ran (`batchRanSinceBank`; a fresh start banks or walks immediately) so the pause runs with the bank still closed. The module travels itself, but only when there is nothing to click: `readyForBank()` is `AScriptBank.bankVisible()` — true whenever a bank object the orchestrator can open is in the scene **and on camera** — so a visible bank is used directly and the game walks the last few tiles as part of the click. `doAction()` walks (`Rs2Walker.walkTo(anchor, BANK_TRAVEL_RADIUS = 4)`) only when no bank is loaded; a bank in the scene but off camera gets a camera turn, not a walk. `TRAVEL_STAGNATION_MS` (120 s) with no movement at all stops the module loudly. The post-batch break runs once per batch (the `postBatchBreakDone` guard), never per tick while travelling with no bars. A furnace that is in the scene but **not rendered yet** (`getObjectClickbox` falls back to a canvas-sized rectangle — the render-distance/fog boundary) is never clicked blindly: the module waits `RENDER_WAIT_STEPS` (5 brief steps), then turns the camera to force a re-render and retries on a later tick — such a click would land as "Walk here" (the menu-entry override does not save it) — and those misses use their own budget (`MAX_RENDER_MISSES` = 20, reset by any successful render) rather than `MAX_ACTION_FAILURES`; a persistent condition stops loudly ("furnace not rendered after N attempts").

**Motherload Mine notes:** A trip completes at the sack-empty falling edge (`shouldEmptySack` goes true while emptying and falls back to false once the sack is collected and the ore deposited) — the post-trip break + `mlmAfk` run once there, bank-free (the trip ends at the deposit box; no `needsBank` yield needed). Gems (`mlmDropGems`) are dropped through `AScriptDrop.dropAll` (one paced, verified pass — the set is small, so the slicer is not needed). A missing pickaxe needs a bank trip regardless of what the bank mirror says (`needsBank` = no usable pickaxe; the mirror is refreshed only by an open bank, so reading it there false-stopped the first run). `readyForBank` is `AScriptBank.bankVisible()`; while the bank is not in reach `doAction` owns the recovery — turn to a loaded bank object or walk to the nearest bank with a 120 s stagnation bound. `isBankMissingMaterials` counts a **noted** pickaxe in the inventory as recoverable (it is un-noted by the deposit), and `doBank` withdraws the best tier with the verified amount path, never `Rs2Bank.withdrawItem`/`withdrawOne`.

**GCK special case:** GemCrabKiller handles its own banking internally — the bank (Tal Teklan) is too far from the cave for the orchestrator's open-check-withdraw cycle. `needsBank()` returns false; the module's internal state machine transitions to BANKING when food runs out. The orchestrator calls `doAction()` every tick and GCK routes internally (WALKING→FIGHTING→BANKING→WAITING). Its randomness layer (`gemCrabKillerAfk`, default on) therefore cannot ride the house `needsBank` yield: the post-kill break (unconditional short pause + 3–120 s AFK, 5 s min-gap) runs inline in the dead-crab branch, once per kill (`postKillBreakDone`, cleared with `hasLooted` when a new fight is engaged) — before the internal `BANKING` transition when the loot filled the inventory, before the exit-crawl otherwise (the `WAITING` window starts after it), always with the bank still closed.

**Jewel Enchant notes:** The cosmic rune stack is held as a **locked inventory slot** (the mould pattern, `AScriptBank.ensureStackLocked`): `doBank()` locks it *before* the blanket deposit, so the deposit never removes it; when the stack is consumed empty the slot lock is gone and the next bank cycle withdraws the whole stack and re-locks it. **Noted jewellery** is a certificate for the configured unenchanted item: the missing-materials verdict counts it via `AScriptBank.bankableAtLeast`, `doBank()`'s `depositAllExcept(cosmic)` un-notes it, and step 4 re-withdraws the base item — a note-only stack is never a "ran out of jewellery" stop; a noted usable staff is likewise counted as available and deposited before the staff step so it can be worn. Lvl-7 Enchant consumes blood + soul runes — no staff supplies those, so `staffCoverableRunes()` is empty for that level and they are always withdrawn; staff mode only skips the runes the *equipped* staff actually provides (per rune, not per level). A level that needs a staff with none worn and none in the bank stops with a message instead of looping. The action is two client-mouse clicks — the spellbook icon (bounds from widget 218/3 filtered by `MagicAction.getSprite()`, after opening the "Jewellery Enchantments" sub-tab) and the inventory slot (`Rs2Inventory.itemBounds`) — and success is confirmed by the jewellery count dropping or `Rs2Player.isAnimating()`, with a 3-attempt stop (`MAX_ENCHANT_FAILURES`). At batch end (the unenchanted stack runs out) the post-batch break + `jewelEnchantAfk` run bank-closed: with a batch that actually ran, `needsBank` yields one tick (`batchRanSinceBank` gate, below) so the pause happens before the bank trip (crafting-jewelry parity) — a fresh start with nothing in hand banks immediately, and a missing-consumable state (no cosmics with jewellery in hand) defers to the bank cycle instead of stopping (`doAction` never self-stops on a state banking can fix). **2026-10-16 additions:** `doBank()` ends with the contained-inventory sweep — `AScriptBank.depositAllExcept(keptIds)`, keep-set = cosmic + the level's runes + the raw jewellery (mechanism: lock the keepers → toolbar deposit → unlock, the unlock+toolbar pattern mirrored; locked slots and the lamp untouched; a keeper that cannot be locked aborts with nothing deposited and the cycle retries) — so the item a staff equip displaces is deposited in the SAME cycle instead of waiting for a clean bank trip (or staying forever when the run stops first), and the inventory holds exactly the run's materials. The spell selection verifies the icon click actually ARMED the spell (`Rs2Inventory.isItemSelected()`, true for a selected spell; bounded wait + one re-click) before the inventory click — an unarmed left-click wears the item under the cursor. The landed-cast check requires the ENCHANTED count to rise (`JewelEnchantItem.getEnchantedIds()` lists every charge variant per piece) — a misclick-wear drops the raw count and must not read as an enchant. **Staff sourcing (2026-10-18):** the elemental staff is taken from wherever it already is — WORN (kept as-is, never upgraded) > HELD in the inventory > the bank — where "better" is the coverage of the level's staff-coverable runes and ties go to the item already at hand, so a held staff that does not cover the spell's rune needs in full lets a strictly better bank staff win (`staffToWear` over `bestStaffFor` is the single resolution behind `needsBank`, `isBankMissingMaterials`, `describeMissing`, the rune-coverage computation and the doBank staff plan). A staff the player is CARRYING is therefore never a missing-material stop and never costs a bank trip by itself (live 2026-10-18: a held staff failed the old "worn or in the bank" verdict and stopped the run 21 s into the first bank cycle). A held staff is worn by `doAction.ensureStaffWorn()` with the client mouse on its inventory slot with the BANK CLOSED — with the bank open an inventory left-click is a deposit, never a Wear — behind one bounded re-click and skipped for the tick while a cast is armed. Inside a bank cycle the same wear runs as **`doBank` step 0, before any deposit or withdraw**: the cycle that banked the player's staff is the one that deposited before anything could protect it, so the staff is put on FIRST and the cycle is handed back (the next tick reruns the restock with the staff worn — an equipped item is never deposited, so no slot lock and no keep-set entry is involved for it at all). A staff that exists only in the bank is withdrawn with `AScriptBank.withdrawVerified` (the grid-free, verified withdraw the runes/jewellery use) BEFORE the jewellery fill, which takes every remaining slot — a staff withdraw after it can never fit — and `closeBankForWear()` closes the window for the wear click (bounded wait; a bank that will not close retries the cycle instead of clicking the staff into the bank). Never `Rs2Bank.withdrawAndEquip`/`withdrawOne(id)`: dead dispatches on this client (silent `false`, zero menu entries — the module looped "Failed to equip STAFF_OF_WATER" into "Bank Cycle Failed" on 2026-10-18).

**Enchant target slot:** the click targets the **last** occupied jewellery slot (`AScriptPick.last(id)` — highest slot index, the documented opt-out from the slot randomization; since `items()` walks the container in ascending slot order), not the first match, and the slot is resolved **immediately before the click** (after the spellbook click and the tab switch), never at the top of `doAction`. The enchant spell consumes the **first** inventory item of the type it finds regardless of which item was selected, so a target resolved earlier is stale: by the time the bot's virtual-mouse click lands the chosen piece may already be enchanted, and casting on an already-enchanted item does nothing. Resolving late leaves only the mouse travel time in the race window.

**Enchant auto-recast race:** after a cast, the game automatically re-casts the jewellery enchantment every **7 game ticks (4.2 s)** on the next item of the same type if uninterrupted (manual casting is possible every 3 ticks). The auto-cast does **not** disarm the spell, but it can consume the target while the script is arming/switching tabs. `doAction` therefore:
- baselines `enchantedCount(item)` at the **start of the tick** (before staff wear / spell selection); a rise from that baseline is auto-cast progress — skip the manual click and reset the failure counter;
- after the tab switch, re-checks the count and returns if the auto-cast already progressed;
- hovers the resolved last slot and **re-reads the slot id** before pressing: if it no longer holds `getUnenchantedId()`, the auto-cast consumed it — skip (progress, never a failure);
- re-checks `isSpellArmed(spell)` immediately before the press (cheap: selected-widget sprite/interface compare, widget-id fallback). The auto-cast leaves the spell armed; if the selection was lost, skip and re-arm next tick — an unarmed inventory click would **wear** the jewellery;
- treats a changed/empty target, a lost selection or a missing unenchanted item as progress/retry, and only fails when an unenchanted item remains, the slot is unchanged, and no count rise/animation occurred;
- `selectSpell` skips the icon click when the desired spell is already on the cursor (the auto-cast's normal state), clears a stale **different** selection by clicking the selected widget itself (never an arbitrary item, which an armed spell would cast on), and verifies the specific spell icon — not merely `Rs2Inventory.isItemSelected()`, which is true for any stale item/spell.

**Barbarian Village Fisher notes:** Cooking never latches on an unconfirmed dispatch. `doCookingDispatch()` waits for the make-X box (`How many would you like to cook?`) and only then presses SPACE and sets `currentlyCookingID`/`cookingConfirmed`; if the box never appeared it returns `false` and the next tick retries. The XP wait itself has **no** animation precondition — cooking has no modelled animation, and `isAnimating(600)` misses the ~2.4 s per-fish cycle, which would re-open the make-X box every 1-2 ticks. The in-flight signals are used only to *interpret* a failed wait (`isCookingInFlight()` = `isInteracting()` — the direct one — or `isAnimating()`): in flight ⇒ re-wait without counting a failure, not in flight ⇒ release the latch and re-dispatch. Escalation is a **stagnation** deadline (60 s, reset on every XP received, because a full 26-fish batch is already ~62 s) and it only fires when nothing is in flight. The drop phase goes through `AScriptDrop.drop(...)`: raw + cooked (`BarbarianFish` enum ids) + every "Burnt fish" variant (name-matched, so 323/343/357/367/369 are all covered), never the rod/bait — sliced 5–9 slots per tick instead of clicking the whole bag in one tick. A `dropping` latch keeps the phase running across slices (a freed slot must not send the module back to fishing mid-drop) and the phase ends with the cycle break when `remaining()` hits 0. The cook path may only select a **raw** id: `BarbarianFish` owns the raw/cooked ids (validated against `CookingAction` in `BarbarianFishTest`), `doCookingDispatch` re-checks the picked model right before the recipe, and `AScriptClick.select` verifies the **selected item id** — a stale slot (the last raw fish finishing into burnt) aborts the dispatch instead of applying burnt fish to the fire. The fishing-landed wait (8–30 s) and the cooking-XP wait (8–15 s plus a 4–8 s grace before the latch is released) are deliberately long: a lagging client otherwise reads as a failed dispatch and re-clicks the spot / re-opens the make-X box, which is exactly the gap that produced the burnt-fish attempts. A stale make-X box is dismissed with ESC and never re-dispatched over, and `isCookingInFlight()` also blocks a dispatch between fish. The fishing click keeps a bounded transient-retry budget (`SPOT_CLICK_RETRIES`, backoff per attempt) that escalates to `fail()` at the ceiling, so a click that never lands stops the run instead of spinning. **F1/F1.1 conversion status (cards t_3c6b786c, t_c7b0a398):** both dispatches are converted. Cooking: `AScriptClick.useItemOnObject` resolving the scenery fire (id 43475) on the tile the module already verifies — `Rs2Inventory.useItemOnObject` injection is gone from that path. Fishing spot: **Lure** (the spot's default op) is `AScriptClick.clickActor(fishingSpot.getNpc())` — a verified left click on the clickbox (the Lure row must rank top; a player on the spot sends the recipe through the frozen menu instead of stealing the click), the human signature; **Bait** is `AScriptClick.clickActor(fishingSpot.getNpc(), "Bait")`, the verified action pick (the recipe right-clicks and presses OUR "Bait" row out of the open frozen menu; an action that already ranks top is still a plain left click) — a left-click can only ever dispatch the default op, so Bait needs the row pick. The click gate is unchanged (landed wait → `SPOT_CLICK_RETRIES` backoff → `fail()`); a `cameraTurned()` outcome is a retry that spends neither a retry nor a failure life, and the recipe only reports the click was issued, so the effect is still judged by that gate. Pass the **raw NPC** (`fishingSpot.getNpc()`), not the model: the row is matched by reference against `MenuEntry#getActor()` (identifier/NPC-index fallback). **Needs in-game validation (Bait + Lure modes) after the merge/build.** A post-cycle break + `barbarianVillageFisherAfk` run once per completed cycle: inline after a drop session lands, and — for the banking functions — on the tick after `doBank` succeeds (the orchestrator has closed the bank by then; `postCycleBreakPending`). `readyForBank` is `AScriptBank.bankVisible()`: a restock (rod/bait out, or a full inventory in `BANK_RAW`/`COOK_AND_BANK`) only hands the bank phase to the orchestrator once a bank it can click is in the scene and on camera — the Village's bank is Edgeville, outside the loaded scene from the river, so the module normally walks there itself through its own `WALK_TO_BANK` phase (before this gate, `needsBank` fired with no readiness check and the tick returned before that walk could ever run).

**Ammonite Crabs notes:** Banking is walk-gated: `needsBank()` returns true only when a configured supply is out **and** the player is within 10 tiles of the bank — `doBank()` never walks. The module drives `WALK_TO_BANK` itself (fisher pattern); the orchestrator then opens the bank, runs `isBankMissingMaterials` (stops the run when the configured food / 4-dose potion is missing from the bank — no walk-to-bank ping-pong), and dispatches `doBank`, which withdraws by **item ID** (no fuzzy name matches; other potion doses are not backfilled) and re-withdraws food after the blanket deposit. Recovery measures against the **fight spot** (Chebyshev distance): > 25 tiles → walk back; > 100 → stop (died/teleported) — never the old hub worldhop anchor. Aggro: no combat for 10 s at the spot → `RESETTING` (reset tile → back). On arrival the scattered crabs within 15 tiles of the spot are pulled with **client-mouse clicks** (camera turn first, off the client thread; the canvas-sized clickbox fallback is never clicked). Seaweed spores are looted from the idle path with the verified ground-item recipe (`AScriptClick.clickGroundItem(spore.getTileItem(), spore.getTile())`), on a retry cooldown; a `cameraTurned()` outcome retries next tick without spending the cooldown. Crash hop: another player within 3 tiles of the player or the spot for 7 s (out of combat, `ammoniteCrabHopOnCrash`) → `Microbot.hopToWorld`, return checked. `eatAt(50)` owns food — the QOL auto-eat skips this module. Food now goes through `AScriptFood.bestFood()`/`dispatch` with a confirmed bite, not `Rs2Player.eatAt` (first slot, no confirmation). **Randomness layer (2026-10-18):** the post-kill break + `ammoniteCrabAfk` run once per kill, read off the **combat falling edge** — the fight is passive (auto-retaliate), so no module action marks a kill and the single fire on the edge IS the once-per-kill guard. The AFK does **not** stop combat (the crabs keep attacking and the player keeps retaliating); what it removes is the module's own interventions — re-planting walk, spore looting, potion sips — which is the profile of a human standing AFK at the spot. `lastCombatMs` is restarted after the break so the time it spent does not read as aggro decay and send the next tick to `RESETTING`. Its min-gap is 30 s rather than the house 5 s: a 1-HP crab dies every couple of seconds here, so a 5 s gap would AFK after nearly every kill and dominate the run.

**Firemaking notes:** Tick-driven burn tracking with **no persistent flag** — state derives from the log count plus a 20 s stall window each tick, so pause/resume, fire death and restarts self-heal; a stall re-engages (finds the nearest fire, or lights one at the configured spot). Fires are found **by object id only** (`49927` Forester's campfire → `26185` Fire; `...OnClientThread()`, radius 12) — scenery fires that merely look like fires (e.g. the Barbarian Village fire, id 43475) are not convertible and are never targeted. Using logs on a lit fire is what **creates** a campfire, so with no fire in range the module lights its own (tinderbox on the last log; tinderbox held as a locked tool slot, withdrawn by numeric id `590`). Both dispatches are **client-mouse item-on-X recipes** (`AScriptClick` — the pilot of the aScript mouse refactor): logs→fire is `useItemOnObject(logItem, fire)` — a physical click on a log slot, a verified selection, then a physical click on the fire's real clickbox (camera first, render waited out); tinderbox→logs is the same recipe item-on-item with the last log's slot as the target, and every failure path clears the selection the recipe created. The recipe is followed by the brief make-X probe → SPACE only if the prompt shows — post-12-Aug-2026 it only appears with 2+ log types; a single log type auto-burns — and the start is confirmed by the tending animation (`BURNING_ANIMATION_IDS`, all tiers + `HUMAN_CREATEFIRE`) or the first log consumed; three failed starts stop the module loudly. Logs are withdrawn by **numeric id** (the name "logs" is a substring of every log type). Post-batch break + `firemakingAfk` run bank-closed via the `postBatchBreakDone` yield, gated by `batchRanSinceBank` (cannonball parity) — a fresh empty start banks immediately instead of breaking first.

**Barbarian Fishing notes:** Powerfishing at Otto's Grotto (leaping fish; drop-only). The barbarian rod (tool) and feathers (stack) are locked inventory anchors; banking is walk-gated (fisher pattern) at the **Barbarian Outpost chest** — `needsBank()` yields only within 10 tiles, the module walks itself, and a rod/feather missing from the bank stops the run instead of ping-ponging. Drops go through `AScriptDrop.drop(...)` (5–9 slots per tick, paced, with a unit-count verification so a dead click pipeline stops loudly); the post-batch break + `barbarianFishingAfk` run when a drop session completes. The dragon harpoon special activates at most once per full-energy cycle and is **verified via `getSpecState()` before latching** — an unconfirmed dispatch is re-attempted (bounded) instead of burning the cycle; the state resets when the spec is consumed (energy drops) or the harpoon is unequipped. The spot query is anchored to Otto's Grotto (`withIds(BARB_FISH).nearestOnClientThread(anchor, 25)`); spot clicks use the actor-clickbox recipe (canvas-sized fallback rejected) behind a `SPOT_CLICK_RETRIES` budget that escalates to `fail()` — and each click is gated on **catch evidence**: the post-click wait ends when `Rs2Inventory.emptySlotCount()` changes (first fish in the inventory), never on `isAnimating()`/`isInteracting()` — both read true from the moment the interaction engages, seconds before the first catch, so the loop re-clicked the spot every ~3 s until fishing started (live menu log: four `Use-rod` entries in 11 s); Fishing 48 is the `validateSelection` guard.

**Cannon notes:** Maintenance-only module: it repairs and reloads a **placed** dwarf multicannon and **never banks** — `needsBank`/`isBankMissingMaterials`/`doBank` are honest stubs (`false`), `readyForBank` is not overridden, and restocking (like placing and picking up) is the player's job. The cannon is latched from the `OWNEDMCANNON` varp — the SW corner of the 3x3 footprint, so the OBJECT stands on the centre tile (`+1,+1`, the `CannonOverlay`/`Rs2Cannon` convention) — and **never** from a nearby object, so a stranger's cannon at a shared spot is never clicked; the anchor is kept for the whole run and stays readable while the cannon is outside the loaded scene, which is what lets the module walk back if the player drifts (`TRAVEL_STAGNATION_MS` 120 s, progress measured as "the player moved", never as the distance shrinking). No cannon placed → loud stop after the failure budget. Both actions are verified before they count: a repair must clear the broken object id **and** `MCANNON_DECAYED` (bounded 6 s; broken ids 5/14916/43028 — `Rs2Cannon` omits id 5), a load must raise `CANNON_AMMO` (bounded 5 s). Clicks use the client-mouse recipe (camera turn first, canvas-sized clickbox rejected behind `MAX_RENDER_MISSES` = 20 — never a blind click). An empty pool with fewer than `MIN_LOAD_BALLS` (15) in the inventory is **terminal**: the module stops loudly with an actionable message instead of idling forever. Randomness: short post-action pause plus `cannonAfk`, hard-capped at 50 s — `Math.min(AFK_MAX_MS, logNormalBounded(3_000, AFK_MAX_MS, weatherMultiplier))`, because the weather multiplier stretches the log-normal ceiling, so the cap has to be a clamp on the sample.

**Woodcutting notes:** Tick-driven chop loop — the woodcutting animation (`WOODCUTTING_ANIMATION_IDS`, every gameval tier) is the in-flight signal and log-count increases are the batch tracker, so a slow tier never reads as a stall and tree depletion self-heals (animation stops → the next tick re-resolves and clicks the nearest tree). Trees are found within the config radius of the **activation anchor** (search centre and return point) with the composition action filter ("Chop down"/"Chop"/"Cut") as the discriminator plus an exact-name check — the hub's hardwood trick generalised. The click is the `AScriptClick.clickObject` client-mouse recipe (camera turn = retry, never a failure). Drop mode calls `AScriptDrop.drop(...)` (5–9 logs per tick, paced and verified; only the target log is dropped — a full inventory with no target logs stops loudly instead of waiting on a count that can never fall); Bank mode runs the orchestrator cycle with `readyForBank` = `AScriptBank.bankVisible()` and a 120 s travel watchdog, returning to the anchor after the deposit — a held **log basket** fills when the inventory nears full and is emptied + slot-locked in `doBank` so the deposit keeps it. **Bird nests** are looted from the idle path (`woodcuttingLootNests`, radius 15, never mid-chop). The axe is auto-managed: any worn axe is kept, otherwise the best tier in the bank is withdrawn and equipped (failed equip → locked slot fallback), picked from ONE bank snapshot; the axe spec mirrors the BVF harpoon-spec latch. The post-batch break + `woodcuttingAfk` run once per batch that actually ran (`batchRanSinceBank` gating, bank-closed yield in Bank mode, inline bank-free in Drop mode).

### Module conventions (enforced for all modules)

Shared banking/notify logic lives in `ascript/util/`:
- `AScriptBank.depositAll()` / `depositAndWaitEmpty()` — toolbar button deposit (+ wait empty).
- `AScriptBank.withdrawVerified(name)` — withdraw-all + verify that the inventory count ROSE, `false` on miss/timeout.
- `AScriptNotify.notify(title, message)` — ORANGE Discord embed tagged "aScript".
- `AScriptFood.bestFood()` / `dispatch(food)` / `edibleCount()` / `belowHpPercent(pct)` — the shared eating path (largest per-bite heal, instance drawn by `AScriptPick.pickFood`, unnoted only, blighted food first in the Wilderness). Modules that own their food use this instead of `Rs2Player.eatAt`, which eats the first slot in slot order.
- `AScriptDrop.drop(filter, status, weatherMultiplier)` / `dropAll(...)` — sliced, verified inventory dropping (`NOTHING_TO_DROP` / `DROPPED` / `NO_PROGRESS`).
- `AScriptFletching.itemName(id, fallback)` / `inventoryQuantity(id)` / `combinedAtLeast(id, amount)` / `hasKnife()` / `heldKnifeId()` / `bankHasKnife()` — ID-first Fletching item resolution. `itemName` uses `ItemComposition.getMembersName()` (the real name, without the F2P " (Members)" suffix); the rest count unnoted units and combine the inventory with the retrying bank read.

Modules must call these instead of re-implementing them.

- **Module clicks go through the shared `AScriptClick` recipes** (`ascript/util/AScriptClick.java`): `clickObject(TileObject[, action[, renderWaitSteps]])`, `clickActor(Actor[, action])` and `clickGroundItem(TileItem, Tile[, action])` — camera first, the target's real clickbox, the canvas-sized fallback never clicked, and the dispatch **verified against the hover menu** (see "Verified entity dispatch" below). `resolveObject` is the gate-first half for callers that own the click. Plus the item-on-X recipes `useItemOnObject`/`useItemOnNpc`/`useItemOnPlayer`/`useItemOnItem` (a physical click on the item slot → verified selection → a physical click on the target; every failure path cancels the selection on the **source item's own slot**, never at the last press point). **The selection is armed on the item's real `Use` row** (the `onPostMenuSort` swap — a plain slot click dispatches the default op, which is `Wield` for bolts and the fletcher's knife), and an accidentally worn item is removed with `AScriptClick.unequip(itemId)` before the recipe refuses the dispatch. The recipes report whether the click was issued — confirming the EFFECT (make-X prompt, animation, XP/count) stays with the caller's tracking. The item recipes also bring the inventory tab forward before slot reads/clicks (`Rs2Tab.switchTo(InterfaceTab.INVENTORY)` when not current) — a closed inventory tab leaves slot bounds empty; scripts that depend on other panels must switch them the same way before widget work.
- **Every inventory item a module clicks resolves through `AScriptPick`** (`ascript/util/AScriptPick.java`): the helper returns the same item TYPE with a randomized instance — the target slot is drawn with a log-normal rank over the candidates in slot order, so a script working a 14-item batch does not click the same pixel every cycle (`Rs2Inventory.get` always returns the lowest matching slot). Contract: it changes WHICH candidate is clicked, never WHICH candidates qualify — the type is resolved with the plain `Rs2Inventory.get` rules (fuzzy name matching and the shortest-name tie-break included) and only the instance is drawn, so a converted site cannot silently retarget a different item. The one qualification the helper enforces itself is **banknotes are never candidates** (a certificate is not the item a Use click can act on); a noted reference resolves to its base id through `Rs2ItemModel.getUnNotedId()` and draws the unnoted instances. Resolve the pick at the click site (immediately before the recipe), never at the top of `doAction`. Opt-outs are code-level and named: `AScriptPick.first(...)` (the first occurrence is load-bearing) and `AScriptPick.last(...)` (the target must stay as late as possible — Jewel Enchant's armed cast consumes the first-of-type item, so an early slot can already have been converted when the click lands); every opt-out names its reason at the call site, and `grep -rn "AScriptPick\.\(first\|last\)" ascript/` is the exception list. **Drops are centralized — `AScriptPick.dropRoute(...)` + `AScriptDrop`**: `dropRoute` precomputes one shuffled slot route per slice (no slot is drawn twice; the slot is re-read before each click, `AScriptClick.dropAtSlot`, which installs the item's real Drop row), and the shared `AScriptDrop.drop(filter, status, weatherMultiplier)` owns the randomised 5–9-slot slice, the per-click pacing and the unit-count verification. `dropAll(...)` is the one-pass variant for small sets only (gems). Modules keep their own drop-phase latch and call `drop` once per tick until `Result.remaining() == 0`; `NOTHING_TO_DROP` is an empty state (never a failure), `NO_PROGRESS` is a failed drop. Do NOT go back to `Rs2Inventory.dropAll*` for a module's own drops: those walk slot order and dispatch by `invokeMenu` injection with a fixed sleep. **Food is the third shape — `AScriptPick.pickFood(foods)` / `AScriptFood`**: the heal priority is untouched (biggest per-bite heal wins, food with no `Rs2Food` entry scores 0 and ranks last) and only the INSTANCE inside the top-heal group is drawn, with the same log-normal rank as every other click target. The QOL auto-eat and the food-owning modules (Gem Crab Killer, Ammonite Crabs) go through `AScriptFood.bestFood()`/`dispatch(...)`; a module must not reach for the lowest food slot (`Rs2Player.eatAt`).
- **Pre-click hovers use `AScriptClick.moveNaturally(point)`** — never `Microbot.getMouse().move(point)`. `VirtualMouse.move` only emits a single MOVED event (a teleport), and `NaturalMouse.moveTo` early-returns when the pointer already rests on the destination, so the following click becomes an instantaneous press with no natural path or weather speed. This applies to Jewel Enchant's cast, the drop recipe's hover and the item-on-X selection hover; all three use the shared helper.
- **Banking must use the toolbar "Deposit inventory" button** (`AScriptBank.depositAll()`, no args) — never grid-targeted `depositAll(String/id)`. Grid interactions die silently on some machines (see "Bank item-GRID interactions" below).
- **Every withdraw must be verified** with `AScriptBank.withdrawVerified(name)` so a failed withdraw never advances to CRAFTING with an empty inventory.
- **Mouse speed is global and pinned (fork customization).** `Rs2Antiban.activityIntensity` is locked to `VERY_LOW` and `setActivityIntensity` is a no-op (`Rs2AntibanIntensityLockTest`); `NaturalMouse` speed is hardcoded to the slow tier. Modules carry no mouse-speed code — the old `managePrecisionMouseSpeed()`/`isPrecisionModule()` machinery was removed.
- **Missing-materials / invalid-selection stops** must call `Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE)` with a `stopRequested`/`exitRequested` guard and a one-shot Discord notify (see "Stopping the script"). Fletching also self-escalates after 3 consecutive craft failures.
- **Missing-materials checks must use the retrying bank reads**: `Rs2Bank.hasBankItem(name|id, amount)` (2-tick retry + miss debug), never the bare `Rs2Bank.hasItem(...)` mirror read. The mirror is refreshed only by bank container events, so one stale read used to decide a stop — and the same trap sat inside `AScriptBank.hasBankItem`, the withdraw pre-check, despite its name. Check *amounts* for anything consumed per action, count banknotes as bankable inventory (`AScriptBank.bankableAtLeast`, which uses the retrying reads), and keep a stop message's own conditions identical to the check's: an inventory-only `describeMissing` can name an item that IS in the bank or in the inventory as banknotes.

### Cooking: raw ids only

- `CookingAction` (`runelite-client/.../skillcalculator/skills/CookingAction.java`) is the source of truth for **what can be cooked**: each entry carries the **cooked** item id (`getItemId()`), the level (`getLevel()`) and the XP (`getXp()`); it imports `gameval.ItemID`.
- The raw counterpart is a *different* id in `runelite-api/.../ItemID.java` (very large — search `RAW_`): `RAW_TROUT` 335 / `TROUT` 333, `RAW_SALMON` 331 / `SALMON` 329, `RAW_PIKE` 349 / `PIKE` 351, `RAW_HERRING` 345 / `HERRING` 347. Burnt variants (`BURNTFISH1` 323, `BURNTFISH2` 343, `BURNTFISH3` 357, `BURNTFISH4` 367, `BURNTFISH5` 369) all share the display name "Burnt fish" and are never cookable.
- A cook dispatch may only ever select a **raw** id; cooked and burnt ids belong to the drop/bank phase. Encode the raw↔cooked pairing in one enum (`BarbarianFish` in the BVF package) and assert it against `CookingAction` in a unit test — never carry a hand-written id array into the cook path.
- The item-on-X recipe verifies the **selected item id** before applying it (`AScriptClick.select`), so a slot that changed under the cursor (a raw stack finishing into burnt) aborts the dispatch instead of using the wrong item on the fire/range. Modules with their own cooking loop should rely on that rather than assuming "something is selected" means the right item.

### Verified entity dispatch (AScriptClick)

Every plain entity click goes through one shared tail (`pressVerified`): rest the cursor on a point inside the resolved clickbox, read the hover menu on the client thread, and only then press.

| Hover state | Action |
|---|---|
| our row ranked **top** | plain left click at the point — the same hover rebuilds to the same ranking, so it cannot resolve elsewhere |
| our row present but **beaten** (a player standing on the NPC/object, another NPC on the tile) | right-click, wait for the menu + stable anchor, press **our** row in the frozen menu |
| our row **gone** | bounded nudge/retry, then **no click** — clicking blindly would hit whatever ranks top |

- **Item-on-X selection is its own verified dispatch.** A plain inventory click dispatches the item's *default* op, which is `Use` for logs/fish/gems but **`Wield` for bolts (ammo) and the fletcher's knife**. `AScriptClick.select` therefore arms the same real-`Use` row swap as the drop recipe (`onPostMenuSort`) and waits until that row is the top hover entry before pressing; a swap that cannot be resolved means **no click** (the module retries, it never falls back to the default op). The selection is re-checked immediately before the item-on-item target click, and an item still worn by a lost-selection race is taken back off with `AScriptClick.unequip(itemId)` — a client-mouse click on the equipment slot verified against the worn container, never `Rs2Equipment.unEquip`'s injection.

- **Identity is part of the match, not just the clickbox.** Objects: `GAME_OBJECT_FIRST..FIFTH_OPTION` + object id + the scene coords the client packs into param0/param1 (multi-tile GameObjects size-adjusted; the `api.tileobject.models.Rs2TileObjectModel` wrapper is unwrapped through `getTileObject()` because it is not itself a `GameObject`). NPCs: `NPC_FIRST..FIFTH_OPTION` + the actor reference **or** the NPC index (the fallback makes `Rs2NpcModel` wrappers work; prefer `model.getNpc()` anyway). Ground items: `GROUND_ITEM_FIRST..FIFTH_OPTION` + item id + scene coords (`GroundItemsPlugin` reads the same fields).
- **Option matching** is case-insensitive after `Text.removeTags(...)` — the Ground Items plugin recolors menu options, and the tag is not part of the op.
- **Non-default actions** are the optional argument: `clickObject(furnace, "Smelt")`, `clickActor(spot, "Bait")`, `clickActor(npc, "Dismiss")`, `clickGroundItem(item, tile, "Bury")`. A left click can only ever reach the default op; the recipe still left-clicks when the named row already ranks top, and presses the frozen-menu row otherwise.
- **Never hardcode the default op name** — verify the top row instead. The cannon's op changes with state ("Repair" vs "Fire"), and a verified default click is exactly what a state machine wants.
- **`Target.miss()`** names the failure: `NONE`, `NOT_RENDERED`, `CAMERA_TURNED`, `CURSOR_LOST`, `HOVER_GONE`, `MENU_STUCK`. Keep render-miss budgets on `NOT_RENDERED`/`CAMERA_TURNED`; `HOVER_GONE` is a hover failure (retry next tick, not "not rendered"), and `MENU_STUCK` means nothing may click until a human closes the menu.
- **In-recipe confirmation stops at the press** (our row pressed + menu closed, or the top row clicked). Confirming the EFFECT — animation, XP, count, interface — stays with the caller.
- **Tests**: `AScriptClickMatchersTest` guards the matcher rules (ids/coords, NPC index fallback, color tags, action filtering, multi-tile unwrap, inventory slot/item identity for the `Use`/`Drop` swap); each migrated call site still needs in-game validation.

### Post-batch breaks: the yield must mean "a batch just ended"

The bank-closed post-batch break runs from `doAction` while `needsBank` yields the break flag for one tick — the pause happens before the bank trip. Gate the yield on `batchRanSinceBank` (set in `doAction` when materials are in hand; cleared by a completed `doBank` and in `resetExitFlag`): `return !batchRanSinceBank || <postBatchBreakDone>;`. Without the gate the SAME branch fires on a fresh start (nothing in the inventory, flag unset), the tick runs `doAction` before the FIRST bank trip ever happens, and a missing-materials self-stop there ends the run at start with a fully stocked bank (live: Jewel Enchant, 2026-10-09). Companion rules: `doAction` must never `stopWithMessage` on a state banking can fix (missing-materials verdicts belong to `needsBank`/`isBankMissingMaterials` — Jewel Enchant's cosmic guard is now a plain `return` that defers to the bank cycle), and a module whose `readyForBank` can be false (Cannonball Smelter) also gates its own pool-empty `doAction` break (`batchRanSinceBank && !postBatchBreakDone`), because the not-ready travel path reaches `doAction` with zero materials.

### Missing-materials verdicts are confirmed before they stop

`tick()` does not stop on the first `isBankMissingMaterials` verdict: it must hold `MISSING_CONFIRMATION_TICKS` (3) times in a row **and** the bank mirror must be live (`Rs2Bank.getBankLiveEpoch() > 0`) — a restored, never-live snapshot is not trusted as "empty". `MISSING_MAX_TICKS` (12) bounds the wait so an unreadable mirror cannot hold the tick. The counter resets when the verdict is false and on the disabled branch; deliberately **not** on the successful-open path, which runs every tick and would zero it before the threshold could ever be reached.

Three stop titles, deliberately distinct — the operator must be able to tell them apart:

| title | meaning |
|---|---|
| `No Materials (<Module>)` | confirmed empty on a live mirror |
| `Bank Cycle Failed (<Module>)` | the bank opened, but `doBank` failed N times — the deposit/withdraw did not land (the `AScriptBank` WARN lines name the item) |
| `Bank State Unreadable (<Module>)` | no live mirror within the confirmation bound |

### Reading a failed withdraw

`[AScriptBank] Failed to withdraw <item> x<n> after 3 attempts [<state>] — <miss>` is deliberately self-describing:

- `<state>` comes from `AScriptBank.resolution(...)`: whether the bank **mirror** resolves the item, at which slot, and whether that slot has a **clickable widget** at that moment.
- `<miss>` is `Rs2Bank.getLastWithdrawMiss()` — why the dispatch had nothing to click: `no item widget for '<item>' at bank slot N — sending a 1x1 fallback click` (the bank list re-sorted under a stale mirror, or the tab/scroll did not land) or `the 'Enter amount:' prompt never appeared …` (the prompt click was swallowed).
- The per-attempt debug line adds `hadItemToClick=` and the bank epoch `before→after`. A mirror that never advanced **cannot** have changed its answer — a third retry against it is futile, and saying so is the point.

The chain behind it: `Rs2Bank.withdrawX/withdrawOne/withdrawAll(name)` resolve the item from the **mirror** (`findBankItem`) and then compute the click rectangle from the **live widget** at the mirror's slot (`getItemWidget(slot)`). A mismatch between the two is what makes a click land nowhere — the two silent `return false` paths this instrumentation replaced.

### The bank phase gate (`readyForBank`)

`tick()`'s bank step opens the bank and caches its contents before a module's `doBank` runs — that cache is what `isBankMissingMaterials` and the module's own bank queries read. `Rs2Bank.openBank()` can only interact with a bank object **in the loaded scene**, so a module that starts far away must say so:

- Return `false` from `readyForBank()` while the module still has preparation to do (walking to the bank), and drive that preparation from `doAction()` — the tick falls through to it whenever `needsBank()` is true but the module is not ready. This is the fisher/ammonite walk-gate, generalised and available to every module; `AmmoniteCrabsScript` still implements it inline (`bankTripNeeded(config) && isCloseToBank()`) and is equivalent — migrate it only if you are touching it anyway.
- While the gate is false, `doAction()` is the only code running: keep the trip bounded and stop through `stopWithMessage` when the player stops making progress. Silent idling is the defect this gate exists to prevent.
- **Judge readiness on the object, never on a distance to a coordinate.** The gate should ask "is a bank the orchestrator can click loaded in the scene?" (`Rs2GameObject.findBank()` / `findGrandExchangeBooth()` — the same candidates `openBank()` uses), not "am I within N tiles of a waypoint". A distance proxy reports not-ready while the bank is plainly visible, and the module then walks to a hardcoded point instead of clicking the booth it is standing next to — a textbook bot pattern (flagged live 2026-10-08, one commit after the gate shipped). Clicking a visible bank IS the human behaviour: the game walks the last few tiles as part of the interaction. The walker is only for a bank that is not loaded at all; when the bank is in the scene but off camera, a single `Rs2Camera.turnTo(bank)` is the whole "approach".
- The shared body is **`AScriptBank.bankVisible()`** — reuse it instead of re-deriving the check (`CannonballSmelterScript` and `BarbarianVillageFisherScript` both call it; `AScriptBank.findBankObject()` gives the object itself when a module needs to turn toward it).
- If the module reports ready and the bank still will not open, the orchestrator counts consecutive failures and stops loudly ("Bank Unreachable", `MAX_BANK_OPEN_FAILURES`) — never an unbounded silent retry. That counter is only safe because honest modules gate the phase.
- Cost accepted: the "No Materials" check can only run once the module is at the bank, so an empty bank is detected **after** the walk rather than before it.

## Adding QOL features

QOL features don't run as automation scripts. Add config items under the QOL `@ConfigSection` in `AScriptConfig.java`. Implement them as event listeners or overlays registered in `AScriptPlugin.startUp()`.

### Implemented QOL features

| Feature | Config keys | Where |
|---------|-------------|-------|
| Auto zoom out | `autoZoomOut` | `tick()` step 1b — rate-limited to 60 s |
| Auto eat | `autoEat`, `autoEatMinHpPercent`, `autoEatMaxHpPercent` | `tick()` step 1c + `rollEatThreshold()` |
| Random event handler | `eventDismissGenieAction`, `eventDismissCountCheckAction`, `eventDismissLampSkill`, `eventDismissStrayLamps` | `ascript/eventdismiss/` — blocking events registered in `AScriptPlugin.startUp()` |

**Random event handler invariants:**
- Always-on while the plugin is enabled (no master toggle; not part of the `ScriptType` dispatch). The two `BlockingEvent`s are registered in `AScriptPlugin.startUp()` and removed in `shutDown()`.
- Every lamp use waits for a sustained idle window (~2–4 s, the MLM idleness pattern): `UseLampEvent.validate()` only enqueues once the window elapsed (the script-pause gate is not held while waiting); `LampUtility.useLamp()` waits the same window internally, covering the Genie/Count Check accept paths.
- Every dialogue phase is deadline-bounded (`DIALOGUE_PHASE_TIMEOUT_MS`): a dialogue that never closes — or an unforeseen dialogue shape — must never hold the script-pause gate.
- Genie and the Count Check random event are continue-only dialogues (one bounded closer covers both); a Count Check talk that ends without a lamp dismisses the NPC and marks it tried — never a talk loop. Reward detection is count-based: the lamps in the inventory are counted before the talk, and the lamp-wait ends when that count rises or the NPC leaves — a pre-existing (stranded) lamp never reads as the event's reward. The wait window is wall-clock (~5 s average, `LAMP_WAIT_AVG_MS`), advanced by the event requeue loop — never a blocking sleep. After interacting with the NPC the flow waits (bounded 5 s) for it to lose focus and despawn — a lingering Genie is never re-talked.
- The pre-action sleep is 4–40 s log-normal with the explicit weather multiplier (the 2-arg `logNormalBounded` overload already applies weather — never apply it twice).
- Banking yields to lamps: every bank path — the orchestrator's open step and `GemCrabKillerScript.doBank` (it banks on its own; its `needsBank()` is always false) — consults `LampUtility.yieldBankingToLamp(...)` before opening. An XP lamp in the inventory blocks banking until the lamp-use event consumes it; lamps are never deposited, and `UseLampEvent` bypasses the stray-lamp toggle and the idle window while a yield is pending. A lamp skill with no interface widget never blocks banking and warns once.
- Lamps are never deposited: before any deposit `AScriptBank` locks the lamp's slot (the mould/cosmic-rune pattern, `ensureLampLocked`, exact-name match) and its empty-checks ignore locked slots and the lamp (`isEmptyExceptLocksAndLamp`) — a stranded lamp must never stall a deposit or an empty-inventory wait. The `UseLampEvent` breaker (`MAX_LAMP_USE_FAILURES = 5`, counted on the warn paths only) suspends lamp handling and unblocks banking instead of retrying forever; the suspension clears when the lamp leaves the inventory. A partial failure can leave the lamp interface open — `useLamp` closes a stale one first (Close action, Escape fallback).
- **F3 click conversion (card t_b3adef8c):** the handler's own dispatches go through the `AScriptClick` recipes now — the accept-path talk is `AScriptClick.clickActor(npc.getNpc())` (raw NPC, the BVF precedent), the dismiss is `AScriptClick.clickActor(npc.getNpc(), "Dismiss")`, and the lamp rub is a physical slot click (`Rs2Tab.switchTo(InterfaceTab.INVENTORY)` → `Rs2Inventory.itemBounds` → `Microbot.getMouse().click(bounds)`). `Rs2NpcModel.click` and `Rs2Inventory.interact(ItemID.LAMP, "Rub")` are gone from `ascript/eventdismiss/`. Grounding: "Talk-to" is the first (left-click) op of both event NPCs and "Rub" the lamp's default — OSRS wiki infobox `options` order, `Talk-to, Dismiss` (Genie 326/327 and Count Check 12551/12552) and `Rub, Destroy` (Lamp 2528); "Dismiss" is an NPC composition action, never the default, so it needs the row pick. A talk/dismiss that reports no click (`!clicked()`: off-camera target, hull not rendered yet, or the hover/menu never exposed the row) stops that pass instead of falling through — a Count Check would otherwise be marked `countCheckTried` with no dialogue ever starting — and the `Microbot.status` line the old `Rs2NpcModel.click` set is kept. Needs in-game validation with the final bundled test pass (Genie + Count Check accept, a plain dismiss, one lamp rub).

**Auto-eat invariants:**
- Skipped entirely for `ScriptType.GEM_CRAB_KILLER`: that module owns HP management (50% normal eat, 2% emergency, banks for food at 25%, Dharok mode holds HP at 10). A global 35–60% eat would double-eat in normal mode and break Dharok mode; Ammonite Crabs is skipped the same way (the module eats at 50 and banks when empty). `autoEatEnabled()` is the single gate.
- The threshold is rolled once (`rollEatThreshold()`), held across ticks, and **only re-rolled after a bite is CONFIRMED** — never per tick, and never on an unconfirmed dispatch.
- The bite goes through `eatBestFood()`, NOT `Rs2Player.eatAt(pct)`: `eatAt()` → `useFood()` eats the FIRST food in slot order (a shark in slot 3 always beats a summer pie in slot 20, whatever the heal). `eatBestFood()` keeps `useFood()`'s rules (unnoted only, blighted food first in the Wilderness, "jug of wine" is drunk) and picks the **largest per-bite heal** from `Rs2Food`; the INSTANCE within the top-heal group is then DRAWN by `AScriptPick.pickFood(...)` — the same log-normal rank toward the first occurrences as every other click target, never the lowest slot every time — and food with no heal in the table (level-scaled/random heals) sorts last. `Rs2Food` is therefore the heal source of truth for the auto-eat priority — keep it in sync with the wiki's `Food/All food` (heal per bite) and `Food/Fast foods` (eat delays) tables.
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
- [ ] Tick logic is clear: config check → phase resolve → validation → bank check (`readyForBank` gate) → dispatch
- [ ] A module whose bank needs travelling gates the bank phase with `readyForBank()`, keeps its walk radius inside the readiness distance, and stops loudly when the trip stalls
- [ ] Module clicks use the client mouse — no `doInvoke` / `NewMenuEntry` / `interact(...)`
- [ ] Item-on-X selections go through `AScriptClick` (never a raw slot click), so a wearable source such as bolts or the knife is never equipped by its default `Wield` op; a worn recipe item is recovered before the bank/material check
- [ ] `isBankMissingMaterials()` checks bank *amounts* for anything consumed per action, and every bank read in it is the retrying `Rs2Bank.hasBankItem(...)` family — never the bare `Rs2Bank.hasItem(...)` mirror read
- [ ] Material availability uses `AScriptBank.bankableAtLeast` (banknotes count, because the bank cycle un-notes them); `needsBank` counts only unnoted inventory, and bank withdrawals run in "Withdraw as item" mode
- [ ] Every stop path notifies (status + log + `AScriptNotify` + config reset), and repeated action failures escalate to a stop
- [ ] This AGENTS.md is updated with new modules, states, or config items
