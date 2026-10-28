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

### HWID spoofing off by default
- **Files:** `MicrobotConfig.java`
- **What:** `spoofDeviceId()` defaults to `false`. The upstream switch and the `hwid-agent` stay in the tree, but the fork never publishes `microbot.hwid.spoof=true` on its own. Identity handling is external: each Microbot sandbox container gets its own machine-id/product_uuid/MAC, and the agent is deliberately not attached there.
- **Why:** Without the agent attached, the upstream default only produces "Spoof device ID is on, but the HWID agent is not attached" warnings at every login.
- **Merge check:** `grep -A3 "default boolean spoofDeviceId" runelite-client/src/main/java/net/runelite/client/plugins/microbot/MicrobotConfig.java | grep "return false"`

## Agent Server

### Remote Access (bindAllInterfaces)
- **Files:** `AgentServerConfig.java`, `AgentServerPlugin.java`, `AgentHandler.java`
- **What:** Adds `bindAllInterfaces` config option to bind agent server to `0.0.0.0` instead of `127.0.0.1`, allowing connections from other machines on the network (e.g. via Tailscale).
- **Security:** `AgentHandler` bypasses loopback check when enabled. Config warning recommends firewall or Tailscale ACL.

## Entity API

### Tile-Object Impostor Resolution (`Rs2TileObjectModel`)
- **File:** `Rs2TileObjectModel.java`
- **What:** Three fork fixes for multiloc objects whose impostor shape is not resolved yet — restored 2026-10-20 after `7224ac34c8` (the feat/custom recompaction) brought back the upstream bodies:
  - `getName()` null-guards the object composition (an unresolved impostor dereferenced null → NPE in the interact path).
  - `getObjectComposition()` keeps the base composition when `getImpostor()` is null, instead of replacing it with null (a multiloc whose shape is not resolved used to read as "no composition").
  - `getVisibleObjectComposition()` returns the impostor only when it is resolved, and is the composition `interact()` uses — so `getImpostor()`/`getImpostorIds()` are read on the client thread instead of from a script thread (the client-thread guardrail flags the latter).
- **Tests:** `Rs2TileObjectModelCompositionTest`, `ClientThreadGuardrailTest`.

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

### Banked Transport Withdrawal — Note Mode + Confirmation
- **Files:** `Rs2Walker.java` → `walkWithBankingState()` / `prepareTransportLoadoutAtBank()`; `banking/WithdrawNoteModePolicy.java`; `banking/TransportWithdrawalConfirmation.java`
- **What:** Restored 2026-10-20 after `7224ac34c8` dropped the wiring (the policy classes survived, only their tests referenced them):
  - `WithdrawNoteModePolicy` — when the loadout needs a non-stackable transport item while the bank is in noted-withdraw mode, the withdrawal switches to item mode first (`Rs2Bank.setWithdrawAsItem()`) and the player's setting is restored in a `finally`. Without it the item arrives as banknotes the route cannot consume and the walk exits.
  - `TransportWithdrawalConfirmation` — the withdrawal is confirmed from the carried inventory quantity of both the requested and the actually withdrawn id (a noted withdrawal lands as a different id) over an 8 s window, instead of trusting the menu dispatch. The equip step resolves the provider across the same id set.
- **Tests:** `WithdrawNoteModePolicyTest`, `TransportWithdrawalConfirmationTest`.

### Route Camera Policy
- **Files:** `Rs2Walker.java` → `alignCameraTowardWalkTarget()`; `geometry/RouteCameraPolicy.java`
- **What:** Restored 2026-10-20. The camera turns only when the target is not clickable in the scene (or a recent scene click on it failed), before the turn throttle and view-variation gates — instead of turning after every click behind an unconditional `distance < 4` early return plus a 1.2 s throttle.
- **Tests:** `RouteCameraPolicyTest`.

