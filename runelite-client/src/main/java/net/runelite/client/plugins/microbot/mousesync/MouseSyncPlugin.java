package net.runelite.client.plugins.microbot.mousesync;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.util.HotkeyListener;

import javax.inject.Inject;
import java.awt.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Synchronises the bot's virtual mouse with the user's physical cursor.
 *
 * <p>When the bot starts an interaction it disables user mouse input via
 * {@link ClientUI#getClient()}.setEnabled(false)} and
 * {@code canvas.setFocusable(false)}. After the interaction completes,
 * a configurable grace period keeps input disabled so the user cannot
 * accidentally interfere with the return movement. Once the grace period
 * expires the bot naturally moves the cursor (via {@code NaturalMouse})
 * to the user's last known OS mouse position and re-enables input.
 *
 * <p>An emergency hotkey (default CTRL+X) immediately stops the bot
 * and restores mouse control regardless of the current state.
 */
@PluginDescriptor(
        name = PluginDescriptor.Mocrosoft + "Mouse Sync",
        description = "Syncs the bot cursor with the user's physical mouse to prevent teleports and input conflicts",
        tags = {"mouse", "sync", "input", "antiban", "microbot"},
        enabledByDefault = true
)
@Slf4j
public class MouseSyncPlugin extends Plugin {

    // ── State machine ──────────────────────────────────────────────────
    public enum State {
        /** No bot interaction in progress; user has full mouse control. */
        IDLE,
        /** Bot is performing clicks/moves. User input is disabled. */
        BOT_ACTIVE,
        /** Bot interaction finished; grace countdown running. */
        GRACE_PERIOD,
        /** Grace elapsed; cursor is being moved back to the user position. */
        RETURNING
    }

    private volatile State state = State.IDLE;

    // ── Injected dependencies ──────────────────────────────────────────
    @Inject private Client client;
    @Inject private MouseSyncConfig config;
    @Inject private ConfigManager configManager;
    @Inject private KeyManager keyManager;

    // ── Scheduling ─────────────────────────────────────────────────────
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> graceTimer;

    // ── Last known OS mouse position (screen coordinates) ─────────────
    private volatile java.awt.Point lastOsMousePosition;

    // ── Config provider ────────────────────────────────────────────────
    @Provides
    MouseSyncConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MouseSyncConfig.class);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────
    @Override
    protected void startUp() {
        Microbot.mouseSyncPlugin = this;
        executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MouseSync");
            t.setDaemon(true);
            return t;
        });
        keyManager.registerKeyListener(emergencyKeyListener);
        log.info("Mouse Sync started (grace={}ms, hotkey=CTRL+X)", config.gracePeriodMs());
    }

    @Override
    protected void shutDown() {
        keyManager.unregisterKeyListener(emergencyKeyListener);
        cancelGraceTimer();
        // Always restore input on shutdown so the user is never stuck
        enableUserInput();
        state = State.IDLE;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        Microbot.mouseSyncPlugin = null;
        log.info("Mouse Sync stopped");
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!MouseSyncConfig.GROUP.equals(event.getGroup())) return;
        if ("enabled".equals(event.getKey())) {
            if (!config.enabled()) {
                // Disabling mid-session: clean up immediately
                emergencyRelease();
            }
        }
    }

    // ── Public API — called by VirtualMouse / NaturalMouse ─────────────

    /**
     * Call this when the bot is about to start an interaction (click, move, drag).
     * Disables user input and transitions to {@link State#BOT_ACTIVE}.
     */
    public void onBotInteractionStart() {
        if (!config.enabled() || state == State.BOT_ACTIVE) return;
        cancelGraceTimer();
        disableUserInput();
        state = State.BOT_ACTIVE;
        log.debug("MouseSync: BOT_ACTIVE — user input disabled");
    }

    /**
     * Call this after the bot interaction (click, move, drag) has completed.
     * Starts the grace period countdown. After the grace period the cursor
     * returns to the user's position and input is re-enabled.
     */
    public void onBotInteractionEnd() {
        if (!config.enabled() || state != State.BOT_ACTIVE) return;
        state = State.GRACE_PERIOD;

        // Snapshot the user's current OS mouse position *now* so we know
        // where to return after the grace period.
        snapshotOsMousePosition();

        int graceMs = config.gracePeriodMs();
        log.debug("MouseSync: GRACE_PERIOD ({}ms)", graceMs);

        cancelGraceTimer();
        graceTimer = executor.schedule(this::finishGracePeriod, graceMs, TimeUnit.MILLISECONDS);
    }

    // ── Internal ───────────────────────────────────────────────────────

    private void finishGracePeriod() {
        if (state != State.GRACE_PERIOD) return;

        snapshotOsMousePosition();
        java.awt.Point target = lastOsMousePosition;
        if (target == null) {
            log.debug("MouseSync: no OS position captured — restoring input immediately");
            enableUserInput();
            state = State.IDLE;
            return;
        }

        state = State.RETURNING;
        log.debug("MouseSync: RETURNING to ({}, {})", target.x, target.y);

        net.runelite.api.Point canvasTarget = screenToCanvas(target);
        if (canvasTarget == null) {
            log.debug("MouseSync: canvas not visible — restoring input");
            enableUserInput();
            state = State.IDLE;
            return;
        }

        // Use NaturalMouse for human-like return movement.
        // NaturalMouse.moveTo runs on its own thread and dispatches
        // synthetic events to the canvas. We wait for it to finish,
        // then re-enable input.
        executor.submit(() -> {
            try {
                if (Microbot.getNaturalMouse() != null) {
                    Microbot.getNaturalMouse().moveTo(canvasTarget.getX(), canvasTarget.getY());
                } else {
                    // Fallback: direct move
                    Microbot.getMouse().move(canvasTarget.getX(), canvasTarget.getY());
                }
            } catch (Exception e) {
                log.warn("MouseSync: return movement failed", e);
            } finally {
                enableUserInput();
                state = State.IDLE;
                log.debug("MouseSync: IDLE — user input restored");
            }
        });
    }

    private void emergencyRelease() {
        cancelGraceTimer();
        enableUserInput();
        state = State.IDLE;
        log.info("MouseSync: emergency release — input restored");
    }

    // ── Input control ──────────────────────────────────────────────────

    private void disableUserInput() {
        try {
            ClientUI.getClient().setEnabled(false);
            Microbot.getClient().getCanvas().setFocusable(false);
        } catch (Exception e) {
            log.warn("MouseSync: failed to disable user input", e);
        }
    }

    private void enableUserInput() {
        try {
            ClientUI.getClient().setEnabled(true);
            Microbot.getClient().getCanvas().setFocusable(true);
        } catch (Exception e) {
            log.warn("MouseSync: failed to enable user input", e);
        }
    }

    // ── OS mouse position ──────────────────────────────────────────────

    private void snapshotOsMousePosition() {
        try {
            PointerInfo info = MouseInfo.getPointerInfo();
            if (info != null) {
                lastOsMousePosition = info.getLocation(); // java.awt.Point
            }
        } catch (Exception e) {
            log.debug("MouseSync: failed to read OS mouse position", e);
        }
    }

    /**
     * Translate a screen-coordinate Point to canvas-relative coordinates,
     * accounting for stretched mode and window position.
     */
    private net.runelite.api.Point screenToCanvas(java.awt.Point screenPoint) {
        try {
            Canvas canvas = Microbot.getClient().getCanvas();
            java.awt.Point canvasLocationOnScreen = canvas.getLocationOnScreen();
            int cx = screenPoint.x - canvasLocationOnScreen.x;
            int cy = screenPoint.y - canvasLocationOnScreen.y;

            // Account for stretched mode
            if (Microbot.getClient().isStretchedEnabled()) {
                Dimension stretched = Microbot.getClient().getStretchedDimensions();
                Dimension real = Microbot.getClient().getRealDimensions();
                if (stretched != null && real != null && real.width > 0 && real.height > 0) {
                    cx = (int) ((long) cx * real.width / stretched.width);
                    cy = (int) ((long) cy * real.height / stretched.height);
                }
            }
            return new net.runelite.api.Point(cx, cy);
        } catch (Exception e) {
            log.debug("MouseSync: screenToCanvas failed", e);
            return null;
        }
    }

    // ── Grace timer management ─────────────────────────────────────────

    private void cancelGraceTimer() {
        if (graceTimer != null && !graceTimer.isDone()) {
            graceTimer.cancel(false);
            graceTimer = null;
        }
    }

    // ── Emergency hotkey ───────────────────────────────────────────────

    private final HotkeyListener emergencyKeyListener = new HotkeyListener(() -> config.emergencyHotkey()) {
        @Override
        public void hotkeyPressed() {
            if (!config.enabled()) return;
            log.info("MouseSync: EMERGENCY HOTKEY pressed — stopping plugin and restoring input");
            emergencyRelease();
            // Disable this plugin entirely so it can't re-lock the mouse.
            // User re-enables via the Microbot plugin list.
            Microbot.stopPlugin(MouseSyncPlugin.class);
        }
    };

    // ── State accessors (for overlay / debug) ──────────────────────────

    public State getState() { return state; }
    public boolean isInputDisabled() { return state == State.BOT_ACTIVE || state == State.GRACE_PERIOD; }
}
