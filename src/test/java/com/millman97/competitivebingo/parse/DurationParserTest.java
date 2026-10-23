package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.MetricType;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DurationParserTest
{
	@Test
	public void parsesEveryFixtureMessage()
	{
		for (JsonObject fixture : ChatFixtures.cases("duration"))
		{
			final String message = fixture.get("message").getAsString();
			final List<DurationParser.ParsedDuration> parsed =
				DurationParser.parse(ChatText.sanitise(message));

			assertFalse("Should have parsed: " + message, parsed.isEmpty());
			final DurationParser.ParsedDuration first = parsed.get(0);

			assertEquals("durationMs for: " + message,
				fixture.get("durationMs").getAsLong(), first.getDurationMs());

			assertEquals("personalBest for: " + message,
				fixture.get("personalBest").getAsBoolean(), first.isPersonalBest());

			assertEquals("personalBestMs for: " + message,
				ChatFixtures.optionalLong(fixture, "personalBestMs"), first.getPersonalBestMs());

			final String expectedActivity = ChatFixtures.optionalString(fixture, "activity");
			if (expectedActivity == null)
			{
				assertNull("Should be anonymous: " + message, first.getActivity());
			}
			else
			{
				assertNotNull("Should name its activity: " + message, first.getActivity());
				assertEquals("activity base for: " + message,
					expectedActivity, first.getActivity().getBase());
				assertEquals("variant for: " + message,
					ChatFixtures.optionalString(fixture, "variant"), first.getActivity().getVariant());
				assertEquals("metric ID for: " + message,
					fixture.get("metricId").getAsString(),
					MetricIds.of(MetricType.DURATION, first.getActivity().getFull()));
			}
		}
	}

	@Test
	public void ignoresMessagesThatAreNotDurations()
	{
		for (String message : ChatFixtures.messages("durationNoMatch"))
		{
			assertTrue("Should not have parsed: " + message,
				DurationParser.parse(ChatText.sanitise(message)).isEmpty());
		}
	}

	@Test
	public void theatreOfBloodReportsTheRaidAndNamedCompletedWave()
	{
		final List<DurationParser.ParsedDuration> parsed = DurationParser.parse(ChatText.sanitise(
			"Wave 'The Final Challenge' (Normal Mode) complete!<br>Duration: 7:42.60<br>"
				+ "Theatre of Blood completion time: 21:33.60 (new personal best)"));

		assertEquals(2, parsed.size());

		final DurationParser.ParsedDuration raid = parsed.get(0);
		assertNotNull(raid.getActivity());
		assertEquals("Theatre of Blood", raid.getActivity().getFull());
		assertEquals(1293600L, raid.getDurationMs());

		final DurationParser.ParsedDuration wave = parsed.get(1);
		assertEquals("Theatre of Blood - The Final Challenge", wave.getActivity().getBase());
		assertEquals("Normal Mode", wave.getActivity().getVariant());
		assertEquals(462600L, wave.getDurationMs());
		assertEquals("time.theatre_of_blood_the_final_challenge_normal_mode",
			MetricIds.of(MetricType.DURATION, wave.getActivity().getFull()));
	}

	@Test
	public void hallowedSepulchreReportsFloorAndOverallTogether()
	{
		final List<DurationParser.ParsedDuration> parsed = DurationParser.parse(ChatText.sanitise(
			"Floor 5 time: <col=ff0000>1:30</col> (new personal best)<br>"
				+ "Overall time: <col=ff0000>5:20</col>. Personal best: 5:10"));

		assertEquals(2, parsed.size());
		assertEquals("Hallowed Sepulchre Floor 5", parsed.get(0).getActivity().getFull());
		assertEquals(90000L, parsed.get(0).getDurationMs());
		assertEquals("Hallowed Sepulchre", parsed.get(1).getActivity().getFull());
		assertEquals(320000L, parsed.get(1).getDurationMs());
		assertEquals(Long.valueOf(310000L), parsed.get(1).getPersonalBestMs());
	}

}
