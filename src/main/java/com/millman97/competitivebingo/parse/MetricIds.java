package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.MetricType;
import java.util.Locale;
import javax.annotation.Nullable;
import java.util.regex.Pattern;

/**
 * Derives metric IDs from what the game said, and matches them against a game's tracked list.
 */
public final class MetricIds
{
	public static final String COUNT_PREFIX = "kc.";
	public static final String DURATION_PREFIX = "time.";
	public static final String COLLECTION_LOG_PREFIX = "collection_log.";
	public static final String DROP_PREFIX = "drop.";
	public static final String KILL_PREFIX = "kill.";

	/** The only two wildcards the contract permits. */
	public static final String COLLECTION_LOG_WILDCARD = COLLECTION_LOG_PREFIX + "*";
	public static final String DROP_WILDCARD = DROP_PREFIX + "*";

	private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
	private static final Pattern TRIM_UNDERSCORE = Pattern.compile("^_+|_+$");
	private static final Pattern EXACT_ID = Pattern.compile("(?:kc|kill|time|collection_log|drop)\\.[a-z0-9_]{1,128}");

	private MetricIds()
	{
	}

	/**
	 * Lowercase, collapse every run of non-alphanumerics to one underscore, trim the ends.
	 * "Chambers of Xeric: Challenge Mode" becomes "chambers_of_xeric_challenge_mode".
	 *
	 * @return the slug, or an empty string if the name contained nothing usable
	 */
	public static String slug(String rawName)
	{
		final String lowered = rawName.toLowerCase(Locale.ENGLISH);
		return TRIM_UNDERSCORE.matcher(NON_SLUG.matcher(lowered).replaceAll("_")).replaceAll("");
	}

	/**
	 * The family a metric ID belongs to, from its namespace.
	 *
	 * @return the type, or null for a wildcard or anything outside the supported namespaces
	 */
	@Nullable
	public static MetricType typeOf(String metricId)
	{
		if (!isExact(metricId))
		{
			return null;
		}
		if (metricId.startsWith(COUNT_PREFIX))
		{
			return MetricType.COUNT;
		}
		if (metricId.startsWith(DURATION_PREFIX))
		{
			return MetricType.DURATION;
		}
		if (metricId.startsWith(COLLECTION_LOG_PREFIX))
		{
			return MetricType.COLLECTION_LOG;
		}
		if (metricId.startsWith(DROP_PREFIX))
		{
			return MetricType.DROP;
		}
		if (metricId.startsWith(KILL_PREFIX))
		{
			return MetricType.KILL;
		}
		return null;
	}

	public static boolean isExact(@Nullable String metricId)
	{
		return metricId != null && EXACT_ID.matcher(metricId).matches();
	}

	public static String prefixFor(MetricType type)
	{
		switch (type)
		{
			case COUNT:
				return COUNT_PREFIX;
			case DURATION:
				return DURATION_PREFIX;
			case COLLECTION_LOG:
				return COLLECTION_LOG_PREFIX;
			case DROP:
				return DROP_PREFIX;
			case KILL:
				return KILL_PREFIX;
			default:
				throw new IllegalArgumentException("No prefix for metric type " + type);
		}
	}

	/**
	 * Builds the full metric ID for an observation, e.g. COUNT of "Vorkath" gives "kc.vorkath".
	 *
	 * @throws IllegalArgumentException if the name slugs to nothing, which means the caller
	 *                                  matched a message it should not have
	 */
	public static String of(MetricType type, String rawName)
	{
		final String slug = slug(rawName);
		if (slug.isEmpty())
		{
			throw new IllegalArgumentException("Cannot build a " + type + " metric ID from '" + rawName + "'");
		}
		return prefixFor(type) + slug;
	}
}
