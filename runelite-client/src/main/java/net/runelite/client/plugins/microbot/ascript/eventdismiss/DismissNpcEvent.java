package net.runelite.client.plugins.microbot.ascript.eventdismiss;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.client.plugins.microbot.BlockingEvent;
import net.runelite.client.plugins.microbot.BlockingEventPriority;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;

import java.awt.event.KeyEvent;
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

    /**
     * How long a full inventory is given to free a slot before the event is dismissed. Nothing in
     * the client can free one while the scripts are paused, but the player can, and the event NPC
     * despawns on its own — so the wait is short and bounded and it ends with a dismiss instead of
     * a retry that can never succeed.
     */
    private static final int FULL_INVENTORY_WAIT_MS = 10_000;

    /** Dismiss attempts that did not remove the NPC before random events are ignored for a while. */
    private static final int MAX_DISMISS_FAILURES = 3;

    /** Backoff after {@link #MAX_DISMISS_FAILURES}: validate() ignores event NPCs until it ends. */
    private static final long DISMISS_GIVE_UP_MS = 60_000;

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

    /**
     * Set once the 4-40 s humanised pre-action delay has been paid for this event instance. The
     * delay must run once, not on every retry: a bounded wait for inventory space would otherwise
     * re-sleep up to 40 s per attempt with the script-pause gate held.
     */
    private volatile boolean preActionDelayDone = false;

    /** First tick that saw a full inventory during this event — 0 while not waiting. */
    private volatile long fullInventorySince = 0L;

    /**
     * Set when a phase ended in a state this event cannot drive (a dialogue that never closed, an
     * inventory that never freed a slot). Later passes only dismiss: re-talking an NPC with an
     * unresolved dialogue is the talk loop this class already had once.
     */
    private volatile boolean dismissOnly = false;

    /** Name of the NPC this event instance is driving — resets the per-event state on a change. */
    private volatile String eventNpcName = null;

    /** Consecutive dismiss attempts that did not remove the NPC. */
    private int dismissFailures = 0;

    /** While now &lt; giveUpUntil, validate() ignores event NPCs (a dismiss that cannot land). */
    private volatile long giveUpUntil = 0L;

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
        if (System.currentTimeMillis() < giveUpUntil) {
            // A dismiss that cannot land (menu locked, NPC already gone server-side) must not
            // block every script: ignore event NPCs for the backoff window, then retry.
            return false;
        }
        if (waitingForLamp.get()) {
            return true;
        }
        Rs2NpcModel npc = getRandomEventNpc();
        if (npc == null) {
            // The random event is over — a future one starts fresh.
            resetEventState();
            return false;
        }
        return npc.hasLineOfSight();
    }

    /** Clears every per-event field; called once the event NPC is gone. */
    private void resetEventState() {
        countCheckTried = false;
        preActionDelayDone = false;
        fullInventorySince = 0L;
        dismissOnly = false;
        eventNpcName = null;
        dismissFailures = 0;
        resetLampWaitState();
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

        // A different NPC means a different event: per-event state never carries over.
        if (!name.equals(eventNpcName)) {
            eventNpcName = name;
            preActionDelayDone = false;
            fullInventorySince = 0L;
            dismissOnly = false;
            dismissFailures = 0;
            resetLampWaitState();
        }

        if (dismissOnly) {
            // An earlier phase ended in a state this event cannot drive. Dismiss and end the
            // attempt; the scheduler re-enqueues while the NPC lingers, dismiss-only.
            endWithDismiss(npc, name);
            return true;
        }

        // Change A: humanised pre-action delay, 4–40 s log-normal and weather-modulated —
        // the same timing pattern as the aScript modules. It runs inside the event, so the
        // script-pause gate holds the scripts for its duration; the rare long tail is the
        // intended behaviour, do not "optimise" it away. The explicit-multiplier overload is
        // used because the two-argument one already applies weather on its own.
        // Paid ONCE per event: a bounded retry must not re-sleep 4-40 s with the gate held.
        if (!preActionDelayDone) {
            preActionDelayDone = true;
            WeatherModulation.ensureFresh();
            double weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();
            Global.sleep(Rs2Random.logNormalBounded(4000, 40000, weatherMultiplier));
        }

        // A Count Check whose accept cycle already ran is only ever dismissed from now on.
        boolean accept = shouldAcceptLamp(name) && !("Count Check".equals(name) && countCheckTried);
        if (accept) {
            if (Rs2Inventory.isFull()) {
                // Nothing in the client can free a slot while the scripts are paused, so the
                // wait is bounded and ends with a dismiss: holding the gate on a state that
                // cannot change pauses every script for nothing.
                if (fullInventorySince == 0L) {
                    fullInventorySince = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - fullInventorySince < FULL_INVENTORY_WAIT_MS) {
                    log.info("Inventory full — waiting up to {} ms for a free slot before dismissing {}",
                            FULL_INVENTORY_WAIT_MS, name);
                    return false;
                }
                log.info("Inventory still full after {} ms — dismissing {} instead of retrying",
                        FULL_INVENTORY_WAIT_MS, name);
                fullInventorySince = 0L;
                dismissOnly = true;
                endWithDismiss(npc, name);
                return true;
            }
            fullInventorySince = 0L;
            boolean done = acceptLamp(npc, name);
            if (done) awaitNpcUnfocus();
            return done;
        }

        dismissOnly = true;
        endWithDismiss(npc, name);
        return true;
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

        // "Talk-to" is the first (left-click) op of both event NPCs — OSRS wiki
        // infoboxes list `options = Talk-to, Dismiss` for Genie (326/327) and the
        // Count Check random event (12551/12552), first entry = default op — so the
        // default-op recipe (a plain left-click on the real clickbox) is what a human
        // does here. The recipe only reports the click was issued: when it did not
        // click (off-camera target, hull not rendered yet) this must stop, because
        // the Count Check below would otherwise be marked talked-to with no dialogue
        // ever starting — and a tried Count Check is only ever dismissed afterwards.
        Microbot.status = "Talk-to " + name;
        if (!AScriptClick.clickActor(npc.getNpc()).clicked()) {
            return false;
        }
        // Both the Genie and the Count Check random event are "Click here to continue"
        // dialogues (the options dialogue on the wiki belongs to the standing Lumbridge
        // Count Check, not the random event) — one bounded closer covers both.
        if (!continueDialogueUntilClosed()) {
            // The dialogue never closed inside the phase deadline. Close what can be closed and
            // end the event: re-talking with the dialogue still up is the loop this replaced, and
            // the event must not hold the script-pause gate on a dialogue it cannot drive.
            forceCloseDialogue();
            dismissOnly = true;
            log.info("Dialogue did not close within {} ms — ending the event instead of re-talking",
                    DIALOGUE_PHASE_TIMEOUT_MS);
            return true;
        }

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
            dismissOnly = true;
            endWithDismiss(npc, name);
            return true;
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
     *
     * @return {@code true} when the dialogue closed inside the deadline
     */
    private boolean continueDialogueUntilClosed() {
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
            return false;
        }
        Rs2Dialogue.sleepUntilNotInDialogue();
        return true;
    }

    /**
     * Best-effort close of a dialogue that outlived its phase deadline: the continue op while one
     * is offered, Escape as the fallback when the esc-close setting allows it (the house pattern
     * from {@code LampUtility.closePendingLampInterface}).
     */
    private void forceCloseDialogue() {
        for (int i = 0; i < 3 && Rs2Dialogue.hasContinue(); i++) {
            Rs2Dialogue.clickContinue();
            Global.sleep(Rs2Random.logNormalBounded(300, 700));
        }
        if (!Rs2Dialogue.isInDialogue()) {
            return;
        }
        if (Rs2Settings.isEscCloseInterfaceSettingEnabled()) {
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
            Global.sleepUntil(() -> !Rs2Dialogue.isInDialogue(), 2000);
        } else if (Rs2Dialogue.isInDialogue()) {
            log.warn("Dialogue could not be closed (no continue op, esc-close disabled)");
        }
    }

    private boolean handleLampWait() {
        if (System.currentTimeMillis() > lampWaitDeadline) {
            log.warn("Lamp wait timeout");
            resetLampWaitState();
            // The NPC is still here but this event cannot get a lamp out of it: from now on it is
            // only dismissed, so a lingering Genie is never re-talked (the "Have a nice day" loop).
            dismissOnly = true;
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

    /**
     * Dismiss the event NPC.
     *
     * @return {@code true} when the NPC is gone afterwards, {@code false} when the click did not
     *         land or the NPC is still in the scene
     */
    private boolean dismiss(Rs2NpcModel npc) {
        // "Dismiss" is a menu op (an NPC composition action), not a dialogue option:
        // Rs2Npc.getRandomEventNPC() finds event NPCs by "Dismiss" in
        // npcComposition.getActions(), and RuneLite's RandomEventPlugin matches the
        // same string against MenuAction.NPC_FIRST_OPTION..FIFTH_OPTION entries. It
        // is never the left-click default (the wiki infoboxes list it second, after
        // "Talk-to"), so a plain left-click could never reach it — pick our row out
        // of the open option menu instead. A click that did not happen leaves the NPC
        // here, so there is nothing to wait for; the caller retries on a later pass.
        Microbot.status = "Dismiss " + npc.getName();
        if (!AScriptClick.clickActorOp(npc.getNpc(), "Dismiss").clicked()) {
            return false;
        }
        return Global.sleepUntil(() -> getRandomEventNpc() == null);
    }

    /**
     * Dismiss and end the attempt. A dismiss that does not remove the NPC is counted; after
     * {@link #MAX_DISMISS_FAILURES} the event stops validating for {@link #DISMISS_GIVE_UP_MS}.
     * Without that bound a menu op the client will not dispatch keeps the event — and every
     * script in the client — paused on an NPC it can never clear.
     */
    private void endWithDismiss(Rs2NpcModel npc, String name) {
        if (dismiss(npc)) {
            dismissFailures = 0;
            return;
        }
        if (++dismissFailures >= MAX_DISMISS_FAILURES) {
            dismissFailures = 0;
            giveUpUntil = System.currentTimeMillis() + DISMISS_GIVE_UP_MS;
            log.warn("Could not dismiss {} after {} attempts — ignoring random events for {} ms",
                    name, MAX_DISMISS_FAILURES, DISMISS_GIVE_UP_MS);
        }
    }

    @Override
    public BlockingEventPriority priority() {
        return BlockingEventPriority.LOWEST;
    }
}
