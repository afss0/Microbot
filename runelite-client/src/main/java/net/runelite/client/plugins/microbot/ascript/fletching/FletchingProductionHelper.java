package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Fletching-specific helper for interacting with the production interface (SKILLMULTI/270).
 * <p>
 * Unlike {@link Rs2Widget#handleProcessingInterface}, this:
 * <ul>
 *   <li>Searches by ACTIONS or by the product id/name only (avoids matching "string" in
 *       "How many would you like to string?")</li>
 *   <li>Skips keyboard shortcut (can close interface without triggering production)</li>
 *   <li>Finds real bounds from MODEL/child widgets when a parent LAYER has zero bounds</li>
 *   <li>Uses {@link Microbot#getMouse()} for all clicks (virtual mouse)</li>
 * </ul>
 * <p>
 * {@link #clickProductionProduct(int)} is the ID-first entry point: the product's current display
 * name comes from {@link ItemComposition#getMembersName()} and the row is matched by item id
 * first, then by exact name. No hand-written product-name string is involved.
 */
@Slf4j
public final class FletchingProductionHelper {

    private FletchingProductionHelper() {
    }

    /**
     * Clicks the widget with the given action in the production interface.
     * All widget access happens on the client thread; only the resolved bounds cross back to
     * the caller for the virtual-mouse click. Returns true if the click was dispatched.
     */
    public static boolean clickProductionAction(String actionText) {
        if (!Rs2Widget.isProductionWidgetOpen()) {
            log.error("[FletchingProduction] Production interface not open");
            return false;
        }

        String lower = actionText.toLowerCase();
        Integer targetId = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget mainWidget = Microbot.getClient().getWidget(InterfaceID.SKILLMULTI, 0);
            if (mainWidget == null) return null;
            Widget target = findWidget(mainWidget, w -> actionMatches(w, lower));
            return target == null ? null : target.getId();
        }).orElse(null);

        if (targetId == null) {
            log.warn("[FletchingProduction] No widget with action '{}' found", actionText);
            return false;
        }
        return clickWidget(targetId, "action '" + actionText + "'");
    }

    /**
     * Clicks the production row for {@code productId}. The row is resolved by the widget's item id
     * first (the most reliable identity), then by the exact {@link ItemComposition#getMembersName()}
     * label. Returns true if the click was dispatched.
     */
    public static boolean clickProductionProduct(int productId) {
        if (!Rs2Widget.isProductionWidgetOpen()) {
            log.error("[FletchingProduction] Production interface not open");
            return false;
        }
        if (productId <= 0) {
            log.warn("[FletchingProduction] Invalid product id {}", productId);
            return false;
        }

        ItemComposition composition = Microbot.getRs2ItemManager().getItemComposition(productId);
        String resolvedName = composition == null ? null : composition.getMembersName();
        if (resolvedName != null && resolvedName.isEmpty()) {
            resolvedName = null;
        }
        final String productName = resolvedName;

        Integer targetId = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget mainWidget = Microbot.getClient().getWidget(InterfaceID.SKILLMULTI, 0);
            if (mainWidget == null) return null;

            Widget byItem = findWidget(mainWidget, w -> !w.isHidden() && w.getItemId() == productId);
            if (byItem != null) {
                return byItem.getId();
            }
            if (productName == null) {
                return null;
            }
            Widget byName = findWidget(mainWidget, w -> !w.isHidden() && textMatches(w, productName));
            return byName == null ? null : byName.getId();
        }).orElse(null);

        if (targetId == null) {
            log.warn("[FletchingProduction] No product row for '{}' (id {})", productName, productId);
            return false;
        }
        return clickWidget(targetId, "product '" + productName + "' (id " + productId + ")");
    }

    /**
     * Clicks the first quantity button of {@code preferences} that the open prompt actually
     * offers. The prompt decides which quantities exist (the bolt prompt has 1 / 5 / 10 only —
     * there is no "All" button), so a caller must select a real option instead of relying on
     * the previous default, which is whatever the last player left selected.
     * <p>
     * Only interactive widgets count: a widget has to expose a click action, so the
     * non-interactive "All" label some prompts draw is never mistaken for a button (clicking it
     * would leave the old quantity selected while the caller believed the batch size was known).
     * A missing preference is the normal case, so it is logged at debug, never at info.
     *
     * @return the option that was clicked ({@code "All"}, {@code "10"}, ...), or {@code null}
     *         when the prompt offers none of the preferences and the previous default stays in
     *         effect
     */
    public static String clickQuantityOption(String... preferences) {
        if (preferences == null || preferences.length == 0 || !Rs2Widget.isProductionWidgetOpen()) {
            return null;
        }
        AtomicInteger targetId = new AtomicInteger(-1);
        String clicked = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget mainWidget = Microbot.getClient().getWidget(InterfaceID.SKILLMULTI, 0);
            if (mainWidget == null || mainWidget.isHidden()) {
                return null;
            }
            for (String preference : preferences) {
                Widget target = findWidget(mainWidget, w -> isQuantityButton(w, preference));
                if (target != null) {
                    targetId.set(target.getId());
                    return preference;
                }
            }
            log.debug("[FletchingProduction] Prompt offers none of {}; previous quantity stays selected. Buttons: {}",
                    Arrays.toString(preferences), describeButtons(mainWidget));
            return null;
        }).orElse(null);

        if (clicked == null || targetId.get() < 0) {
            return null;
        }
        return clickWidget(targetId.get(), "quantity '" + clicked + "'") ? clicked : null;
    }

    /**
     * Resolve and click a widget id found on the client thread. Bounds are resolved again here
     * (still on the client thread) and only the rectangle is used for the virtual-mouse click.
     */
    private static boolean clickWidget(int widgetId, String description) {
        int groupId = widgetId >>> 16;
        int childId = widgetId & 0xFFFF;
        log.debug("[FletchingProduction] Target {}: {}:{}", description, groupId, childId);

        Rectangle bounds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget root = Microbot.getClient().getWidget(InterfaceID.SKILLMULTI, 0);
            Widget target = Microbot.getClient().getWidget(groupId, childId);
            return resolveBounds(target, root);
        }).orElse(null);
        if (bounds == null) {
            log.warn("[FletchingProduction] Widget bounds invalid for {}", description);
            return false;
        }
        Microbot.getMouse().click(bounds);
        return true;
    }

    // ── internal ─────────────────────────────────────────────────

    private static Widget findWidget(Widget root, Predicate<Widget> predicate) {
        if (root == null) {
            return null;
        }
        if (predicate.test(root)) {
            return root;
        }
        Widget[][] groups = {
                root.getChildren(), root.getNestedChildren(),
                root.getDynamicChildren(), root.getStaticChildren()
        };
        for (Widget[] group : groups) {
            if (group == null) continue;
            for (Widget child : group) {
                Widget found = findWidget(child, predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean actionMatches(Widget widget, String lowerAction) {
        String[] actions = widget.getActions();
        if (actions == null) return false;
        for (String action : actions) {
            if (action != null && stripTags(action).toLowerCase().contains(lowerAction)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code widget} is an interactive quantity button for {@code quantity}: it must
     * carry a click action (text labels and layout layers carry none) and either that action or
     * its own text must be exactly the quantity.
     */
    private static boolean isQuantityButton(Widget widget, String quantity) {
        if (widget == null || widget.isHidden()) {
            return false;
        }
        String[] actions = widget.getActions();
        if (actions == null || actions.length == 0) {
            return false;
        }
        for (String action : actions) {
            if (action != null && stripTags(action).equalsIgnoreCase(quantity)) {
                return true;
            }
        }
        return stripTags(widget.getText()).equalsIgnoreCase(quantity);
    }

    /** Short, log-friendly view of the prompt's clickable widgets (text + actions). */
    private static String describeButtons(Widget root) {
        List<String> found = new ArrayList<>();
        collectButtons(root, found);
        return found.isEmpty() ? "none" : String.join(", ", found);
    }

    private static void collectButtons(Widget widget, List<String> found) {
        if (widget == null || found.size() >= 12) {
            return;
        }
        String[] actions = widget.getActions();
        String text = stripTags(widget.getText());
        if (!widget.isHidden() && actions != null && actions.length > 0 && text.length() <= 12) {
            found.add((widget.getId() & 0xFFFF) + ":text='" + text + "',actions=" + Arrays.toString(actions));
        }
        Widget[][] groups = {
                widget.getChildren(), widget.getNestedChildren(),
                widget.getDynamicChildren(), widget.getStaticChildren()
        };
        for (Widget[] group : groups) {
            if (group == null) continue;
            for (Widget child : group) {
                collectButtons(child, found);
            }
        }
    }

    private static boolean textMatches(Widget widget, String expected) {
        return expected.equalsIgnoreCase(stripTags(widget.getText()))
                || expected.equalsIgnoreCase(stripTags(widget.getName()));
    }

    private static String stripTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
    }

    private static Rectangle resolveBounds(Widget target, Widget root) {
        if (target == null) {
            return null;
        }
        Rectangle bounds = usable(target.getBounds());
        if (bounds != null) return bounds;

        bounds = firstUsable(target.getDynamicChildren());
        if (bounds != null) return bounds;
        bounds = firstUsable(target.getStaticChildren());
        if (bounds != null) return bounds;
        bounds = firstUsable(target.getChildren());
        if (bounds != null) return bounds;
        bounds = firstUsable(target.getNestedChildren());
        if (bounds != null) return bounds;

        Widget parent = target.getParent();
        if (parent == null || parent == root) return null;
        bounds = usable(parent.getBounds());
        if (bounds != null) return bounds;
        bounds = firstUsable(parent.getDynamicChildren());
        if (bounds != null) return bounds;
        bounds = firstUsable(parent.getStaticChildren());
        if (bounds != null) return bounds;
        bounds = firstUsable(parent.getChildren());
        if (bounds != null) return bounds;
        return firstUsable(parent.getNestedChildren());
    }

    private static Rectangle firstUsable(Widget[] widgets) {
        if (widgets == null) return null;
        for (Widget widget : widgets) {
            if (widget == null) continue;
            Rectangle bounds = usable(widget.getBounds());
            if (bounds != null) return bounds;
            bounds = firstUsable(widget.getDynamicChildren());
            if (bounds != null) return bounds;
            bounds = firstUsable(widget.getStaticChildren());
            if (bounds != null) return bounds;
        }
        return null;
    }

    private static Rectangle usable(Rectangle bounds) {
        return bounds != null && bounds.width > 0 && bounds.height > 0 ? bounds : null;
    }
}
