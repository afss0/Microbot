# Custom Features (afss0 fork)

This document tracks all modifications unique to this fork compared to upstream (chsami/Microbot).
Used during merges to verify nothing is lost.

## Antiban

### Weather-Based Modulation
- **Files:** `WeatherModulation.java`, `WeatherPanel.java`
- **What:** Modulates anti-ban timing using real-world weather data (Open-Meteo API). Temperature affects action speed, precipitation affects break frequency, wind gusts add variability.
- **Integration points:**
  - `AntibanPlugin.java` — init + refresh on startup/reset
  - `Rs2Antiban.java` — TIMEOUT scaled by `combinedSpeedFactor()`, micro-break chance offset
  - `Rs2AntibanSettings.java` — `weatherLat`, `weatherLon`, `weatherCityName` fields (the old `weatherEnabled` toggle was removed on 2026-09-05 — weather modulation is always on)
  - `NaturalMouse.java` — `SpeedManager` wrapper applies weather factor to mouse movement
  - `MasterPanel.java` + `NavigationPanel.java` — ⛅ Weather tab in antiban UI

### Weather Modulation for Mouse Interactions
- **Files:** `Rs2UiHelper.java`, `VirtualMouse.java`, `FactoryTemplates.java`, `Rs2Random.java`
- **What:** Extends weather modulation to all mouse interactions beyond just speed:
  - **Click position jitter** (`Rs2UiHelper.getClickingPoint()`) — wind gusts add 0–2px extra imprecision via `windGustFactor()`
  - **Click error chance** (`VirtualMouse.click()`) — rain + wind add 0–9% chance of clicking 1–3px off-target via `mistakeProbabilityOffset()`
  - **Overshoot count** (`FactoryTemplates`) — rain + wind add +0 to +2 overshoots via `mistakeProbabilityOffset()`
  - **Rs2Random overload** — `logNormalBounded(min, max, multiplier)` for weather-scaled timing
  - **Rs2Random automatic modulation (2026-09-26)** — `systemWait()` (backing `wait`/`waitEx`) and the two-arg `logNormalBounded(min, max)` now automatically stretch by `1/combinedSpeedFactor()`: waits only ever lengthen in worse weather (never shorten). The three-arg overload applies ONLY its explicit multiplier — callers that weather-scale themselves are not double-scaled.
- **Safety:** Always active by design (the old `weatherEnabled` toggle was removed on 2026-09-05). Factor reads use cached values only — no API calls at call time; refresh stays in `WeatherModulation.ensureFresh()` (AntibanPlugin / ascript).

