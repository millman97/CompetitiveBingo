package com.millman97.competitivebingo.parse;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the single message the game emits when a collection log slot is filled.
 */
public final class CollectionLogParser
{
	private static final Pattern NEW_ITEM = Pattern.compile("New item added to your collection log: (?<item>.+)");

	private CollectionLogParser()
	{
	}

	/**
	 * @param message chat text with colour tags removed
	 * @return the item name, or empty if this is not a collection log message
	 */
	public static Optional<String> parse(String message)
	{
		final Matcher m = NEW_ITEM.matcher(message);
		if (!m.find())
		{
			return Optional.empty();
		}

		// The game ends the line with a full stop for some items and not others.
		String item = m.group("item").trim();
		while (item.endsWith("."))
		{
			item = item.substring(0, item.length() - 1).trim();
		}

		return item.isEmpty() || MetricIds.slug(item).isEmpty() ? Optional.empty() : Optional.of(item);
	}
}