### Ranged First-Route Interaction
- **Files:** `Rs2Walker.java` → `checkFirstRouteInteractionAtRange()` / `handleFirstRouteInteractionAtRange()`; `RouteSceneObjectSnapshot.java`; `RouteObjectRangePolicy.java`
- **What:** Restored 2026-10-20 after `7224ac34c8` dropped the pass from `processWalk` (leaving both policy classes and their tests orphaned, plus `collectRouteTransportsForInteractionScan()` callerless). Before an active minimap interim yields the tail pass, the walker selects the first pending route interaction from one bounded scene snapshot (`RouteSceneObjectSnapshot.capture`) and gates ranged transport dispatch on `RouteObjectRangePolicy.isRangedObjectDispatchEligible` (object-executor transports, plus short fence/railing agility shortcuts). While that pass owns the interaction, the segment handler loop and the interim continuation click are suppressed.
- **Tests:** `RouteSceneObjectSnapshotTest`, `RouteObjectRangePolicyTest`, `FirstRouteInteractionWiringTest`.

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
- **What:** AIO script hosting multiple automation scripts under one plugin, built on standard `Script` (Phase enum: DISABLED/IDLE/WAITING/BANKING/RUNNING/ERROR — migrated from StateMachineScript). `Phase` describes the **automation** state only; QOL state (auto-eat) is reported separately. Modules are plain helper classes (no Guice, no Script inheritance).
- **Current modules:**
  - **Crafting** (`ascript/crafting/`) — Gem Cutting, Glassblowing, Staff Making, Flax Spinning, Dragon Leather, Jewelry
  - **Random event handler** (`ascript/eventdismiss/`) — always-on Genie/Count Check dismissal + XP lamp use (lamp skill configurable); banking interlock keeps lamps out of deposits (locked lamp slot + bank yield)
  - **Cannonball Smelter** (`ascript/cannonballsmelter/`) — smelt steel bars into cannonballs (Edgeville, Shilo Village, Prifddinas, Port Phasmatys); mould as a locked slot; Jewelry-style random-AFK layer (`cannonballAfk`)
  - **Firemaking** (`ascript/firemaking/`) — burn logs on fires / forester's campfires (GE corners, Castle Wars); deadlock-free tick-driven burn tracking (stall re-engage + relight), ID-only fire lookup, locked tinderbox slot; Jewelry-style random-AFK layer (`firemakingAfk`)
  - **Barbarian Fishing** (`ascript/barbarianfishing/`) — powerfish leaping fish at Otto's Grotto (drop-only); rod + feather locked slots, walk-gated bank at the Barbarian Outpost chest, spec actuation verified before latching, sliced humanized mouse drops; Jewelry-style random-AFK layer (`barbarianFishingAfk`)
  - **Cannoning** (`ascript/cannoning/`) — maintain a placed dwarf multicannon: repairs it when it breaks (broken object id + `MCANNON_DECAYED` varbit, both verified before it counts) and reloads it from the inventory when `CANNON_AMMO` drops to the configured threshold (≥ 15 balls); latches only the player's own cannon from the `OWNEDMCANNON` varp (a stranger's cannon at a shared spot is never clicked), client-mouse clicks behind a render-race budget, loud actionable stop when the cannonballs run out (never banks); Jewelry-style random-AFK layer (`cannonAfk`, hard-capped at 50 s)
  - **Woodcutting** (`ascript/woodcutting/`) — chop trees (Drop/powercut or Bank) + log basket + bird-nest looting; action-filtered tree lookup around the activation anchor, best-axe auto-equip from one bank snapshot, axe spec (BVF latch), Jewelry-style random-AFK layer (`woodcuttingAfk`)
  - **Mining** (`ascript/mining/`) — mine ore rocks (Drop/powermine or Bank) with action-filtered rock lookup around the activation anchor, Progressive mode (best ore by level + travel to a verified mine), pickaxe auto-manage (worn > inventory > best from one bank snapshot) + spec latch, gem-mine deposit-box cycle, basalt Snowflake noting, clay bracelet (withdraw + bank-closed wear), opt-in crowded-world hop, Jewelry-style AFK layer (`miningAfk`)
  - **Bolt Enchant** (`ascript/boltenchant/`) — enchant gem-tipped crossbow bolts (10 gem types × regular/dragon) with the single "Enchant Crossbow Bolt" spell: one interface click starts the game's 10-cast repeat (up to 100 bolts) and the module waits the batch out (count-drop tracking, `STALL_MS` guard against mid-batch clicks); cosmic locked anchor, elemental-staff rune coverage via the shared `Rs2Staff` catalogue, Jewelry-style AFK layer (`boltEnchantAfk`)
- **Anti-detection:**
  - All timing uses `Rs2Random.logNormalBounded()` with log-normal distributions (not uniform)
  - AFK timing: `Rs2Random.logNormalBounded(3000, 60000, weatherMultiplier)`
  - Animation waits: `Rs2Random.logNormalBounded(15000, 45000, weatherMultiplier)`
  - Widget interactions use `sleepUntilTrue()` for production widget detection (no fixed sleeps)
  - Missing materials → immediate stop with status message (no silent failures)
