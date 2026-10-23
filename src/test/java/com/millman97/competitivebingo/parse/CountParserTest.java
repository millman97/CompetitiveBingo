package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.MetricType;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CountParserTest
{
	@Test
	public void parsesEveryFixtureMessage()
	{
		final Locale original = Locale.getDefault();
		try
		{
			Locale.setDefault(new Locale("tr", "TR"));
			for (JsonObject fixture : ChatFixtures.cases("count"))
			{
				final String message = fixture.get("message").getAsString();
				final Optional<CountParser.ParsedCount> parsed =
					CountParser.parse(ChatText.sanitise(message));

				assertTrue("Should have parsed: " + message, parsed.isPresent());

				assertEquals("activity for: " + message,
					fixture.get("activity").getAsString(), parsed.get().getActivity().getFull());

				assertEquals("count for: " + message,
					fixture.get("count").getAsLong(), parsed.get().getCount());

				assertEquals("metric ID for: " + message,
					fixture.get("metricId").getAsString(),
					MetricIds.of(MetricType.COUNT, parsed.get().getActivity().getFull()));
			}
		}
		finally { Locale.setDefault(original); }
	}

	@Test
	public void ignoresMessagesThatAreNotCounts()
	{
		for (String message : ChatFixtures.messages("countNoMatch"))
		{
			assertFalse("Should not have parsed: " + message,
				CountParser.parse(ChatText.sanitise(message)).isPresent());
		}
	}

}
