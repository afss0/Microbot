package net.runelite.client.plugins.microbot.ascript.eventdismiss;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;

import static net.runelite.client.plugins.microbot.ascript.eventdismiss.LampWidgetConstants.*;

@Slf4j
public class LampUtility {

    /** Total bound for waiting on the sustained-idle window before a lamp use is skipped. */
    private static final long IDLE_GATE_TIMEOUT_MS = 30_000;

    private static final Map<Skill, Integer> SKILL_WIDGET_MAP = new EnumMap<>(Skill.class);

    // ── Idle gate (change B) ────────────────────────────────────────────
    // A lamp is only rubbed after the player has been idle for a sustained 2–4 s window
    // (the house idleness pattern from MotherloadMineScript.mineVeins). The state is touched
    // by two threads — the BlockingEventManager scheduler thread (UseLampEvent.validate)
    // and the blocking executor (awaitIdleWindow) — so it is kept volatile and every
    // transition goes through the synchronized state machine below.
    private static volatile long idleSince = 0L;
    private static volatile int idleThreshold = 0;

    static {
        SKILL_WIDGET_MAP.put(Skill.ATTACK, WIDGET_ATTACK);
        SKILL_WIDGET_MAP.put(Skill.STRENGTH, WIDGET_STRENGTH);
        SKILL_WIDGET_MAP.put(Skill.RANGED, WIDGET_RANGED);
        SKILL_WIDGET_MAP.put(Skill.MAGIC, WIDGET_MAGIC);
        SKILL_WIDGET_MAP.put(Skill.DEFENCE, WIDGET_DEFENCE);
        SKILL_WIDGET_MAP.put(Skill.SAILING, WIDGET_SAILING);
        SKILL_WIDGET_MAP.put(Skill.HITPOINTS, WIDGET_HITPOINTS);
        SKILL_WIDGET_MAP.put(Skill.PRAYER, WIDGET_PRAYER);
        SKILL_WIDGET_MAP.put(Skill.AGILITY, WIDGET_AGILITY);
        SKILL_WIDGET_MAP.put(Skill.HERBLORE, WIDGET_HERBLORE);
        SKILL_WIDGET_MAP.put(Skill.THIEVING, WIDGET_THIEVING);
        SKILL_WIDGET_MAP.put(Skill.CRAFTING, WIDGET_CRAFTING);
        SKILL_WIDGET_MAP.put(Skill.RUNECRAFT, WIDGET_RUNECRAFT);
        SKILL_WIDGET_MAP.put(Skill.SLAYER, WIDGET_SLAYER);
        SKILL_WIDGET_MAP.put(Skill.FARMING, WIDGET_FARMING);
        SKILL_WIDGET_MAP.put(Skill.MINING, WIDGET_MINING);
        SKILL_WIDGET_MAP.put(Skill.SMITHING, WIDGET_SMITHING);
        SKILL_WIDGET_MAP.put(Skill.FISHING, WIDGET_FISHING);
        SKILL_WIDGET_MAP.put(Skill.COOKING, WIDGET_COOKING);
        SKILL_WIDGET_MAP.put(Skill.FIREMAKING, WIDGET_FIREMAKING);
        SKILL_WIDGET_MAP.put(Skill.WOODCUTTING, WIDGET_WOODCUTTING);
        SKILL_WIDGET_MAP.put(Skill.FLETCHING, WIDGET_FLETCHING);
        SKILL_WIDGET_MAP.put(Skill.CONSTRUCTION, WIDGET_CONSTRUCTION);
        SKILL_WIDGET_MAP.put(Skill.HUNTER, WIDGET_HUNTER);
    }

    public static int getSkillWidgetId(Skill skill) {
        if (skill == null) {
            return -1;
        }
        return SKILL_WIDGET_MAP.getOrDefault(skill, -1);
    }

    /**
     * Sustained-idle state machine shared by both gate users: resets while the player is
     * animating or moving, starts a 2–4 s window on the first idle observation, and reports
     * true once that window has elapsed.
     */
    public static synchronized boolean isIdleWindowElapsed() {
        if (Rs2Player.isAnimating() || Rs2Player.isMoving()) {
            idleSince = 0L;
            return false;
        }
        if (idleSince == 0L) {
            idleSince = System.currentTimeMillis();
            idleThreshold = Math.max(2000, (int) Rs2Random.randomGaussian(3000, 600));
            return false;
        }
        return System.currentTimeMillis() - idleSince >= idleThreshold;
    }