### Activity Intensity Lock (mouse-speed slider no-op)
- **Files:** `Rs2Antiban.java`
- **What:** `activityIntensity` pinned to `VERY_LOW`; `setActivityIntensity(...)` is a no-op and `setActivity()` no longer overwrites the field (playStyle seeding still uses the activity's own intensity, so cooldown cadence is unchanged). Scripts, hub plugins (e.g. AutoWoodcutting), the dynamic-intensity feature and the UI slider can no longer churn it — it was constantly flipping Very Low ↔ Low. Mouse speed is unaffected: forced independently in `NaturalMouse.getFactory()`.

## Anti-Detection

### Auto-Run Toggle Removal
- **Files:** `Script.java`, `Rs2Walker.java`
- **What:** Removed programmatic `toggleRunEnergy(true)` calls. In-game auto-run settings handle this; re-enabling it programmatically is a detectable automation marker. Only stamina potion usage remains.
- **Comment:** "Run toggle intentionally NOT done here — the in-game settings already handle auto-run, and re-enabling it programmatically is a detectable automation marker."

## Agent Server

### Remote Access (bindAllInterfaces)
- **Files:** `AgentServerConfig.java`, `AgentServerPlugin.java`, `AgentHandler.java`
- **What:** Adds `bindAllInterfaces` config option to bind agent server to `0.0.0.0` instead of `127.0.0.1`, allowing connections from other machines on the network (e.g. via Tailscale).
- **Security:** `AgentHandler` bypasses loopback check when enabled. Config warning recommends firewall or Tailscale ACL.

## Walker

### Unconditional Finish Distance (no exceptions)
- **Files:** `Rs2Walker.java` → `tightFinishThreshold()`, `walkWithStateInternal()`, `walkStep()`, `walkWithBankedTransportsAndStateLocked()`
- **What:** The walk completes whenever the player is within the configured finish distance (default 10 Chebyshev tiles) of the goal — no exceptions. Arrival is purely distance-based: the upstream 1–2 tile tight cap (and its `isGoalReachable` BFS guard) is removed, as is the `arrival_declined_unreachable` gate that declined arrival next to an unwalkable goal without a reachable tile beside it.
- **Trade-off:** a blocked goal no longer forces proximity — ARRIVED can land up to the finish distance away on the wrong side of a wall/door; callers must not assume adjacency.

### RouteRecovery Skip Occupied Tile
- **File:** `RouteRecovery.java`
- **What:** Skips the tile the player is already standing on during minimap click recovery. Clicking own tile produces no movement but walker treats it as successful, causing loop until stall recovery fires.

### Interim Close Tiles Threshold
- **File:** `Rs2Walker.java`
- **What:** `INTERIM_CLOSE_TILES` 5→6. When the player is within this many tiles of the interim minimap click target, the walker considers it "arrived" and selects a new checkpoint. Raising it from 5 to 6 gives the walker a slightly larger arrival window, reducing unnecessary re-clicks on short segments while still keeping movement smooth.

### ShortestPath Default Distances
- **File:** `ShortestPathConfig.java`
- **What:** `recalculateDistance` 10→15, `reachedDistance` 5→10. More lenient thresholds reduce unnecessary path recalculations.

## Mouse Sync

### Cursor Synchronization with User
- **Files:** `MouseSyncPlugin.java`, `MouseSyncConfig.java` (new package: `mousesync/`), `VirtualMouse.java`
- **What:** When enabled, the bot disables user mouse input during interactions, waits a grace period after the interaction completes, then naturally moves the cursor (via NaturalMouse) back to the user's physical OS mouse position and re-enables input. Prevents "teleporting" and user/bot input conflicts.
- **State machine:** IDLE → BOT_ACTIVE → GRACE_PERIOD → RETURNING → IDLE
- **Emergency hotkey:** CTRL+X — immediately stops plugin and restores mouse control. Plugin disables itself via `Microbot.stopPlugin()`.
- **Config:** `enabled` (default false), `gracePeriodMs` (default 5000), `emergencyHotkey` (default CTRL+X)
- **Integration:** VirtualMouse hooks `onBotInteractionStart()` / `onBotInteractionEnd()` in all click/drag methods. Standalone moves also trigger start/end.
- **Re-enable:** Toggle the plugin off and on via the Microbot plugin list.

### Guice Injection Fix
- **Files:** `Microbot.java`, `MouseSyncPlugin.java`
- **What:** Removed `@Inject` from `MouseSyncPlugin` static field in `Microbot.java`. Guice's `requestStaticInjection(Microbot.class)` runs before plugin discovery, so `MouseSyncConfig` wasn't bound yet. Plugin now self-registers in `startUp()` (`Microbot.mouseSyncPlugin = this`) and clears in `shutDown()`. Field made `public` for cross-package access.

### interactionInProgress Flag Fix
- **File:** `VirtualMouse.java`
- **What:** `interactionInProgress` was declared but never set to `true`. NaturalMouse internal moves during `click()`/`drag()` triggered `mouseSyncOnEnd()` repeatedly, re-enabling user input during walker steps. Now set on entry and cleared in finally blocks of all click/drag methods.

### Respect User's Global Disable Input
- **File:** `MouseSyncPlugin.java`
- **What:** Added `inputWasAlreadyDisabled` flag. When `onBotInteractionStart()` fires, it checks `ClientUI.getClient().isEnabled()` — if input was already disabled (user clicked "Disable Input" button), the flag is set. `enableUserInput()` now skips re-enabling when this flag is true, respecting the user's explicit choice. Flag resets when state returns to IDLE.

### Unified InputSelector Usage
- **Files:** `MouseSyncPlugin.java`, `Microbot.java`, `MicrobotPlugin.java`
- **What:** `disableUserInput()` and `enableUserInput()` now call `InputSelector.disableClick()` / `InputSelector.enableClick()` — the same methods used by the toolbar "Disable Input" / "Enable Input" buttons. This ensures the button icon state stays in sync with the actual input state. InputSelector instance stored in `Microbot.inputSelector` (public static field with `@Getter`).

### Cursor Position Tracking
- **File:** `MouseSyncPlugin.java`
- **What:** Added `cursorTracker` — a scheduled task (50ms interval) that reads the user's OS cursor position via `MouseInfo.getPointerInfo()`, converts screen→canvas coordinates (stretched-mode aware), and updates `VirtualMouse.lastMove`. Only runs while `ClientUI.getClient().isEnabled()` is true (user has input control). Skips during bot interactions and when the user clicked "Disable Input". Starts in `startUp()`, stops in `shutDown()`.

### Walker Activity Guard
- **File:** `MouseSyncPlugin.java`
- **What:** `cursorTracker` skips when `Rs2Walker.getCurrentTarget() != null`. `finishGracePeriod()` retries every 1s while walker is active — prevents cursor teleportation. `onBotInteractionStart()` also skips when `state == RETURNING` — prevents NaturalMouse cursor-return movement from triggering MouseSync (which would set `inputWasAlreadyDisabled=true` and permanently block input re-enable). During walking: each walker click disables input (BOT_ACTIVE), each click end starts grace period, next walker click cancels it.

## Build

### aScript — AIO Multi-Script Orchestrator
- **Files:** `ascript/` package (AScript.java, AScriptConfig.java, AScriptPlugin.java, AScriptOverlay.java, ScriptType.java, crafting/)
- **What:** AIO script hosting multiple automation scripts under one plugin, built on standard `Script` (Phase enum: DISABLED/IDLE/BANKING/CRAFTING/ERROR — migrated from StateMachineScript). Modules are plain helper classes (no Guice, no Script inheritance).
- **Current modules:**
  - **Crafting** (`ascript/crafting/`) — Gem Cutting, Glassblowing, Staff Making, Flax Spinning, Dragon Leather, Jewelry
  - **Random event handler** (`ascript/eventdismiss/`) — always-on Genie/Count Check dismissal + XP lamp use (lamp skill configurable); banking interlock keeps lamps out of deposits (locked lamp slot + bank yield)
  - **Cannonball Smelter** (`ascript/cannonballsmelter/`) — smelt steel bars into cannonballs (Edgeville, Shilo Village, Prifddinas, Port Phasmatys); mould as a locked slot; Jewelry-style random-AFK layer (`cannonballAfk`)
  - **Firemaking** (`ascript/firemaking/`) — burn logs on fires / forester's campfires (GE corners, Castle Wars); deadlock-free tick-driven burn tracking (stall re-engage + relight), ID-only fire lookup, locked tinderbox slot; Jewelry-style random-AFK layer (`firemakingAfk`)
  - **Barbarian Fishing** (`ascript/barbarianfishing/`) — powerfish leaping fish at Otto's Grotto (drop-only); rod + feather locked slots, walk-gated bank at the Barbarian Outpost chest, spec actuation verified before latching, sliced humanized mouse drops; Jewelry-style random-AFK layer (`barbarianFishingAfk`)
  - **Cannoning** (`ascript/cannoning/`) — maintain a placed dwarf multicannon: repairs it when it breaks (broken object id + `MCANNON_DECAYED` varbit, both verified before it counts) and reloads it from the inventory when `CANNON_AMMO` drops to the configured threshold (≥ 15 balls); latches only the player's own cannon from the `OWNEDMCANNON` varp (a stranger's cannon at a shared spot is never clicked), client-mouse clicks behind a render-race budget, loud actionable stop when the cannonballs run out (never banks); Jewelry-style random-AFK layer (`cannonAfk`, hard-capped at 50 s)
  - **Woodcutting** (`ascript/woodcutting/`) — chop trees (Drop/powercut or Bank) + log basket + bird-nest looting; action-filtered tree lookup around the activation anchor, best-axe auto-equip from one bank snapshot, axe spec (BVF latch), Jewelry-style random-AFK layer (`woodcuttingAfk`)
