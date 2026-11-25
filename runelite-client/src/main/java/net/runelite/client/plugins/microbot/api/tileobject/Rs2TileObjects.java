package net.runelite.client.plugins.microbot.api.tileobject;

import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.TileObjectType;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Compatibility-free tile-object helpers for the fork code that predates the
 * 2.6.59 removal of {@code util.gameobject.Rs2GameObject}.
 *
 * <p>All reads go through {@link Microbot#getRs2TileObjectCache()} and the
 * query API; no legacy tile-object package is referenced.</p>
 */
public final class Rs2TileObjects {

    private Rs2TileObjects() {
    }

    public static TileObject findObjectById(int id) {
        return Microbot.getRs2TileObjectCache().query().withId(id).nearestOnClientThread();
    }

    public static TileObject findObjectByLocation(WorldPoint location) {
        if (location == null) {
            return null;
        }
        return Microbot.getRs2TileObjectCache().query().at(location).firstOnClientThread();
    }

    public static TileObject findBank() {
        return Rs2BankObjects.findBank();
    }

    public static TileObject findGrandExchangeBooth() {
        return Rs2BankObjects.findGrandExchangeBooth();
    }

    public static boolean exists(int id) {
        return Microbot.getRs2TileObjectCache().query().withId(id).firstOnClientThread() != null;
    }

    public static List<Integer> getObjectIdsByName(String name) {
        if (name == null) {
            return List.of();
        }
        return Microbot.getRs2TileObjectCache().query()
                .withName(name)
                .toListOnClientThread()
                .stream()
                .map(Rs2TileObjectModel::getId)
                .collect(Collectors.toList());
    }

    public static ObjectComposition convertToObjectComposition(int id) {
        return Rs2TileObjectModel.compositionOf(id);
    }

    public static ObjectComposition convertToObjectComposition(TileObject object) {
        return object == null ? null : new Rs2TileObjectModel(object).getObjectComposition();
    }

    public static boolean hasAction(ObjectComposition composition, String action) {
        if (composition == null || action == null || action.isBlank()) {
            return false;
        }
        return Arrays.stream(composition.getActions())
                .filter(Objects::nonNull)
                .anyMatch(candidate -> candidate.equalsIgnoreCase(action)
                        || candidate.toLowerCase().contains(action.toLowerCase()));
    }

    public static List<TileObject> getAll() {
        return Microbot.getRs2TileObjectCache().query()
                .toListOnClientThread()
                .stream()
                .map(Rs2TileObjectModel::getTileObject)
                .collect(Collectors.toList());
    }

    public static List<TileObject> getAll(Predicate<TileObject> predicate) {
        return Microbot.getRs2TileObjectCache().query()
                .where(object -> predicate.test(object))
                .toListOnClientThread()
                .stream()
                .map(Rs2TileObjectModel::getTileObject)
                .collect(Collectors.toList());
    }

    public static List<TileObject> getAll(Predicate<TileObject> predicate,
                                          WorldPoint anchor, int distance) {
        return Microbot.getRs2TileObjectCache().query()
                .within(anchor, distance)
                .where(object -> predicate.test(object))
                .toListOnClientThread()
                .stream()
                .map(Rs2TileObjectModel::getTileObject)
                .collect(Collectors.toList());
    }

    public static GameObject getGameObject(Predicate<GameObject> predicate, WorldPoint anchor) {
        if (predicate == null) {
            return null;
        }
        Rs2TileObjectModel found = Microbot.getRs2TileObjectCache().query()
                .ofType(TileObjectType.GAME)
                .within(anchor, Integer.MAX_VALUE)
                .where(object -> object.getTileObject() instanceof GameObject
                        && predicate.test((GameObject) object.getTileObject()))
                .nearestOnClientThread(anchor, Integer.MAX_VALUE);
        return found == null ? null : (GameObject) found.getTileObject();
    }

    public static List<GameObject> getGameObjects(Predicate<GameObject> predicate,
                                                   WorldPoint anchor, int distance) {
        return Microbot.getRs2TileObjectCache().query()
                .ofType(TileObjectType.GAME)
                .within(anchor, distance)
                .where(object -> object.getTileObject() instanceof GameObject
                        && predicate.test((GameObject) object.getTileObject()))
                .toListOnClientThread()
                .stream()
                .map(object -> (GameObject) object.getTileObject())
                .collect(Collectors.toList());
    }

    public static List<WallObject> getWallObjects(Predicate<WallObject> predicate,
                                                   WorldPoint anchor, int distance) {
        return Microbot.getRs2TileObjectCache().query()
                .ofType(TileObjectType.WALL)
                .within(anchor, distance)
                .where(object -> object.getTileObject() instanceof WallObject
                        && predicate.test((WallObject) object.getTileObject()))
                .toListOnClientThread()
                .stream()
                .map(object -> (WallObject) object.getTileObject())
                .collect(Collectors.toList());
    }

    public static boolean interact(TileObject object, String action) {
        return object != null && new Rs2TileObjectModel(object).click(action);
    }

    public static boolean interact(int id) {
        return Microbot.getRs2TileObjectCache().query().withId(id).interact();
    }

    public static boolean interact(int id, String action) {
        return Microbot.getRs2TileObjectCache().query().withId(id).interact(action);
    }

    public static boolean interact(int id, String action, int maxDistance) {
        return Microbot.getRs2TileObjectCache().query().withId(id).interact(action, maxDistance);
    }
}