    /**
     * Waits — with short polling sleeps — until the sustained-idle window elapses, bounded by
     * {@code timeoutMs}. On timeout the lamp use is skipped (returns false) instead of holding
     * the script-pause gate; the normal validate/requeue path retries later.
     */
    public static boolean awaitIdleWindow(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isIdleWindowElapsed()) {
                return true;
            }
            Global.sleep(Rs2Random.logNormalBounded(300, 700));
        }
        log.info("Idle window not reached within {} ms — skipping lamp use for now", timeoutMs);
        return false;
    }

    public static boolean useLamp(Skill skill) {
        if (skill == null || !Rs2Inventory.contains(ItemID.LAMP)) {
            // Lamp gone — a future lamp starts with a fresh failure streak.
            resetUseFailures();
            return false;
        }

        // Breaker: this streak already exhausted its retries — stay off until the lamp
        // leaves the inventory (the bank paths proceed; deposits lock/ignore the lamp).
        if (useSuspended) {
            return false;
        }

        // The Rub op is unavailable in bank mode — never attempt it. The bank paths yield
        // to the lamp before opening, so reaching here with the bank open means an accept
        // flow ran over an open bank: bail and let the normal retry path sort it out.
        if (Rs2Bank.isOpen()) {
            return false;
        }

        // Change B: never interrupt an activity that is already running — wait for the
        // sustained-idle window first. This covers every call site, including the Genie and
        // Count Check accept paths.
        if (!awaitIdleWindow(IDLE_GATE_TIMEOUT_MS)) {
            return false;
        }

        // A previous attempt may have left the lamp interface open (e.g. the confirm did
        // not land). It is modal, so a stale one would make the rub land in an undefined
        // state — close it first so every attempt starts clean.
        closePendingLampInterface();

        if (!Rs2Inventory.interact(ItemID.LAMP, "Rub")) {
            log.warn("Failed to rub lamp");
            noteUseFailure();
            return false;
        }

        if (!Global.sleepUntil(() -> Rs2Widget.isWidgetVisible(LAMP_WIDGET_GROUP, LAMP_WIDGET_ROOT), 3000)) {
            log.warn("Lamp interface did not open");
            noteUseFailure();
            return false;
        }

        int skillWidgetId = getSkillWidgetId(skill);
        if (skillWidgetId == -1) {
            log.warn("Unsupported lamp skill: {}", skill);
            noteUseFailure();
            return false;
        }

        Rs2Widget.clickWidget(LAMP_WIDGET_GROUP, skillWidgetId);
        Global.sleep(Rs2Random.logNormalBounded(600, 1200));

        Rs2Widget.clickWidget(LAMP_WIDGET_GROUP, LAMP_CONFIRM_BUTTON);

        // Confirming the skill produces an XP-award "Click here to continue" dialogue.
        // Dismiss it so the lamp is actually consumed and the leftover dialogue does not
        // block other plugins once this blocking event releases the script-pause gate.
        Global.sleepUntil(() -> Rs2Dialogue.hasContinue() || !Rs2Inventory.contains(ItemID.LAMP), 2000);
        for (int i = 0; i < 5 && Rs2Dialogue.hasContinue(); i++) {
            Rs2Dialogue.clickContinue();
            Global.sleep(Rs2Random.logNormalBounded(600, 1200));
        }

        if (!Global.sleepUntil(() -> !Rs2Inventory.contains(ItemID.LAMP), 3000)) {
            log.warn("Lamp was not consumed after confirm");
            noteUseFailure();
            return false;
        }

        consecutiveUseFailures = 0;
        log.info("Used lamp on {}", skill);
        return true;
    }

    /**
     * Closes a lamp interface (widget group 240) left open by a previous, partially
     * failed attempt. Prefers the interface's own Close action; Escape is the fallback
     * and only works when the esc-close setting is enabled (the house pattern from
     * {@code Rs2Death.closeInterfaces}). Best effort: if neither closes it, the next
     * attempt still runs (and now counts toward the breaker).
     */
    private static void closePendingLampInterface() {
        if (!Rs2Widget.isWidgetVisible(LAMP_WIDGET_GROUP, LAMP_WIDGET_ROOT)) {
            return;
        }
        Rs2Widget.findWidgetsWithAction("Close", LAMP_WIDGET_GROUP, true);
        if (Global.sleepUntil(() -> !Rs2Widget.isWidgetVisible(LAMP_WIDGET_GROUP, LAMP_WIDGET_ROOT), 2000)) {
            return;
        }
        if (Rs2Settings.isEscCloseInterfaceSettingEnabled()) {
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
            Global.sleepUntil(() -> !Rs2Widget.isWidgetVisible(LAMP_WIDGET_GROUP, LAMP_WIDGET_ROOT), 2000);
        } else {
            log.warn("Leftover lamp interface could not be closed (no Close action, esc-close disabled)");
        }
    }

    // ── Lamp-use breaker (persistent-failure circuit breaker) ───────────
    // useLamp failures are counted on exactly the paths that log a warning. After
    // MAX_LAMP_USE_FAILURES consecutive failures the handler suspends: no further
    // attempts (UseLampEvent.validate returns false — the requeue loop stops) and
    // banking stops yielding (yieldBankingToLamp clears the request and proceeds).
    // The suspended state clears lazily once the lamp leaves the inventory, so a
    // future lamp starts with a fresh streak.
    private static final int MAX_LAMP_USE_FAILURES = 5;
    private static volatile int consecutiveUseFailures = 0;
    private static volatile boolean useSuspended = false;

    /** True while the breaker has suspended lamp attempts for the current lamp. */
    public static boolean isUseSuspended() {
        return useSuspended;
    }

    /** Clears the failure streak — the lamp is gone, or the plugin is resetting. */
    public static void resetUseFailures() {
        consecutiveUseFailures = 0;
        useSuspended = false;
    }

    private static void noteUseFailure() {
        consecutiveUseFailures++;
        if (consecutiveUseFailures >= MAX_LAMP_USE_FAILURES && !useSuspended) {
            useSuspended = true;
            log.warn("Lamp use failed {} times in a row — suspending lamp handling until the lamp leaves the inventory",
                    consecutiveUseFailures);
        }
    }

    // ── Bank-yield gate (lamps are never banked) ────────────────────────
    // An XP lamp in the inventory blocks every bank path: lamps cannot be deposited and
    // must be used first. The bank side calls yieldBankingToLamp(...) before opening;
    // the pending request makes UseLampEvent fire even when the stray-lamp toggle is off.
    private static volatile boolean bankYieldPending = false;
    /** Warn-once guard for a lamp skill with no interface widget (e.g. OVERALL). */
    private static volatile boolean warnedInvalidSkill = false;

    /** True when {@code skill} maps to a lamp-interface widget (the lamp can be used). */
    public static boolean canUseLampSkill(Skill skill) {
        return getSkillWidgetId(skill) != -1;
    }

    /** True while banking is yielding to a lamp that still has to be used. */
    public static boolean isBankYieldPending() {
        return bankYieldPending;
    }

    /**
     * Lamp-vs-bank gate: called by every bank path before it opens the bank. While an XP
     * lamp sits in the inventory, banking must yield — the lamp-use blocking event has to
     * consume it first. Closes the bank if it is somehow open (the Rub op is unavailable
     * in bank mode) and asks {@link UseLampEvent} to fire next (it bypasses the
     * stray-lamp toggle and the idle window while the request is pending).
     * <p>
     * A lamp whose skill has no interface widget can never be used — blocking banking on
     * it would deadlock the script, so it warns once and lets banking proceed. The same
     * applies when the breaker has suspended the lamp: banking proceeds, and the deposit
     * helpers lock/ignore the lamp.
     *
     * @return true when banking must yield this tick (a usable lamp is in the inventory)
     */
    public static boolean yieldBankingToLamp(Skill skill) {
        if (!Rs2Inventory.contains(ItemID.LAMP)) {
            bankYieldPending = false;
            resetUseFailures();
            return false;
        }
        if (!canUseLampSkill(skill)) {
            if (!warnedInvalidSkill) {
                warnedInvalidSkill = true;
                log.warn("Lamp skill {} has no lamp interface — banking cannot yield to it", skill);
            }
            bankYieldPending = false;
            return false;
        }
        if (useSuspended) {
            // The breaker tripped: banking must not stay blocked for a lamp that cannot
            // be used — proceed (the deposit helpers lock/ignore the lamp).
            bankYieldPending = false;
            return false;
        }
        if (Rs2Bank.isOpen()) {
            // Rub is unavailable while the bank interface is open — close it and let the
            // lamp-use event run; banking resumes on a later tick.
            Rs2Bank.closeBank();
            Global.sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
        }
        if (!bankYieldPending) {
            bankYieldPending = true;
            log.info("Lamp in inventory — banking yields until the lamp is used");
        }
        return true;
    }

    public static void reset() {
        idleSince = 0L;
        idleThreshold = 0;
        bankYieldPending = false;
        warnedInvalidSkill = false;
        consecutiveUseFailures = 0;
        useSuspended = false;
    }
}
