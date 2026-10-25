package net.runelite.client.plugins.microbot.ascript.util;

import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guardrail for the verified-dispatch identity matchers: the hover row a press resolves to must
 * be pinned to OUR entity (id + scene coords, actor reference or NPC index) and, when an action
 * is named, to that option — a wrong match would press another entity's row or the wrong op.
 */
public class AScriptClickMatchersTest {

    // ── option matching ────────────────────────────────────

    @Test
    public void nullActionMatchesAnyOption() {
        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getOption()).thenReturn("Attack");
        assertTrue(AScriptClick.optionMatches(entry, null));
        assertTrue(AScriptClick.optionMatches(entry, " "));
    }

    @Test
    public void optionMatchingIsCaseInsensitiveAndIgnoresColorTags() {
        MenuEntry entry = mock(MenuEntry.class);
        // The Ground Items plugin recolors menu options; the tag is not part of the op.
        when(entry.getOption()).thenReturn("<col=ff9040>Take</col>");
        assertTrue(AScriptClick.optionMatches(entry, "take"));
        assertTrue(AScriptClick.optionMatches(entry, "Take"));
        assertFalse(AScriptClick.optionMatches(entry, "Bury"));
    }

    @Test
    public void missingOptionTextNeverMatchesANamedAction() {
        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getOption()).thenReturn(null);
        assertFalse(AScriptClick.optionMatches(entry, "Take"));
    }

    // ── objects ────────────────────────────────────────────

    @Test
    public void objectRowMustMatchTypeIdAndSceneCoords() {
        GameObject object = gameObject(16469, localPoint(32, 32), 1, 1);

        assertTrue(AScriptClick.isOwnObjectAction(objectRow(16469, 32, 32, "Smelt"), object, "smelt"));
        assertTrue(AScriptClick.isOwnObjectAction(objectRow(16469, 32, 32, "Smelt"), object, null));

        assertFalse("a different option must not match",
                AScriptClick.isOwnObjectAction(objectRow(16469, 32, 32, "Use"), object, "Smelt"));
        assertFalse("a different object id must not match",
                AScriptClick.isOwnObjectAction(objectRow(16468, 32, 32, "Smelt"), object, "Smelt"));
        assertFalse("the same id on another tile must not match",
                AScriptClick.isOwnObjectAction(objectRow(16469, 33, 32, "Smelt"), object, "Smelt"));
        assertFalse("the item-on-object family is not a plain click",
                AScriptClick.isOwnObjectAction(entry(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT,
                        16469, 32, 32, "Use"), object, null));
    }

    @Test
    public void multiTileGameObjectCoordsAreSizeAdjusted() {
        // sizeX=2, sizeY=2 at (50,60) → the row carries the centre tile (49,59).
        GameObject object = gameObject(24009, localPoint(50, 60), 2, 2);
        assertTrue(AScriptClick.isOwnObjectAction(objectRow(24009, 49, 59, "Smelt"), object, "Smelt"));
        assertFalse(AScriptClick.isOwnObjectAction(objectRow(24009, 50, 60, "Smelt"), object, "Smelt"));
    }

    @Test
    public void tileObjectModelWrapperIsUnwrappedForTheSizeAdjustment() {
        // The cache hands modules Rs2TileObjectModel, which is NOT a GameObject; without the
        // unwrap a multi-tile object would never match its own row.
        GameObject gameObject = gameObject(24009, localPoint(50, 60), 2, 2);
        Rs2TileObjectModel wrapper = new Rs2TileObjectModel(gameObject);

        assertTrue(AScriptClick.isOwnObjectAction(objectRow(24009, 49, 59, "Smelt"), wrapper, "Smelt"));
        assertFalse(AScriptClick.isOwnObjectAction(objectRow(24009, 50, 60, "Smelt"), wrapper, null));
    }

    // ── NPCs ───────────────────────────────────────────────

    @Test
    public void npcRowMatchesByActorReferenceOrNpcIndex() {
        NPC crab = mock(NPC.class);
        when(crab.getIndex()).thenReturn(4242);

        MenuEntry referenceRow = entry(MenuAction.NPC_FIRST_OPTION, 4242, 0, 0, "Attack");
        when(referenceRow.getActor()).thenReturn(crab);
        assertTrue(AScriptClick.isOwnActorAction(referenceRow, crab, "attack"));
        assertTrue(AScriptClick.isOwnActorAction(referenceRow, crab, null));

        // A model wrapper (Rs2NpcModel) is a different Actor instance; the index identifies it.
        MenuEntry indexRow = entry(MenuAction.NPC_SECOND_OPTION, 4242, 0, 0, "Bait");
        assertTrue(AScriptClick.isOwnActorAction(indexRow, crab, "Bait"));

        assertFalse("another NPC's index must not match",
                AScriptClick.isOwnActorAction(entry(MenuAction.NPC_FIRST_OPTION, 4243, 0, 0, "Attack"), crab, "Attack"));
        assertFalse("a different option must not match",
                AScriptClick.isOwnActorAction(indexRow, crab, "Lure"));
        assertFalse("the item-on-NPC family is not a plain click",
                AScriptClick.isOwnActorAction(entry(MenuAction.WIDGET_TARGET_ON_NPC, 4242, 0, 0, "Use"), crab, null));
    }

    // ── ground items ───────────────────────────────────────

    @Test
    public void groundItemRowMustMatchTypeIdAndSceneCoords() {
        TileItem item = mock(TileItem.class);
        when(item.getId()).thenReturn(21512); // seaweed spore

        assertTrue(AScriptClick.isOwnGroundItemAction(
                entry(MenuAction.GROUND_ITEM_THIRD_OPTION, 21512, 32, 32, "Take"),
                item, 32, 32, "take"));
        assertTrue(AScriptClick.isOwnGroundItemAction(
                entry(MenuAction.GROUND_ITEM_THIRD_OPTION, 21512, 32, 32, "Take"),
                item, 32, 32, null));

        assertFalse("a different tile must not match",
                AScriptClick.isOwnGroundItemAction(
                        entry(MenuAction.GROUND_ITEM_THIRD_OPTION, 21512, 33, 32, "Take"),
                        item, 32, 32, "Take"));
        assertFalse("a different item must not match",
                AScriptClick.isOwnGroundItemAction(
                        entry(MenuAction.GROUND_ITEM_THIRD_OPTION, 21511, 32, 32, "Take"),
                        item, 32, 32, "Take"));
        assertFalse("a different option must not match",
                AScriptClick.isOwnGroundItemAction(
                        entry(MenuAction.GROUND_ITEM_THIRD_OPTION, 21512, 32, 32, "Take"),
                        item, 32, 32, "Bury"));
        assertFalse("a tile-entry family is not a ground-item row",
                AScriptClick.isOwnGroundItemAction(
                        entry(MenuAction.GAME_OBJECT_FIRST_OPTION, 21512, 32, 32, "Take"),
                        item, 32, 32, null));
    }

    // ── fixtures ───────────────────────────────────────────

    /** A LocalPoint whose {@code getSceneX()/getSceneY()} are the given scene coordinates. */
    private static LocalPoint localPoint(int sceneX, int sceneY) {
        return new LocalPoint(sceneX << Perspective.LOCAL_COORD_BITS, sceneY << Perspective.LOCAL_COORD_BITS);
    }

    private static GameObject gameObject(int id, LocalPoint local, int sizeX, int sizeY) {
        GameObject object = mock(GameObject.class);
        when(object.getId()).thenReturn(id);
        when(object.getLocalLocation()).thenReturn(local);
        when(object.sizeX()).thenReturn(sizeX);
        when(object.sizeY()).thenReturn(sizeY);
        return object;
    }

    private static MenuEntry objectRow(int id, int sceneX, int sceneY, String option) {
        return entry(MenuAction.GAME_OBJECT_FIRST_OPTION, id, sceneX, sceneY, option);
    }

    private static MenuEntry entry(MenuAction type, int identifier, int param0, int param1, String option) {
        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getType()).thenReturn(type);
        when(entry.getIdentifier()).thenReturn(identifier);
        when(entry.getParam0()).thenReturn(param0);
        when(entry.getParam1()).thenReturn(param1);
        when(entry.getOption()).thenReturn(option);
        return entry;
    }
}
