package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.DurationDetail;
import com.google.gson.Gson;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The pairing behaviour is the part most likely to break silently, because both orders occur in
 * the live game and neither is obviously "the" order.
 */
public class ChatObserverTest
{
	private ChatObserver observer;

	@Before
	public void setUp()
	{
		observer = new ChatObserver(new MetricAliases(new Gson()));
	}

	@Test
	public void aNamedTrialWithoutAPersonalBestStillReportsItsTime()
	{
		observer.onChatMessage("You have completed the Tempor Tantrum and achieved the Marlin rank.", 100);
		final Observation result = observer.onChatMessage("Time: 4:04.", 100).get(0);
		assertEquals("time.tempor_tantrum", result.getMetricId());
		assertEquals(Long.valueOf(244_000), result.getValue());
		assertNull(((DurationDetail) result.getDetail()).getPersonalBestMs());
	}

	@Test
	public void fracturedArchiveReportsItsOwnTotalWithoutClaimingThePreviousBoss()
	{
		observer.onChatMessage("Your Vorkath kill count is: 342.", 100);
		final List<Observation> observations = observer.onChatMessage(
			"The Fractured Archive: Total completion time: 32:10.20. Personal best: 30:00.00", 101);

		assertEquals(1, observations.size());
		assertEquals("time.the_fractured_archive", observations.get(0).getMetricId());
		assertEquals(Long.valueOf(1_930_200), observations.get(0).getValue());
		assertEquals("The Fractured Archive", ((DurationDetail) observations.get(0).getDetail()).getActivity());
	}

	@Test
	public void countThenDurationPairs()
	{
		observer.onChatMessage("Your Vorkath kill count is: 342.", 100);
		final List<Observation> observations =
			observer.onChatMessage("Fight duration: 1:30. Personal best: 1:25", 100);

		assertEquals(1, observations.size());
		assertEquals("time.vorkath", observations.get(0).getMetricId());
		assertEquals(Long.valueOf(90000L), observations.get(0).getValue());
		assertNull("a duration has no meaningful increment", observations.get(0).getDelta());
	}

	@Test
	public void durationThenCountAlsoPairs()
	{
		// Some bosses send the time first. The duration waits for a name.
		final List<Observation> first =
			observer.onChatMessage("Fight duration: 1:30. Personal best: 1:25", 100);
		assertTrue("the unnamed duration should be held, not reported", first.isEmpty());

		final List<Observation> second = observer.onChatMessage("Your Zulrah kill count is: 12.", 100);

		assertEquals(2, second.size());
		assertEquals("kc.zulrah", second.get(0).getMetricId());
		assertEquals("time.zulrah", second.get(1).getMetricId());
		assertEquals(Long.valueOf(90000L), second.get(1).getValue());
	}

	@Test
	public void anUnclaimedDurationExpiresRatherThanAttachingToTheWrongActivity()
	{
		observer.onChatMessage("Fight duration: 1:30. Personal best: 1:25", 100);

		// Far outside the pairing window: a kill a minute later is a different activity.
		final List<Observation> observations = observer.onChatMessage("Your Zulrah kill count is: 12.", 200);

		assertEquals(1, observations.size());
		assertEquals("kc.zulrah", observations.get(0).getMetricId());
	}

	@Test
	public void killCountsAreRememberedForDrops()
	{
		assertNull(observer.lastCountFor("Vorkath"));

		observer.onChatMessage("Your Vorkath kill count is: 342.", 100);

		assertEquals(Integer.valueOf(342), observer.lastCountFor("Vorkath"));
		assertEquals("lookup is case insensitive", Integer.valueOf(342), observer.lastCountFor("vorkath"));
		assertNull(observer.lastCountFor("Zulrah"));
	}

	@Test
	public void resetForgetsEverythingFromThePreviousSession()
	{
		observer.onChatMessage("Your Vorkath kill count is: 342.", 100);
		observer.onChatMessage("Fight duration: 1:30. Personal best: 1:25", 100);

		observer.reset();

		assertNull("a previous session's count must never label a new drop", observer.lastCountFor("Vorkath"));
		assertEquals("no duration should be left waiting", 1,
			observer.onChatMessage("Your Zulrah kill count is: 12.", 100).size());
	}

	@Test
	public void slayerTaskTotalsAreNamedProperly()
	{
		// The message says only "tasks", which would slug to the meaningless kc.tasks.
		assertEquals("kc.slayer_tasks", observer
			.onChatMessage("You've completed 250 tasks; return to a Slayer master.", 100)
			.get(0).getMetricId());

		assertEquals("kc.wilderness_slayer_tasks", observer
			.onChatMessage("You've completed 40 Wilderness tasks; return to Krystilia.", 101)
			.get(0).getMetricId());

		assertEquals("at least N is still N", Long.valueOf(500L), observer
			.onChatMessage("You've completed at least 500 tasks; return to a Slayer master.", 102)
			.get(0).getValue());
	}

	@Test
	public void questSpeedrunCannotBeClaimedByAnUnrelatedCounter()
	{
		assertTrue(observer.onChatMessage("Speedrun duration: 12:34. Personal best: 11:00", 100).isEmpty());
		final List<Observation> observations = observer.onChatMessage(
			"Your Vorkath kill count is: 342.", 101);

		assertEquals(1, observations.size());
		assertEquals("kc.vorkath", observations.get(0).getMetricId());
	}

	@Test
	public void namedMultiTimeMessageEmitsOverallAndCompletedWave()
	{
		final List<Observation> observations = observer.onChatMessage(
			"Wave 'The Final Challenge' (Normal Mode) complete!<br>Duration: 7:42.60<br>"
				+ "Theatre of Blood completion time: 21:33.60 (new personal best)", 100);

		assertEquals(2, observations.size());
		assertEquals("time.theatre_of_blood", observations.get(0).getMetricId());
		assertEquals("time.theatre_of_blood_the_final_challenge_normal_mode",
			observations.get(1).getMetricId());
	}
}
