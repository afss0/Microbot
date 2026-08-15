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
  - `Rs2AntibanSettings.java` — `weatherEnabled`, `weatherLat`, `weatherLon`, `weatherCityName` fields
  - `NaturalMouse.java` — `SpeedManager` wrapper applies weather factor to mouse movement
  - `MasterPanel.java` + `NavigationPanel.java` — ⛅ Weather tab in antiban UI

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

### isGoalReachable Guard
- **File:** `Rs2Walker.java` → `tightFinishThreshold()`
- **What:** Skips tight finish cap when goal tile is reachable from player's current position (no wall/door blocking). Prevents walker from forcing 1-2 tile proximity when direct path exists.

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
- **What:** `onBotInteractionEnd()` and `cursorTracker` skip when `Rs2Walker.getCurrentTarget() != null`. Input IS still disabled during walker clicks (prevents user interference), but grace period and cursor return are suppressed — the walker manages its own lifecycle. When the walker finishes (currentTarget cleared), the next `onBotInteractionEnd()` transitions to GRACE_PERIOD normally. Uses the public `getCurrentTarget()` API.

## Build

### Project Rename
- **Files:** `settings.gradle.kts`, `runelite-client/build.gradle.kts`, `build-number.txt`
- **What:** Renames project to `microbot_afss0` and shaded jar artifact to `microbot_afss0-<version>.jar` to distinguish from upstream builds.

### Build Number Auto-Increment
- **Files:** `runelite-client/build.gradle.kts`, `build-number.txt`
- **What:** `shadowJar` and `microbotReleaseJar` now include a build number (`b0001`, `b0002`, ...) in the JAR filename. `build-number.txt` at repo root auto-increments on each `assemble` run. Format: `microbot_afss0-<version>-<buildN>-shaded.jar`. Ensures every build produces a uniquely-named artifact.

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
grep -r "isGoalReachable" runelite-client/src/main/java/ | head -5
grep -r "Skip the tile" runelite-client/src/main/java/ | head -5
grep "microbot_afss0" settings.gradle.kts
grep "return 15" runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/ShortestPathConfig.java | head -1
ls runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java
grep "interactionInProgress" runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/VirtualMouse.java | head -3
grep "inputWasAlreadyDisabled" runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java | head -3
grep "InputSelector.disableClick\|InputSelector.enableClick" runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java | head -3
grep "cursorTracker" runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java | head -3
grep "getCurrentTarget" runelite-client/src/main/java/net/runelite/client/plugins/microbot/mousesync/MouseSyncPlugin.java | head -3
grep "getAndIncrementBuildNumber" runelite-client/build.gradle.kts | head -1
```
