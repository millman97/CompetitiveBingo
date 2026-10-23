package com.millman97.competitivebingo.parse;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether a game asked for a metric.
 */
public final class MetricFilter
{
	private final Set<String> exact;
	private final boolean allCollectionLog;
	private final boolean allDrops;

	private MetricFilter(Set<String> exact, boolean allCollectionLog, boolean allDrops)
	{
		this.exact = exact;
		this.allCollectionLog = allCollectionLog;
		this.allDrops = allDrops;
	}

	/** A filter that matches nothing, used before a game config has been loaded. */
	public static MetricFilter empty()
	{
		return new MetricFilter(Collections.emptySet(), false, false);
	}

	/**
	 * @param trackedMetrics the game's list, verbatim
	 * @throws IllegalArgumentException if an entry contains a wildcard that is not one of the
	 *                                  two permitted forms. A malformed config is refused rather
	 *                                  than silently interpreted.
	 */
	public static MetricFilter of(Collection<String> trackedMetrics)
	{
		return of(trackedMetrics, Collections.emptyList());
	}

	/**
	 * @param trackedMetrics the game's list, verbatim
	 * @param alsoTracked    metric IDs asked for by some other part of the config — today, the
	 *                       {@code dropValueRules} entries. Naming a rule is what asks for its
	 *                       metric, so it does not have to appear in {@code trackedMetrics} as
	 *                       well, and a filter that did not know about it would drop every event
	 *                       the rule produced.
	 */
	public static MetricFilter of(Collection<String> trackedMetrics, Collection<String> alsoTracked)
	{
		final Set<String> exact = new HashSet<>(alsoTracked);
		if (exact.stream().anyMatch(id -> !MetricIds.isExact(id)))
		{
			throw new IllegalArgumentException("Invalid additional tracked metric.");
		}
		boolean allCollectionLog = false;
		boolean allDrops = false;

		for (String entry : trackedMetrics)
		{
			if (MetricIds.COLLECTION_LOG_WILDCARD.equals(entry))
			{
				allCollectionLog = true;
			}
			else if (MetricIds.DROP_WILDCARD.equals(entry))
			{
				allDrops = true;
			}
			else if (entry != null && entry.indexOf('*') >= 0)
			{
				throw new IllegalArgumentException("Unsupported wildcard in trackedMetrics: '" + entry
					+ "'. Only " + MetricIds.COLLECTION_LOG_WILDCARD + " and " + MetricIds.DROP_WILDCARD + " are permitted.");
			}
			else
			{
				if (!MetricIds.isExact(entry))
				{
					throw new IllegalArgumentException("Invalid tracked metric: '" + entry + "'.");
				}
				exact.add(entry);
			}
		}

		return new MetricFilter(exact, allCollectionLog, allDrops);
	}

	public boolean matches(String metricId)
	{
		if (exact.contains(metricId))
		{
			return true;
		}
		if (allCollectionLog && metricId.startsWith(MetricIds.COLLECTION_LOG_PREFIX))
		{
			return true;
		}
		return allDrops && metricId.startsWith(MetricIds.DROP_PREFIX);
	}

	/** Whether the game tracks any drop at all, which decides if the Loot Tracker check applies. */
	public boolean tracksAnyDrop()
	{
		return allDrops || exact.stream().anyMatch(id -> id.startsWith(MetricIds.DROP_PREFIX));
	}

	/** Whether the game tracks any collection log entry, which decides if that check applies. */
	public boolean tracksAnyCollectionLog()
	{
		return allCollectionLog || exact.stream().anyMatch(id -> id.startsWith(MetricIds.COLLECTION_LOG_PREFIX));
	}

	/** Whether chat count visibility can affect any metric this game tracks. */
	public boolean tracksAnyCountOrDuration()
	{
		return exact.stream().anyMatch(id -> id.startsWith(MetricIds.COUNT_PREFIX)
			|| id.startsWith(MetricIds.DURATION_PREFIX));
	}

	public boolean tracksAnyKill()
	{
		return exact.stream().anyMatch(id -> id.startsWith(MetricIds.KILL_PREFIX));
	}
}
