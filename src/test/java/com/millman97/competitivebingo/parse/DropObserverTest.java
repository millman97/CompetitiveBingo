package com.millman97.competitivebingo.parse;

import com.google.gson.Gson;
import com.millman97.competitivebingo.model.DropDetail;
import java.util.Collections;
import net.runelite.client.game.ItemStack;
import org.junit.Test;
import static org.junit.Assert.*;

public class DropObserverTest
{
	private final ChatObserver chat = new ChatObserver(new MetricAliases(new Gson()));
	private final DropObserver observer = new DropObserver(chat);
	private final DropObserver.Items items = new DropObserver.Items()
	{
		public int canonicalize(int id) { return id == 999 ? 4151 : id; }
		public String nameOf(int id) { return id == 4151 ? "Abyssal whip" : null; }
	};

	@Test
	public void dropsKeepCanonicalItemsQuantitiesAndKnownKillCounts()
	{
		chat.onChatMessage("Your Abyssal demon kill count is: 42.", 100);
		final Observation result = observer.onLootReceived("Abyssal demon", "NPC", 100,
			Collections.singletonList(new ItemStack(999, 2)), items, DropValueRules.empty(), null).get(0);
		assertEquals("drop.abyssal_whip", result.getMetricId());
		assertEquals(Long.valueOf(2), result.getDelta());
		assertNull(result.getValue());
		final DropDetail detail = (DropDetail) result.getDetail();
		assertEquals(4151, detail.getItemId());
		assertEquals(Integer.valueOf(42), detail.getKillCount());
	}

	@Test
	public void playerLootIsExcludedBeforeResolvingNamesOrPrices()
	{
		assertTrue(observer.onLootReceived("Other player", "PLAYER", 100,
			Collections.singletonList(new ItemStack(4151, 1)), null, DropValueRules.empty(), null).isEmpty());
	}

	@Test
	public void separateSameTickStacksRemainDistinct()
	{
		assertEquals(0, drop(100).getObservationOrdinal());
		assertEquals(1, drop(100).getObservationOrdinal());
		assertEquals(0, drop(101).getObservationOrdinal());
	}

	private DropDetail drop(int tick)
	{
		return (DropDetail) observer.onLootReceived("Abyssal demon", "NPC", tick,
			Collections.singletonList(new ItemStack(4151, 1)), items, DropValueRules.empty(), null).get(0).getDetail();
	}
}
