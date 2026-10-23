package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.KillDetail;
import com.millman97.competitivebingo.model.MetricType;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Observes Loot Tracker's classified NPC loot. Raw server loot can also be pickpockets.
 */
@Slf4j
@Singleton
public class NpcKillObserver
{
	public static final String SIGNAL = "SERVER_NPC_LOOT";

	/*
	 * RuneLite issue/PR coverage documents normal plus tertiary packets for these encounters.
	 * They all have authoritative KC support anyway, so kill.* is intentionally unsupported.
	 */
	private static final Set<String> MULTI_PACKET_EXCLUSIONS = Set.of(
			"phosani_s_nightmare",
			"callisto",
			"artio",
			"vet_ion",
			"calvar_ion",
			"venenatis",
			"spindel",
			"the_hueycoatl"
		);
	private int ordinalTick = Integer.MIN_VALUE;
	private int nextOrdinal;

	@Nullable
	public Observation onLootReceived(String type, Object metadata, @Nullable String npcName,
		int combatLevel, int gameTick, MetricFilter filter)
	{
		if (!"NPC".equals(type) || !(metadata instanceof Integer) || npcName == null)
		{
			return null;
		}
		final int npcId = (Integer) metadata;
		final String slug = MetricIds.slug(npcName);
		if (npcId < 0 || slug.isEmpty())
		{
			return null;
		}

		final String metricId = MetricIds.KILL_PREFIX + slug;
		if (!filter.matches(metricId))
		{
			return null;
		}

		// Prefer the game's cumulative counter whenever this game is already tracking it.
		if (filter.matches(MetricIds.COUNT_PREFIX + slug))
		{
			log.debug("Ignoring observed {} kill because its authoritative count is tracked", npcName);
			return null;
		}

		if (MULTI_PACKET_EXCLUSIONS.contains(slug))
		{
			log.debug("Ignoring observed {} kill because it can publish multiple loot packets", npcName);
			return null;
		}

		if (ordinalTick != gameTick)
		{
			ordinalTick = gameTick;
			nextOrdinal = 0;
		}
		return Observation.builder()
			.metricId(metricId)
			.type(MetricType.KILL)
			.delta(1L)
			.detail(new KillDetail(npcId, npcName, combatLevel < 0 ? null : combatLevel,
				SIGNAL, nextOrdinal++))
			.build();
	}

	public void reset()
	{
		ordinalTick = Integer.MIN_VALUE;
		nextOrdinal = 0;
	}
}
