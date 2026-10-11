package net.runelite.client.plugins.microbot.ascript.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;

import java.awt.Rectangle;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Shared client-mouse click recipes for aScript modules — the "clicks go through the
 * client mouse" rule ({@code ascript/AGENTS.md}): a module's own action is a physical
 * click on real bounds (widget slot or entity clickbox), never a menu-entry injection
 * ({@code Microbot.doInvoke} / {@code NewMenuEntry} / {@code Rs2Inventory.interact(...)}).
 * <p>
 * Three recipes, consolidated from the per-module copies that grew in Cannoning
 * (object), Ammonite Crabs (actor) and Cannonball Smelter (object render gate):
 * <ul>
 *   <li><b>Object / actor click</b> — camera first, then the target's real clickbox
 *       through {@link Microbot#getMouse()}. The canvas-sized fallback rectangle
 *       {@link Rs2UiHelper#getObjectClickbox} / {@link Rs2UiHelper#getActorClickbox}
 *       return for a target that is not rendered yet is never clicked — it lands on the
 *       terrain as "Walk here". Callers keep their own render-miss accounting: the
 *       recipes report why a click did not happen via {@link Target}.</li>
 *   <li><b>Item-on-object / item-on-item</b> — select the first item with a physical
 *       click on its inventory slot, verify the game picked it up, click the target
 *       (an entity clickbox or another inventory slot) physically. Any failure path
 *       clears a selection the recipe created, because a dangling cursor item silently
 *       converts the NEXT inventory op.</li>
 * </ul>
 * A physical click returns nothing, so these recipes only report whether the click was
 * issued — confirming the EFFECT (make-X prompt, animation, XP drop, count drop) stays
 * with the caller, which owns the module-specific tracking and failure escalation.
 */
@Slf4j
public final class AScriptClick {

    /**
     * Bounded poll for an entity hull that has not rendered yet (the Cannonball Smelter
     * render-gate budget). A render race is not a module failure: it must not spend
     * action lives, and it never justifies a blind click.
     */
    private static final int RENDER_WAIT_STEPS = 5;

    private AScriptClick() {
    }

    /**
     * Outcome of a click recipe: either a real clickbox that was (or is ready to be)
     * clicked, or the reason there is none. The canvas-sized fallback rectangle is never
     * a result.
     */
    public static final class Target {
        private final Rectangle clickbox;
        private final boolean cameraTurned;
        private final boolean clicked;

        private Target(Rectangle clickbox, boolean cameraTurned, boolean clicked) {
            this.clickbox = clickbox;
            this.cameraTurned = cameraTurned;
            this.clicked = clicked;
        }

        /** The target's real clickbox, or {@code null} when it has none yet. */
        public Rectangle clickbox() {
            return clickbox;
        }

        /** True when a real (non-canvas-fallback) clickbox was resolved. */
        public boolean ready() {
            return clickbox != null;
        }

        /** True when the recipe issued the physical click. */
        public boolean clicked() {
            return clicked;
        }

        /**
         * True when the target was off camera and the camera has just been turned —
         * retry on a later tick; this is not a failure and must not spend lives.
         */
        public boolean cameraTurned() {
            return cameraTurned;
        }
    }

    // ── Object / actor recipes ─────────────────────────────

    /**
     * Object click recipe: camera first, then the object's real clickbox through the
     * client mouse.
     *
     * @param object the target object (a {@code Rs2TileObjectModel} or a raw tile object)
     * @return the outcome — {@link Target#clicked()} when the click was issued
     */
    public static Target clickObject(TileObject object) {
        return clickObject(object, 0);
    }

    /**
     * Object click recipe with a bounded render wait: when the object's hull has not
     * rendered yet, poll for up to {@code renderWaitSteps} short intervals instead of
     * returning immediately.
     *
     * @param renderWaitSteps 0 = no wait (return {@code NOT_RENDERED} right away)
     * @return the outcome — {@link Target#clicked()} when the click was issued
     */
    public static Target clickObject(TileObject object, int renderWaitSteps) {
        Target target = resolveObject(object, renderWaitSteps);
        if (!target.ready()) {
            return target;
        }
        Microbot.getMouse().click(target.clickbox());
        return new Target(target.clickbox(), false, true);
    }

    /**
     * First half of the object recipe — camera first, then the object's real clickbox,
     * without clicking. For callers that gate the click themselves (the smelter's
     * render-miss budget) or compose their own action on top of it.
     */
    public static Target resolveObject(TileObject object) {
        return resolveObject(object, 0);
    }

    /**
     * First half of the object recipe with the bounded render wait.
     * <p>
     * The camera turn runs off the client thread on purpose: {@code setAngle}'s key-hold
     * wait no-ops on the client thread (Ammonite Crabs {@code attackCrab} note).
     *
     * @param renderWaitSteps how long to poll for a hull that has not rendered yet
     *                        (0 = return immediately); an off-camera object returns
     *                        immediately regardless — the turn is the whole action
     */
    public static Target resolveObject(TileObject object, int renderWaitSteps) {
        if (object == null) {
            return notRendered();
        }
        if (!Rs2Camera.isTileOnScreen(object)) {
            Rs2Camera.turnTo(object);
            return new Target(null, true, false);
        }
        Rectangle clickbox = realClickbox(Rs2UiHelper.getObjectClickbox(object));
        for (int i = 0; i < renderWaitSteps && clickbox == null; i++) {
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(150, 300));
            clickbox = realClickbox(Rs2UiHelper.getObjectClickbox(object));
        }
        return clickbox == null ? notRendered() : new Target(clickbox, false, false);
    }

    /**
     * Actor (NPC) click recipe: camera first, then the actor's real clickbox through the
     * client mouse — the Cannoning object recipe with the actor hull. The actor lookups
     * run on the client thread (see {@link Rs2UiHelper#getActorClickbox(Actor)}).
     */
    public static Target clickActor(Actor actor) {
        if (actor == null) {
            return notRendered();
        }
        final LocalPoint local = Microbot.getClientThread().invoke(actor::getLocalLocation);
        if (local == null) {
            return notRendered();
        }
        if (!Rs2Camera.isTileOnScreen(local)) {
            Rs2Camera.turnTo(local);
            return new Target(null, true, false);
        }
        // Canvas size is read off the lambda: calling Client#getCanvasWidth/Height from
        // inside a clientThread.invoke lambda registers them with the
        // ClientThreadGuardrailTest inference and would flag unrelated pre-existing
        // util/api callers (Ammonite Crabs attackCrab note).
        final int canvasWidth = Microbot.getClient().getCanvasWidth();
        final int canvasHeight = Microbot.getClient().getCanvasHeight();
        Rectangle clickbox = Microbot.getClientThread().invoke(() -> {
            Rectangle box = Rs2UiHelper.getActorClickbox(actor);
            if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) {
                return null;
            }
            // Canvas-sized fallback rectangle = no real clickbox — never click it blindly.
            if (box.getWidth() >= canvasWidth - 2 || box.getHeight() >= canvasHeight - 2) {
                return null;
            }
            return box;
        });
        if (clickbox == null) {
            return notRendered();
        }
        Microbot.getMouse().click(clickbox);
        return new Target(clickbox, false, true);
    }

    // ── Item-on-X recipe ───────────────────────────────────

    /**
     * Item-on-object recipe (client mouse, no injection): select {@code item} with a
     * physical click on its inventory slot, verify the game picked it up, click the
     * object's real clickbox (camera first, canvas fallback rejected, render waited
     * out), and report whether the use click was issued.
     * <p>
     * Confirming the effect — the make-X prompt, the animation, an XP drop — stays with
     * the caller, which owns the module's tracking. On any failure path a selection this
     * recipe created is cleared (the {@code eatReady} idiom: a click at the current
     * position drops it), because a dangling cursor item silently converts the next
     * inventory op. A stale selection present on entry is cleared too — otherwise the
     * item click would apply that pair instead of selecting.
     * <p>
     * The item's default left-click op must be "Use" (true for logs, tinderbox and the
     * other supported items): a physical click cannot pick a non-default op.
     *
     * @return true when the "use item on object" click was issued (effect unverified)
     */
    public static boolean useItemOnObject(Rs2ItemModel item, TileObject object) {
        if (!select(item)) {
            return false;
        }
        Target target = resolveObject(object, RENDER_WAIT_STEPS);
        if (!target.ready()) {
            clearSelection();
            return false;
        }
        Microbot.getMouse().click(target.clickbox());
        return true;
    }

    /**
     * Item-on-item recipe — the same select-verify-click flow with the target resolved
     * as another inventory slot (tinderbox on logs) instead of an entity clickbox.
     * Selection cleanup on the failure path as in
     * {@link #useItemOnObject(Rs2ItemModel, TileObject)}.
     *
     * @return true when the "use item on item" click was issued (effect unverified)
     */
    public static boolean useItemOnItem(Rs2ItemModel item, Rs2ItemModel target) {
        if (!select(item)) {
            return false;
        }
        Rectangle bounds = target == null ? null : Rs2Inventory.itemBounds(target);
        if (bounds == null) {
            log.warn("[AScriptClick] no inventory bounds for the use target (slot {})",
                    target == null ? -1 : target.getSlot());
            clearSelection();
            return false;
        }
        Microbot.getMouse().click(bounds);
        return true;
    }

    /**
     * Drop a pending cursor selection: a physical click at the current position cancels
     * it (the {@code eatReady} pattern in {@code AScript}). {@code Rs2Inventory.deselect()}
     * is NOT usable for this — it re-uses the selected widget's item id, which is -1 for
     * a spell.
     *
     * @return true when no selection is pending (immediately, or after the cancel click)
     */
    public static boolean clearSelection() {
        if (!Rs2Inventory.isItemSelected()) {
            return true;
        }
        Microbot.getMouse().click();
        return sleepUntil(() -> !Rs2Inventory.isItemSelected(),
                Rs2Random.logNormalBounded(400, 900));
    }

    /**
     * Step one of the item-on-X recipes: pick the item up with a physical click on its
     * inventory slot and verify the game actually selected it.
     */
    private static boolean select(Rs2ItemModel item) {
        if (Rs2Inventory.isItemSelected() && !clearSelection()) {
            log.warn("[AScriptClick] could not clear a stale selection — not clicking an item");
            return false;
        }
        Rectangle bounds = item == null ? null : Rs2Inventory.itemBounds(item);
        if (bounds == null) {
            log.warn("[AScriptClick] no inventory bounds for the item to use (slot {})",
                    item == null ? -1 : item.getSlot());
            return false;
        }
        Microbot.getMouse().click(bounds);
        if (!sleepUntil(Rs2Inventory::isItemSelected, Rs2Random.logNormalBounded(1500, 2500))) {
            log.warn("[AScriptClick] the item click did not select anything");
            return false;
        }
        return true;
    }

    // ── Shared clickbox filtering ──────────────────────────

    private static Target notRendered() {
        return new Target(null, false, false);
    }

    /**
     * The rectangle is the canvas-sized fallback {@link Rs2UiHelper#getObjectClickbox} /
     * {@link Rs2UiHelper#getActorClickbox} return when the target has no hull yet (not
     * rendered in the last frame) — a click on it scatters over the terrain as
     * "Walk here". A real clickbox is a small fraction of the canvas, never this big.
     * Both dimensions are checked (the actor copy's form; the fallback is canvas-sized
     * in both, so the object callers reject exactly the same set as before).
     *
     * @return the clickbox itself, or {@code null} when nothing clickable is there
     */
    private static Rectangle realClickbox(Rectangle clickbox) {
        if (clickbox == null || clickbox.getWidth() <= 0 || clickbox.getHeight() <= 0) {
            return null;
        }
        final int canvasWidth = Microbot.getClient().getCanvasWidth();
        final int canvasHeight = Microbot.getClient().getCanvasHeight();
        if (clickbox.getWidth() >= canvasWidth - 2 || clickbox.getHeight() >= canvasHeight - 2) {
            return null;
        }
        return clickbox;
    }
}