- **Anti-detection:**
  - All timing uses `Rs2Random.logNormalBounded()` with log-normal distributions (not uniform)
  - AFK timing: `Rs2Random.logNormalBounded(3000, 60000, weatherMultiplier)`
  - Animation waits: `Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier)`
  - Widget interactions use `sleepUntilTrue()` for production widget detection (no fixed sleeps)
  - Missing materials → immediate stop with status message (no silent failures)
- **Weather integration:** `WeatherModulation.ensureFresh()` called once per craft cycle; `combinedSpeedFactor()` scales all waits
- **Config:** Automation section (enabled + dropdown), per-module sections (closedByDefault), QOL section (placeholder)
- **Review fixes (Aug 2026):**
  - Flax Spinning now walks to the configured spinning wheel (`flaxSpinLocation`), turns camera, interacts with "Spin"; stops once + Discord notify when wheel/location missing (was: no navigation at all)
  - `tick()` honors `Rs2Bank.openBank()` return value — failed open retries next tick instead of checking stock against an empty bank snapshot (false "missing materials" stop)
  - Added `CraftingScript.validateSelection()` — invalid sub-selections (activity set, sub-type NONE) stop early with clear status instead of timeout loops
  - Removed dead/misleading config: `jewelryCompletionAction` (never implemented), `PROGRESSIVE` entries in CraftingGlass/CraftingStaff (behaved like NONE)
  - Amethyst branch gates on the amount dialog before clicking (same as other gem branches)
  - Local sleep/sleepUntil helpers replaced by `Global.sleep`/`Global.sleepUntil` (client-thread guard + interrupt handling)
- **Jewelry banking fix (Aug 2026):**
  - Item-grid interactions in the bank die silently on some machines — injected CC_OP entries (`depositAll`/`depositX`) AND raw physical slot clicks all fail there (verified live via Agent Server across three approaches); withdraw-side injected entries and toolbar BUTTON clicks work fine.
  - `bankJewelry()` now deposits via `Rs2Bank.depositAll()` (no args) — a raw click on the "Deposit inventory" toolbar button with `waitForInventoryChanges` verification. Deposits crafted jewelry + leftover gems together. Before depositing, the tool slot (mould) is locked via `Rs2Bank.toggleItemLock()` when unlocked (locks persist account-wide; requires bank slot locking enabled in settings). If the injected lock op dies on grid-silent machines the script proceeds anyway — the withdraw-mould step restores an unprotected tool automatically.
  - All bank-step `sleepUntil(...)` results checked; timeout returns `false` → tick retries BANKING instead of looping blind (was: silent BANKING↔CRAFTING loop stuck forever with full inventory).
  - Merge check: `grep "Depositing inventory" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/CraftingScript.java | head -1`
- **Jewelry batch-tracking fix (Oct 2026):**
  - The craft session is now **per-tick and bar-count driven**: while bars remain, `craftJewelry` stays in-flight (bar-count drop / `Rs2Player.isAnimating()` / 8 s stall window) and never touches the furnace mid-batch. The old blocking waits (`sleepUntil(isGainingExp, 3–12 s)` + `sleepUntil(!isGainingExp || no bars, 15–45 s)`, return values ignored) read "done" while a full-inventory make-all (~50 s) was still running; the next tick re-clicked the furnace, and mid-make interactions resolve as the "Cancel" menu entry that stops the batch (live log: SMELT+MAKE re-pairs 5–30 s into batches, CANCEL mid-batch).
  - The item option click **retries until actually dispatched** (`sleepUntilTrue` over `Rs2Widget.clickWidget`; the old single click could silently miss while the make interface tree was still settling) and the batch start is confirmed (animation or bar consumed) before success is reported; ≤3 failed start attempts escalate to the existing craft-failure stop.
  - Batch end: a **post-batch break** (short pause + the `craftingAfk` randomness layer) runs once with the bank still closed (`needsBankJewelry` yields via `jewelryPostBatchBreakDone`), then the bank cycle withdraws more bars — cannonball-smelter parity.
  - Merge check: `grep "JEWELRY_STALL_WINDOW_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/CraftingScript.java | head -1`
- **Withdraw retry hardening (Oct 2026):**
  - `AScriptBank.withdrawVerified*` no longer fails on a single 3 s verify miss: each attempt verifies in two windows (3 s main + 2 s grace — a landing that is merely late under transient lag is caught by the grace window; live cause of a spurious `Failed to withdraw bow string x14` whose items landed ~3–6 s after the click) and an unconfirmed dispatch is retried up to 3 attempts total with exponential backoff (≈0.8 s / 1.6 s, jittered via `Rs2Random.waitEx`).
  - Each retry waits the backoff and re-checks the inventory **before** re-clicking (a slow landing is confirmed, never double-withdrawn); the bank window is left open across attempts — the loop never closes/re-opens it.
  - The `Failed to withdraw` WARN fires only after all attempts are exhausted; late-landing recoveries log at DEBUG.
  - Merge check: `grep "WITHDRAW_GRACE_TIMEOUT_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptBank.java | head -1`
