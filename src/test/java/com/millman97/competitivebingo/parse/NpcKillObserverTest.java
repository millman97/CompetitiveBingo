package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.KillDetail;
import com.millman97.competitivebingo.model.MetricType;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class NpcKillObserverTest
{
	private final NpcKillObserver observer = new NpcKillObserver();

	@Test
	public void emitsTrackedServerAttributedKill()
	{
		final Observation result = serverKill(415, "Abyssal demon", 124, 100,
			MetricFilter.of(Collections.singletonList("kill.abyssal_demon")));

		assertEquals("kill.abyssal_demon", result.getMetricId());
		assertEquals(MetricType.KILL, result.getType());
		assertNull(result.getValue());
		assertEquals(Long.valueOf(1), result.getDelta());
		final KillDetail detail = (KillDetail) result.getDetail();
		assertEquals(415, detail.getNpcId());
		assertEquals("Abyssal demon", detail.getNpcName());
		assertEquals(Integer.valueOf(124), detail.getCombatLevel());
		assertEquals("SERVER_NPC_LOOT", detail.getSignal());
		assertEquals(0, detail.getObservationOrdinal());
	}

	@Test
	public void authoritativeCountWinsWhenBothAreConfigured()
	{
		assertNull(serverKill(8061, "Vorkath", 732, 100,
			MetricFilter.of(Arrays.asList("kill.vorkath", "kc.vorkath"))));
	}

	@Test
	public void excludesKnownMultiPacketEncounters()
	{
		assertNull(serverKill(9416, "Phosani's Nightmare", 1024, 100,
			MetricFilter.of(Collections.singletonList("kill.phosani_s_nightmare"))));
	}

	@Test
	public void simultaneousKillsReceiveDistinctOrdinals()
	{
		final MetricFilter filter = MetricFilter.of(Collections.singletonList("kill.abyssal_demon"));
		final Observation first = serverKill(415, "Abyssal demon", 124, 100, filter);
		final Observation second = serverKill(415, "Abyssal demon", 124, 100, filter);
		final Observation nextTick = serverKill(415, "Abyssal demon", 124, 101, filter);

		assertEquals(0, ((KillDetail) first.getDetail()).getObservationOrdinal());
		assertEquals(1, ((KillDetail) second.getDetail()).getObservationOrdinal());
		assertEquals(0, ((KillDetail) nextTick.getDetail()).getObservationOrdinal());
	}

	private Observation serverKill(int id, String name, int level, int tick, MetricFilter filter)
	{
		return observer.onLootReceived("NPC", id, name, level, tick, filter);
	}

	@Test
	public void pickpocketAndPlayerLootCannotEstablishAKill()
	{
		final MetricFilter filter = MetricFilter.of(Collections.singletonList("kill.guard"));
		assertNull(observer.onLootReceived("PICKPOCKET", 1, "Guard", 21, 100, filter));
		assertNull(observer.onLootReceived("PLAYER", 1, "Guard", 21, 100, filter));
	}

}
