package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.MetricType;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Translates activity names the game spells awkwardly into canonical metric IDs.
 */
@Slf4j
@Singleton
public class MetricAliases
{
	private static final String RESOURCE = "/com/millman97/competitivebingo/metric-aliases.json";

	/** Lowercased raw activity name, per metric type, to canonical metric ID. */
	private final Map<MetricType, Map<String, String>> aliases = new HashMap<>();

	@Inject
	public MetricAliases(Gson gson)
	{
		try (InputStream in = MetricAliases.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing bundled resource " + RESOURCE);
			}

			final JsonObject root = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			aliases.put(MetricType.COUNT, read(root, "count"));
			aliases.put(MetricType.DURATION, read(root, "duration"));
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Could not read " + RESOURCE, e);
		}

		log.debug("Loaded metric aliases: {} count, {} duration",
			aliases.get(MetricType.COUNT).size(), aliases.get(MetricType.DURATION).size());
	}

	private static Map<String, String> read(JsonObject root, String section)
	{
		final Map<String, String> out = new HashMap<>();
		final JsonElement element = root.get(section);
		if (element == null)
		{
			return out;
		}

		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
		{
			if (entry.getKey().startsWith("_"))
			{
				continue;
			}
			out.put(entry.getKey().toLowerCase(Locale.ENGLISH), entry.getValue().getAsString());
		}
		return out;
	}

	/**
	 * Resolves an activity to its metric ID, applying an alias if one is defined.
	 *
	 * @param type     COUNT or DURATION; the other families have no aliases
	 * @param activity the activity name as the game spelled it
	 */
	public String resolve(MetricType type, Activity activity)
	{
		final Map<String, String> forType = aliases.get(type);
		if (forType != null)
		{
			final String aliased = forType.get(activity.getFull().toLowerCase(Locale.ENGLISH));
			if (aliased != null)
			{
				return aliased;
			}
		}
		return MetricIds.of(type, activity.getFull());
	}
}
