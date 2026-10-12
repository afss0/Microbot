package net.runelite.client.plugins.microbot.ascript.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
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
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
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
     * Bounded attempts to open the option menu with the target's own entry in it. Each
     * retry closes the menu, re-nudges the hover and right-clicks again — an open menu is
     * frozen, so one that came up without our entry never fixes itself either.
     */
    private static final int MENU_ATTEMPTS = 3;

    /**
     * Row geometry of the client's option menu — the hit-test constants questhelper uses
     * to map menu entries to screen rows ({@code DetailedQuestStep
     * .renderHoveredMenuEntryPanel}: a 15 px row under a header of row + 3).
     */
    private static final int MENU_ROW_HEIGHT = 15;
    private static final int MENU_HEADER_HEIGHT = MENU_ROW_HEIGHT + 3;

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
     * out), and make the press resolve to the OBJECT.
     * <p>
     * Confirming the effect — the make-X prompt, the animation, an XP drop — stays with
     * the caller, which owns the module's tracking. On any failure path a selection this
     * recipe created is cancelled with a press on the item's OWN inventory slot — a press
     * at the last press point would re-dispatch whatever ranks top there, which in the
     * contended case is exactly the mis-dispatch this recipe exists to prevent — because
     * a dangling cursor item silently converts the next inventory op. A stale selection
     * present on entry is cleared too — otherwise the item click would apply that pair
     * instead of selecting.
     * <p>
     * The item's default left-click op must be "Use" (true for logs, tinderbox and the
     * other supported items): a physical click cannot pick a non-default op.
     * <p>
     * <b>Menu priority.</b> With a cursor item selected the client ranks
     * {@code WIDGET_TARGET_ON_PLAYER} above {@code WIDGET_TARGET_ON_GAME_OBJECT} (the
     * client's item-on-X family — confirmed against a live menu log), so a player
     * standing on the fire would win a plain left-click. The recipe reads the hover
     * menu's actual ranking first, then:
     * <ul>
     *   <li><b>Object entry already on top</b> — press at the point, exactly as before.
     *       The same hover rebuilds to the same ranking, so nothing has to survive the
     *       press: a movement frame inside it (weather offset, cursor-tracker drift)
     *       rebuilds the identical ranking.</li>
     *   <li><b>A higher-priority entity shares the hover</b> — right-click to open the
     *       option menu and press OUR OWN ROW in it. An open menu is frozen client-side
     *       ("The menu is not rebuilt when it is open", {@code MenuEntrySwapperPlugin}),
     *       so what the row press resolves to is fixed at open time and no movement frame
     *       can change it. Deprioritize flags ({@link MenuEntry#setDeprioritized(boolean)})
     *       were deliberately NOT used: they live on the current menu's entry objects, and
     *       every movement the sanctioned press path can emit rebuilds the menu and drops
     *       them before dispatch — the swap cannot be made to survive.</li>
     * </ul>
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
            cancelSelectionOnOwnSlot(item, "the cursor never settled on the resolved point");
            return false;
        }
        if (!awaitOwnEntry(isOurs, point)) {
            cancelSelectionOnOwnSlot(item, "the menu has no entry for the use target");
            return false;
        }
        Rank rank = rankOfOwnEntry(isOurs);
        if (rank == Rank.GONE) {
            cancelSelectionOnOwnSlot(item, "the use target's entry left the hover menu");
            return false;
        }
        if (rank == Rank.BEATEN) {
            // Contended: a higher-priority entity (a player standing on the fire) shares
            // the hover. A left-click would dispatch to it, so pick our own row from the
            // open option menu instead of racing the ranking.
            RowPick pick = pressViaMenuRow(point, isOurs);
            if (pick == RowPick.DISPATCHED) {
                return true;
            }
            if (pick == RowPick.MENU_STUCK) {
                log.error("[AScriptClick] the option menu would not close — leaving the cursor "
                        + "selection pending (close the menu manually)");
                return false;
            }
            cancelSelectionOnOwnSlot(item, "the open menu never exposed the use target's row");
            return false;
        }
        // Rank.TOP — uncontended: the object's entry already IS the left-click default.
        // The same hover rebuilds to the same ranking, so a movement frame inside this
        // press cannot change what dispatches — and no priority flag exists here for such
        // a frame to drop. The flags are never set on this path at all.
        Microbot.getMouse().click(point);
        return true;
    }

    // ── Menu pick (item-on-X) ──────────────────────────────

    /**
     * Put the cursor on {@code point} and wait until it is really there. The approach runs
     * through {@code NaturalMouse} so the recipe keeps the natural arc the plain
     * {@code click(clickbox)} used to produce — a single teleported MOVED event would be an
     * input signature no human produces. The corrective move only fires when the arc stopped
     * short: {@code VirtualMouse.handleClick} skips its pre-click move (so the press keeps
     * the no-move fast path {@code PointerState.isAt} gates) only when the tracked pointer
     * already rests on the press point.
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
     * leaves the pointer resting on it — the press that follows can therefore take its
     * no-move path.
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

    /** How the hover menu ranks the target's entry: gone, beaten, or the left-click default. */
    private enum Rank {
        GONE,
        BEATEN,
        TOP
    }

    /**
     * Read the hover menu's ranking of the target's entry on the client thread — the
     * entry at the last index is what a left-click dispatches to ("higher-index entries
     * appearing toward the top of the minimenu", {@code MenuManager}).
     *
     * @return {@link Rank#GONE} also when the client thread could not be reached — a
     *         failed read must fail the recipe, never default to "click it"
     */
    private static Rank rankOfOwnEntry(Predicate<MenuEntry> isOurs) {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> {
                    MenuEntry[] entries = Microbot.getClient().getMenuEntries();
                    int own = topmostIndex(entries, isOurs);
                    if (own < 0) {
                        return Rank.GONE;
                    }
                    return own == entries.length - 1 ? Rank.TOP : Rank.BEATEN;
                })
                .orElse(Rank.GONE);
    }

    /** Outcome of the open-menu row pick. */
    private enum RowPick {
        /** Our row was pressed and the menu closed on it — dispatched. */
        DISPATCHED,
        /** Every bounded attempt ran out with the menu closed and no press issued. */
        FAILED,
        /** The open menu would not close — nothing may be clicked until a human closes it. */
        MENU_STUCK
    }

    /**
     * Contended path: right-click at {@code point} to open the option menu for the hover,
     * find the target's own entry in the (frozen) open menu, and press its row. The open
     * menu is not rebuilt while it is open ({@code MenuEntrySwapperPlugin.onPostMenuSort}:
     * "The menu is not rebuilt when it is open"), so the row we read is the row that
     * dispatches — a movement frame inside the row press (cursor-tracker drift, the
     * weather offset) can move the cursor but cannot change what is listed or where; the
     * offset only shifts a press centered in a 15 px row.
     * <p>
     * Bounded: a menu that came up without our entry is closed, the hover is re-nudged,
     * and the right-click is repeated; a menu that will not close at all reports
     * {@link RowPick#MENU_STUCK} so the caller leaves the cursor selection alone (any
     * further click would dispatch an unknown row).
     */
    private static RowPick pressViaMenuRow(Point point, Predicate<MenuEntry> isOurs) {
        for (int attempt = 0; attempt < MENU_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                nudge(point);
            }
            Microbot.getMouse().click(point, true);
            int own = awaitOwnRow(isOurs);
            if (own >= 0 && clickOwnRow(own)) {
                return RowPick.DISPATCHED;
            }
            if (!closeMenu()) {
                return RowPick.MENU_STUCK;
            }
        }
        return RowPick.FAILED;
    }

    /**
     * Wait for the option menu to open with the target's entry in it. One read once the
     * menu is open is enough — the open menu is frozen, so its entries do not change.
     *
     * @return the entry's index in the open menu, or -1 when the menu never opened or the
     *         open menu does not list it
     */
    private static int awaitOwnRow(Predicate<MenuEntry> isOurs) {
        if (!sleepUntil(AScriptClick::menuOpen, Rs2Random.logNormalBounded(400, 900))) {
            return -1;
        }
        return topmostIndex(isOurs);
    }

    /**
     * Press the center of the target's row in the open menu and confirm the menu closed
     * on it — an open menu closes only when one of its rows (or Cancel) is picked, so
     * "menu gone" is the cheap observable for "a row dispatched".
     */
    private static boolean clickOwnRow(int ownIndex) {
        Point row = rowPoint(ownIndex);
        if (row == null) {
            return false;
        }
        Microbot.getMouse().click(row);
        return sleepUntil(() -> !menuOpen(), Rs2Random.logNormalBounded(400, 900));
    }

    /**
     * Center of entry {@code index} in the open option menu, in canvas coordinates — the
     * questhelper hit-test geometry ({@code DetailedQuestStep.renderHoveredMenuEntryPanel}:
     * a 15 px row under a header of row + 3), with the array ↔ display direction the sort
     * documents: "higher-index entries appearing toward the top of the minimenu"
     * ({@code MenuManager}), so index {@code length - 1} is the first row.
     *
     * @return the row center, or {@code null} when the menu is closed or the index is gone
     */
    private static Point rowPoint(int index) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            if (!client.isMenuOpen()) {
                return null;
            }
            MenuEntry[] entries = client.getMenuEntries();
            if (index < 0 || index >= entries.length) {
                return null;
            }
            int rowFromTop = entries.length - 1 - index;
            int x = client.getMenuX() + client.getMenuWidth() / 2;
            int y = client.getMenuY() + MENU_HEADER_HEIGHT + rowFromTop * MENU_ROW_HEIGHT
                    + MENU_ROW_HEIGHT / 2;
            return new Point(x, y);
        }).orElse(null);
    }

    /** The client's option-menu open flag — live client state, read on the client thread. */
    private static boolean menuOpen() {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().isMenuOpen())
                .orElse(false);
    }

    /**
     * Close an option menu this recipe opened. The bottom "Cancel" entry closes it
     * without dispatching anything, so its row is pressed through the same geometry as
     * the pick above; the Escape key is the fallback when that entry is somehow absent.
     *
     * @return true when no menu is open afterwards — only then may anything else click
     */
    private static boolean closeMenu() {
        if (!menuOpen()) {
            return true;
        }
        int cancel = topmostIndex(entry -> "Cancel".equals(entry.getOption()));
        if (cancel >= 0) {
            Point row = rowPoint(cancel);
            if (row != null) {
                Microbot.getMouse().click(row);
            }
            if (sleepUntil(() -> !menuOpen(), Rs2Random.logNormalBounded(400, 900))) {
                return true;
            }
        }
        Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
        return sleepUntil(() -> !menuOpen(), Rs2Random.logNormalBounded(400, 900));
    }

    /**
     * Fail the item-on-object recipe cleanly: log why, then cancel the selection this
     * recipe created with a press on the item's OWN inventory slot — a press at the last
     * press point would dispatch whatever ranks top there (the use-on-player mis-dispatch
     * in the contended case), so the cancel never happens there. A selection that survives
     * the cancel click is logged; the next {@code select()} retries it.
     */
    private static void cancelSelectionOnOwnSlot(Rs2ItemModel item, String why) {
        log.warn("[AScriptClick] {} — not pressing", why);
        Rectangle bounds = item == null ? null : Rs2Inventory.itemBounds(item);
        if (bounds == null) {
            clearSelection(); // last resort — the pre-existing click-at-current-position idiom
            return;
        }
        Microbot.getMouse().click(Rs2UiHelper.getClickingPoint(bounds, true));
        if (!sleepUntil(() -> !Rs2Inventory.isItemSelected(),
                Rs2Random.logNormalBounded(400, 900))) {
            log.warn("[AScriptClick] the cursor selection is still pending after the cancel click");
        }
    }

    /** Highest-ranked index of the target's entry, or -1 when the menu holds none. */
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
     * The action is {@code WIDGET_TARGET_ON_GAME_OBJECT} — the constant every other fork
     * path uses for this interaction ({@code Rs2GameObject} / {@code Rs2TileObjectModel}
     * builders), confirmed against a live menu log from the firemaking pilot:
     * {@code getOption=Use, getTarget="<item> -> <object>", getType=WIDGET_TARGET_ON_GAME_OBJECT,
     * getIdentifier=<object id>, getParam0/param1=<scene coords>, getItemId=-1}.
     * Only ever evaluated on the client thread, inside the menu reads above.
     * <p>
     * Deliberately separate from the menu pick, which takes the matcher as an input: a
     * {@code WIDGET_TARGET_ON_NPC} / {@code WIDGET_TARGET_ON_PLAYER} overload is a thin
     * follow-up that only swaps this matcher for {@link MenuEntry#getNpc()}
     * / {@link MenuEntry#getPlayer()}.
     */
    private static boolean isOwnObjectEntry(MenuEntry entry, TileObject object) {
        if (entry.getType() != MenuAction.WIDGET_TARGET_ON_GAME_OBJECT
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
