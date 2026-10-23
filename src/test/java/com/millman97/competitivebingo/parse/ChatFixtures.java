package com.millman97.competitivebingo.parse;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the real chat message strings in src/test/resources/chat-fixtures.json.
 */
final class ChatFixtures
{
	private static final JsonObject ROOT = load();

	private ChatFixtures()
	{
	}

	private static JsonObject load()
	{
		try (InputStream in = ChatFixtures.class.getResourceAsStream("/chat-fixtures.json"))
		{
			if (in == null)
			{
				throw new IllegalStateException("chat-fixtures.json is missing from the test resources");
			}
			return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Could not read chat-fixtures.json", e);
		}
	}

	static List<JsonObject> cases(String section)
	{
		final JsonArray array = ROOT.getAsJsonArray(section);
		final List<JsonObject> out = new ArrayList<>(array.size());
		for (JsonElement element : array)
		{
			out.add(element.getAsJsonObject());
		}
		if (out.isEmpty())
		{
			throw new IllegalStateException("Fixture section '" + section + "' is empty");
		}
		return out;
	}

	static List<String> messages(String section)
	{
		final JsonArray array = ROOT.getAsJsonArray(section);
		final List<String> out = new ArrayList<>(array.size());
		for (JsonElement element : array)
		{
			out.add(element.getAsString());
		}
		if (out.isEmpty())
		{
			throw new IllegalStateException("Fixture section '" + section + "' is empty");
		}
		return out;
	}

	/** Reads a field that may be absent or explicitly null. */
	static String optionalString(JsonObject o, String key)
	{
		final JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	static Long optionalLong(JsonObject o, String key)
	{
		final JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsLong();
	}
}