- **Weather integration:** `WeatherModulation.ensureFresh()` called once per craft cycle; `combinedSpeedFactor()` scales all waits
- **Config:** Automation section (enabled + dropdown) at position 0, QOL section at position 1, per-module sections (closedByDefault) after them in alphabetical order — see the config-order entry below
- **Review fixes (Aug 2026):**
  - Flax Spinning now walks to the configured spinning wheel (`flaxSpinLocation`), turns camera, interacts with "Spin"; stops once + Discord notify when wheel/location missing (was: no navigation at all)
  - `tick()` honors `Rs2Bank.openBank()` return value — failed open retries next tick instead of checking stock against an empty bank snapshot (false "missing materials" stop)
  - Added `CraftingScript.validateSelection()` — invalid sub-selections (activity set, sub-type NONE) stop early with clear status instead of timeout loops
  - Removed dead/misleading config: `jewelryCompletionAction` (never implemented), `PROGRESSIVE` entries in CraftingGlass/CraftingStaff (behaved like NONE)
  - Amethyst branch waits for the multi-product production widget first, clicks the product, then answers the quantity prompt (the old order waited for the amount dialog before the product existed and timed out)
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
- **aScript craft-batch latch, amethyst sequence, level guards (Oct 2026):**
  - Every non-jewelry craft method (gems, glass, staffs, flax, dragon leather) now uses the same **cross-tick batch latch** as jewelry/cannonballs: dispatch → confirm the batch actually started (animation or material count drop) → latch (`batchMaterialRef` / `batchLastCount` / `batchLastProgressAt`, 8 s stall window) so the following ticks only wait and never re-dispatch. The old `sleepUntil(!Microbot.isGainingExp || !hasItem(...), 15–45 s)` wait was already true before the first XP drop, so it returned immediately, the method reported success, the failure counter reset, and the next tick re-clicked the same production recipe — the batch-was-dispatched-once-per-tick defect. A batch that does not start now returns `false` and counts toward `MAX_CONSECUTIVE_CRAFT_FAILURES`.
  - The shared trailing sleep/AFK is skipped while a batch is latched (jewelry parity), so a running batch is paced by its own in-flight branch instead of firing the AFK layer every tick.
  - Amethyst gem cutting follows the real UI order: chisel-on-block opens the multi-product production widget, the configured product is clicked there, and only then is the "How many do you wish to make?" quantity step answered (`enableQuantityOption("All")`).
  - Crafting-level capability guards moved into `validateSelection()` (gems + jewelry) so a level-locked selection stops with a message **before** the bank trip; a `getRealSkillLevel()` read of 0 is treated as "skill data loading" and re-checked next tick instead of stopping the run.
  - Jewelry furnaces are per-location object ids on `JewelryLocation` (Edgeville 16469, Falador/Port Phasmatys 24009, Mount Karuulm 34342, Zanaris 12100, Shilo Village 30157/30158) instead of one hardcoded 16469 that only resolved at Edgeville; the stop message names the location's own ids.
  - Fletching `validateSelection()` gates darts/bolts/arrows on the data enum's `meetsLevelRequirement()` (also 0-read tolerant); `lastProgressAt` is reset with the counters in the jewelry and cannonball batch scopes; dragon-leather needle checks use `AScriptBank.hasUnnotedItem`.
  - The **"Fletch into Bolt Tips"** continuation is actually reachable and latched: `needsBankGems()` yields (reports no bank need) while the cut stack + an unnoted chisel can still be processed, so the tick reaches `doAction()` instead of depositing the cut gems; the uncut-stack-exhausted path no longer clears the latch unconditionally (a `"Ruby"` bolt-tip batch would otherwise re-dispatch every tick), and the cut-gem count/pick use **exact** names so `"Ruby"` cannot resolve an `"Uncut ruby"` stack. `CraftingGem.boltTips` gates the continuation to the gems that have a bolt-tip recipe — a cut zenyte is jewellery stock, and without the gate it would idle forever instead of banking.
  - Merge check: `grep "CRAFT_STALL_WINDOW_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/CraftingScript.java | head -1`
  - Merge check: `grep "isBoltTips" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/CraftingScript.java | head -1`
  - Merge check: `grep "getFurnaceObjectIds" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/JewelryLocation.java | head -1`
  - Merge check: `grep "fletchingLevelLoaded" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/fletching/FletchingScript.java | head -1`
- **Withdraw retry hardening (Oct 2026):**
  - `AScriptBank.withdrawVerified*` no longer fails on a single 3 s verify miss: each attempt verifies in two windows (3 s main + 2 s grace — a landing that is merely late under transient lag is caught by the grace window; live cause of a spurious `Failed to withdraw bow string x14` whose items landed ~3–6 s after the click) and an unconfirmed dispatch is retried up to 3 attempts total with exponential backoff (≈0.8 s / 1.6 s, jittered via `Rs2Random.waitEx`).
  - Each retry waits the backoff and re-checks the inventory **before** re-clicking (a slow landing is confirmed, never double-withdrawn); the bank window is left open across attempts — the loop never closes/re-opens it.
  - The `Failed to withdraw` WARN fires only after all attempts are exhausted; late-landing recoveries log at DEBUG.
  - Merge check: `grep "WITHDRAW_GRACE_TIMEOUT_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptBank.java | head -1`
