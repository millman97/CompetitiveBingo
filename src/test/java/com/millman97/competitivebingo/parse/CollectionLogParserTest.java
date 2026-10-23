package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.MetricType;
import com.google.gson.JsonObject;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CollectionLogParserTest
{
	@Test
	public void parsesEveryFixtureMessage()
	{
		for (JsonObject fixture : ChatFixtures.cases("collectionLog"))
		{
			final String message = fixture.get("message").getAsString();
			final Optional<String> parsed = CollectionLogParser.parse(ChatText.sanitise(message));

			assertTrue("Should have parsed: " + message, parsed.isPresent());
			assertEquals("item name for: " + message,
				fixture.get("itemName").getAsString(), parsed.get());
			assertEquals("metric ID for: " + message,
				fixture.get("metricId").getAsString(),
				MetricIds.of(MetricType.COLLECTION_LOG, parsed.get()));
		}
	}

	@Test
	public void ignoresMessagesThatAreNotCollectionLogEntries()
	{
		for (String message : ChatFixtures.messages("collectionLogNoMatch"))
		{
			assertFalse("Should not have parsed: " + message,
				CollectionLogParser.parse(ChatText.sanitise(message)).isPresent());
		}
	}

}
