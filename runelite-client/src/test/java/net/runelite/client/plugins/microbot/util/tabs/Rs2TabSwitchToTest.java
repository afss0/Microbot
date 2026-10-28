package net.runelite.client.plugins.microbot.util.tabs;

import java.util.Optional;
import java.util.concurrent.Callable;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class Rs2TabSwitchToTest
{
	private MockedStatic<Microbot> microbot;
	private Client client;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		when(clientThread.runOnClientThreadOptional(any(), anyLong())).thenAnswer(inv ->
		{
			Callable<?> callable = inv.getArgument(0);
			return Optional.ofNullable(callable.call());
		});
		microbot = Mockito.mockStatic(Microbot.class);
		microbot.when(Microbot::getClient).thenReturn(client);
		microbot.when(Microbot::getClientThread).thenReturn(clientThread);
	}

	@After
	public void tearDown()
	{
		microbot.close();
	}

	@Test
	public void alreadyOpenTabSucceedsEvenWhenTheCacheMissedTheChange()
	{
		when(client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL)).thenReturn(InterfaceTab.INVENTORY.getVarcIntIndex());
		assertTrue(Rs2Tab.switchTo(InterfaceTab.INVENTORY));

		when(client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL)).thenReturn(InterfaceTab.EQUIPMENT.getVarcIntIndex());
		assertTrue(Rs2Tab.switchTo(InterfaceTab.EQUIPMENT));

		verify(client, never()).runScript(anyInt(), any());
		assertEquals(InterfaceTab.EQUIPMENT, Rs2Tab.getCurrentTab());
	}
}