- **Bolt Enchant module (Oct 2026):**
  - New `BOLT_ENCHANT` module (`ascript/boltenchant/`) — the interface's own batch is the action: clicking a bolt's row in the SKILLMULTI bolt panel makes the game repeat the cast ten times (up to 100 bolts) and stop by itself, so the module clicks **once per batch** and tracks it across ticks by the unenchanted count (a 10-bolt drop = progress). No batch latch: a finished repeat and a dead batch are the same branch (stale count + ≥ 10 bolts ⇒ one row click), so pause/resume and human takeovers self-heal.
  - Mid-batch clicks are the hazard (a row click while the repeat runs cancels it): the click is unreachable until the count has been still for `STALL_MS` (7 s), with `count` drops refreshing the timestamp every cast. Runes are checked before the click, so a batch that stopped because the runes ran out routes to the bank cycle instead of spending a failure life.
  - Spellbook entry is `218/13` (gameval `MagicSpellbook.XBOWS_ENCHANT`, action "View") — the Hub's `218.10` no longer exists. The row is resolved by item id first, then by the enchanted label; ids are the legacy `ItemID` pairs (the gameval `..._ENCHANTED_2.._5` copies are not the tradeable item).
  - Banking mirrors Jewel Enchant: cosmic locked anchor, staff first (worn > held > bank, worn with the bank closed), contained-inventory sweep. Staff mode is an optimisation — no usable staff anywhere means every rune is carried, never a stop.
  - Merge check: `grep "STALL_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/boltenchant/BoltEnchantScript.java | head -1`
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
- **Mining module (Oct 2026):**
  - New `MINING` module — migration of the hub Auto Mining plugin (v1.0.14) mapped onto the AModule contract (no FSM port): the mine loop is tick-driven (mining animation = in-flight signal; product counts = batch tracker), banking runs through the orchestrator (`readyForBank` = `AScriptBank.bankVisible()`), and the hub's injected deposit/drop paths are replaced by the toolbar deposit and sliced client-mouse drops.
  - Findings designed out: the per-item `depositAll(Predicate)` grid-silent loop (toolbar deposit now), injected `dropAllExcept` with fixed order (sliced `nextDropTarget` drops now), the unverified `setSpecState(1000)` tick spam (verified latch now), the dead `withdrawAndEquip` clay bracelet path (withdrawVerified + bank-closed wear), the unchecked basalt noting trip (verified recipe + noted-state confirmation + return to the anchor), the never-enforced `membersOnly` flag and the bank-counting item requirements in the ported location data, and `Rs2Inventory.interact` injection in the gem branch (client-mouse click now).
  - Progressive location data ported from `MiningRockLocations` with the unused `ResourceLocationOption` subsystem dropped and each flagged entry wiki-verified (Al Kharid gem rocks removed unless confirmed; etc.).
  - Merge check: `grep "MINING_ANIMATION_IDS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/mining/MiningScript.java | head -1`
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
- **aScript cleanup round (Oct 2026):**
  - The inline "Banking Failed — No X in bank" notifies are gone from Crafting, Fletching, Cannonball Smelter, Barbarian Village Fisher, Ammonite Crabs, Firemaking and Jewel Enchant: a failed withdraw can be non-missing (closed window, lag), and the orchestrator's confirmed missing-materials verdict owns that message. The pickaxe verdicts in Mining/MLM stay — they fire after the bank was actually checked.
  - Duplicate `@ConfigSection` positions resolved: QOL 4→13, Jewel Enchant 5→14, Ammonite Crabs 7→15 (every section now has a unique position).
  - `AScriptPlugin.startUp()` no longer does `pauseAllScripts.compareAndSet(true, false)`: enabling aScript used to resume scripts paused by the user or by a break handler. Scripts start from the overlay/panel action only.
  - `ascript/AGENTS.md` module table: the cannon row points at the real package (`ascript/cannoning/`) and Mining (`ascript/mining/`) is listed.
  - Merge check: `grep -rn "Banking Failed" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript --include=*.java | grep -v mining | grep -v motherloadmine` (expect empty)
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
  - Merge check: `grep "enchantedIds" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantItem.java | head -1`
