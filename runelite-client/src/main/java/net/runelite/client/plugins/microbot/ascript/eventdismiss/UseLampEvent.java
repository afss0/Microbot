package net.runelite.client.plugins.microbot.ascript.eventdismiss;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.client.plugins.microbot.BlockingEvent;
import net.runelite.client.plugins.microbot.BlockingEventPriority;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

@Slf4j
public class UseLampEvent implements BlockingEvent {

    private final AScriptConfig config;

    public UseLampEvent(AScriptConfig config) {
        this.config = config;
    }

    @Override
    public boolean validate() {
        if (!Rs2Inventory.contains(ItemID.LAMP)) {
            // The lamp is gone — a future lamp starts with a fresh failure streak.
            LampUtility.resetUseFailures();
            return false;
        }
        // The Rub op is unavailable while the bank is open — wait for it to close
        // instead of attempting a use that cannot succeed.
        if (Rs2Bank.isOpen()) {
            return false;
        }
        // No interface widget for the selected skill — attempting would only warn-loop.
        if (!LampUtility.canUseLampSkill(config.eventDismissLampSkill())) {
            return false;
        }
        // Breaker tripped on this lamp's streak — no further attempts until it leaves the
        // inventory (banking already treats the lamp as a locked item and proceeds).
        if (LampUtility.isUseSuspended()) {
            return false;
        }
        // Bank-yield request (a bank path was blocked by this lamp): fire immediately —
        // the bank flow is already stalled and useLamp's internal idle wait keeps the rub
        // polite. Otherwise the opportunistic path: stray-lamp toggle + sustained-idle
        // window (change B) — the timer is advanced here (manager thread) and in
        // LampUtility.useLamp (executor thread); LampUtility keeps it thread-safe.
        return LampUtility.isBankYieldPending()
                || (config.eventDismissStrayLamps() && LampUtility.isIdleWindowElapsed());
    }

    @Override
    public boolean execute() {
        log.debug("Using stray lamp on {}", config.eventDismissLampSkill());
        return LampUtility.useLamp(config.eventDismissLampSkill());
    }

    @Override
    public BlockingEventPriority priority() {
        return BlockingEventPriority.NORMAL;
    }
}
