package net.runelite.client.plugins.microbot.ascript;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.ascript.crafting.CraftingScript;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.discord.Rs2Discord;

import java.awt.*;
import java.util.concurrent.TimeUnit;

/**
 * aScript — AIO orchestrator (standard Script, no state machine).
 * <p>
 * Each tick: check config → resolve phase → bank if needed → craft.
 * Sub-scripts (crafting, etc.) are dispatched directly.
 * <p>
 * If a sub-script detects missing materials in both bank AND inventory,
 * it stops immediately and sets scriptSelection to NONE.
 */
@Slf4j
public class AScript extends Script {

    /** High-level phase exposed to the overlay. */
    public enum Phase { DISABLED, IDLE, BANKING, CRAFTING, ERROR }

    private AScriptConfig config;

    @Getter
    private Phase currentPhase = Phase.DISABLED;

    private boolean stopRequested;
    /** Last time the auto zoom-out ran (rate-limited — players don't re-zoom every tick). */
    private long lastZoomOutTime;
    /** Antiban intensity to restore when the Crafting module deactivates (null = nothing captured). */
    private ActivityIntensity previousMouseIntensity;
    /** True while we've forced VERY_LOW for the Crafting module. */
    private boolean craftingMouseSpeedApplied;

    // ── Sub-scripts ─────────────────────────────────────────────
    private final CraftingScript craftingScript = new CraftingScript();
    private CraftingScript.Phase craftingActivity = CraftingScript.Phase.NONE;

    // ── Main loop ───────────────────────────────────────────────

    public boolean run(AScriptConfig config) {
        this.config = config;
        log.info("[AScript] Starting AIO script");

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return; // heartbeat / pause guard
                tick();
            } catch (Exception ex) {
                log.error("[AScript] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    private void tick() {
        // 0. Antiban: Very Low mouse speed while the Crafting module is active,
        // restore the previous intensity when it stops / switches module
        manageCraftingMouseSpeed();

        // 1. Config check
        if (config == null || !config.enabled() || config.scriptSelection() == ScriptType.NONE) {
            currentPhase = Phase.DISABLED;
            stopRequested = false;
            return;
        }

        // 1b. QOL: keep the camera zoomed out (rate-limited —
        // players don't re-zoom every tick)
        if (config.autoZoomOut()
                && System.currentTimeMillis() - lastZoomOutTime > 60_000) {
            lastZoomOutTime = System.currentTimeMillis();
            Rs2Camera.zoomOutFully();
        }

        // 2. Resolve crafting activity
        craftingActivity = craftingScript.resolvePhase(config);

        // 2b. Invalid sub-selection (e.g. GEM_CUTTING with no gem) → stop once, clear message
        String selectionError = craftingScript.validateSelection(config, craftingActivity);
        if (selectionError != null) {
            if (!stopRequested) {
                stopRequested = true;
                Microbot.status = "STOPPED — " + selectionError;
                log.warn("[AScript] Invalid configuration, stopping: {}", selectionError);
                notifyDiscord("aScript Stopped", selectionError);
                if (Rs2Bank.isOpen()) {
                    Rs2Bank.closeBank();
                    sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
                }
                craftingScript.resetExitFlag();
                Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            }
            currentPhase = Phase.ERROR;
            return;
        }

        // 3. Missing materials from both bank+inventory → stop once
        if (craftingActivity != CraftingScript.Phase.NONE
                && craftingScript.needsBank(config, craftingActivity)) {

            // Open bank to check stock; if opening fails, wait for the next tick.
            // Checking materials against a stale/empty bank snapshot (bank never opened)
            // causes false "no materials" stops.
            if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
                currentPhase = Phase.BANKING;
                return;
            }

            if (craftingScript.isBankMissingMaterials(config, craftingActivity)) {
                if (!stopRequested) {
                    stopRequested = true;
                    String missing = craftingScript.describeMissing(config, craftingActivity);
                    Microbot.status = "STOPPED — " + missing;
                    log.warn("[AScript] No materials in bank or inventory, stopping: {}", missing);
                    notifyDiscord("aScript Stopped — No Materials", missing);
                    Rs2Bank.closeBank();
                    sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
                    craftingScript.resetExitFlag();
                    Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
                }
                currentPhase = Phase.ERROR;
                return;
            }
            // Bank HAS materials — fall through to BANKING
        } else {
            // Inventory has everything (or no activity) — close bank if open
            if (Rs2Bank.isOpen()) {
                Rs2Bank.closeBank();
                sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
            }
        }

        // 4. Dispatch
        if (craftingActivity == CraftingScript.Phase.NONE) {
            currentPhase = Phase.IDLE;
            return;
        }

        if (craftingScript.needsBank(config, craftingActivity)) {
            currentPhase = Phase.BANKING;
            craftingScript.doBank(config, craftingActivity);
        } else {
            currentPhase = Phase.CRAFTING;
            craftingScript.doCraft(config, craftingActivity);
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────

    /**
     * Forces {@link ActivityIntensity#VERY_LOW} mouse speed while the Crafting
     * module is active and restores the previous intensity when it stops or the
     * user switches to another module.
     * <p>
     * Note: {@code Rs2Antiban.setActivityIntensity} is global (it also disables
     * dynamic intensity), so the previous value must be captured and restored —
     * otherwise other scripts would inherit Very Low after this one stops.
     */
    private void manageCraftingMouseSpeed() {
        boolean craftingActive = config != null && config.enabled()
                && config.scriptSelection() == ScriptType.CRAFTING;

        if (craftingActive && !craftingMouseSpeedApplied) {
            previousMouseIntensity = Rs2Antiban.getActivityIntensity();
            Rs2Antiban.setActivityIntensity(ActivityIntensity.VERY_LOW);
            craftingMouseSpeedApplied = true;
            log.debug("[AScript] Mouse speed -> VERY_LOW (Crafting active), previous: {}",
                    previousMouseIntensity);
        } else if (!craftingActive && craftingMouseSpeedApplied) {
            if (previousMouseIntensity != null) {
                Rs2Antiban.setActivityIntensity(previousMouseIntensity);
            }
            craftingMouseSpeedApplied = false;
            log.debug("[AScript] Mouse speed restored to {}", previousMouseIntensity);
        }
    }

    /**
     * Send Discord notification if webhook is configured.
     */
    private void notifyDiscord(String title, String message) {
        try {
            String playerName = Microbot.getClient().getLocalPlayer() != null
                    ? Microbot.getClient().getLocalPlayer().getName() : "Unknown";
            Rs2Discord.sendCustomNotification(
                    title,
                    message,
                    Rs2Discord.convertColorToInt(Color.ORANGE),
                    playerName,
                    "aScript"
            );
        } catch (Exception e) {
            log.warn("Failed to send Discord notification: {}", e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        stopRequested = false;
        craftingScript.resetExitFlag();
        currentPhase = Phase.DISABLED;
        super.shutdown();
    }
}
