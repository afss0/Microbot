package net.runelite.client.plugins.microbot.util.walker;

import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class Rs2WalkerTransportsMinigameTeleportTest
{
	@Test
	public void missingSelectedWidgetDoesNotMatch()
	{
		assertFalse(Rs2WalkerTransports.widgetTextMatches(null, "barbarian assault"));
	}

	@Test
	public void nullWidgetTextDoesNotMatch()
	{
		assertFalse(Rs2WalkerTransports.widgetTextMatches(widgetWithText(null), "barbarian assault"));
	}

	@Test
	public void matchingTextIgnoresCase()
	{
		assertTrue(Rs2WalkerTransports.widgetTextMatches(widgetWithText("Barbarian Assault"), "barbarian assault"));
	}

	@Test
	public void differentTextDoesNotMatch()
	{
		assertFalse(Rs2WalkerTransports.widgetTextMatches(widgetWithText("Castle Wars"), "barbarian assault"));
	}

	private static Widget widgetWithText(String text)
	{
		Widget widget = mock(Widget.class);
		when(widget.getText()).thenReturn(text);
		return widget;
	}
}
