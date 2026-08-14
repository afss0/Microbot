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

### ShortestPath Default Distances
- **File:** `ShortestPathConfig.java`
- **What:** `recalculateDistance` 10→15, `reachedDistance` 5→10. More lenient thresholds reduce unnecessary path recalculations.

## Mouse Sync

### Cursor Synchronization with User
- **Files:** `MouseSyncPlugin.java`, `MouseSyncConfig.java` (new package: `mousesync/`)
- **What:** When enabled, the bot disables user mouse input during interactions, waits a grace period after the interaction completes, then naturally moves the cursor (via NaturalMouse) back to the user's physical OS mouse position and re-enables input. Prevents "teleporting" and user/bot input conflicts.
- **State machine:** IDLE → BOT_ACTIVE → GRACE_PERIOD → RETURNING → IDLE
- **Emergency hotkey:** CTRL+X — immediately stops plugin and restores mouse control. Plugin disables itself via `Microbot.stopPlugin()`.
- **Config:** `enabled` (default false), `gracePeriodMs` (default 5000), `emergencyHotkey` (default CTRL+X)
- **Integration:** VirtualMouse hooks `onBotInteractionStart()` / `onBotInteractionEnd()` in all click/drag methods. Standalone moves also trigger start/end.
- **Re-enable:** Toggle the plugin off and on via the Microbot plugin list.

## Build

### Project Rename
- **Files:** `settings.gradle.kts`, `runelite-client/build.gradle.kts`, `build-number.txt`
- **What:** Renames project to `microbot_afss0` and shaded jar artifact to `microbot_afss0-<version>.jar` to distinguish from upstream builds.

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
```
