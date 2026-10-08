package net.runelite.client.plugins.microbot.ascript.eventdismiss;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.client.plugins.microbot.BlockingEvent;
import net.runelite.client.plugins.microbot.BlockingEventPriority;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class DismissNpcEvent implements BlockingEvent {

    /**
     * Deadline for every dialogue wait/loop phase. Hitting it gives up instead of holding
     * the script-pause gate: a dialogue that never closes — or an unforeseen dialogue shape
     * — must not be able to freeze every script (the upstream plugin already hit this class
     * of bug once with a leftover dialogue).
     */
    private static final int DIALOGUE_PHASE_TIMEOUT_MS = 30_000;

    /** Average bound for the Genie lamp wait: rolled per wait with the house jitter. */
    private static final int LAMP_WAIT_AVG_MS = 5000;

    private final AScriptConfig config;
    private final AtomicBoolean waitingForLamp = new AtomicBoolean(false);
    /** Wall-clock deadline of the current lamp wait; 0 while no wait is active. */
    private volatile long lampWaitDeadline = 0L;

    /**
     * Lamp count captured BEFORE interacting with the event NPC. The lamp-wait phase ends
     * when this count rises (a new lamp actually arrived) or the NPC leaves — a lamp
     * already in the inventory must never read as this event's reward.
     */
    private final AtomicInteger lampsBeforeWait = new AtomicInteger(0);

    /**
     * Set once a Count Check accept cycle has been driven. While it is set, this Count Check
     * is only ever dismissed — never talked to again — so a cycle that ended without a lamp
     * (failed checks, no reward, or an unresolved dialogue) cannot become a talk loop.
     * Cleared when the random event NPC is gone, so a future Count Check is treated as fresh.
     */
    private volatile boolean countCheckTried = false;

    public DismissNpcEvent(AScriptConfig config) {
        this.config = config;
    }

    private Rs2NpcModel getRandomEventNpc() {
        var oldModel = Rs2Npc.getRandomEventNPC();
        if (oldModel == null) return null;
        return Microbot.getRs2NpcCache().query().where(n -> n.getNpc().equals(oldModel.getRuneliteNpc())).nearest();
    }

    /** Number of XP lamps in the inventory — the wait compares before/after counts. */
    private static int countLamps() {
        return Rs2Inventory.count(item -> item.getId() == ItemID.LAMP);
    }

    /**
     * Rolls the lamp-wait window: ~5 s on average with the house jitter, floored at 2 s.
     * The wait is advanced by the event requeue loop (short execute() calls), so it never
     * blocks the scripts — only the final lamp use holds the script-pause gate.
     */
    private static int rollLampWaitWindow() {
        return Math.max(2000, (int) Rs2Random.randomGaussian(LAMP_WAIT_AVG_MS, 1000));
    }

    /**
     * After interacting with the event NPC it loses focus and despawns on its own
     * (<~5 s). Waiting for that here keeps the next validate() from starting a whole
     * new interaction — a lingering Genie is never re-talked ("Have a nice day" loops).
     * Bounded: a stubborn NPC ends the wait at the timeout.
     */
    private void awaitNpcUnfocus() {
        Global.sleepUntil(() -> getRandomEventNpc() == null, 5000);
    }

    @Override
    public boolean validate() {
        if (waitingForLamp.get()) {
            return true;
        }
        Rs2NpcModel npc = getRandomEventNpc();
        if (npc == null) {
            // The random event is over — a future Count Check is a fresh one.
            countCheckTried = false;
            return false;
        }
        return npc.hasLineOfSight();
    }

    @Override
    public boolean execute() {
        if (waitingForLamp.get()) {
            boolean done = handleLampWait();
            if (done) awaitNpcUnfocus();
            return done;
        }

        Rs2NpcModel npc = getRandomEventNpc();
        if (npc == null)
            return true;

        String name = npc.getName();
        if (name == null)
            return true;

        // Change A: humanised pre-action delay, 4–40 s log-normal and weather-modulated —
        // the same timing pattern as the aScript modules. It runs inside the event, so the
        // script-pause gate holds the scripts for its duration; the rare long tail is the
        // intended behaviour, do not "optimise" it away. The explicit-multiplier overload is
        // used because the two-argument one already applies weather on its own.
        WeatherModulation.ensureFresh();
        double weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();
        Global.sleep(Rs2Random.logNormalBounded(4000, 40000, weatherMultiplier));

        // A Count Check whose accept cycle already ran is only ever dismissed from now on.
        boolean accept = shouldAcceptLamp(name) && !("Count Check".equals(name) && countCheckTried);
        if (accept) {
            if (Rs2Inventory.isFull()) {
                log.info("Inventory full — waiting for space to accept lamp from {}", name);
                return false;
            }
            boolean done = acceptLamp(npc, name);
            if (done) awaitNpcUnfocus();
            return done;
        }

        dismiss(npc);
        return !validate();
    }

    private boolean shouldAcceptLamp(String npcName) {
        if ("Genie".equals(npcName)) {
            return config.eventDismissGenieAction() == EventAction.ACCEPT;
        }
        if ("Count Check".equals(npcName)) {
            return config.eventDismissCountCheckAction() == EventAction.ACCEPT;
        }
        return false;
    }

    private boolean acceptLamp(Rs2NpcModel npc, String name) {
        boolean countCheck = "Count Check".equals(name);

        // Count the lamps BEFORE interacting: the receive check waits for this count to
        // rise — a lamp already in the inventory (stranded from an earlier failure) must
        // never read as this event's reward.
        int lampsBefore = countLamps();

        npc.click("Talk-to");
        // Both the Genie and the Count Check random event are "Click here to continue"
        // dialogues (the options dialogue on the wiki belongs to the standing Lumbridge
        // Count Check, not the random event) — one bounded closer covers both.
        continueDialogueUntilClosed();

        if (countCheck) {
            // The talk cycle has been driven — this Count Check is done being talked to.
            countCheckTried = true;
            // The lamp can land a tick after the dialogue closes; short grace for the
            // count to rise.
            Global.sleepUntil(() -> countLamps() > lampsBefore, 1500);
        }

        if (countLamps() > lampsBefore) {
            log.info("Lamp received — using on {}", config.eventDismissLampSkill());
            Global.sleep(Rs2Random.logNormalBounded(600, 1200));
            if (LampUtility.useLamp(config.eventDismissLampSkill())) {
                return true;
            }
        }

        if (countCheck) {
            // Ended without a usable lamp (failed checks / no reward / the lamp could not be
            // used now): end the interaction. Dismiss the NPC; if it lingers, the
            // countCheckTried marker routes later passes to dismiss-only.
            dismiss(npc);
            return !validate();
        }

        // Genie: no new lamp yet (or it arrived but could not be consumed) — wait for the
        // count to rise, or for the NPC to disappear / be dismissed.
        log.info(countLamps() > lampsBefore
                ? "Lamp received but not consumed yet — retrying"
                : "Waiting for the lamp to appear in inventory");
        waitingForLamp.set(true);
        lampsBeforeWait.set(lampsBefore);
        lampWaitDeadline = System.currentTimeMillis() + rollLampWaitWindow();

        Global.sleep(Rs2Random.logNormalBounded(600, 1200));

        if (countLamps() > lampsBefore) {
            if (LampUtility.useLamp(config.eventDismissLampSkill())) {
                resetLampWaitState();
                return true;
            }
        }

        return false;
    }

    /**
     * Advances a "Click here to continue" dialogue until it closes. Deadline-bounded: a
     * dialogue that never closes must not hold the script-pause gate forever.
     */
    private void continueDialogueUntilClosed() {
        Rs2Dialogue.sleepUntilInDialogue();
        long deadline = System.currentTimeMillis() + DIALOGUE_PHASE_TIMEOUT_MS;

        while (Rs2Dialogue.isInDialogue() && System.currentTimeMillis() < deadline) {
            if (Rs2Dialogue.hasContinue()) {
                Rs2Dialogue.clickContinue();
                Global.sleep(Rs2Random.logNormalBounded(600, 1200));
            } else {
                Global.sleep(Rs2Random.logNormalBounded(300, 600));
            }
        }

        if (Rs2Dialogue.isInDialogue()) {
            log.info("Dialogue still open after {} ms — giving up", DIALOGUE_PHASE_TIMEOUT_MS);
        } else {
            Rs2Dialogue.sleepUntilNotInDialogue();
        }
    }

    private boolean handleLampWait() {
        if (System.currentTimeMillis() > lampWaitDeadline) {
            log.warn("Lamp wait timeout");
            resetLampWaitState();
            return true;
        }

        if (countLamps() > lampsBeforeWait.get()) {
            log.info("Lamp appeared — using on {}", config.eventDismissLampSkill());
            if (LampUtility.useLamp(config.eventDismissLampSkill())) {
                resetLampWaitState();
                return true;
            }
            // The new lamp is here but could not be consumed yet — keep retrying
            // (bounded by the wait window and the breaker's suspension).
            return false;
        }

        // No new lamp yet; if the NPC left (or was dismissed) nothing more is coming.
        if (getRandomEventNpc() == null) {
            log.info("Random event ended before a new lamp arrived — stopping the wait");
            resetLampWaitState();
            return true;
        }

        return false;
    }

    private void resetLampWaitState() {
        waitingForLamp.set(false);
        lampWaitDeadline = 0L;
        lampsBeforeWait.set(0);
    }

    private void dismiss(Rs2NpcModel npc) {
        npc.click("Dismiss");
        Global.sleepUntil(() -> getRandomEventNpc() == null);
    }

    @Override
    public BlockingEventPriority priority() {
        return BlockingEventPriority.LOWEST;
    }
}
