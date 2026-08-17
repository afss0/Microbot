package net.runelite.client.plugins.microbot.ascript;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.crafting.CraftingScript;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * aScript — AIO state-machine orchestrator.
 * <p>
 * States model the high-level lifecycle; sub-scripts (crafting, etc.)
 * are dispatched from the active state.
 * <p>
 * If a sub-script detects missing materials in both bank AND inventory,
 * it stops immediately (sets activity to NONE). Banking is still part
 * of the automation loop when inventory is empty but bank has stock.
 */
@Slf4j
public class AScript extends StateMachineScript<AScript.State> {

    enum State {
        DISABLED,
        IDLE,
        BANKING,
        CRAFTING,
        ERROR
    }

    private AScriptConfig config;
    private boolean actionDone;
    private boolean stopRequested; // prevent repeated exit attempts

    // ── Sub-scripts ─────────────────────────────────────────────
    private final CraftingScript craftingScript = new CraftingScript();
    private CraftingScript.Phase craftingPhase = CraftingScript.Phase.NONE;

    @Override
    protected State initialState() {
        return State.DISABLED;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                // DISABLED → IDLE when enabled
                Transition.<State>from(State.DISABLED)
                        .when(() -> config != null && config.enabled() && config.scriptSelection() != ScriptType.NONE,
                                "enabled && script != NONE")
                        .because("Script enabled")
                        .goTo(State.IDLE),

                // IDLE → BANKING when inventory needs materials (bank has stock)
                Transition.<State>from(State.IDLE)
                        .when(this::craftingNeedsBank, "craftingNeedsBank()")
                        .because("Inventory empty, bank has stock")
                        .goTo(State.BANKING),

                // IDLE → CRAFTING when ready to craft
                Transition.<State>from(State.IDLE)
                        .when(() -> !craftingNeedsBank() && craftingPhase != CraftingScript.Phase.NONE,
                                "!craftingNeedsBank() && phase != NONE")
                        .because("Materials ready, crafting")
                        .goTo(State.CRAFTING),

                // BANKING → IDLE when bank done
                Transition.<State>from(State.BANKING)
                        .when(() -> actionDone, "actionDone")
                        .because("Banking complete")
                        .goTo(State.IDLE),

                // CRAFTING → IDLE when craft action done
                Transition.<State>from(State.CRAFTING)
                        .when(() -> actionDone, "actionDone")
                        .because("Crafting action complete")
                        .goTo(State.IDLE),

                // Any active state → DISABLED when script set to NONE
                Transition.<State>from(State.IDLE)
                        .when(() -> config != null && config.scriptSelection() == ScriptType.NONE,
                                "script == NONE")
                        .because("Script disabled")
                        .goTo(State.DISABLED),
                Transition.<State>from(State.BANKING)
                        .when(() -> config != null && config.scriptSelection() == ScriptType.NONE,
                                "script == NONE")
                        .because("Script disabled")
                        .goTo(State.DISABLED),
                Transition.<State>from(State.CRAFTING)
                        .when(() -> config != null && config.scriptSelection() == ScriptType.NONE,
                                "script == NONE")
                        .because("Script disabled")
                        .goTo(State.DISABLED),

                // Any non-error state → ERROR on config null
                Transition.<State>from(State.IDLE)
                        .when(() -> config == null, "config == null")
                        .because("Config lost")
                        .goTo(State.ERROR),
                Transition.<State>from(State.BANKING)
                        .when(() -> config == null, "config == null")
                        .because("Config lost")
                        .goTo(State.ERROR),
                Transition.<State>from(State.CRAFTING)
                        .when(() -> config == null, "config == null")
                        .because("Config lost")
                        .goTo(State.ERROR)
        );
    }

    @Override
    protected void onState(State state) {
        actionDone = false;

        switch (state) {
            case DISABLED:
                break;

            case IDLE:
                if (stopRequested) return; // already stopping, don't loop

                craftingPhase = craftingScript.resolvePhase(config);

                // If materials/tools are missing from inventory, check if bank has stock
                if (craftingPhase != CraftingScript.Phase.NONE
                        && craftingScript.needsBank(config, craftingPhase)) {

                    // Open bank to check stock (and keep it open for BANKING state)
                    if (!Rs2Bank.isOpen()) {
                        Rs2Bank.openBank();
                    }

                    // Bank is open — check if it has materials
                    if (craftingScript.isBankMissingMaterials(config, craftingPhase)) {
                        // Bank has NO materials — stop the script (once)
                        stopRequested = true;
                        String missing = craftingScript.describeMissing(config, craftingPhase);
                        Microbot.status = "STOPPED — " + missing;
                        log.warn("[AScript] No materials in bank or inventory, stopping: {}", missing);
                        Rs2Bank.closeBank();
                        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
                        craftingPhase = CraftingScript.Phase.NONE;
                        craftingScript.resetExitFlag();
                        Microbot.getConfigManager().setConfiguration("ascript", "scriptSelection", ScriptType.NONE);
                    }
                    // If bank HAS materials, fall through to actionDone = true
                    // Bank stays open for BANKING state to use
                } else {
                    // Inventory has all materials — close bank if open (heading to CRAFTING)
                    if (Rs2Bank.isOpen()) {
                        Rs2Bank.closeBank();
                        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
                    }
                }

                actionDone = true;
                break;

            case BANKING:
                if (craftingScript.doBank(config, craftingPhase)) {
                    actionDone = true;
                }
                break;

            case CRAFTING:
                craftingScript.doCraft(config, craftingPhase);
                actionDone = true;
                break;

            case ERROR:
                log.warn("[AScript] In error state");
                break;
        }
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        actionDone = false;
        // Reset flags when script is re-enabled
        if (from == State.DISABLED && to == State.IDLE) {
            stopRequested = false;
            craftingScript.resetExitFlag();
        }
    }

    @Override
    protected State onError(State state, Exception e) {
        log.error("[AScript] Error in state {}: {}", state, e.getMessage(), e);
        return State.ERROR;
    }

    // ── Delegation helpers ──────────────────────────────────────

    private boolean craftingNeedsBank() {
        return config != null
                && config.scriptSelection() == ScriptType.CRAFTING
                && craftingPhase != CraftingScript.Phase.NONE
                && craftingScript.needsBank(config, craftingPhase);
    }

    // ── Lifecycle ───────────────────────────────────────────────

    public boolean run(AScriptConfig config) {
        this.config = config;
        log.info("[AScript] Starting AIO script");

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                step();
            } catch (Exception ex) {
                log.error("[AScript] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }
}