- **Firemaking module (Oct 2026):**
  - New `FIREMAKING` module — the robust replacement for the hub GEFiremaker pattern: no persistent "burning" flag (the hub's guard could deadlock forever when the fire died in the unprotected engage window or the loop idled through a burn's end); state is re-derived from the log count + a 20 s stall window every tick, so pause/resume, fire death and restarts self-heal.
  - Fires are found by object id only (`49927` → `26185`, radius 12, `...OnClientThread()`): scenery fires that merely look like fires (e.g. Barbarian Village, id 43475) are not convertible. No fire in range ⇒ light own at the configured spot (tinderbox locked tool slot, numeric-id withdraw) — the tend then creates/converts the campfire.
  - Dispatch follows the hub AutoWoodcutting campfire flow: `useItemOnObject` → brief make-X probe (SPACE only if the prompt shows; single log type auto-burns since 12 Aug 2026) → confirm via tending animation or first log consumed; 3 failed starts stop with a Discord notify.
  - Merge check: `grep "BURN_STALL_WINDOW_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/firemaking/FiremakingScript.java | head -1`
- **Barbarian Fishing module (Oct 2026):**
  - New `BARBARIAN_FISHING` module — migrated from the Hub `barbarianfishing` plugin (Otto's Grotto rod fishing) onto the module contract, with the hub review findings folded in instead of carried: the dragon-harpoon spec activation is verified via `getSpecState()` before latching (a missed orb click no longer burns the whole energy cycle), the camera turn runs off the client thread, and every action is a client-mouse click (no menu injection).
  - Powerdrop with rod + feathers as locked anchors; drops slice 5–9 items per tick with count-drop verification; the post-batch break + `barbarianFishingAfk` run when a drop session completes.
  - Banking is restock-only (rod/feather missing): walk-gated to the Barbarian Outpost chest (`BankLocation.BARBARIAN_OUTPOST`); `isBankMissingMaterials` uses the retrying `hasBankItem` so a stale bank mirror cannot stop the run.
  - Merge check: `grep "HARPOON_SPEC_MAX_ATTEMPTS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/barbarianfishing/BarbarianFishingScript.java | head -1`
- **Woodcutting module (Oct 2026):**
  - New `WOODCUTTING` module — migration of the hub AutoWoodcutting plugin mapped onto the AModule contract (no FSM port): the chop loop is tick-driven (woodcutting animation = in-flight signal; log-count increases = batch tracker), banking runs through the orchestrator (`readyForBank` = `AScriptBank.bankVisible()`, travel only when no bank is in sight, 120 s stagnation watchdog), and the hub's Burn/Fletch workflows are dropped in favour of the existing Firemaking/Fletching modules.
  - Trees resolve by composition **action filter** + exact name within the configured radius of the activation anchor (the hub's name-only search could target the wrong tree type — wrong log id downstream). Clicks go through the `AScriptClick.clickObject` client-mouse recipe.
  - Drop mode slices 5–9 logs per tick (fisher pattern); Bank mode returns to the anchor after the deposit and runs a held **log basket** (filled near-full, emptied + slot-locked in `doBank` so the deposit keeps it). **Bird nests** looted from the idle path (`woodcuttingLootNests`, radius 15). Axe handling: tier-ordered enum, best tier withdrawn+equipped from ONE bank snapshot (never a per-tier scan through the retrying reads), locked-slot fallback when the equip fails, spec attack via the BVF harpoon-spec latch.
  - Merge check: `grep "WOODCUTTING_ANIMATION_IDS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/woodcutting/WoodcuttingScript.java | head -1`
- **aScript AFK-layer rollout + precision machinery removal (Oct 2026):**
  - `mlmAfk` / `barbarianVillageFisherAfk` / `jewelEnchantAfk` — default-on random-AFK layers (Jewelry/Crafting parity: unconditional short log-normal pause + 3–120 s log-normal AFK, weather-modulated, interruptible, 5 s min-gap). Triggers: MLM at the sack-empty trip falling edge (bank-free deposit-box flow); Barbarian Village Fisher once per completed cycle (inline after a drop session; bank-closed on the tick after `doBank` via `postCycleBreakPending`); Jewel Enchant at batch end with a bank-closed yield (`needsBank` returns the `jewelEnchantPostBatchBreakDone` flag for one tick).
  - Removed the dead precision-mouse machinery: `AScript.managePrecisionMouseSpeed()` + `AModule.isPrecisionModule()` + all per-module overrides — `Rs2Antiban.setActivityIntensity` is a no-op (activity intensity locked to VERY_LOW), so the block could never do anything.
  - eventdismiss QOL sleeps switched from uniform `Global.sleep(min, max)` to `Rs2Random.logNormalBounded` (lamp flow + dialogue closer).
  - Merge check: `grep "jewelEnchantPostBatchBreakDone" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantScript.java | head -1`
- **aScript Gem Crab Killer post-kill AFK layer (Oct 2026):**
  - `gemCrabKillerAfk` (default on) — the fourth module of the AFK-layer rollout: after every completed kill (kill + optional loot) the module takes the unconditional short pause + 3–120 s log-normal AFK (weather-modulated, `sleepInterruptibly`, 5 s min-gap, status `AFK (Ns)`), once per kill (`postKillBreakDone`, cleared with `hasLooted` when a new fight is engaged — a corpse still in the scene never earns a second break).
  - GCK banks through its own state machine (`needsBank()` is always false), so the house `needsBank` yield cannot take the tick: the break runs inline in the dead-crab branch with the bank still closed — before the internal `BANKING` transition when the loot filled the inventory, before the exit-crawl otherwise (the `WAITING` window starts after it, so the break never eats the wait).
  - Merge check: `grep "postKillBreak" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/gemcrabkiller/GemCrabKillerScript.java | head -1`
- **aScript bank-phase gate + cannonball travel (Oct 2026):**
  - `AModule.readyForBank(config)` (default `true`) gates the orchestrator's bank open/cache cycle. A module that must prepare first — walk to the bank area — returns `false`, so `AScript.tick` skips the bank phase and dispatches `doAction()` instead. Without it, a bank the module could not reach froze the tick **before** `doAction()` was ever dispatched again: the cannonball smelter hung forever when started away from Edgeville (status stuck on "Opening bank", no log line, no stop).
  - `AScript` now bounds consecutive failed opens (`MAX_BANK_OPEN_FAILURES = 10`) and stops loudly ("Bank Unreachable") instead of returning forever; the counter resets on the disabled branch, a successful open, `stopWithMessage` and `shutdown`.
  - Cannonball: `CannonballSmelterFurnace` carries each site's `BankLocation` (the walk anchor — bank and furnace share a region, so no furnace coordinates are needed); `readyForBank` is judged on the **object** — a bank booth/chest or GE booth in the scene and on camera (`Rs2GameObject.findBank()`/`findGrandExchangeBooth()`, the same candidates `openBank()` uses) — never on a distance to a coordinate: a visible bank is clicked directly (the game walks the last few tiles as part of the interaction), a bank in the scene but off camera gets one `Rs2Camera.turnTo`, and only a bank that is not loaded at all makes `doAction` walk (`Rs2Walker.walkTo(anchor, 4)`) behind a 120 s **no-movement** watchdog (progress = "the player moved", because a teleport can move you farther first) that stops the module loudly; the post-batch break runs once per batch instead of every tick while walking with no bars. The distance-to-anchor form was the first cut and produced exactly the anti-pattern it was meant to avoid — walking to a hardcoded point with the bank in plain sight — flagged live the day it shipped.
  - Furnace render gate: a furnace that is in the scene but not rendered yet (`getObjectClickbox` falls back to a canvas-sized rectangle — the render-distance/fog boundary) is never clicked blindly. The module waits `RENDER_WAIT_STEPS`, then turns the camera to force a re-render and retries on a later tick — clicking the canvas rectangle lands as "Walk here" (the menu-entry override does not save it) and drifts the player. Those misses use their own `MAX_RENDER_MISSES` = 20 budget (reset by any successful render) instead of `MAX_ACTION_FAILURES`; a persistent condition stops loudly. Removes the residual misclick (~1 per 10-15 min in the validated session).
  - Barbarian Village Fisher: `readyForBank` = `AScriptBank.bankVisible()`. Its `needsBank` fires with no gate, so a restock (rod/bait out, or a full inventory in `BANK_RAW`/`COOK_AND_BANK`) used to freeze the tick before its own `WALK_TO_BANK` phase could run — and the Village's bank is Edgeville, **outside** the loaded scene from the river, so that path is the normal one, not a corner case. The shared `AScriptBank.bankVisible()` / `findBankObject()` helpers are the standard gate body (cannonball + Village Fisher use them).
  - Merge check: `grep "readyForBank" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1`
- **aScript bank-material checks: confirmed verdicts + retrying reads (Oct 2026):**
  - Every module missing-materials check moved onto the **retrying** `Rs2Bank.hasBankItem(name|id, amount)` family instead of the bare `Rs2Bank.hasItem(...)` mirror read (the mirror is refreshed only by bank container events): Crafting (all six branches), Fletching (materials + the stop message text, which was inventory-only and could name an item that IS in the bank), Jewel Enchant (jewellery / cosmic runes / staff lookup), Ammonite Crabs (food / potion), Barbarian Village Fisher (rod / bait), Cannonball Smelter (mould by id).
  - `AScriptBank.hasBankItem` — the withdraw pre-check — was the worst offender: despite the name it wrapped the bare mirror read. A stale "present" sent three withdraw dispatches against a bank that no longer listed the item; a stale "absent" failed the withdraw silently (no WARN). It now uses the retrying family.
  - The orchestrator **confirms a missing verdict** before stopping: `consecutiveMissingVerdicts` must reach `MISSING_CONFIRMATION_TICKS` (3) on a **live** mirror (`getBankLiveEpoch() > 0`), bounded by `MISSING_MAX_TICKS` (12) so an unreadable mirror cannot hold the tick. A restored (non-live) snapshot is never trusted as "empty". The counter resets when the verdict is false or the script is disabled — deliberately NOT on the successful-open path, which runs every tick and would zero it before it could ever reach the threshold.
  - Stop messages are split and name the module: "No Materials (<Module>)" (confirmed empty), "Bank Cycle Failed (<Module>)" (the bank opened, the deposit/withdraw did not land — previously mislabelled "No Materials"), "Bank State Unreadable (<Module>)" (no live mirror).
  - Evidence: the only observed stop (2026-10-05 18:12, container 0) came from the doBank-failure route — three `Failed to withdraw yew longbow (u) x14` with no item click in the log at all — not from the missing-materials verdict.
  - Merge check: `grep "MISSING_CONFIRMATION_TICKS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1`
- **aScript withdraw diagnostics (Oct 2026):** `Rs2Bank.lastWithdrawMiss` (+ `getLastWithdrawMiss()`) records why a withdraw dispatch had nothing to click — the null-model lookup, the `1x1` fallback when the mirror's slot has no live widget (tab closed / list scrolled / slot re-sorted under a stale mirror), and the `Enter amount:` prompt never appearing. `AScriptBank.withdrawWithRetry` dispatches as `BooleanSupplier`, logs `hadItemToClick` + the bank epoch before→after + `resolution(nameOrId)` per attempt, and the final WARN carries both (`[<mirror slot / clickable widget state>] — <miss>`). The three `withdrawBankItem*` wrappers now return the underlying `Rs2Bank` result instead of `void`. This is the log to read when a withdraw fails 3×: it distinguishes "the bank really lacks it" from "the mirror and the live bank widget disagreed".
- **aScript post-batch break: only after a batch that actually ran (Oct 2026):**
  - The bank-closed post-batch yield (`needsBank` returning the break flag while the inventory is empty) fired on ANY empty state — including a fresh start with every material in the bank. Each of Jewel Enchant / Firemaking / Cannonball Smelter / Crafting (jewelry) now tracks `batchRanSinceBank` (set in `doAction` when materials are in hand, cleared by a completed bank cycle and on reset): the yield — and the break itself — apply only once the module has actually worked materials, and a fresh empty state banks immediately.
  - Jewel Enchant ran `doAction` before the first bank trip in exactly that state and **hard-stopped with "No cosmic runes"** (cosmics sat in the bank, nothing in hand): starting it with no jewellery in the inventory died on its first action tick, before the first bank ever opened (observed live 2026-10-09 20:01, container 1). The cosmic check is reordered after the batch-end branch and no longer stops the run — the missing-cosmic state defers to the bank cycle, where `needsBank` routes it.
  - Firemaking, Cannonball Smelter and Crafting (jewelry) carried the same startup-yield shape without the hard stop — they ran the whole post-batch break (up to the 3–120 s AFK) before their first bank trip; the same gate fixes them.
  - Merge check: `grep "batchRanSinceBank" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantScript.java | head -1`
- **aScript sequential bank acquires: keep-list so a cycle never releases its own locks (Oct 2026):**
  - `AScriptBank.ensureLocked` (behind `ensureToolLocked`/`ensureStackLocked`) releases locked slots holding a DIFFERENT item as "strays" from a previous module — but when one `doBank` acquires two anchors back-to-back, the second ensure saw the lock the first had just established as a stray: it unlocked the rod, the blanket deposit sent it back to the bank, and the cycle re-withdrew it bare (observed live 2026-10-10 at the GE booth, click for click: lock → unlock → deposit → re-withdraw, rod left unlocked). The ensure calls now take a `keepLocked` keep-list of the cycle's own items and never release those; both fishers (Village + Otto's Grotto, which shared the shape) pass rod+bait, and the Village Fisher's final safety re-withdraw goes through `ensureToolLocked` so the rod comes back locked.
  - Merge check: `grep "keepLocked" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptBank.java | head -1`
- **aScript bank close: verified dual-mechanism + hard dispatch gate (Oct 2026):**
  - `Rs2Bank.closeBank()` used to commit to one mechanism (ESC when the account's `KEYBINDING_ESC_TO_CLOSE` is on, else the `Close` child click) and trust it. The fork's `InputArbiter` **drops keyboard emission** while a human owns the input (a real button/key held, or real input within `idleResumeMs`, default 1.8 s): `Rs2Keyboard` fails the KEY_PRESSED/TYPED dispatch and `keyPress(int)` aborts with nothing sent — so with the user active at the client the ESC close was a silent no-op (5 s timeout, bank left open). Observed live 2026-10-13: fletching left the bank open after withdrawing (user watching, mouse active), the orchestrator dispatched the combine against the open bank, and the make-X never opened ("production interface did not open"); the same build with no human input closed the bank on every cycle. The mouse path is not dropped this way (coordinated through InputLoop), so `closeBank()` now verifies each mechanism and falls back to the other (ESC → click `786434`/`11`, click → ESC; 1.2 s per attempt inside the ≈5 s total) and returns the verified state. The shared `BooleanSupplier` keeps the method's compiled-lambda count at one so the guardrail baseline (keyed by lambda ordinal) stays valid.
  - `AScript.run()`'s "no module needs bank" branch now makes the closed bank a **hard precondition** before dispatching `doAction`: an open bank blocks production interfaces (make-X, smelt) for any module, so a close that did not land counts `consecutiveBankCloseFailures`, logs a WARN and returns (retry next tick) instead of acting against the bank; 5 failures in a row stop loudly ("Bank Will Not Close"). The counter resets with the other bank failure counters (on a landed close, `stopWithMessage`, `shutdown`).
  - Merge check: `grep "sleepUntil(isClosed" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/bank/Rs2Bank.java | head -1`
  - Merge check: `grep "Bank Will Not Close" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1`
- **aScript Jewel Enchant bank cycle: staff-scan cost + mid-cycle bank-close resilience (Oct 2026):**
  - `bestStaffInBank` scanned all 23 enum staffs through the retrying `Rs2Bank.hasBankItem`, which pays a 2-tick race-retry sleep for every ABSENT staff (`bankItemRaceRetryWarranted` = count 0 && bank open) — ~20 s of silent stall that read as a frozen step; a human watching took over mid-cycle (observed live 2026-10-14, container 1). It now reads ONE bank snapshot (`Rs2Bank.getAll()`), with a single 2-tick re-read when the pass finds nothing (the stale-mirror case the retrying reads existed for).
  - `doBank` reordered: the rune + jewellery withdraws run BEFORE the staff equip (the equip path interacts with the bank UI; with nothing left to withdraw, a mid-equip state change cannot break the cycle), and each withdraw step re-verifies the bank window (`ensureBankOpen()`, one reopen attempt). A bank closed mid-cycle (the user at the VNC, right after equipping the staff by hand) used to leave the remaining withdraws dispatching into nothing — and the module stopped with a misleading "No Sapphire ring in bank" while the mirror said the ring was there.
  - The inline "Banking Failed — No X in bank" notifies are gone: a failed withdraw can be non-missing (closed window, lag). `doBank` returns `false` and the orchestrator's confirmed missing-materials path owns that verdict.
  - Merge check: `grep "ensureBankOpen" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantScript.java | head -1`
- **aScript client-mouse click recipes: AScriptClick + item-on-object pilot (Oct 2026):**
  - New `ascript/util/AScriptClick.java` — the shared client-mouse click recipes ("clicks go through the client mouse"): `clickObject`/`resolveObject` (camera first, the real clickbox, the canvas-sized fallback never clicked, optional bounded render wait) and `clickActor` (the same for an NPC hull, actor lookups on the client thread, canvas size read off the lambda so the ClientThreadGuardrailTest inference is untouched). The three module-local copies were consolidated onto it without behavior change: Cannoning (object), Ammonite Crabs (actor), Cannonball Smelter (object render gate — it keeps `RENDER_WAIT_STEPS`/`MAX_RENDER_MISSES` and its entry-click `furnace.click("Smelt")`, whose conversion is still pending).
  - New item-on-X recipe (`useItemOnObject` / `useItemOnItem`): a physical click on the item's inventory slot → verified selection → a physical click on the target (an entity clickbox, or another inventory slot for item-on-item). Every failure path clears the selection (the `eatReady` click-at-current-position idiom — a dangling cursor item silently converts the next inventory op). This recipe did not exist anywhere in the fork before.
  - Non-default op pick (`clickActorOp(actor, option)`): the same resolved clickbox, right-clicked, with OUR option's row picked out of the open option menu — the only recipe that can reach an op a plain left-click can never dispatch. Stable menu anchor before row geometry, one client-thread snapshot per read, bounded retries (a menu that comes up without our row is closed and the right-click repeated), in-recipe confirmation = menu closed on our row; confirming the EFFECT stays with the caller's gate. Consumers: BVF "Bait" (F1) and the random event handler's "Dismiss" (F3).
  - Firemaking is the pilot: `Rs2Inventory.useItemOnObject(logId, fireId)` (logs→fire) and `use(TINDERBOX)` + `useLast(logId)` (tinderbox→logs) are gone — both dispatches are physical clicks through the recipe now. The make-X probe, the tending-animation / first-log-consumed / Firemaking-XP confirmations, the 20 s burn-stall window and the failure-counter stop are unchanged; a failed dispatch counts its strike at the click instead of at the effect wait. No menu-entry injection remains in the module.
  - Merge check: `grep "class AScriptClick" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptClick.java | head -1`
  - Merge check: `grep "AScriptClick.useItemOnItem" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/firemaking/FiremakingScript.java | head -1`
  - Merge check: `grep "clickActorOp" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptClick.java | head -1`
- **aScript Jewel Enchant contained-inventory sweep + armed-cast gate (Oct 2026):**
  - A staff equip displaces the previously worn item into the inventory; the module deliberately left it for "the next clean bank cycle" (its own comment), so the stray held a slot for the whole batch — and permanently when the run stopped first (live report 2026-10-16: the displaced Dramen staff sat while the enchant clicks ran, and the user flagged the extraneous clickable as an equip hazard).
  - `doBank()` now ends with `AScriptBank.depositAllExcept(keptIds)` — a new shared sweep: keep-set = cosmic + the level's runes + the raw jewellery; mechanism = lock the keepers → toolbar deposit (the house unlock+toolbar pattern mirrored) → unlock. Locked slots and the XP lamp are never touched, and a keeper that cannot be locked aborts the sweep with nothing deposited (retried next cycle). The inventory now holds exactly the run's materials through the batch.
  - The enchant dispatch verifies the spell actually armed before the inventory left-click: `Rs2Inventory.isItemSelected()` (true for a selected spell; guardrail-baselined util, not a raw client call) with a bounded wait + one re-click — an unarmed click degrades to WEAR under the cursor (second half of the report).
  - Landed-cast confirmation switched from "raw count dropped" to "ENCHANTED side count rose": `JewelEnchantItem` carries `enchantedIds` (every charge variant per piece, wiki-verified; ornamente/imbued excluded) and `JewelEnchantScript.enchantedCount()` sums them — a misclick-wear drops the raw count and must no longer read as a successful enchant.
  - Merge check: `grep "depositAllExcept" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptBank.java | head -1`
  - Merge check: `grep "getEnchantedIds" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantItem.java | head -1`
- **aScript inventory-click slot randomization: AScriptPick (Oct 2026):**
  - New `ascript/util/AScriptPick.java` — the shared slot pick for every inventory item a module clicks (either end of a Use chain). `pick(...)` resolves the item TYPE with the plain `Rs2Inventory.get` rules (fuzzy name matching and the shortest-name tie-break preserved) and draws the INSTANCE with a log-normal rank over the candidates in slot order, so a module working a 14-item batch no longer clicks the same pixel every cycle (`Rs2Inventory.get` always returns the lowest matching slot). Contract: it changes WHICH candidate is clicked, never WHICH candidates qualify, so a converted site cannot silently retarget a different item.
  - Opt-outs are code-level and named — `AScriptPick.first(...)` / `AScriptPick.last(...)` — each with its reason at the call site; `grep -rn "AScriptPick\.\(first\|last\)" ascript/` is the exception list. Jewel Enchant's enchant target is the one shipped opt-out: `last(...)` stays because the armed cast consumes the first-of-type item, so a drawn slot near the front can already have been converted when the click lands. A config toggle was rejected on purpose — it would let a user silently restore the constant pattern.
  - Converted sites (identical filters, only the instance varies): Crafting (amethyst block, uncut gem, bolt-tip cut gem, molten glass, orb, staff, dragon leather), Fletching (unstrung bow AND the bow string — both non-stackable, so both draw), Firemaking (logs→fire, and the tinderbox→log light whose pinned *last* log was inherited from the removed `useLast` injection), Barbarian Village Fisher (raw fish→fire). Tool singletons (chisel, glassblowing pipe, needle, tinderbox) stay on `Rs2Inventory.get` — one instance held at a time, nothing to draw.
  - Rank shape (n = 14): ranks 1-3 hold ~54% of the mass, the last rank ~2.5%. The three-argument `Rs2Random.logNormalBounded(1, n, 1.0)` is deliberate — the 2-arg overload scales its ceiling by the weather multiplier (ranks above n) and the raw body forces a non-positive minimum to 1 (so the index-0-based form can never draw the first item). Slot choice is not timing: no weather term.
  - Guardrail: `AScriptPickTest` — rank within [1, n] for every n in 1..28 (560k draws), first-three mass, and "the last rank stays reachable but rare".
  - Drop sites (Woodcutting, Barbarian Fishing, Barbarian Village Fisher, and Motherload Mine's gem drop) use the helper's second shape, `AScriptPick.nextDropTarget(candidates, cursor)`: an UNIFORM first draw, then a `15 + 100 × WeatherModulation.mistakeProbabilityOffset()` % roll (15 → 24 in rain/gust) between another uniform draw and one of the 2–4 slots nearest the cursor — the window is a mirrored log-normal, so it tends to 4 ("sweep the neighbours, then jump", never 27 corner-to-corner teleports). This replaced `Rs2Inventory.dropAll/dropAllExcept`, which walked slot order AND dispatched through `invokeMenu` injection with a fixed `sleep(150, 300)` — the drop paths are client-mouse now, and each module keeps its slice size (5–9/tick where it had one) plus the count-drop verification. Left alone on purpose: `drop("hammer")` (single tool) and GCK's `interact(...)` HP-lowering clicks (single instance in practice).
  - Food (the eat path) is the third shape, `AScriptPick.pickFood(foods)`: the heal priority is unchanged — biggest per-bite heal first (`Rs2Food`), unknown heals score 0 and rank last, the caller still owns the unnoted/blighted filtering — and only the INSTANCE inside the top-heal group is drawn, with the same log-normal rank as every other click target. `AScript.eatBestFood` (the QOL auto-eat) already calls it; a module that owns its own eating should use it instead of picking the lowest food slot.
  - Merge check: `grep "class AScriptPick" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptPick.java | head -1`
  - Merge check: `grep "AScriptPick.pick" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/CraftingScript.java | head -1`
  - Merge check: `grep "AScriptPick.last" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantScript.java | head -1`
- **Documentation:** `ascript/AGENTS.md` — structure, adding modules, anti-detection patterns, weather modulation, banking pitfalls

### Project Rename
- **Files:** `settings.gradle.kts`, `runelite-client/build.gradle.kts`, `build-number.txt`
- **What:** Renames project to `microbot_afss0` and shaded jar artifact to `microbot_afss0-<version>.jar` to distinguish from upstream builds.

### Build ID — Random Hex (anti-collision)
- **Files:** `runelite-client/build.gradle.kts`
- **What:** `shadowJar` and `microbotReleaseJar` embed a random 32-bit build id in hex (e.g. `b3f7a2c9`) in the JAR filename: `microbot_afss0-<version>-b<8 hex>-shaded.jar`. Identical filenames are practically impossible, so a stale build can never collide with a previously shipped jar. Replaces the old sequential counter — `build-number.txt` was removed on 2026-09-26 (no more counter file to restore before commits/merges).

## Build Fixes

Upstream merge breakage resolved without modifying any upstream RuneLite source files.

### AgilityOverlay RS2Item Adaptation
- **File:** `AgilityOverlay.java`
- **What:** Adapted `marksOfGrace` from `List<Tile>` to `List<RS2Item>`. The fork's `AgilityPlugin` changed the field type to `RS2Item` (which wraps a `Tile`), but the upstream overlay still expected `List<Tile>`. Fixed by importing `RS2Item` and calling `.getTile()` on each entry.

### ETAOverlayPanel Travel Time Inlining
- **File:** `ETAOverlayPanel.java`
- **What:** Inlined `calculateTravelTime()` directly in the overlay class. The original code called `RunEnergyPlugin.calculateTravelTime()`, a static method that was removed from upstream during a merge. Rather than patching upstream `RunEnergyPlugin`, the travel time simulation (run/walk/recovery cycle) was reimplemented as a private static method in this Microbot overlay.

---

## Merge Checklist

When merging upstream, verify these files/features are present:

```
grep -r "WeatherModulation" runelite-client/src/main/java/ | head -5
grep -r "detectable automation" runelite-client/src/main/java/ | head -5
grep -r "bindAllInterfaces" runelite-client/src/main/java/ | head -5
grep "return Math.max(0, configuredChebyshev);" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java | head -1
grep -r "Skip the tile" runelite-client/src/main/java/ | head -5
grep "microbot_afss0" settings.gradle.kts
grep "return 15" runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/ShortestPathConfig.java | head -1
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java
grep "interactionInProgress" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/VirtualMouse.java | head -3
grep "inputWasAlreadyDisabled" runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java | head -3
grep "InputSelector.disableClick\|InputSelector.enableClick" runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java | head -3
grep "cursorTracker" runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java | head -3
grep "getCurrentTarget" runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java | head -3
grep "generateBuildId" runelite-client/build.gradle.kts | head -1
# aScript + weather mouse modulation
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AGENTS.md
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/CraftingScript.java
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/eventdismiss/
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/cannonballsmelter/
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/cannoning/
grep "yieldBankingToLamp" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/eventdismiss/LampUtility.java | head -1
grep "weatherAdjustedOvershoots" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/naturalmouse/util/FactoryTemplates.java | head -1
grep "mistakeProbabilityOffset" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/VirtualMouse.java | head -1
grep "windGustFactor" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/misc/Rs2UiHelper.java | head -1
grep "logNormalBounded.*multiplier" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/math/Rs2Random.java | head -1
# weather timing in Rs2Random + activity intensity lock (2026-09-26)
grep "weatherMultiplier" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/math/Rs2Random.java | head -1
grep "intensity is locked" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/antiban/Rs2Antiban.java | head -1
grep "effectiveMax" runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/math/Rs2RandomLogNormalBoundedTest.java | head -1
# aScript client-mouse click recipes (2026-10-11)
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptClick.java
grep "useItemOnObject(logItem, fire)" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/firemaking/FiremakingScript.java | head -1
# aScript woodcutting module (2026-10-15)
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/woodcutting/
grep "WOODCUTTING_ANIMATION_IDS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/woodcutting/WoodcuttingScript.java | head -1
# aScript jewel-enchant contained-inventory sweep + armed-cast gate (2026-10-16)
grep "depositAllExcept" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptBank.java | head -1
grep "getEnchantedIds" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantItem.java | head -1
# aScript Ammonite random-AFK layer + unified Afk defaults (2026-10-18)
grep "ammoniteCrabAfk" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/ammonitecrabs/AmmoniteCrabsScript.java | head -1
grep "AFK_MIN_GAP_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/ammonitecrabs/AmmoniteCrabsScript.java | head -1
grep -A1 "default boolean craftingAfk" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -2
grep -A1 "default boolean fletchingAfk" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -2
```
