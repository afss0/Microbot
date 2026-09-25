package net.runelite.client.plugins.devtools;

import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.util.input.PointerState;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MicrobotMouseOverlayTest
{
	@After
	public void tearDown()
	{
		PointerState.reset();
	}

	@Test
	public void realCursorDoesNotMoveBotCursorOverlay()
	{
		PointerState.setFromBot(100, 120);
		PointerState.setFromReal(500, 520);

		Point botCursor = MicrobotMouseOverlay.botCursorPosition();
		assertEquals(100, botCursor.getX());
		assertEquals(120, botCursor.getY());
	}

	/** While the user owns input (no MouseSync hold), the crosshair follows real input. */
	@Test
	public void livePointerDrivesCrosshairWhileUserOwnsInput()
	{
		PointerState.setFromBot(100, 120);
		PointerState.setFromReal(500, 520);

		Point crosshair = MicrobotMouseOverlay.crosshairPosition(null);
		assertEquals(500, crosshair.getX());
		assertEquals(520, crosshair.getY());
	}
}
