package net.runelite.client.plugins.microbot.ascript;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.ascript.crafting.CraftingScript;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;

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
        // 1. Config check
        if (config == null || !config.enabled() || config.scriptSelection() == ScriptType.NONE) {
            currentPhase = Phase.DISABLED;
            stopRequested = false;
            return;
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
                if (Rs2Bank.isOpen()) {
                    Rs2Bank.closeBank();
                    sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
                }
                craftingScript.resetExitFlag();
                Microbot.getConfigManager().setConfiguration("ascript", "scriptSelection", ScriptType.NONE);
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
                    Rs2Bank.closeBank();
                    sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
                    craftingScript.resetExitFlag();
                    Microbot.getConfigManager().setConfiguration("ascript", "scriptSelection", ScriptType.NONE);
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

    @Override
    public void shutdown() {
        stopRequested = false;
        craftingScript.resetExitFlag();
        currentPhase = Phase.DISABLED;
        super.shutdown();
    }
}
