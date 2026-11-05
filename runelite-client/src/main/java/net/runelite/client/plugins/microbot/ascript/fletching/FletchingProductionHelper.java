package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Window in which the prompt's quantity options are collected before the largest is picked.
     * The button {@code actions[]} arrive asynchronously after the interface opens, so one
     * snapshot would pick whatever is ready at that instant and the quantity would change from
     * batch to batch.
     */
    private static final long QUANTITY_SETTLE_MS = 800;
    private static final int QUANTITY_POLL_MS = 100;

    /** Last quantity clicked, across prompts: a change is worth an INFO line, the steady state is not. */
    private static String lastPickedQuantity;

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
     * Clicks the <b>largest</b> quantity the open prompt offers, waiting for its options to settle
     * first.
     * <p>
     * Two signals are collected over {@link #QUANTITY_SETTLE_MS} and unioned by option:
     * <ul>
     *   <li>the button's own {@code actions[]} — the real button (a product row carries an action
     *       such as "Make sets:", so its subtree is skipped entirely);</li>
     *   <li>the button's label text ("10" on a TEXT child), which is present from the first render
     *       and survives the window in which the action list is still empty.</li>
     * </ul>
     * That union is what makes the choice deterministic: depending on when the old code sampled,
     * "10" was still actionless while "1" and "5" were already live, so it clicked whichever was
     * ready and the quantity flipped batch after batch.
     *
     * @return the clicked option ({@code "10"}, {@code "All"}, ...), or {@code null} when the
     *         prompt is gone or exposes no quantity at all (the previous default stays selected)
     */
    public static String clickLargestQuantity() {
        if (!Rs2Widget.isProductionWidgetOpen()) {
            return null;
        }
        Map<String, Integer> options = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + QUANTITY_SETTLE_MS;
        while (true) {
            Microbot.getClientThread().runOnClientThreadOptional(() -> {
                Widget mainWidget = Microbot.getClient().getWidget(InterfaceID.SKILLMULTI, 0);
                if (mainWidget != null && !mainWidget.isHidden()) {
                    collectQuantityButtons(mainWidget, options);
                }
                return null;
            });
            if (!Rs2Widget.isProductionWidgetOpen()) {
                return null;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            AScriptSleep.sleepInterruptibly(QUANTITY_POLL_MS);
        }

        String largest = largestQuantity(options.keySet());
        if (largest == null) {
            log.debug("[FletchingProduction] Prompt exposes no quantity; previous default stays selected. Buttons: {}",
                    describeAvailableButtons());
            return null;
        }
        if (!clickWidget(options.get(largest), "quantity '" + largest + "'")) {
            return null;
        }
        if (largest.equals(lastPickedQuantity)) {
            log.debug("[FletchingProduction] Quantity {} selected", largest);
        } else {
            lastPickedQuantity = largest;
            log.info("[FletchingProduction] Quantity {} selected — largest the prompt offers", largest);
        }
        return largest;
    }

    /**
     * Pre-order walk that maps every quantity the prompt exposes to the widget that carries it.
     * A widget whose actions exist but are not a quantity is a product row: it is recorded as
     * nothing and not descended into, so its "10 x …" label can never be mistaken for a button.
     */
    private static void collectQuantityButtons(Widget widget, Map<String, Integer> options) {
        if (widget == null || widget.isHidden()) {
            return;
        }
        String byAction = quantityToken(widget.getActions());
        if (byAction != null) {
            options.put(byAction, widget.getId());
        } else {
            String byLabel = quantityToken(widget.getText());
            if (byLabel != null) {
                options.putIfAbsent(byLabel, widget.getId());
            }
            if (hasAction(widget)) {
                return;
            }
        }
        Widget[][] groups = {
                widget.getChildren(), widget.getNestedChildren(),
                widget.getDynamicChildren(), widget.getStaticChildren()
        };
        for (Widget[] group : groups) {
            if (group == null) continue;
            for (Widget child : group) {
                collectQuantityButtons(child, options);
            }
        }
    }

    private static boolean hasAction(Widget widget) {
        String[] actions = widget.getActions();
        if (actions == null || actions.length == 0) {
            return false;
        }
        for (String action : actions) {
            if (action != null && !stripTags(action).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** {@code "10"} / {@code "All"} as a canonical option, or {@code null} for anything else. */
    private static String quantityToken(String raw) {
        if (raw == null) {
            return null;
        }
        String clean = stripTags(raw);
        if (clean.isEmpty() || clean.length() > 4) {
            return null;
        }
        if ("All".equalsIgnoreCase(clean)) {
            return "All";
        }
        for (int i = 0; i < clean.length(); i++) {
            if (!Character.isDigit(clean.charAt(i))) {
                return null;
            }
        }
        return clean;
    }

    private static String quantityToken(String[] actions) {
        if (actions == null) {
            return null;
        }
        for (String action : actions) {
            String token = quantityToken(action);
            if (token != null) {
                return token;
            }
        }
        return null;
    }

    /** "All" outranks every number; otherwise the numerically larger option wins. */
    private static String largestQuantity(Set<String> options) {
        String best = null;
        for (String option : options) {
            if (best == null || quantityRank(option) > quantityRank(best)) {
                best = option;
            }
        }
        return best;
    }

    private static int quantityRank(String option) {
        if ("All".equalsIgnoreCase(option)) {
            return Integer.MAX_VALUE;
        }
        int value = 0;
        for (int i = 0; i < option.length(); i++) {
            value = value * 10 + (option.charAt(i) - '0');
        }
        return value;
    }

    /** One-shot read of the prompt's clickable widgets for the "no quantity" debug line. */
    private static String describeAvailableButtons() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget mainWidget = Microbot.getClient().getWidget(InterfaceID.SKILLMULTI, 0);
            return mainWidget == null ? "no prompt" : describeButtons(mainWidget);
        }).orElse("unavailable");
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
