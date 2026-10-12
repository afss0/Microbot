package net.runelite.client.plugins.microbot.ascript.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.input.PointerState;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;

import java.awt.Rectangle;
import java.util.function.Predicate;

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

    /**
     * Bounded attempts for the hover menu to expose an entry for the point the cursor has
     * just landed on. The menu rebuilds on mouse movement, so a read taken right after
     * arrival can still hold the previous point's menu.
     */
    private static final int ENTRY_ATTEMPTS = 5;

    /**
     * Attempts spent only waiting, before the cursor starts being stepped off the point
     * and back on to re-trigger the rebuild — a stale menu never fixes itself.
     */
    private static final int NUDGE_AFTER_ATTEMPT = 2;

    /** How far (px) the cursor steps off the point, and back on, to force a rebuild. */
    private static final int NUDGE_PX = 2;

    /**
     * Bounded polls for a menu-priority change to show up in the menu rank. The client
     * re-sorts the menu after the deprioritized flags are set — the same pass RuneLite's
     * menu entry swapper rides — so the rank is re-read rather than assumed.
     */
    private static final int PROMOTE_ATTEMPTS = 6;

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
     * physical click on its inventory slot, verify the game picked it up, put the cursor
     * on the object's real clickbox (camera first, canvas fallback rejected, render waited
     * out), make a left-click there resolve to the OBJECT, and press at that same point.
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
     * <p>
     * <b>Menu priority.</b> With a cursor item selected the client ranks
     * {@code ITEM_USE_ON_PLAYER} above {@code ITEM_USE_ON_GAME_OBJECT}, so a player
     * standing on the fire steals the press. The recipe therefore demotes every entry the
     * client ranked above the object's own with {@link MenuEntry#setDeprioritized(boolean)}
     * — the flag RuneLite's menu entry swapper uses for the same job — waits until the
     * object's entry really is the top of the minimenu (the entry a left-click dispatches
     * to), and only then presses. Nothing is fabricated: every entry is a real one the
     * client built for this hover, only marked, and the press is a physical click on the
     * object's real clickbox. When the object's entry never reaches the top the recipe
     * fails instead of pressing at a target it knows is wrong.
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

        Predicate<MenuEntry> isOurs = entry -> isOwnObjectEntry(entry, object);

        // Resolve the press point once: getClickingPoint randomizes on every call, and the
        // press has to land on the exact point the menu was built for.
        Point point = Rs2UiHelper.getClickingPoint(target.clickbox(), true);
        if (!arriveAt(point)) {
            log.warn("[AScriptClick] the cursor never settled on the resolved point — not pressing");
            clearSelection();
            return false;
        }
        if (!awaitOwnEntry(isOurs, point)) {
            log.warn("[AScriptClick] the menu has no entry for the use target — not pressing");
            clearSelection();
            return false;
        }
        if (!promoteToPrimaryClick(isOurs)) {
            log.warn("[AScriptClick] a higher-priority entry stayed ranked above the use target — not pressing");
            clearSelection();
            return false;
        }
        // The cursor already rests on the point, so NaturalMouse.moveTo short-circuits and
        // VirtualMouse.handleClick skips its pre-click move: a pure press/release carrying
        // no movement frame, and therefore no menu rebuild between the demotion and the
        // press. The flags are never restored — they live on entries the next rebuild
        // replaces, and a deprioritized entry is a state real swapper users run anyway.
        Microbot.getMouse().click(point);
        return true;
    }

    // ── Menu priority (item-on-X) ─────────────────────────

    /**
     * Put the cursor on {@code point} and wait until it is really there. The approach runs
     * through {@code NaturalMouse} so the recipe keeps the natural arc the plain
     * {@code click(clickbox)} used to produce — a single teleported MOVED event would be an
     * input signature no human produces. The corrective move only fires when the arc stopped
     * short: {@code VirtualMouse.handleClick} skips its pre-click move (so the menu is not
     * rebuilt between the demotion and the press) only when the tracked pointer already
     * rests on the press point.
     *
     * @return true when the pointer rests on the point
     */
    private static boolean arriveAt(Point point) {
        if (Microbot.getNaturalMouse() != null) {
            Microbot.getNaturalMouse().moveTo(point.getX(), point.getY());
        } else {
            Microbot.getMouse().move(point);
        }
        if (!PointerState.isAt(point.getX(), point.getY())) {
            Microbot.getMouse().move(point);
        }
        return sleepUntil(() -> PointerState.isAt(point.getX(), point.getY()),
                Rs2Random.logNormalBounded(400, 900));
    }

    /**
     * Wait for the hover menu to expose the target's own entry. The menu rebuilds only on
     * mouse movement, so from {@code NUDGE_AFTER_ATTEMPT} on the cursor is stepped off the
     * point and back on to re-trigger it (small budget); a stale menu never fixes itself.
     */
    private static boolean awaitOwnEntry(Predicate<MenuEntry> isOurs, Point point) {
        for (int attempt = 0; attempt < ENTRY_ATTEMPTS; attempt++) {
            if (attempt >= NUDGE_AFTER_ATTEMPT) {
                nudge(point);
            }
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(80, 200));
            if (hasOwnEntry(isOurs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Step the cursor off the point and straight back on, so the client rebuilds the menu
     * for this hover. The step stays inside the canvas, and the move back onto the point
     * leaves the pointer resting on it — the press still has no movement frame to skip.
     */
    private static void nudge(Point point) {
        int canvasWidth = Microbot.getClient().getCanvasWidth();
        int canvasHeight = Microbot.getClient().getCanvasHeight();
        int dx = nudgeStep(point.getX(), canvasWidth);
        int dy = nudgeStep(point.getY(), canvasHeight);
        if (dx == 0 && dy == 0) {
            return;
        }
        Microbot.getMouse().move(new Point(point.getX() + dx, point.getY() + dy));
        Microbot.getMouse().move(point);
    }

    /** A {@code NUDGE_PX} px step that stays inside the canvas, or 0 when none fits. */
    private static int nudgeStep(int coordinate, int canvasSize) {
        if (coordinate + NUDGE_PX <= canvasSize - 2) {
            return NUDGE_PX;
        }
        if (coordinate - NUDGE_PX >= 1) {
            return -NUDGE_PX;
        }
        return 0;
    }

    private static boolean hasOwnEntry(Predicate<MenuEntry> isOurs) {
        return topmostIndex(isOurs) >= 0;
    }

    /**
     * Demote every entry the client ranked above the target's with
     * {@link MenuEntry#setDeprioritized(boolean)}, then wait until the target's entry is
     * the top of the minimenu. Only real, existing entries are marked — no reorder, no
     * fabrication — and the client's own sort does the rest, so the recipe verifies the
     * very thing the click will resolve to instead of assuming it.
     *
     * @return true when a left-click would now dispatch to the target's entry
     */
    private static boolean promoteToPrimaryClick(Predicate<MenuEntry> isOurs) {
        for (int attempt = 0; attempt < PROMOTE_ATTEMPTS; attempt++) {
            Pass pass = Microbot.getClientThread()
                    .runOnClientThreadOptional(() -> demoteAbove(isOurs))
                    .orElse(Pass.GONE);
            if (pass == Pass.GONE) {
                return false;
            }
            if (pass == Pass.TOP) {
                return true;
            }
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(60, 160));
        }
        return false;
    }

    /**
     * One pass over the live menu, run on the client thread — the menu is live client
     * state, and RuneLite's own swapper marks its entries there too.
     */
    private enum Pass {
        /** The target's entry is gone: the menu no longer describes this hover. */
        GONE,
        /** Entries above the target were marked; the client's sort has not run yet. */
        PENDING,
        /** The target's entry is the top of the minimenu. */
        TOP
    }

    private static Pass demoteAbove(Predicate<MenuEntry> isOurs) {
        MenuEntry[] entries = Microbot.getClient().getMenuEntries();
        int own = topmostIndex(entries, isOurs);
        if (own < 0) {
            return Pass.GONE;
        }
        // Higher index = higher in the minimenu (MenuManager: "higher-index entries
        // appearing toward the top"), so everything above the last index outranks it and
        // the last index is the entry a left-click dispatches to.
        if (own == entries.length - 1) {
            return Pass.TOP;
        }
        for (int i = own + 1; i < entries.length; i++) {
            if (entries[i] != null) {
                entries[i].setDeprioritized(true);
            }
        }
        return Pass.PENDING;
    }

    private static int topmostIndex(Predicate<MenuEntry> isOurs) {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> topmostIndex(Microbot.getClient().getMenuEntries(), isOurs))
                .orElse(-1);
    }

    /** Highest-ranked index of the target's entry, or -1 when the menu holds none. */
    private static int topmostIndex(MenuEntry[] entries, Predicate<MenuEntry> isOurs) {
        int found = -1;
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] != null && isOurs.test(entries[i])) {
                found = i;
            }
        }
        return found;
    }

    /**
     * The entry the press must resolve to for {@code object}: the client's own
     * "use &lt;item&gt; on &lt;object&gt;" line, matched on action, object id and the scene
     * coordinates the client packs into param0/param1 — the mapping
     * {@code Rs2TileObjectModel.click} mirrors when it builds an entry for a tile object.
     * Only ever evaluated on the client thread, inside the menu passes above.
     * <p>
     * Deliberately separate from the priority helper, which takes the matcher as an input:
     * an {@code ITEM_USE_ON_NPC} / {@code ITEM_USE_ON_PLAYER} overload is a thin follow-up
     * that only swaps this matcher for {@link MenuEntry#getNpc()} / {@link MenuEntry#getPlayer()}.
     */
    private static boolean isOwnObjectEntry(MenuEntry entry, TileObject object) {
        if (entry.getType() != MenuAction.ITEM_USE_ON_GAME_OBJECT
                || entry.getIdentifier() != object.getId()) {
            return false;
        }
        int[] scene = sceneCoords(object);
        return entry.getParam0() == scene[0] && entry.getParam1() == scene[1];
    }

    /**
     * Scene coordinates the client packs into an object menu entry's param0/param1 —
     * {@code Rs2TileObjectModel.click}'s mapping: a multi-tile {@link GameObject} is stored
     * at its south-west-centre tile, every other tile object at its own local location.
     * Both readings agree for the 1-tile objects the recipe runs on today.
     */
    private static int[] sceneCoords(TileObject object) {
        if (object instanceof GameObject) {
            GameObject gameObject = (GameObject) object;
            LocalPoint local = gameObject.getLocalLocation();
            int sceneX = local.getSceneX() - (gameObject.sizeX() > 1 ? gameObject.sizeX() / 2 : 0);
            int sceneY = local.getSceneY() - (gameObject.sizeY() > 1 ? gameObject.sizeY() / 2 : 0);
            return new int[]{sceneX, sceneY};
        }
        LocalPoint local = object.getLocalLocation();
        return new int[]{local.getSceneX(), local.getSceneY()};
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
