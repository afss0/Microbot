package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

/**
 * Fletching-specific helper for interacting with the production interface (SKILLMULTI/270).
 * <p>
 * Unlike {@link Rs2Widget#handleProcessingInterface}, this:
 * <ul>
 *   <li>Searches by ACTIONS array only (avoids matching "string" in "How many would you like to string?")</li>
 *   <li>Skips keyboard shortcut (can close interface without triggering production)</li>
 *   <li>Finds real bounds from MODEL children when parent LAYER has zero bounds</li>
 *   <li>Uses {@link Microbot#getMouse()} for all clicks (virtual mouse)</li>
 * </ul>
 */
@Slf4j
public class FletchingProductionHelper {

    /**
     * Clicks the widget with the given action in the production interface.
     * All widget access happens on the client thread; only the resolved bounds
     * cross back to the caller for the virtual-mouse click.
     * Returns true if the click was dispatched.
     */
    public static boolean clickProductionAction(String actionText) {
        if (!Rs2Widget.isProductionWidgetOpen()) {
            log.error("Production interface not open");
            return false;
        }

        // find widget with actionText in actions[] array only (client thread)
        Integer targetId = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget mainWidget = Microbot.getClient().getWidget(270, 0);
            if (mainWidget == null) return null;
            Widget target = findWidgetByAction(actionText, mainWidget);
            return target == null ? null : target.getId();
        }).orElse(null);

        if (targetId == null) {
            log.warn("No widget with action '{}' found in production interface", actionText);
            return false;
        }

        int groupId = targetId >>> 16;
        int childId = targetId & 0xFFFF;
        log.debug("Production target: {}:{}", groupId, childId);

        // click via virtual mouse — get real bounds from children if parent has zero bounds
        java.awt.Rectangle bounds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget target = Microbot.getClient().getWidget(groupId, childId);
            if (target == null) return null;

            // try widget's own bounds first
            java.awt.Rectangle b = target.getBounds();
            if (b != null && b.width > 0 && b.height > 0) return b;
            // fall back to children (dynamic then static)
            Widget[] dyn = target.getDynamicChildren();
            if (dyn != null) {
                for (Widget child : dyn) {
                    if (child == null) continue;
                    b = child.getBounds();
                    if (b != null && b.width > 0 && b.height > 0) return b;
                }
            }
            Widget[] stat = target.getStaticChildren();
            if (stat != null) {
                for (Widget child : stat) {
                    if (child == null) continue;
                    b = child.getBounds();
                    if (b != null && b.width > 0 && b.height > 0) return b;
                }
            }
            return null;
        }).orElse(null);

        if (bounds != null) {
            Microbot.getMouse().click(bounds);
            return true;
        }

        log.warn("Widget bounds invalid, cannot click production action '{}'", actionText);
        return false;
    }

    // ── internal ─────────────────────────────────────────────────

    private static Widget findWidgetByAction(String actionText, Widget root) {
        if (root == null) return null;
        String lower = actionText.toLowerCase();

        Widget found = matchByAction(root, lower);
        if (found != null) return found;

        Widget[] dyn = root.getDynamicChildren();
        if (dyn != null) {
            for (Widget child : dyn) {
                if (child == null) continue;
                found = matchByAction(child, lower);
                if (found != null) return found;
                found = findWidgetByAction(actionText, child);
                if (found != null) return found;
            }
        }
        Widget[] stat = root.getStaticChildren();
        if (stat != null) {
            for (Widget child : stat) {
                if (child == null) continue;
                found = matchByAction(child, lower);
                if (found != null) return found;
                found = findWidgetByAction(actionText, child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Widget matchByAction(Widget widget, String lowerAction) {
        String[] actions = widget.getActions();
        if (actions == null) return null;
        for (String action : actions) {
            if (action != null && action.toLowerCase().contains(lowerAction)) {
                return widget;
            }
        }
        return null;
    }
}
