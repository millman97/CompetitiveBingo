package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.parse.MetricFilter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;

/**
 * Evaluates whether the client is in a state where the tracked metrics can actually be observed.
 *
 * Reads live client state, so {@link #evaluate} must be called on the client thread.
 */
@Slf4j
@Singleton
public class ReadinessChecks
{
	private final Client client;
	private final PluginManager pluginManager;

	@Inject
	public ReadinessChecks(Client client, PluginManager pluginManager)
	{
		this.client = client;
		this.pluginManager = pluginManager;
	}

	/**
	 * @param filter            the current game's tracked metrics, which decide whether the
	 *                          collection log and loot checks apply at all
	 * @param blockedWorldTypes WorldType names the game refuses, from its config
	 * @return every applicable check, satisfied or not
	 */
	public List<ReadinessCheck> evaluate(MetricFilter filter, @Nullable List<String> blockedWorldTypes)
	{
		final List<ReadinessCheck> checks = new ArrayList<>(4);

		if (requiresKillCountMessages(filter))
		{
			checks.add(killCountFilter());
		}
		checks.add(worldType(blockedWorldTypes));

		if (filter.tracksAnyCollectionLog())
		{
			checks.add(collectionLogNotification());
		}

		if (filter.tracksAnyDrop() || filter.tracksAnyKill())
		{
			checks.add(lootTracker());
		}

		return checks;
	}

	static boolean requiresKillCountMessages(MetricFilter filter)
	{
		// Anonymous duration lines may need a nearby count message to identify their activity.
		return filter.tracksAnyCountOrDuration();
	}

	public static boolean allSatisfied(List<ReadinessCheck> checks)
	{
		return checks.stream().allMatch(ReadinessCheck::isSatisfied);
	}

	/**
	 * Varbit 4930. Non-zero means the game is hiding boss kill count messages behind the spam
	 * filter, and no counter message ever reaches chat for this plugin to read.
	 */
	private ReadinessCheck killCountFilter()
	{
		final int value = client.getVarbitValue(VarbitID.BOSS_KILLCOUNT_FILTERED);
		return value == 0
			? ReadinessCheck.ok(ReadinessCheck.Id.KILL_COUNT_FILTER, "Kill count messages visible")
			: ReadinessCheck.failed(ReadinessCheck.Id.KILL_COUNT_FILTER, "Kill count messages visible",
				"Turn off Settings > All Settings > Chat > 'Filter out boss kill-count with spam-filter'.");
	}

	/**
	 * Varbit 11959: 0 none, 1 chat, 2 popup, 3 both. This plugin reads the chat line, so the
	 * chat bit has to be set — popup-only produces no message at all.
	 */
	private ReadinessCheck collectionLogNotification()
	{
		final int value = client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM);
		final boolean chatEnabled = (value & 1) != 0;

		return chatEnabled
			? ReadinessCheck.ok(ReadinessCheck.Id.COLLECTION_LOG_NOTIFICATION, "Collection log chat message on")
			: ReadinessCheck.failed(ReadinessCheck.Id.COLLECTION_LOG_NOTIFICATION, "Collection log chat message on",
				"Set Settings > All Settings > Chat > 'Collection log - New addition notification' to include chat.");
	}

	/** The block list is the game's, not the plugin's. An unknown name is ignored, not guessed at. */
	private ReadinessCheck worldType(@Nullable List<String> blockedWorldTypes)
	{
		final Set<WorldType> blocked = toWorldTypes(blockedWorldTypes);

		final String offending = client.getWorldType().stream()
			.filter(blocked::contains)
			.map(Enum::name)
			.collect(Collectors.joining(", "));

		if (offending.isEmpty())
		{
			return ReadinessCheck.ok(ReadinessCheck.Id.WORLD_TYPE, "World is eligible");
		}

		return ReadinessCheck.failed(ReadinessCheck.Id.WORLD_TYPE, "World is eligible",
			"Hop to a world this game accepts — it does not count progress from a "
				+ offending + " world.");
	}

	/** Names the running client does not know are skipped; a newer server may know more of them. */
	static Set<WorldType> toWorldTypes(@Nullable List<String> names)
	{
		final Set<WorldType> types = EnumSet.noneOf(WorldType.class);
		if (names == null)
		{
			return types;
		}

		for (String name : names)
		{
			try
			{
				types.add(WorldType.valueOf(name));
			}
			catch (IllegalArgumentException e)
			{
				log.debug("Server named an unknown world type: {}", name);
			}
		}
		return types;
	}

	/**
	 * Drops come from RuneLite's own Loot Tracker plugin. With it switched off, no loot event is
	 * posted at all.
	 */
	private ReadinessCheck lootTracker()
	{
		final boolean active = pluginManager.getPlugins().stream()
			.filter(LootTrackerPlugin.class::isInstance)
			.anyMatch(pluginManager::isPluginActive);

		return active
			? ReadinessCheck.ok(ReadinessCheck.Id.LOOT_TRACKER, "Loot Tracker plugin on")
			: ReadinessCheck.failed(ReadinessCheck.Id.LOOT_TRACKER, "Loot Tracker plugin on",
				"Turn on RuneLite's Loot Tracker plugin — loot and kill classification require it.");
	}
}
