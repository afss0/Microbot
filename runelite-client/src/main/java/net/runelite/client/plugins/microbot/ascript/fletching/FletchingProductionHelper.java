package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.Rectangle;
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