- **aScript Jewel Enchant staff sourcing: inventory-first, bank only when strictly better (Oct 2026):**
  - The module treated the elemental staff as "worn or in the bank" only — `isWearingStaffFor` read the equipment slot, `bestStaffInBank` the bank snapshot — so a staff the player was CARRYING counted as no staff at all. Starting the script with a compatible staff in the inventory opened the bank, found no staff there and hard-stopped on the missing-materials verdict: "Sapphire ring enchant: no elemental staff (not worn, not in bank)", 21 s into the first bank cycle (live 2026-10-18 15:31, container 1).
  - One resolution now feeds every staff decision (`staffToWear` over `bestStaffFor`): worn > held > bank, where "better" is the coverage of the level's staff-coverable runes and ties go to the item already at hand — so a held staff that does not cover the spell's rune needs in full lets a strictly better bank staff win. A worn staff is still never upgraded.
  - `needsBank` no longer forces a bank trip for a staff that is merely held (only when the bank holds a strictly better one, or when nothing usable is in hand); `isBankMissingMaterials`/`describeMissing` count a held staff as usable, and the verdict text is now "no usable elemental staff (not worn, not held, not in bank)". A staff covering none of the level's runes (an air staff for Lvl-5) stays "not usable" everywhere.
  - `doAction.ensureStaffWorn()` is the wear site for the no-bank-trip path (a staff in the bag with nothing else missing): client mouse on its inventory slot with the bank CLOSED (with it open an inventory left-click is a deposit, never a Wear), one bounded re-click, skipped for the tick while a cast is armed.
  - The staff is WORN FIRST inside a bank cycle too (`doBank` step 0, before any deposit or withdraw): the module wears it there and hands the cycle back, so the next tick reruns the restock with the staff on. A staff that exists only in the bank is withdrawn with the verified, grid-free `AScriptBank.withdrawVerified` BEFORE the jewellery fill — that fill takes every remaining slot, so a staff withdraw after it can never fit. `closeBankForWear()` closes the window for the wear click (bounded wait; a bank that will not close retries the cycle instead of clicking the staff into the bank).
  - No slot lock and no keep-set entry is involved for the staff: an equipped item is never deposited, so "protect the staff across the cycle" stops being a question once the wear happens first. (The first cut of this fix did lock the slot and thread it through the keep-sets — correct, but needless complexity, and it protected the wrong thing: the deposit that actually banked the staff runs in step 1, before any keep-set exists.)
  - `Rs2Bank.withdrawAndEquip` / `Rs2Bank.withdrawOne(id)` are dead dispatches in the live client (silent `false`, zero `MenuEntryImpl` lines — observed 2026-10-18: the module looped "Failed to equip STAFF_OF_WATER" → "Bank Cycle Failed" three times; the method injects an op index derived from the `BANK_QUANTITY_TYPE` varbit instead of clicking). The module no longer calls either anywhere.
  - Merge check: `grep "ensureStaffWorn" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantScript.java | head -1`
  - Merge check: `grep "closeBankForWear" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantScript.java | head -1`
  - Merge check: `grep -c "withdrawAndEquip" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantScript.java` (expect 0 real calls — comments only)
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

