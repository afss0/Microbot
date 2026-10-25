package net.runelite.client.plugins.microbot.alerts;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DashboardSyncTest
{
	private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000042");
	private static final String REQUEST = "6f1c7a8e-1a2b-4c3d-9e8f-0123456789ab";

	@Test
	public void payloadCarriesTheSessionScreenshotSettingAndState()
	{
		JsonObject state = new JsonObject();
		state.addProperty("status", "LOGGED_IN");

		JsonObject payload = DashboardSync.payload(SESSION, false, state);

		assertEquals(SESSION.toString(), payload.get("sessionId").getAsString());
		assertFalse(payload.get("screenshotsAllowed").getAsBoolean());
		assertEquals("LOGGED_IN", payload.getAsJsonObject("gameState").get("status").getAsString());
	}

	@Test
	public void screenshotRequestsAreReadFromTheCommands()
	{
		String body = "{\"commands\":[{\"id\":\"" + REQUEST + "\",\"type\":\"screenshot\"},{\"id\":\"" + REQUEST + "\",\"type\":\"unknown\"}]}";

		assertEquals(List.of(REQUEST), DashboardSync.screenshotRequests(new Gson(), body));
	}

	@Test
	public void unreadableResponsesHaveNoCommands()
	{
		Gson gson = new Gson();
		assertTrue(DashboardSync.screenshotRequests(gson, "").isEmpty());
		assertTrue(DashboardSync.screenshotRequests(gson, "not json").isEmpty());
		assertTrue(DashboardSync.screenshotRequests(gson, "{\"commands\":[{\"type\":\"screenshot\"}]}").isEmpty());
		assertTrue(DashboardSync.screenshotRequests(gson, "{\"commands\":[{\"id\":\"../../etc\",\"type\":\"screenshot\"}]}").isEmpty());
	}

	@Test
	public void backoffDoublesUpToTwoMinutes()
	{
		assertEquals(5_000, DashboardSync.backoff(1));
		assertEquals(10_000, DashboardSync.backoff(2));
		assertEquals(80_000, DashboardSync.backoff(5));
		assertEquals(120_000, DashboardSync.backoff(6));
		assertEquals(120_000, DashboardSync.backoff(500));
	}

	@Test
	public void xpGainedCountsFromTheFirstReadingAndResetsPerAccount()
	{
		XpTracker tracker = new XpTracker();

		assertEquals(0, tracker.gained(1, Skill.ATTACK, 0, false));
		assertEquals(0, tracker.gained(1, Skill.ATTACK, 1_000, true));
		assertEquals(250, tracker.gained(1, Skill.ATTACK, 1_250, true));
		assertEquals(0, tracker.gained(1, Skill.MINING, 50, true));
		assertEquals(1, tracker.account());
		assertEquals(0, tracker.gained(2, Skill.ATTACK, 9_000, true));
		assertEquals(100, tracker.gained(2, Skill.ATTACK, 9_100, true));
		assertEquals(2, tracker.account());
	}

	@Test
	public void xpFromZeroCountsOnceTheStatsHaveLoaded()
	{
		XpTracker tracker = new XpTracker();

		assertEquals(0, tracker.gained(1, Skill.MINING, 0, true));
		assertEquals(35, tracker.gained(1, Skill.MINING, 35, true));
	}

	@Test
	public void screenshotsAreJpegsNoWiderThanTheLimit() throws Exception
	{
		BufferedImage frame = new BufferedImage(2560, 1440, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = frame.createGraphics();
		graphics.setColor(Color.RED);
		graphics.fillRect(0, 0, 2560, 1440);
		graphics.dispose();

		byte[] jpeg = DashboardScreenshot.encode(frame);

		assertEquals((byte) 0xFF, jpeg[0]);
		assertEquals((byte) 0xD8, jpeg[1]);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
		assertEquals(DashboardScreenshot.MAX_WIDTH, decoded.getWidth());
		assertEquals(720, decoded.getHeight());
	}

	@Test
	public void smallScreenshotsKeepTheirSize() throws Exception
	{
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(DashboardScreenshot.encode(new BufferedImage(765, 503, BufferedImage.TYPE_INT_RGB))));

		assertEquals(765, decoded.getWidth());
		assertEquals(503, decoded.getHeight());
	}

	@Test
	public void noisyScreenshotsAreShrunkUnderTheUploadLimit() throws Exception
	{
		BufferedImage frame = new BufferedImage(1280, 1024, BufferedImage.TYPE_INT_RGB);
		java.util.Random random = new java.util.Random(42);
		for (int y = 0; y < frame.getHeight(); y++)
		{
			for (int x = 0; x < frame.getWidth(); x++)
			{
				frame.setRGB(x, y, random.nextInt());
			}
		}

		assertTrue(DashboardScreenshot.encode(frame).length <= DashboardScreenshot.MAX_BYTES);
	}
}
