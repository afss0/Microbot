package net.runelite.client.plugins.microbot.ascript.util;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.input.PointerState;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.util.Text;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Shared client-mouse click recipes for aScript modules — the "clicks go through the
 * client mouse" rule ({@code ascript/AGENTS.md}): a module's own action is a physical
 * click on real bounds (widget slot or entity clickbox), never a menu-entry injection
 * ({@code Microbot.doInvoke} / {@code NewMenuEntry} / {@code Rs2Inventory.interact(...)}).
 * <p>
 * Recipes, consolidated from the per-module copies that grew in Cannoning
 * (object), Ammonite Crabs (actor) and Cannonball Smelter (object render gate):
 * <ul>
 *   <li><b>Object / actor / ground-item click</b> — camera first, then the target's real
 *       clickbox through {@link Microbot#getMouse()}. The canvas-sized fallback rectangle
 *       {@link Rs2UiHelper#getObjectClickbox} / {@link Rs2UiHelper#getActorClickbox} /
 *       {@link Rs2UiHelper#getTileClickbox} return for a target that is not rendered yet is
 *       never clicked — it lands on the terrain as "Walk here". The press is
 *       <b>dispatch-verified</b>: the hover menu must rank the target's own row top (a plain
 *       left click then dispatches it), otherwise the option menu is opened and OUR row is
 *       pressed out of the frozen menu — a player standing on the target can no longer steal
 *       the click. Callers keep their own render-miss accounting: the recipes report why a
 *       click did not happen via {@link Target#miss()}.</li>
 *   <li><b>Non-default op</b> — the same recipes take an optional action name
 *       ({@code "Bait"}, {@code "Dismiss"}, {@code "Smelt"}, ...). A left click can only ever
 *       reach the default op, so a non-default row is matched on its option text and pressed
 *       in the open menu; an action that already ranks top is still dispatched with a plain
 *       click.</li>
 *   <li><b>Inventory action</b> ({@link #inventoryOptionAtSlot}) — press an item's own
 *       non-default option through the same swap/verify/press tail (the auto-eat QOL uses it
 *       for {@code Eat}/{@code Drink}). A plain slot click can only dispatch the container's
 *       default op — {@code Deposit} while the bank is open — so the real row is promoted
 *       first.</li>
 *   <li><b>Item-on-X</b> ({@link #useItemOnObject(Rs2ItemModel, TileObject)} /
 *       {@link #useItemOnNpc} / {@link #useItemOnPlayer} / {@link #useItemOnItem}) —
 *       select the first item with a physical click on its inventory slot, verify the game
 *       picked it up, click the target (an entity clickbox or another inventory slot)
 *       physically. The selection is armed on the item's real <b>{@code Use}</b> row: a plain
 *       slot click dispatches the item's default op, which equips a bolt stack or the
 *       fletcher's knife, so {@link #onPostMenuSort()} moves the real {@code Use} row on top
 *       (the drop recipe's MES mechanics) and no press is issued until it is. Any failure path
 *       clears a selection the recipe created, because a dangling cursor item silently
 *       converts the NEXT inventory op; an item the click wore by mistake is taken back off
 *       ({@link #unequip(int)}) before the recipe refuses the dispatch. A banknote is rejected
 *       outright — it is a certificate, not the item a Use click can act on.</li>
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

    /**
     * Post-press confirmation for the item-on-X pick: a real "use" dispatch consumes the
     * cursor selection, and that consumption — not "the menu closed", which Cancel also
     * produces — is what tells a dispatch from a no-op.
     */
    private static final BooleanSupplier SELECTION_CONSUMED =
            () -> sleepUntil(() -> !Rs2Inventory.isItemSelected(),
                    Rs2Random.logNormalBounded(400, 900));

    /**
     * Post-press confirmation for a non-default op pick: such a row carries no cursor
     * selection to consume, so "our row was pressed and the menu closed on it" is the
     * whole in-recipe confirmation. Confirming the EFFECT (the animation, the
     * interaction, the XP) stays with the caller's existing gate.
     */
    private static final BooleanSupplier MENU_CLOSED_IS_ENOUGH = () -> true;

    private AScriptClick() {
    }

    /**
     * Why a recipe stopped short of a press. {@link #NONE} means the recipe reached its press
     * (a plain left click on the top-ranked row, or a press on our row in the open menu);
     * every other value names the step that stopped it, so a module can keep its render-miss
     * budget separate from a hover/menu failure instead of counting both as "not rendered".
     */
    public enum Miss {
        /** No failure: the target resolved and the press (when due) was issued. */
        NONE,
        /** No real hull yet (canvas-sized fallback rejected) — a render race, retry. */
        NOT_RENDERED,
        /** The target was off camera and the camera has just been turned — retry. */
        CAMERA_TURNED,
        /** The cursor never settled on the resolved press point. */
        CURSOR_LOST,
        /** The hover/open menu never exposed the target's own row. */
        HOVER_GONE,
        /** The open option menu would not close — nothing may be clicked until a human closes it. */
        MENU_STUCK
    }

    /**
     * Outcome of an inventory item action press ({@link #inventoryOptionAtSlot}). A physical
     * press returns nothing, so only {@link #ISSUED} means the press happened — confirming the
     * effect stays with the caller.
     */
    public enum ItemClickResult {
        /** The press was issued (the effect is the caller's to confirm). */
        ISSUED,
        /** The container exposed no such action, or the slot changed under the cursor. */
        NO_ACTION,
        /** A cursor selection/menu could not be cleared or the pointer did not settle. */
        BLOCKED
    }

    /**
     * Outcome of a click recipe: the resolved clickbox (when there is one), whether the press
     * was issued, and — when it was not — at which step the recipe stopped.
     */
    public static final class Target {
        private final Rectangle clickbox;
        private final boolean clicked;
        private final Miss miss;

        private Target(Rectangle clickbox, boolean clicked, Miss miss) {
            this.clickbox = clickbox;
            this.clicked = clicked;
            this.miss = miss;
        }

        /** The target's real clickbox, or {@code null} when it has none yet. */
        public Rectangle clickbox() {
            return clickbox;
        }

        /** True when a real (non-canvas-fallback) clickbox was resolved. */
        public boolean ready() {
            return clickbox != null;
        }

        /** True when the recipe issued the press (plain click or our row in the open menu). */
        public boolean clicked() {
            return clicked;
        }

        /** Why the recipe stopped short of a press — {@link Miss#NONE} when it did not. */
        public Miss miss() {
            return miss;
        }

        /**
         * True when the target was off camera and the camera has just been turned —
         * retry on a later tick; this is not a failure and must not spend lives.
         */
        public boolean cameraTurned() {
            return miss == Miss.CAMERA_TURNED;
        }

        private Target clickIssued() {
            return new Target(clickbox, true, Miss.NONE);
        }

        private Target missed(Miss reason) {
            return new Target(clickbox, false, reason);
        }
    }

    // ── Object / actor / ground-item recipes ───────────────

    /**
     * Left-click {@code object}'s default op, dispatch-verified against the hover menu.
     * <p>
     * The object's real clickbox is resolved first (camera, canvas fallback rejected), then
     * the cursor rests on the press point and the hover menu is read: the object's own row
     * ranked top means a plain left click dispatches it; a higher-priority entity sharing the
     * hover (a player standing on the object) sends the recipe through the open menu, where
     * OUR row is pressed out of a menu the client freezes. No row at all means no click.
     *
     * @param object the target object (a {@code Rs2TileObjectModel} or a raw tile object)
     * @return the outcome — {@link Target#clicked()} when the press was issued
     */
    public static Target clickObject(TileObject object) {
        return clickObject(object, null, 0);
    }

    /**
     * {@link #clickObject(TileObject)} with a bounded render wait: when the object's hull has
     * not rendered yet, poll for up to {@code renderWaitSteps} short intervals instead of
     * returning immediately.
     *
     * @param renderWaitSteps 0 = no wait (return {@code NOT_RENDERED} right away)
     */
    public static Target clickObject(TileObject object, int renderWaitSteps) {
        return clickObject(object, null, renderWaitSteps);
    }

    /**
     * Click {@code object}'s named action (e.g. {@code "Smelt"}, {@code "Deposit"},
     * {@code "Climb-down"}). A left click can only ever reach the default op, so a
     * non-default row is pressed out of the open option menu; an action that already ranks
     * top is still dispatched with a plain click.
     *
     * @param action the exact op text, case-insensitive; {@code null}/blank = default op
     */
    public static Target clickObject(TileObject object, String action) {
        return clickObject(object, action, 0);
    }

    /** {@link #clickObject(TileObject, String)} with the bounded render wait. */
    public static Target clickObject(TileObject object, String action, int renderWaitSteps) {
        Target target = resolveObject(object, renderWaitSteps);
        if (!target.ready()) {
            return target;
        }
        return pressVerified(target, describeObject(object, action),
                entry -> isOwnObjectAction(entry, object, action), MENU_CLOSED_IS_ENOUGH);
    }

    /**
     * First half of the object recipe — camera first, then the object's real clickbox,
     * without clicking. For callers that gate the click themselves or compose their own
     * action on top of it.
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
            return new Target(null, false, Miss.CAMERA_TURNED);
        }
        Rectangle clickbox = realClickbox(Rs2UiHelper.getObjectClickbox(object));
        for (int i = 0; i < renderWaitSteps && clickbox == null; i++) {
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(150, 300));
            clickbox = realClickbox(Rs2UiHelper.getObjectClickbox(object));
        }
        return clickbox == null ? notRendered() : new Target(clickbox, false, Miss.NONE);
    }

    /**
     * Left-click {@code actor}'s default op (its top-ranked action), dispatch-verified against
     * the hover menu — {@link #clickObject(TileObject)} with the actor hull. Pass the raw
     * {@link NPC} when possible ({@code model.getNpc()}); a model wrapper is also matched
     * through its NPC index, so the click cannot drift to another actor on the same hover.
     */
    public static Target clickActor(Actor actor) {
        return clickActor(actor, null);
    }

    /**
     * Click {@code actor}'s named action (e.g. {@code "Bait"}, {@code "Dismiss"}), verified:
     * a plain click when it already ranks top, otherwise OUR row out of the open option menu.
     *
     * @param action the exact op text, case-insensitive; {@code null}/blank = default op
     */
    public static Target clickActor(Actor actor, String action) {
        Target target = resolveActor(actor);
        if (!target.ready()) {
            return target;
        }
        return pressVerified(target, describeActor(actor, action),
                entry -> isOwnActorAction(entry, actor, action), MENU_CLOSED_IS_ENOUGH);
    }

    /**
     * First half of the actor recipe — camera first, then the actor's real clickbox,
     * without clicking.
     *
     * @return the resolved clickbox, or a {@link Target} saying why there is none
     *         (an off-camera actor returns with {@link Target#cameraTurned()} set —
     *         the turn is the whole action there, retry on a later tick)
     */
    private static Target resolveActor(Actor actor) {
        if (actor == null) {
            return notRendered();
        }
        final LocalPoint local = Microbot.getClientThread().invoke(actor::getLocalLocation);
        if (local == null) {
            return notRendered();
        }
        if (!Rs2Camera.isTileOnScreen(local)) {
            Rs2Camera.turnTo(local);
            return new Target(null, false, Miss.CAMERA_TURNED);
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
        return new Target(clickbox, false, Miss.NONE);
    }

    /**
     * Pick up / interact with a ground item, dispatch-verified: the tile's clickbox is
     * clicked only once the hover menu ranks the item's own row (item id + scene tile) top;
     * a higher-priority hover goes through the open menu, where OUR row is pressed out of a
     * frozen menu. The canvas-sized fallback of an unrendered tile is never clicked.
     *
     * @param item the ground item (e.g. {@code Rs2TileItemModel}, which implements {@link TileItem})
     * @param tile the tile it lies on ({@code model.getTile()})
     */
    public static Target clickGroundItem(TileItem item, Tile tile) {
        return clickGroundItem(item, tile, null);
    }

    /**
     * Click a ground item's named action ({@code "Take"} is the usual default; item
     * definitions also expose actions such as {@code "Bury"}), verified like the default op.
     *
     * @param action the exact op text, case-insensitive; {@code null}/blank = default op
     */
    public static Target clickGroundItem(TileItem item, Tile tile, String action) {
        if (item == null || tile == null) {
            return notRendered();
        }
        final LocalPoint local = Microbot.getClientThread().invoke(tile::getLocalLocation);
        if (local == null) {
            return notRendered();
        }
        Target target = resolveGroundItem(tile, local);
        if (!target.ready()) {
            return target;
        }
        final int sceneX = local.getSceneX();
        final int sceneY = local.getSceneY();
        return pressVerified(target, describeGroundItem(item, action),
                entry -> isOwnGroundItemAction(entry, item, sceneX, sceneY, action),
                MENU_CLOSED_IS_ENOUGH);
    }

    /**
     * Resolve a ground item's clickbox from its tile: camera first, then the tile rectangle
     * {@link Rs2UiHelper#getTileClickbox} maps on the client thread, with the canvas-sized
     * fallback rejected (the tile is not rendered yet).
     */
    private static Target resolveGroundItem(Tile tile, LocalPoint local) {
        if (!Rs2Camera.isTileOnScreen(local)) {
            Rs2Camera.turnTo(local);
            return new Target(null, false, Miss.CAMERA_TURNED);
        }
        Rectangle clickbox = realClickbox(
                Microbot.getClientThread().invoke(() -> Rs2UiHelper.getTileClickbox(tile)));
        return clickbox == null ? notRendered() : new Target(clickbox, false, Miss.NONE);
    }


    // ── Inventory menu swap (menu-entry-swapper mechanics) ─────────

    /**
     * Pending menu swap: the client menu's {@code PostMenuSort} moves this item's named real
     * row ({@code Drop} or {@code Use}) to the top. The swap state has to be static —
     * {@link #onPostMenuSort()} is invoked by the plugin listener and has no caller to hand it
     * to.
     */
    private static volatile String menuSwapOption;
    private static volatile int menuSwapSlot = -1;
    private static volatile int menuSwapItemId = -1;

    /**
     * Serializes the armed window among menu-swap callers (drops and item-on-X selections).
     * Two concurrent swaps would otherwise overwrite each other's slot and one caller could
     * press the other's row. {@code onPostMenuSort} must NEVER take this lock: it runs on the
     * client thread, while the holder waits on client-thread reads, so locking there would
     * deadlock the recipe the first time it polls the slot state.
     */
    private static final Object MENU_SWAP_LOCK = new Object();

    /** The item-on-X selection action. */
    private static final String USE_OPTION = "Use";

    /**
     * Bounded attempts to take an accidentally worn item off again.
     */
    private static final int UNEQUIP_ATTEMPTS = 3;

    /**
     * Inventory-row identity for a menu entry: dynamic children carry their slot in
     * {@link Widget#getIndex()} and the item in {@link Widget#getItemId()}, while the client
     * also packs the same slot/item into {@code param0} / {@link MenuEntry#getItemId()} for
     * inventory ops. Either source may identify the row. Matching the slot (not just the option
     * text) is what keeps the swap from reordering another slot's row.
     * <p>
     * Only ever evaluated on the client thread (hover/open-menu reads and the swap hook).
     */
    static boolean occupiesInventorySlot(MenuEntry entry, int slot, int itemId) {
        if (entry == null) {
            return false;
        }
        Widget widget = entry.getWidget();
        boolean slotMatches = (widget != null && widget.getIndex() == slot)
                || entry.getParam0() == slot;
        if (!slotMatches) {
            return false;
        }
        int entryItemId = widget != null && widget.getItemId() > 0
                ? widget.getItemId() : entry.getItemId();
        return entryItemId <= 0 || entryItemId == itemId;
    }

    /**
     * Drop one inventory item the way RuneLite's Menu Entry Swapper does: the client builds the
     * item's real menu entries on hover, and {@link #onPostMenuSort()} reorders them so the
     * item's own {@code Drop} row is the top (left-click) entry. The press then dispatches that
     * real entry — no fabricated menu entry, no injection, no open menu.
     * <p>
     * A raw click would dispatch the default action ("Use" for raw fish, ore and logs), which
     * selected the item instead of dropping it; the right-click recipe failed live because the
     * open menu rarely exposed the Drop row at 7 fps.
     * <p>
     * The swap stays armed (and is re-applied on every menu rebuild, as MES does) until the
     * press has been processed, so a movement inside the click cannot resolve to "Use".
     *
     * @param slot   inventory slot (0-27)
     * @param itemId item expected in the slot; an empty or different slot is a no-op
     * @return true when the slot no longer holds the item
     */
    public static boolean dropAtSlot(int slot, int itemId) {
        ensureInventoryVisible();
        // An open menu freezes the hover: a press at the slot point would land on whatever
        // row the menu shows there. Every other recipe closes it first; the drop recipe must
        // too, or a menu left by a previous action can turn a Drop into an arbitrary row.
        if (menuOpen() && !closeMenu()) {
            log.error("[AScriptClick] an option menu is open and will not close — not dropping slot {}", slot);
            return false;
        }
        Rs2ItemModel item = Rs2Inventory.getItemInSlot(slot);
        if (item == null || item.getId() != itemId) {
            return false;
        }
        Rectangle bounds = Rs2Inventory.itemBounds(item);
        if (bounds == null) {
            return false;
        }
        Point point = Rs2UiHelper.getClickingPoint(bounds, true);
        boolean dropped = false;
        synchronized (MENU_SWAP_LOCK) {
            armMenuSwap(slot, itemId, "Drop");
            try {
                // Hover first: the client builds the real menu for this slot, PostMenuSort reorders
                // it, and only then does the press mean "Drop". NaturalMouse owns the travel; a
                // direct VirtualMouse.move would teleport and make the press instantaneous.
                if (!moveNaturally(point)) {
                    log.warn("[AScriptClick] the cursor did not settle on the Drop slot — retrying");
                    return false;
                }
                if (!sleepUntil(() -> isMenuSwapOnTop(slot, itemId, "Drop"),
                        Rs2Random.logNormalBounded(400, 800))) {
                    log.warn("[AScriptClick] the Drop row never reached the top of the menu");
                    return false;
                }
                Microbot.getMouse().click(point);
                // The slot clears on the next client frame or two (7 fps in the sandboxes). The swap
                // stays armed until then so any rebuild during the press re-applies it. Poll through
                // the shared randomized wait (anti-ban timing, interrupt handling and the
                // human-override check) instead of a fixed sleep; the trailing re-read keeps a
                // landing that arrived while a human held input from reading as a failed drop.
                dropped = sleepUntil(() -> Rs2Inventory.getIdForSlot(slot) != itemId,
                        Rs2Random.logNormalBounded(500, 1100))
                        || Rs2Inventory.getIdForSlot(slot) != itemId;
            } finally {
                clearMenuSwap();
            }
        }
        return dropped;
    }

    /**
     * Press one of an inventory item's own actions through the client mouse — the generalised
     * form of {@link #dropAtSlot} and {@link #select}, used by the auto-eat QOL for
     * {@code Eat}/{@code Drink}.
     * <p>
     * A physical slot click dispatches the item's <b>default</b> op, which differs by
     * container: {@code Use} for logs, {@code Wield} for bolts, and {@code Deposit} for an
     * inventory item while the bank is open. The MES-style swap therefore promotes the
     * client's own row for {@code option} to the top, the recipe waits until that is verified,
     * and only then presses. A row that never appears means this container does not expose
     * the action ({@link ItemClickResult#NO_ACTION}) — the caller can close the interface
     * instead of clicking blindly. The effect is not confirmed here: the press may land a tick
     * later, and the caller's tracking owns that confirmation.
     *
     * @param slot   inventory slot (0-27)
     * @param itemId item expected in the slot; a stale/different slot is a no-op
     * @param option exact action name, e.g. {@code "Eat"} or {@code "Drink"}
     */
    public static ItemClickResult inventoryOptionAtSlot(int slot, int itemId, String option) {
        if (option == null || option.isBlank()) {
            return ItemClickResult.BLOCKED;
        }
        // A closed inventory tab has no bounds; an external container (bank/GE/shop/deposit
        // box) is already visible and must not have a tab hotkey pressed at it.
        if (Rs2Inventory.getInventory() == null) {
            ensureInventoryVisible();
        }
        if (menuOpen() && !closeMenu()) {
            log.error("[AScriptClick] an option menu is open and will not close — not pressing '{}'", option);
            return ItemClickResult.BLOCKED;
        }
        Rs2ItemModel item = Rs2Inventory.getItemInSlot(slot);
        if (item == null || item.getId() != itemId) {
            return ItemClickResult.NO_ACTION;
        }
        // The normal inventory's widget actions are the item's own actions, so an absent row
        // is definitive there. Other containers (bank, shop, GE, deposit box) build their menu
        // from the container context — the bank's left-click default is Deposit while Eat is a
        // real right-click row — so let the hover/swap verification below decide.
        Widget container = Rs2Inventory.getInventory();
        if (container != null
                && container.getId() == ComponentID.INVENTORY_CONTAINER
                && optionAvailability(item, option) == OptionAvailability.ABSENT) {
            return ItemClickResult.NO_ACTION;
        }
        Rectangle bounds = Rs2Inventory.itemBounds(item);
        if (bounds == null) {
            return ItemClickResult.NO_ACTION;
        }
        Point point = Rs2UiHelper.getClickingPoint(bounds, true);
        synchronized (MENU_SWAP_LOCK) {
            armMenuSwap(slot, itemId, option);
            try {
                // NaturalMouse owns the pre-hover travel; a direct VirtualMouse.move would
                // teleport and make the following press instantaneous.
                if (!moveNaturally(point)) {
                    log.warn("[AScriptClick] the cursor did not settle on the '{}' slot", option);
                    return ItemClickResult.BLOCKED;
                }
                if (!sleepUntil(() -> isMenuSwapOnTop(slot, itemId, option),
                        Rs2Random.logNormalBounded(400, 800))) {
                    log.warn("[AScriptClick] the '{}' row never reached the top of the item menu", option);
                    return ItemClickResult.NO_ACTION;
                }
                Microbot.getMouse().click(point);
                return ItemClickResult.ISSUED;
            } finally {
                clearMenuSwap();
            }
        }
    }

    private enum OptionAvailability { PRESENT, ABSENT, UNKNOWN }

    /**
     * Whether the visible inventory container's widget exposes {@code option} for this slot.
     * {@link OptionAvailability#UNKNOWN} (no widget/children/actions) falls through to the
     * hover verification rather than refusing a dispatch on missing metadata.
     */
    private static OptionAvailability optionAvailability(Rs2ItemModel item, String option) {
        if (item == null || option == null) {
            return OptionAvailability.UNKNOWN;
        }
        Widget container = Rs2Inventory.getInventory();
        if (container == null) {
            return OptionAvailability.UNKNOWN;
        }
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget[] children = container.getDynamicChildren();
            if (children == null) {
                return OptionAvailability.UNKNOWN;
            }
            for (Widget child : children) {
                if (child == null || child.getIndex() != item.getSlot()) {
                    continue;
                }
                String[] actions = child.getActions();
                if (actions == null || actions.length == 0) {
                    return OptionAvailability.UNKNOWN;
                }
                for (String action : actions) {
                    if (action != null && Text.removeTags(action).trim().equalsIgnoreCase(option.trim())) {
                        return OptionAvailability.PRESENT;
                    }
                }
                return OptionAvailability.ABSENT;
            }
            return OptionAvailability.UNKNOWN;
        }).orElse(OptionAvailability.UNKNOWN);
    }

    private static void armMenuSwap(int slot, int itemId, String option) {
        menuSwapOption = option;
        menuSwapSlot = slot;
        menuSwapItemId = itemId;
    }

    private static void clearMenuSwap() {
        menuSwapItemId = -1;
        menuSwapSlot = -1;
        menuSwapOption = null;
    }

    /**
     * Drop any pending menu swap and cancel the recipe state at a script/plugin boundary.
     * A swap left armed by an interrupted run would keep reordering that item's Drop/Use row
     * for the whole session; the plugin shutdown/start hooks call this.
     */
    public static void reset() {
        clearMenuSwap();
    }

    /**
     * MES-style swap, called from the plugin on every menu sort: put the pending item's real
     * {@code Drop} / {@code Use} entry on top of the client menu. Skipped while the menu is
     * open — the open menu is not rebuilt, so swapping there would reorder it under the
     * player's mouse.
     */
    public static void onPostMenuSort() {
        int itemId = menuSwapItemId;
        if (itemId < 0) {
            return;
        }
        int slot = menuSwapSlot;
        String option = menuSwapOption;
        if (option == null) {
            return;
        }
        Client client = Microbot.getClient();
        if (client.isMenuOpen()) {
            return;
        }
        MenuEntry[] entries = client.getMenuEntries();
        int own = -1;
        for (int i = 0; i < entries.length; i++) {
            MenuEntry entry = entries[i];
            if (entry == null || !optionMatches(entry, option)
                    || !occupiesInventorySlot(entry, slot, itemId)) {
                continue;
            }
            own = i;
            break;
        }
        // The client stores the top entry last; moving ours there makes it the left click.
        if (own < 0 || own == entries.length - 1) {
            return;
        }
        MenuEntry top = entries[own];
        System.arraycopy(entries, own + 1, entries, own, entries.length - 1 - own);
        entries[entries.length - 1] = top;
        client.getMenu().setMenuEntries(entries);
    }

    /**
     * True when the client menu's top entry is the pending real row of {@code slot}. The press
     * is withheld until this holds: a left click only ever dispatches the top row, and a
     * movement frame inside the press rebuilds the menu (the swap re-applies on every rebuild).
     */
    private static boolean isMenuSwapOnTop(int slot, int itemId, String option) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            MenuEntry[] entries = Microbot.getClient().getMenuEntries();
            if (entries.length == 0) {
                return false;
            }
            MenuEntry top = entries[entries.length - 1];
            return top != null && optionMatches(top, option)
                    && occupiesInventorySlot(top, slot, itemId);
        }).orElse(false);
    }

    // ── Item-on-X recipe ───────────────────────────────────────────

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
     * The item's <b>real {@code Use} row</b> is what gets selected, regardless of the item's
     * default left-click op: the swap in {@link #onPostMenuSort()} puts the client's own
     * {@code Use} entry on top before the press, so a bolt stack or the fletcher's knife is
     * never worn by the selection click.
     * <p>
     * <b>Menu priority.</b> The contended case (a higher-priority entity shares the
     * hover and would win a plain left-click) and its open-menu row pick are handled by
     * the shared tail {@link #pressUseOn(Rs2ItemModel, Target, Predicate)}, which this
     * recipe, {@link #useItemOnNpc} and {@link #useItemOnPlayer} all run — the matcher
     * is the only per-target part.
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
        return pressUseOn(item, target, entry -> isOwnObjectEntry(entry, object));
    }

    /**
     * Item-on-NPC recipe — the same contract as
     * {@link #useItemOnObject(Rs2ItemModel, TileObject)} with the NPC in the object's
     * place: select {@code item} with a physical click on its inventory slot (verified),
     * resolve the NPC's real clickbox (camera first, canvas-sized fallback rejected,
     * {@link Rs2UiHelper#getActorClickbox}), and make the press resolve to the NPC.
     * <p>
     * The entry is the client's own "use &lt;item&gt; on &lt;NPC&gt;" line,
     * {@link MenuAction#WIDGET_TARGET_ON_NPC} (the item-on-X family this client really
     * uses — the ITEM_USE_ON_* constants never appear in its menu, F0.1 live menu log),
     * matched through {@link MenuEntry#getActor()} against the NPC the caller passed —
     * the generic matcher {@link #isOwnUseTargetEntry}. Menu priority bites here harder
     * than for objects: a player standing on the NPC ranks above it, so the contended
     * row pick in {@link #pressUseOn} is the common path, not the edge case.
     * <p>
     * Every failure path clears a selection this recipe created; confirming the effect
     * (the hit, the pickup, the XP) stays with the caller.
     *
     * @param item the item to use; its real {@code Use} row is selected even when the item's
     *             default left-click op is {@code Wield}
     * @param npc  the target NPC — prefer the raw {@link NPC} (e.g. {@code model.getNpc()});
     *             the NPC-index fallback also matches a model wrapper
     * @return true when the "use item on NPC" click was issued (effect unverified)
     */
    public static boolean useItemOnNpc(Rs2ItemModel item, NPC npc) {
        if (!select(item)) {
            return false;
        }
        Target target = resolveActor(npc);
        if (!target.ready()) {
            clearSelection();
            return false;
        }
        return pressUseOn(item, target,
                entry -> isOwnUseTargetEntry(entry, npc, MenuAction.WIDGET_TARGET_ON_NPC));
    }

    /**
     * Item-on-player recipe — the same contract as
     * {@link #useItemOnNpc(Rs2ItemModel, NPC)} with {@link MenuAction#WIDGET_TARGET_ON_PLAYER}
     * as the entry family and a {@link Player} as the target. Use this only when the
     * interaction really is item-on-player (e.g. using an item on another player); the
     * usual NPC/PC distinction applies — a player on the hover over an NPC target is the
     * competition the row pick handles, not a reason for this overload.
     * <p>
     * Same failure-path and confirmation contract as the other item-on-X recipes.
     *
     * @param item   the item to use; its real {@code Use} row is selected even when the item's
     *               default left-click op is {@code Wield}
     * @param player the target player — pass the raw {@link Player}
     * @return true when the "use item on player" click was issued (effect unverified)
     */
    public static boolean useItemOnPlayer(Rs2ItemModel item, Player player) {
        if (!select(item)) {
            return false;
        }
        Target target = resolveActor(player);
        if (!target.ready()) {
            clearSelection();
            return false;
        }
        return pressUseOn(item, target,
                entry -> isOwnUseTargetEntry(entry, player, MenuAction.WIDGET_TARGET_ON_PLAYER));
    }

    // ── Verified press (plain clicks and item-on-X) ────────

    /**
     * The shared "verified dispatch" tail: rest the cursor on a point inside the resolved
     * clickbox, read the hover menu, and only then press.
     * <ul>
     *   <li><b>Our row already on top</b> — press at the point. The same hover rebuilds to
     *       the same ranking, so a movement frame inside the press (weather offset,
     *       cursor-tracker drift) cannot change what dispatches.</li>
     *   <li><b>A higher-priority entity shares the hover</b> (a player standing on the NPC /
     *       object, another NPC on the tile) — right-click and press OUR row out of the open
     *       option menu, which the client freezes ("The menu is not rebuilt when it is
     *       open", {@code MenuEntrySwapperPlugin}). Deprioritize flags were deliberately not
     *       used: they live on the current menu's entries, and every movement the sanctioned
     *       press path emits rebuilds the menu and drops them.</li>
     *   <li><b>Our row is gone</b> — bounded nudge/retry, then no click. A clickbox point can
     *       fall outside the hull (or the actor walked away); clicking blindly would hit
     *       whatever ranks top there.</li>
     * </ul>
     * One client-thread snapshot per try decides presence AND rank together — split reads
     * raced in the field (the read that found the entry was followed by a rebuild that hid
     * it).
     *
     * @param target      the resolved real clickbox ({@link Target#ready()})
     * @param describe    what is being clicked, for the log lines
     * @param isOurs      matcher for the entry the press must resolve to
     * @param confirmedAfterMenuClose recipe-specific proof the row press dispatched
     *                     ({@link #MENU_CLOSED_IS_ENOUGH} or {@link #SELECTION_CONSUMED})
     * @return the outcome — {@link Target#clicked()} when the press was issued
     */
    private static Target pressVerified(Target target, String describe,
                                        Predicate<MenuEntry> isOurs,
                                        BooleanSupplier confirmedAfterMenuClose) {
        if (!target.ready()) {
            return target;
        }
        // An open menu freezes the hover: any later move/right-click would press an
        // unknown row. Nothing may be clicked until it closes.
        if (menuOpen() && !closeMenu()) {
            log.error("[AScriptClick] an option menu is open and will not close — not clicking {}",
                    describe);
            return target.missed(Miss.MENU_STUCK);
        }
        // Resolve the press point once: getClickingPoint randomizes on every call, and the
        // hover (or open menu) has to be built for the exact point the press lands on.
        Point point = Rs2UiHelper.getClickingPoint(target.clickbox(), true);
        if (!arriveAt(point)) {
            log.warn("[AScriptClick] the cursor never settled on {} — not clicking", describe);
            return target.missed(Miss.CURSOR_LOST);
        }
        awaitFrames(2); // let the client process the arrival and build the hover for it

        Rank rank = awaitRank(point, isOurs);
        if (rank == Rank.GONE) {
            log.warn("[AScriptClick] the hover menu never showed {} — not clicking", describe);
            return target.missed(Miss.HOVER_GONE);
        }
        if (rank == Rank.TOP) {
            Microbot.getMouse().click(point);
            return target.clickIssued();
        }
        RowPick pick = pressViaMenuRow(point, isOurs, confirmedAfterMenuClose);
        if (pick == RowPick.DISPATCHED) {
            return target.clickIssued();
        }
        if (pick == RowPick.MENU_STUCK) {
            log.error("[AScriptClick] the option menu would not close — not clicking {}", describe);
            return target.missed(Miss.MENU_STUCK);
        }
        log.warn("[AScriptClick] the open menu never exposed {} — not pressing", describe);
        return target.missed(Miss.HOVER_GONE);
    }

    /**
     * Read the hover menu's ranking of our entry, retrying a GONE read with a nudge and a
     * growing frame/wait budget (a stale menu never fixes itself). {@link Rank#TOP} and
     * {@link Rank#BEATEN} both prove the entry exists; only an unbroken run of GONE reads
     * fails the recipe.
     */
    private static Rank awaitRank(Point point, Predicate<MenuEntry> isOurs) {
        Rank rank = Rank.GONE;
        for (int attempt = 0; attempt < ENTRY_ATTEMPTS && rank == Rank.GONE; attempt++) {
            if (attempt >= NUDGE_AFTER_ATTEMPT) {
                nudge(point);
            }
            awaitFrames(attempt >= NUDGE_AFTER_ATTEMPT ? 3 : 2);
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(
                    attempt >= NUDGE_AFTER_ATTEMPT ? 60 : 20,
                    attempt >= NUDGE_AFTER_ATTEMPT ? 150 : 80));
            rank = rankOfOwnEntry(isOurs);
        }
        return rank;
    }

    /**
     * Item-on-X selection path: the shared verified press with the cursor-selection proof,
     * plus the item recipe's failure contract — a selection this recipe created is cleared
     * with a press on the item's OWN slot (never at the last press point, which would
     * dispatch whatever ranks top there).
     *
     * @param item   the selected item — used to cancel the selection on failure paths
     * @param target the resolved real clickbox ({@link Target#ready()})
     * @param isOurs matcher for the entry the press must resolve to
     * @return true when the "use item on &lt;target&gt;" click was issued (effect
     *         unverified)
     */
    private static boolean pressUseOn(Rs2ItemModel item, Target target,
                                      Predicate<MenuEntry> isOurs) {
        Target pressed = pressVerified(target, "the use target's entry", isOurs, SELECTION_CONSUMED);
        if (pressed.clicked()) {
            return true;
        }
        if (pressed.miss() == Miss.MENU_STUCK) {
            log.error("[AScriptClick] the option menu would not close — leaving the cursor "
                    + "selection pending (close the menu manually)");
            return false;
        }
        cancelSelectionOnOwnSlot(item, "the use target's entry never dispatched");
        return false;
    }

    // ── Menu pick (shared by the item-on-X and non-default op recipes) ──

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
     * Move the cursor to {@code point} along the NaturalMouse path, including its weather
     * speed modulation. Unlike {@link #arriveAt}, this never falls back to a corrective
     * teleport when NaturalMouse is available: if the pointer does not settle, the caller
     * retries on a later tick. (With NaturalMouse unavailable, the direct virtual-mouse move
     * is the only path left.)
     * <p>
     * {@code VirtualMouse.move(Point)} only emits a single MOVED event, and
     * {@code NaturalMouse.moveTo} early-returns when the pointer is already there — so a
     * pre-click teleport silently turns the following click into an instantaneous press.
     * Every recipe that pre-hovers a slot (drops, item-on-X selection, Jewel Enchant's cast)
     * must use this instead.
     *
     * @return true when the pointer is resting on {@code point}
     */
    public static boolean moveNaturally(Point point) {
        if (point == null) {
            return false;
        }
        WeatherModulation.ensureFresh();
        if (Microbot.getNaturalMouse() != null) {
            Microbot.getNaturalMouse().moveTo(point.getX(), point.getY());
        } else {
            Microbot.getMouse().move(point);
        }
        // NaturalMouse.moveTo is synchronous on the script thread, so the pointer normally
        // rests on the point immediately; only wait when the tracked state lagged behind.
        if (PointerState.isAt(point.getX(), point.getY())) {
            return true;
        }
        return sleepUntil(() -> PointerState.isAt(point.getX(), point.getY()),
                Rs2Random.logNormalBounded(400, 900));
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

    /**
     * Wait roughly {@code frames} rendered client frames. The client processes input and
     * rebuilds the hover/option menus on its own frames, so at a low FPS cap (the runtime
     * box locks at 7 fps — one frame ≈ 143 ms) fixed millisecond waits can read state the
     * client has not produced yet. Frame duration comes from the client's own FPS read,
     * clamped so a stale or zero reading cannot stall the recipe. There is no ready-made
     * "sleep until next frame" helper in the API — {@code getFPS()} / {@code getGameCycle()}
     * are the primitives, and this wraps the former.
     */
    private static void awaitFrames(int frames) {
        int fps = Microbot.getClient().getFPS();
        long frameMs = fps > 0 ? Math.max(100L, Math.min(1_000L / fps, 500L)) : 200L;
        AScriptSleep.sleepInterruptibly((int) (frameMs * frames));
    }

    /**
     * Wait until the open menu's anchor stops moving between two frame-spaced reads — a
     * freshly opened menu settles over a frame or two, and row coordinates computed
     * mid-settle land on the wrong row.
     */
    private static void awaitStableMenuAnchor() {
        int prevX = Integer.MIN_VALUE;
        int prevY = Integer.MIN_VALUE;
        int prevW = Integer.MIN_VALUE;
        for (int i = 0; i < MENU_ATTEMPTS + 2; i++) {
            awaitFrames(2);
            int[] anchor = Microbot.getClientThread()
                    .runOnClientThreadOptional(() -> {
                        Client client = Microbot.getClient();
                        if (!client.isMenuOpen()) {
                            return null;
                        }
                        return new int[]{client.getMenuX(), client.getMenuY(), client.getMenuWidth()};
                    })
                    .orElse(null);
            if (anchor == null) {
                return; // menu closed mid-wait — the caller's read will fail it cleanly
            }
            if (anchor[0] == prevX && anchor[1] == prevY && anchor[2] == prevW) {
                return; // stable across a frame gap
            }
            prevX = anchor[0];
            prevY = anchor[1];
            prevW = anchor[2];
        }
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
     * Contended path — and the whole path of the non-default op recipe: right-click at
     * {@code point} to open the option menu for the hover, find the target's own entry in
     * the (frozen) open menu, and press its row. The open menu is not rebuilt while it is
     * open ({@code MenuEntrySwapperPlugin.onPostMenuSort}: "The menu is not rebuilt when
     * it is open"), so the row we read is the row that dispatches — a movement frame
     * inside the row press (cursor-tracker drift, the weather offset) can move the cursor
     * but cannot change what is listed or where; the offset only shifts a press centered
     * in a 15 px row.
     * <p>
     * Bounded: a menu that came up without our entry is closed, the hover is re-nudged,
     * and the right-click is repeated; a menu that will not close at all reports
     * {@link RowPick#MENU_STUCK} so the caller stops clicking (any further click would
     * dispatch an unknown row).
     *
     * @param confirmedAfterClose what else must hold right after the menu closes on our
     *                            row — the recipe-specific proof that the press really
     *                            dispatched (see {@link #SELECTION_CONSUMED} /
     *                            {@link #MENU_CLOSED_IS_ENOUGH})
     */
    private static RowPick pressViaMenuRow(Point point, Predicate<MenuEntry> isOurs,
                                           BooleanSupplier confirmedAfterClose) {
        for (int attempt = 0; attempt < MENU_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                nudge(point);
            }
            Microbot.getMouse().click(point, true);
            int own = awaitOwnRow(isOurs);
            if (own >= 0 && clickOwnRow(isOurs, confirmedAfterClose)) {
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
        // The menu anchors itself over a frame or two after opening; geometry read before
        // that lands on stale coordinates (the pilot's first attempt clicked the Cancel
        // row off a menu that had not settled yet at 7 fps).
        awaitStableMenuAnchor();
        return topmostIndex(isOurs);
    }

    /**
     * Press the center of the target's row in the open menu and confirm the pick really
     * dispatched. Index and geometry come from ONE client-thread read — a split read raced
     * in the field (index from one array state, coordinates from the next). The menu also
     * closes on Cancel, so "closed" alone is NOT a dispatch (the pilot's first attempt
     * counted a Cancel hit as success): the caller supplies the recipe's own proof on top
     * of the close — the item recipes wait for the cursor selection to be consumed, a
     * non-default op has no selection and stops at the close
     * ({@link #MENU_CLOSED_IS_ENOUGH}).
     *
     * @param confirmedAfterClose evaluated after the menu closed and a frame has passed;
     *                            true = treat the row press as dispatched
     */
    private static boolean clickOwnRow(Predicate<MenuEntry> isOurs, BooleanSupplier confirmedAfterClose) {
        Point row = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            if (!client.isMenuOpen()) {
                return null;
            }
            MenuEntry[] entries = client.getMenuEntries();
            int own = topmostIndex(entries, isOurs);
            if (own < 0) {
                return null;
            }
            int rowFromTop = entries.length - 1 - own;
            int x = client.getMenuX() + client.getMenuWidth() / 2;
            int y = client.getMenuY() + MENU_HEADER_HEIGHT + rowFromTop * MENU_ROW_HEIGHT
                    + MENU_ROW_HEIGHT / 2;
            return new Point(x, y);
        }).orElse(null);
        if (row == null) {
            return false;
        }
        Microbot.getMouse().click(row);
        if (!sleepUntil(() -> !menuOpen(), Rs2Random.logNormalBounded(400, 900))) {
            return false;
        }
        awaitFrames(2);
        return confirmedAfterClose.getAsBoolean();
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
        Point cancelRow = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            if (!client.isMenuOpen()) {
                return null;
            }
            MenuEntry[] entries = client.getMenuEntries();
            int idx = topmostIndex(entries, entry -> "Cancel".equals(entry.getOption()));
            if (idx < 0) {
                return null;
            }
            int rowFromTop = entries.length - 1 - idx;
            int x = client.getMenuX() + client.getMenuWidth() / 2;
            int y = client.getMenuY() + MENU_HEADER_HEIGHT + rowFromTop * MENU_ROW_HEIGHT
                    + MENU_ROW_HEIGHT / 2;
            return new Point(x, y);
        }).orElse(null);
        if (cancelRow != null) {
            Microbot.getMouse().click(cancelRow);
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
        if (!Rs2Inventory.isItemSelected()) {
            return; // already gone — clicking the slot now would dispatch its default op
        }
        if (menuOpen()) {
            // An open menu captures a click at the slot point; leave the selection for the
            // next recipe attempt instead of pressing an arbitrary menu row.
            log.warn("[AScriptClick] the selection stays pending while an option menu is open");
            return;
        }
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
     * Deliberately separate from the menu pick, which takes the matcher as an input: the
     * {@link #useItemOnNpc} / {@link #useItemOnPlayer} overloads are thin follow-ups that
     * only swap this matcher for {@link #isOwnUseTargetEntry}.
     */
    private static boolean isOwnObjectEntry(MenuEntry entry, TileObject object) {
        if (entry.getType() != MenuAction.WIDGET_TARGET_ON_GAME_OBJECT
                || entry.getIdentifier() != object.getId()) {
            return false;
        }
        int[] scene = sceneCoords(object);
        return scene != null && entry.getParam0() == scene[0] && entry.getParam1() == scene[1];
    }

    /**
     * The entry the press must resolve to for an ACTOR target (item-on-NPC /
     * item-on-player): the client's own "use &lt;item&gt; on &lt;actor&gt;" line, matched
     * on the action family — {@link MenuAction#WIDGET_TARGET_ON_NPC} /
     * {@link MenuAction#WIDGET_TARGET_ON_PLAYER}, the same constants this fork's own
     * injections use for an item on an actor ({@code Rs2NpcModel#getMenuAction},
     * {@code Rs2Player#invokeMenu}) and, per the F0.1 live menu log, the family the
     * client's own item-on-X entries actually use (the ITEM_USE_ON_* constants never
     * appear in this client's menu) — plus the target's identity through
     * {@link MenuEntry#getActor()}: reference comparison against the raw actor the caller
     * passed pins OUR target even when another entity shares the hover. The NPC index is
     * accepted as well (identifiers of NPC rows are indices): callers may hand us a model
     * wrapper ({@code Rs2NpcModel}) whose identity differs from the client's actor, and an
     * NPC index is unique among live NPCs. A player row that resolves no actor never
     * matches — there is no identifier to trust.
     * <p>
     * Only ever evaluated on the client thread, inside the menu reads above.
     */
    private static boolean isOwnUseTargetEntry(MenuEntry entry, Actor target, MenuAction type) {
        if (entry == null || target == null || entry.getType() != type) {
            return false;
        }
        if (entry.getActor() == target) {
            return true;
        }
        return target instanceof NPC && entry.getIdentifier() == ((NPC) target).getIndex();
    }

    // ── Plain-click identity matchers ──────────────────────

    /** Plain interaction families (the WIDGET_TARGET_ON_* actions belong to the item-on-X path). */
    private static final Set<MenuAction> OBJECT_OPTIONS = Set.of(
            MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION,
            MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
            MenuAction.GAME_OBJECT_FIFTH_OPTION);
    private static final Set<MenuAction> NPC_OPTIONS = Set.of(
            MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION, MenuAction.NPC_THIRD_OPTION,
            MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION);
    private static final Set<MenuAction> PLAYER_OPTIONS = Set.of(
            MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
            MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION,
            MenuAction.PLAYER_FIFTH_OPTION);
    private static final Set<MenuAction> GROUND_ITEM_OPTIONS = Set.of(
            MenuAction.GROUND_ITEM_FIRST_OPTION, MenuAction.GROUND_ITEM_SECOND_OPTION,
            MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.GROUND_ITEM_FOURTH_OPTION,
            MenuAction.GROUND_ITEM_FIFTH_OPTION);

    /**
     * Option-text match for the action pick. {@code null}/blank means "any row for this
     * entity" — the topmost one is what a left click would dispatch, so no hardcoded default
     * name is needed (cannon ops change with state, for instance). Color tags are stripped:
     * the Ground Items plugin recolors menu options, and the tag is not part of the op.
     */
    static boolean optionMatches(MenuEntry entry, String action) {
        if (action == null || action.isBlank()) {
            return true;
        }
        return entry.getOption() != null
                && Text.removeTags(entry.getOption()).trim().equalsIgnoreCase(action.trim());
    }

    /**
     * The object's own plain interaction row: action family, object id, and the scene
     * coordinates the client packs into param0/param1 ({@link #sceneCoords} — the mapping
     * {@code Rs2TileObjectModel.click} mirrors). The same id on another tile never matches.
     * Only ever evaluated on the client thread, inside the menu reads.
     */
    static boolean isOwnObjectAction(MenuEntry entry, TileObject object, String action) {
        if (entry == null || object == null || !OBJECT_OPTIONS.contains(entry.getType())) {
            return false;
        }
        if (entry.getIdentifier() != object.getId()) {
            return false;
        }
        int[] scene = sceneCoords(object);
        return scene != null && entry.getParam0() == scene[0] && entry.getParam1() == scene[1]
                && optionMatches(entry, action);
    }

    /**
     * The actor's own plain interaction row: the {@code NPC_*} family for NPCs (or
     * {@code PLAYER_*} for players) + option/identity. Identity is the client's actor
     * reference, with the NPC index as an always-available fallback — callers may pass a
     * model wrapper ({@code Rs2NpcModel}) whose identity differs from the client's NPC
     * instance, and an NPC index is unique among live NPCs.
     * Only ever evaluated on the client thread, inside the menu reads.
     */
    static boolean isOwnActorAction(MenuEntry entry, Actor actor, String action) {
        if (entry == null || actor == null) {
            return false;
        }
        Set<MenuAction> families = actor instanceof Player ? PLAYER_OPTIONS : NPC_OPTIONS;
        if (!families.contains(entry.getType())) {
            return false;
        }
        if (!optionMatches(entry, action)) {
            return false;
        }
        if (entry.getActor() == actor) {
            return true;
        }
        return actor instanceof NPC && entry.getIdentifier() == ((NPC) actor).getIndex();
    }

    /**
     * The ground item's own plain interaction row: {@code GROUND_ITEM_*} family, item id
     * (the identifier of a ground-item row — {@code GroundItemsPlugin} reads it the same
     * way) and the scene coords param0/param1 carry. Only ever evaluated on the client thread.
     */
    static boolean isOwnGroundItemAction(MenuEntry entry, TileItem item, int sceneX, int sceneY,
                                         String action) {
        if (entry == null || item == null || !GROUND_ITEM_OPTIONS.contains(entry.getType())) {
            return false;
        }
        return entry.getIdentifier() == item.getId()
                && entry.getParam0() == sceneX && entry.getParam1() == sceneY
                && optionMatches(entry, action);
    }

    // ── Describe helpers (log lines) ───────────────────────

    private static String describeObject(TileObject object, String action) {
        return "'" + (action == null || action.isBlank() ? "default" : action)
                + "' on object " + object.getId();
    }

    private static String describeActor(Actor actor, String action) {
        String name = actor instanceof NPC ? ((NPC) actor).getName() : null;
        return "'" + (action == null || action.isBlank() ? "default" : action) + "' on "
                + (name == null ? "NPC/actor" : "'" + name + "'");
    }

    private static String describeGroundItem(TileItem item, String action) {
        return "'" + (action == null || action.isBlank() ? "default" : action)
                + "' on ground item " + item.getId();
    }

    /**
     * Scene coordinates the client packs into an object menu entry's param0/param1 —
     * {@code Rs2TileObjectModel.click}'s mapping: a multi-tile {@link GameObject} is stored
     * at its south-west-centre tile, every other tile object at its own local location.
     * <p>
     * The cache hands modules an {@link Rs2TileObjectModel}, which wraps the client object
     * and is <b>not itself a {@link GameObject}</b> — without unwrapping, a multi-tile object
     * would silently skip the size adjustment and never match its own menu row.
     */
    private static int[] sceneCoords(TileObject object) {
        TileObject raw = object instanceof Rs2TileObjectModel
                ? ((Rs2TileObjectModel) object).getTileObject()
                : object;
        if (raw == null) {
            return null;
        }
        if (raw instanceof GameObject) {
            GameObject gameObject = (GameObject) raw;
            LocalPoint local = gameObject.getLocalLocation();
            if (local == null) {
                return null;
            }
            int sceneX = local.getSceneX() - (gameObject.sizeX() > 1 ? gameObject.sizeX() / 2 : 0);
            int sceneY = local.getSceneY() - (gameObject.sizeY() > 1 ? gameObject.sizeY() / 2 : 0);
            return new int[]{sceneX, sceneY};
        }
        LocalPoint local = raw.getLocalLocation();
        if (local == null) {
            return null;
        }
        return new int[]{local.getSceneX(), local.getSceneY()};
    }

    /**
     * Item-on-item recipe — the same select-verify-click flow with the target resolved
     * as another inventory slot (tinderbox on logs, gem tips on bolts) instead of an entity
     * clickbox. Selection cleanup on the failure path as in
     * {@link #useItemOnObject(Rs2ItemModel, TileObject)}.
     * <p>
     * A selected cursor item makes the target's left click the client's own
     * "use &lt;item&gt; on &lt;target&gt;" row, whatever the target's default op is (bolts
     * would otherwise be worn). The selection is re-checked immediately before the target
     * click and the target slot is re-read, so a selection lost in the mouse travel cannot
     * turn the click into the target's default action; if it still does, an accidentally
     * worn target is taken back off.
     *
     * @return true when the "use item on item" click was issued (effect unverified)
     */
    public static boolean useItemOnItem(Rs2ItemModel item, Rs2ItemModel target) {
        if (!select(item)) {
            return false;
        }
        // select() closes an open menu, but a menu can appear between the two presses (a
        // human, another plugin): an open menu freezes the hover and would swallow the
        // target press.
        if (menuOpen() && !closeMenu()) {
            cancelSelectionOnOwnSlot(item, "an option menu is open and will not close");
            return false;
        }
        if (target != null && target.isNoted()) {
            cancelSelectionOnOwnSlot(item,
                    "the use target " + target.getId() + " is a banknote — bank it to un-note first");
            return false;
        }
        // The selection can be dropped between the select and the target click. Clicking the
        // target then dispatches ITS default op (bolts: Wield) instead of the item-on-item use.
        if (!Rs2Inventory.isItemSelected() || Rs2Inventory.getSelectedItemId() != item.getId()) {
            cancelSelectionOnOwnSlot(item, "the use selection was lost before the target click (slot "
                    + (target == null ? -1 : target.getSlot()) + ")");
            return false;
        }
        Rectangle bounds = target == null ? null : Rs2Inventory.itemBounds(target);
        if (bounds == null) {
            cancelSelectionOnOwnSlot(item, "no inventory bounds for the use target (slot "
                    + (target == null ? -1 : target.getSlot()) + ")");
            return false;
        }
        // Re-read the target slot at the click site: a stale slot can hold another item now.
        Rs2ItemModel liveTarget = Rs2Inventory.getItemInSlot(target.getSlot());
        if (liveTarget == null || liveTarget.getId() != target.getId()) {
            cancelSelectionOnOwnSlot(item, "the use target changed under its slot (slot "
                    + target.getSlot() + ")");
            return false;
        }
        Microbot.getMouse().click(bounds);
        // The click must CONSUME the selection: a stale target leaves the source item
        // selected, and the next inventory action then applies it somewhere else.
        if (!sleepUntil(() -> !Rs2Inventory.isItemSelected(),
                Rs2Random.logNormalBounded(600, 1200))) {
            // The cursor is over the TARGET here: clicking at the current position would
            // apply the source to the target again (or press the target's default op).
            // Cancel on the source item's own slot, never at the last press point.
            cancelSelectionOnOwnSlot(item, "the use-on-item click did not consume the selection (slot "
                    + target.getSlot() + ")");
            return false;
        }
        if (recoverIfWorn(target, "the use-on-item click wore the target instead of using it")) {
            return false;
        }
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
        if (menuOpen()) {
            // An open menu freezes the hover; a click on the selected widget can land on a
            // menu row instead. Let the caller close/retry rather than press blindly.
            log.warn("[AScriptClick] cannot cancel the cursor selection while an option menu is open");
            return false;
        }
        // Click the selected WIDGET (the inventory item's own slot or the spellbook icon).
        // Clicking at the current cursor position — the old eatReady idiom — can apply the
        // selection to whatever happens to be under the pointer (or cast the spell there).
        Rectangle bounds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget selected = Microbot.getClient().getSelectedWidget();
            return selected == null ? null : selected.getBounds();
        }).orElse(null);
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            // No bounds on the selected widget (a parent/layer): fall back to the selected
            // inventory slot, which cancels an item selection.
            int index = Rs2Inventory.getSelectedItemIndex();
            Rs2ItemModel selectedItem = index >= 0 ? Rs2Inventory.getItemInSlot(index) : null;
            bounds = selectedItem == null ? null : Rs2Inventory.itemBounds(selectedItem);
        }
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            Microbot.getMouse().click(bounds);
        } else {
            // Last resort only: nothing identifies where the selection lives.
            Microbot.getMouse().click();
        }
        return sleepUntil(() -> !Rs2Inventory.isItemSelected(),
                Rs2Random.logNormalBounded(400, 900));
    }

    /**
     * Bring the inventory panel forward before any slot read or click — a closed inventory
     * tab leaves {@code Rs2Inventory.itemBounds} empty and the recipe then has nothing to
     * click (field report: "the script breaks with the inventory tab closed"). The switch
     * is a physical click on the tab and a no-op when the inventory already shows; the
     * frame wait covers the panel render at a low FPS cap. Scripts that depend on OTHER
     * panels must switch them the same way ({@code Rs2Tab.switchTo(...)}) before reading
     * their widgets — same class of failure.
     */
    private static void ensureInventoryVisible() {
        if (!Rs2Tab.isCurrentTab(InterfaceTab.INVENTORY)) {
            Rs2Tab.switchTo(InterfaceTab.INVENTORY);
            awaitFrames(2);
        }
    }

    /**
     * Step one of the item-on-X recipes: arm the item on its real <b>{@code Use}</b> row with a
     * physical click on its inventory slot, and verify the game selected <b>that item</b>.
     * <p>
     * A raw slot click dispatches the item's default inventory op. That is {@code Use} for
     * logs, tinderbox, fish and gems, but {@code Wield} for a bolt stack and the fletcher's
     * knife — the live gem-tip bug: the bolts went to the equipment slot instead of the
     * cursor, the recipe then timed out, and the module eventually stopped with its material
     * on the player's body. The click is therefore preceded by the same MES-style swap the
     * drop recipe uses: {@link #onPostMenuSort()} moves the client's own {@code Use} entry to
     * the top, the recipe waits until that is verified, and only then presses. A swap that
     * cannot be resolved means NO click — never a blind default-op press.
     * <p>
     * "Something is selected" is not enough either. The click targets a slot resolved a moment
     * earlier, and the container can change under it — the live case is the last raw fish of
     * a stack finishing into its cooked/burnt product in the just-freed slot, which made the
     * recipe apply <i>burnt fish</i> to the cooking fire. The selected id is therefore checked
     * against the item the caller asked for; a mismatch cancels the selection on its own slot
     * (a press elsewhere would apply the wrong item to whatever is under it) and refuses the
     * dispatch. If the click wore the item instead (a selection click that raced the swap),
     * the wording is put back on ({@link #unequip(int)}) and the caller retries next tick.
     */
    private static boolean select(Rs2ItemModel item) {
        if (item != null && item.isNoted()) {
            log.warn("[AScriptClick] item {} is a banknote — bank it to un-note before using it", item.getId());
            return false;
        }
        ensureInventoryVisible();
        if (menuOpen() && !closeMenu()) {
            log.error("[AScriptClick] an option menu is open and will not close — not selecting an item");
            return false;
        }
        if (Rs2Inventory.isItemSelected() && !clearSelection()) {
            log.warn("[AScriptClick] could not clear a stale selection — not clicking an item");
            return false;
        }
        if (menuOpen() && !closeMenu()) {
            log.error("[AScriptClick] an option menu is open and will not close — not selecting an item");
            return false;
        }
        Rectangle bounds = item == null ? null : Rs2Inventory.itemBounds(item);
        if (bounds == null) {
            log.warn("[AScriptClick] no inventory bounds for the item to use (slot {})",
                    item == null ? -1 : item.getSlot());
            return false;
        }
        Point point = Rs2UiHelper.getClickingPoint(bounds, true);
        synchronized (MENU_SWAP_LOCK) {
            armMenuSwap(item.getSlot(), item.getId(), USE_OPTION);
            try {
                // NaturalMouse owns the pre-hover travel; a direct VirtualMouse.move would
                // teleport and make the following press instantaneous.
                if (!moveNaturally(point)) {
                    log.warn("[AScriptClick] the cursor did not settle on the item to select (slot {})",
                            item.getSlot());
                    return false;
                }
                if (!sleepUntil(() -> isMenuSwapOnTop(item.getSlot(), item.getId(), USE_OPTION),
                        Rs2Random.logNormalBounded(400, 800))) {
                    log.warn("[AScriptClick] the Use row never reached the top of the item menu "
                            + "(slot {}) — not clicking", item.getSlot());
                    return false;
                }
                Microbot.getMouse().click(point);
                // Keep the swap armed while the press is processed: a menu rebuild inside the
                // click must re-apply Use, exactly as the drop recipe does.
                if (!sleepUntil(Rs2Inventory::isItemSelected, Rs2Random.logNormalBounded(1500, 2500))) {
                    if (recoverIfWorn(item, "the item click wore it instead of selecting it")) {
                        return false;
                    }
                    log.warn("[AScriptClick] the item click did not select anything");
                    return false;
                }
            } finally {
                clearMenuSwap();
            }
        }
        int selectedId = Rs2Inventory.getSelectedItemId();
        if (selectedId != item.getId()) {
            Rs2ItemModel selected = Rs2Inventory.getItemInSlot(Rs2Inventory.getSelectedItemIndex());
            cancelSelectionOnOwnSlot(selected, "the slot click selected item " + selectedId
                    + " instead of " + item.getId() + " (slot " + item.getSlot() + ")");
            return false;
        }
        return true;
    }

    /**
     * Take a worn item off through the equipment panel and return it to the inventory, using a
     * client-mouse click on its equipment slot (the worn slot's default op is {@code Remove}).
     * <p>
     * This is the recovery half of the wearable-material bug: bolts (ammo) and the fletcher's
     * knife have {@code Wield} as their inventory default, so an accidental selection click can
     * equip them. The removal is verified against the worn container and bounded; a slot that
     * will not come off is reported so the module's failure budget can stop the run loudly
     * instead of retrying forever.
     * <p>
     * No menu injection is used ({@code Rs2Equipment.unEquip} injects a fabricated entry, which
     * dies silently on some machines).
     *
     * @param itemId the worn item to remove
     * @return true when the item is no longer worn (including when it was not worn on entry)
     */
    public static boolean unequip(int itemId) {
        Rs2ItemModel worn = Rs2Equipment.get(itemId);
        if (worn == null) {
            return true;
        }
        int childId = wornSlotChild(worn.getSlot());
        if (childId < 0) {
            log.warn("[AScriptClick] no equipment widget for worn slot {} (item {})",
                    worn.getSlot(), itemId);
            return false;
        }
        if (menuOpen() && !closeMenu()) {
            log.error("[AScriptClick] an option menu is open and will not close — not unequipping {}",
                    itemId);
            return false;
        }
        if (!Rs2Tab.switchTo(InterfaceTab.EQUIPMENT)) {
            log.warn("[AScriptClick] could not open the equipment tab to unequip {}", itemId);
            return false;
        }
        awaitFrames(2);
        for (int attempt = 0; attempt < UNEQUIP_ATTEMPTS; attempt++) {
            if (Rs2Widget.clickWidget(InterfaceID.WORNITEMS, childId)
                    && sleepUntil(() -> !Rs2Equipment.isWearing(itemId),
                    Rs2Random.logNormalBounded(600, 1200))) {
                return true;
            }
        }
        log.warn("[AScriptClick] could not unequip item {} after {} attempts",
                itemId, UNEQUIP_ATTEMPTS);
        return false;
    }

    /**
     * Equipment-tab child that holds the worn item for an {@code EquipmentInventorySlot} index —
     * the same mapping {@code Rs2Equipment.SLOT_PARAMS} uses ({@code InterfaceID.WORNITEMS}).
     * Slots 6, 8 and 11 are the unused gaps in the equipment container.
     */
    private static int wornSlotChild(int slot) {
        switch (slot) {
            case 0: return InterfaceID.Wornitems.SLOT0;  // HEAD
            case 1: return InterfaceID.Wornitems.SLOT1;  // CAPE
            case 2: return InterfaceID.Wornitems.SLOT2;  // AMULET
            case 3: return InterfaceID.Wornitems.SLOT3;  // WEAPON
            case 4: return InterfaceID.Wornitems.SLOT4;  // BODY
            case 5: return InterfaceID.Wornitems.SLOT5;  // SHIELD
            case 7: return InterfaceID.Wornitems.SLOT7;  // LEGS
            case 9: return InterfaceID.Wornitems.SLOT9;  // GLOVES
            case 10: return InterfaceID.Wornitems.SLOT10; // BOOTS
            case 12: return InterfaceID.Wornitems.SLOT12; // RING
            case 13: return InterfaceID.Wornitems.SLOT13; // AMMO
            default: return -1;
        }
    }

    /**
     * Detect the accidental-equip failure shape and undo it: the item is gone from the
     * inventory and is now in the worn container. An item that is still in the inventory is
     * not touched (the player may legitimately wear another copy of the same id), and a state
     * where both are false is an ordinary miss the caller handles.
     *
     * @return true when the item WAS worn by the click (whether or not the unequip landed), so
     *         the caller can skip its "nothing was selected" message and retry next tick
     */
    private static boolean recoverIfWorn(Rs2ItemModel item, String why) {
        if (item == null || Rs2Inventory.hasItem(item.getId())
                || !Rs2Equipment.isWearing(item.getId())) {
            return false;
        }
        log.warn("[AScriptClick] {} — unequipping item {} and retrying", why, item.getId());
        if (!unequip(item.getId())) {
            log.warn("[AScriptClick] item {} is still worn; the next attempt will retry the unequip",
                    item.getId());
        }
        return true;
    }

    // ── Shared clickbox filtering ──────────────────────────

    private static Target notRendered() {
        return new Target(null, false, Miss.NOT_RENDERED);
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