- **aScript multi-material withdraws: inventory-slot reservation + bank-truth batch sizing (Oct 2026):**
  - `withdrawVerified(name)` routes to `withdrawAll`, which bails on a full inventory (`Rs2Bank`). With two non-stackable materials per batch the first withdraw filled the bag and the second could never land: Staff Making withdrew every battlestaff (orbs got nothing), gem jewelry withdrew every bar, and Fletching stringing hardcoded 14+14 against a bank stack that may be smaller (`findBankItem(..., amount)` returns null for a short stack, so the dispatch found no row at all and clicked nothing).
  - New `AScriptBank` helpers: `freeSlots()` (= `Rs2Inventory.emptySlotCount()`, so a held tool / XP lamp is already net of the count), `bankQuantity(nameOrId)` (bank units for pre-withdraw planning, with one bounded re-read on a stale mirror) and `ensureBankOpen()` (the Jewel Enchant pattern, shared: reopen a mid-cycle close once, otherwise treat it as a retry).
  - `bankStaffs()`: `batch = min(13, freeSlots/2, staffs, orbs)` — battlestaves AND orbs are non-stackable, so a pair costs two slots; 13 is the classic half-bag batch. Both withdraws verify their count. `bankJewelry()`: bars capped at `freeSlots - 1` (one slot reserved for the cut-gem stack, one gem per bar) and the gem count verified. `FletchingScript.bankBows()`: `batch = min(freeSlots/2, unstrung, bow strings)` with both sides verified. A locked tool/lamp slot therefore shrinks the batch instead of failing the cycle.
  - A closed bank window is reported as a RETRY, never as a missing material: `ensureBankOpen()` runs before each withdraw step, `withdrawWithRetry` and `withdrawVerified(name, quantity)` both guard `Rs2Bank.isOpen()` first, and the batch-planning / withdraw-failure paths log the real cause + set `Microbot.status` instead of raising a per-retry "No X in bank" Discord verdict (the orchestrator's confirmed missing-materials verdict and its "Bank Cycle Failed" counter own the stop).
  - Merge check: `grep "public static int freeSlots" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptBank.java | head -1`
  - Merge check: `grep "AScriptBank.freeSlots" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/CraftingScript.java | head -1`
  - Merge check: `grep "AScriptBank.bankQuantity" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/fletching/FletchingScript.java | head -1`
- **aScript item identity: names, ids and tool catalogues (Oct 2026):**
  - **Widget labels are matched by substring, so a wrong name is a silent miss.** The jewelry batch clicked the production option by `JewelryItem.getName()`; `DRAGONSTONE_BRACELET` was named "Dragon bracelet" while the live option reads "Dragonstone bracelet" (renamed 2015) — the click could never land, the retry expired and 3 failures stopped the batch. `UNSTRUNG_SYMBOL` was "Holy symbol" (the *strung* item); the item is `Unstrung symbol` (1714). `productLabels()` now returns the candidate labels (item name first; the symbol also accepts the legacy "Holy symbol" label, the one interface label that could not be confirmed against the live interface) and `craftJewelry` tries each instead of one fixed name.
  - **The `ItemID` bracelet constants are the cache dummies.** `ItemID.GOLD_BRACELET` = 11068 and `ItemID.SAPPHIRE_BRACELET` = 11071; the real items are `JEWL_GOLD_BRACELET` 11069 and `JEWL_SAPPHIRE_BRACELET` 11072. `JewelryItem.SAPPHIRE_BRACELET` already used 11072; `GOLD_BRACELET` (11068) and `JewelEnchantItem.SAPPHIRE_BRACELET` (11071) are fixed. Latent today — `JewelryItem.itemID` is not read by any code path — but the enchant module's id is live (bank/inventory lookups).
  - **Tool catalogues are one source of truth, level-gated.** `Axe` now carries `woodcuttingLevel` + a `specialAttack` flag and lists every tier/variant (gilded, 3rd age, infernal, trailblazer, crystal, and the 2H "felling" bronze→crystal line): `SPEC_AXE_IDS` is derived from it (`Axe.specialAttackAxeIds()`), so the spec block can no longer see an axe `hasAxe()` does not. `hasAxe()`/`bestAxeInBank()` filter by Woodcutting level; a level read of 0 means "skill data loading" and does NOT filter. `Pickaxe` gained `DRAGON_PICKAXE_PRETTY` 12797 (upgraded) and `_3A_PICKAXE`, and its black-pickaxe levels were wrong (6/10 → Mining 11/Attack 10, per the wiki); selection reads the level through `Rs2Player` (client-thread wrapped) instead of `Microbot.getClient()` directly.
  - **Rod/bait/feathers resolve by id.** `"Fishing rod"` is a substring of `"Fly fishing rod"` and `"Feather"` of every coloured feather, and the bank lock/withdraw match is a case-insensitive substring: Barbarian Village Fisher now carries `FISHING_ROD_ID`/`FLY_FISHING_ROD_ID`/`FISHING_BAIT_ID`/`FEATHER_ID` for every inventory/bank/drop check and passes numeric ids to `ensureToolLocked`/`ensureStackLocked`; Barbarian Fishing does the same for `BARBARIAN_ROD`/`FEATHER`. Display names survive only as notification text (`getRodLabel`/`getBaitLabel`). Fletching's darts/bolts use the same treatment for their plain feather (314) via the new `AScriptBank.hasUnnotedItem(int)` id-exact overload (`"feather"` also matches the coloured feathers, so a name check could report unusable feathers as present), and Crafting's Dragon Leather needle is resolved by id (1733 / costume 29920) for the presence check, the tool lock and the Use-on-leather click — `"needle"` is a substring of `"Costume needle"`.
  - Guardrail: `ItemIdentityAuditTest` pins the labels, the dummy-vs-real ids, the amulet `_U` ids, the axe/pickaxe catalogues and their level gates.
  - Merge check: `grep "productLabels" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/JewelryItem.java | head -1`
  - Merge check: `grep "specialAttackAxeIds" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/woodcutting/Axe.java | head -1`
  - Merge check: `grep "FISHING_ROD_ID" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/barbarianvillagefisher/BarbarianVillageFisherScript.java | head -1`
  - Merge check: `grep "DRAGON_PICKAXE_PRETTY" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/motherloadmine/Pickaxe.java | head -1`
- **Inventory drops install the Drop entry as the left-click (Oct 2026):**
  - A raw left click on an inventory slot dispatches the item's **default** action — `Use` for raw fish, ore and logs — so the sliced droppers used every item instead of dropping it (live report 2026-10-24), and the count-drop verification stopped the module.
  - Fix, in the aScript plugin only (no shared Microbot API change): `AScriptClick.dropAtSlot(slot, itemId)` uses Menu Entry Swapper mechanics. The client builds the slot's real menu entries on hover; `AScriptClick.onPostMenuSort()` (subscribed by the aScript plugin, same `client.isMenuOpen()` guard as MES) moves the item's real `Drop` entry to the top of that array, so the left click dispatches the game's own entry. No fabricated entry, no injection, no open menu, one click per drop.
  - `AScriptPick.dropRoute(itemId)` precomputes the route: the slots holding that item right now, shuffled once (Fisher-Yates), so a pass never draws a slot twice; `dropAtSlot` re-reads the slot and skips one already emptied — one pass clears the bag.
  - Modules: BVF, Barbarian Fishing, Mining, Motherload Mine (gems + hammer cleanup), Woodcutting.
  - Merge check: `grep "dropAtSlot" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptClick.java | head -1`
  - Merge check: `grep -c "dropRoute" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptPick.java`
- **aScript automation/QOL split + asynchronous auto-eat (Oct 2026):**
  - The tick is now two independently gated halves: `runQol(config)` (today: auto-eat) and the module dispatch. QOL keeps working with the Automation switch off, and `Phase` describes automation only — the overlay reports QOL state on its own line. This is why `Phase.CRAFTING` was renamed `RUNNING`: it names the automation phase, not the activity, and every module keeps its own private phase enum.
  - Auto-eat no longer blocks the 600 ms loop. The tick dispatches the Eat/Drink and returns; `EatTracker` confirms the bite on a later tick (unnoted food count dropped **or** boosted hitpoints rose) and is the only writer of the attempt state. Baselines are captured **before** the dispatch, so a fast bite that lands first is not read as "nothing happened".
  - Deliberately no strike counter and no suspension: `TIMED_OUT` clears the attempt and the caller schedules a randomized retry (`Rs2Random.logNormalBounded(600, 2000, weatherMultiplier)`), so a human takeover, a pause, lag or a late bite are ordinary observations rather than a failure streak. A confirmed bite re-rolls the eat threshold, and a second bite may follow on the same tick if HP is still low.
  - Merge check: `grep "class EatTracker" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/EatTracker.java | head -1`
  - Merge check: `grep "private void runAutoEat" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1`
- **aScript game-readiness hold + `WAITING` phase (Oct 2026):**
  - The tick can no longer act on a client that is not ready. `GameReadinessMonitor` decides from built-in state only — `GameState.LOGGED_IN` (`Microbot.isLoggedIn()`), a loaded local player, a scene with a real `WorldView.getBaseX()`, and `Client.getGameCycle()` — and returns the reason (`NOT_LOGGED_IN`, `PLAYER_NOT_LOADED`, `REGION_NOT_LOADED`, `LOOP_STALLED`).
  - The liveness signal is the **game cycle**, not `Client.getTickCount()`: the tick count only advances when RuneLite processes a server tick, so lag or a long region load freezes it on a healthy client and a probe watching it reports a stalled loop. A frozen game cycle for `STALL_MS` (10 s ≈ 500 cycles) means the client loop itself is dead.
  - While not ready the tick is held before any module runs, `Microbot.status` carries `WAITING FOR GAME — <reason>` and the reason is logged once per change (`Game ready after N ms — resuming` on recovery). With automation requested the hold becomes `Phase.WAITING` and times out after `GAME_READY_TIMEOUT_MS` (60 s) with a loud stop (`aScript Stopped — Game Not Ready`); with the switch off or no module selected it holds indefinitely, because the player may sit on a loading screen for as long as they like.
  - The monitor is pure and client-free on purpose (`update(Sample, now)` takes primitives), so the whole truth table is unit-tested without a live client (`GameReadinessMonitorTest`); it is stateful only for the stall window.
  - Merge check: `grep "class GameReadinessMonitor" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/GameReadinessMonitor.java | head -1`
  - Merge check: `grep "WAITING FOR GAME" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1`
  - Merge check: `grep "GAME_READY_TIMEOUT_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1`
- **aScript config section order (Oct 2026):**
  - The config panel sorts by `position`, not by source order: `Automation` stays at 0, `QOL` at 1, and every module section follows in **alphabetical order of its `@ConfigSection(name = ...)` display name** from position 2 (Ammonite Crabs 2 … Woodcutting 16). Inserting a module means renumbering every section after it — a duplicate position silently reorders the panel, it does not error.
  - Merge check: `grep -A1 'name = "QOL"' runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -2`
  - Merge check: `grep -c "@ConfigSection(" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java`
- **aScript bank close only on the running → idle transition (Oct 2026):**
  - The disabled-branch cleanup used to infer "this run left the bank open" from `lastSelection != NONE`. `lastSelection` is written at the top of every tick even while the Enabled checkbox is off, so from the second tick on the condition was true with automation disabled and the loop closed **any** bank the player opened, every 600 ms (live 2026-10-26).
  - The one-shot close is now gated on `runWasActive` (set when a tick actually ran automation, cleared by the cleanup itself): a self-stop or a mid-run disable still closes the bank that run left open, exactly once, while a bank the player opened while the script was idle is left alone. Reset in `run()` alongside the other per-run state.
  - Merge check: `grep "private boolean runWasActive" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1`
- **aScript pre-click hover travel: `AScriptClick.moveNaturally` (Oct 2026):**
  - Never place the cursor with `Microbot.getMouse().move(point)` before a click. `VirtualMouse.move` emits a single MOVED event (a teleport) and `NaturalMouse.moveTo` then early-returns because the pointer already rests on the destination, so the following click becomes an instantaneous press with no natural path and no weather speed factor.
  - `AScriptClick.moveNaturally(point)` owns the travel: NaturalMouse path with weather modulation, no corrective teleport when NaturalMouse is available, and a retry (no click) when the pointer does not settle. Call sites: `dropAtSlot`, `inventoryOptionAtSlot` (the item-on-X selection), the private `select(Rs2ItemModel)` Use arming, and Jewel Enchant's cast hover — so drops and Use-chain clicks do not teleport either.
  - Merge check: `grep "moveNaturally" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptClick.java | head -1`
  - Merge check: `grep -c "getMouse().move(point)" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantScript.java` (expect 0)
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
grep "getVisibleObjectComposition" runelite-client/src/main/java/net/runelite/client/plugins/microbot/api/tileobject/models/Rs2TileObjectModel.java | head -1
# walker: banked transport note mode + confirmation, route camera policy, ranged first-route interaction (2026-10-20)
grep "WithdrawNoteModePolicy.shouldSwitchToItemMode" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java | head -1
grep "TransportWithdrawalConfirmation.start" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java | head -1
grep "RouteCameraPolicy.decide" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java | head -1
grep "RouteSceneObjectSnapshot.capture" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java | head -1
grep "RouteObjectRangePolicy.isRangedObjectDispatchEligible" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java | head -1
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
grep "enchantedIds" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/jewellenchant/JewelEnchantItem.java | head -1
# aScript Ammonite random-AFK layer + unified Afk defaults (2026-10-18)
grep "ammoniteCrabAfk" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/ammonitecrabs/AmmoniteCrabsScript.java | head -1
grep "AFK_MIN_GAP_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/ammonitecrabs/AmmoniteCrabsScript.java | head -1
grep -A1 "default boolean craftingAfk" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -2
grep -A1 "default boolean fletchingAfk" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -2
# aScript mining module (2026-10-18)
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/mining/
grep "MINING_ANIMATION_IDS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/mining/MiningScript.java | head -1
grep "MINING(\"Mining\")" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/ScriptType.java | head -1
grep "new MiningScript()" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1
grep "default MiningOre miningOre" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -1
# aScript multi-material withdraws: slot reservation + bank-truth batch sizing (2026-10-21)
grep "public static int freeSlots" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptBank.java | head -1
grep "AScriptBank.freeSlots" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/crafting/CraftingScript.java | head -1
grep "AScriptBank.bankQuantity" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/fletching/FletchingScript.java | head -1
# upstream wiring that a rebase once dropped with --theirs (2026-10-23)
# Run `:client:compileTestJava` too: these losses compile clean in production and only show up there.
grep "clientAlertReporter.start" runelite-client/src/main/java/net/runelite/client/plugins/microbot/MicrobotPlugin.java | head -1
grep "tour.install(navButton)" runelite-client/src/main/java/net/runelite/client/plugins/microbot/MicrobotPlugin.java | head -1
grep "hwidSpoofCheck.loggedIn" runelite-client/src/main/java/net/runelite/client/plugins/microbot/MicrobotPlugin.java | head -1
grep "Microbot.inputSelector = new InputSelector" runelite-client/src/main/java/net/runelite/client/plugins/microbot/MicrobotPlugin.java | head -1
grep "withdrawPreviousWalkScopedBlocks" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java | head -1
grep "QUICK_CLICK_SETTLE_MIN_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/VirtualMouse.java | head -1
# upstream helpers a rebase dropped (2026-10-24): walker auto-run + agent trace
# Rs2WalkerMovement/Rs2WalkerDoors call these; without them the post-rebase gate fails.
grep "static boolean walkFastCanvasWithAutoRun" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java | head -1
grep "static void requestAutoRun" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java | head -1
grep "KEY_TRACE" runelite-client/src/main/java/net/runelite/client/plugins/microbot/agentserver/AgentServerConfig.java | head -1
grep "AgentTrace.isEnabled" runelite-client/src/main/java/net/runelite/client/plugins/microbot/agentserver/handler/AgentHandler.java | head -1
grep "object is no longer in a loaded world view" runelite-client/src/main/java/net/runelite/client/plugins/microbot/api/tileobject/models/Rs2TileObjectModel.java | head -1
# aScript automation/QOL split + async auto-eat (2026-10-26)
grep "class EatTracker" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/EatTracker.java | head -1
grep "private void runAutoEat" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1
grep "default boolean autoEat" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -1
# aScript game-readiness hold + WAITING phase (2026-10-27)
grep "class GameReadinessMonitor" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/GameReadinessMonitor.java | head -1
grep "WAITING FOR GAME" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1
grep "GAME_READY_TIMEOUT_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1
grep "enum Phase { DISABLED, IDLE, WAITING, BANKING, RUNNING, ERROR }" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1
# aScript config section order (2026-10-27)
grep -A1 'name = "QOL"' runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -2
grep -A1 'name = "Ammonite Crabs"' runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -2
# aScript bank close only on running -> idle + natural hover travel (2026-10-26)
grep "private boolean runWasActive" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScript.java | head -1
grep "moveNaturally" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/util/AScriptClick.java | head -1
# aScript bolt enchant module (2026-10-28)
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/boltenchant/
grep "STALL_MS" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/boltenchant/BoltEnchantScript.java | head -1
grep "boltEnchantAfk" runelite-client/src/main/java/net/runelite/client/plugins/microbot/ascript/AScriptConfig.java | head -1
```
