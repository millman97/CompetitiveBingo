package com.millman97.competitivebingo.sync;

import com.google.gson.Gson;
import com.millman97.competitivebingo.model.EventBatchResponse;
import com.millman97.competitivebingo.model.EventEnvelope;
import com.millman97.competitivebingo.model.EventResult;
import com.millman97.competitivebingo.model.MetricObservation;
import com.millman97.competitivebingo.model.MetricType;
import com.millman97.competitivebingo.testing.ManualExecutor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.HttpUrl;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventSenderTest
{
	private final ManualExecutor worker = new ManualExecutor();
	private final FakeApi api = new FakeApi();
	private final List<SentEvent> outcomes = new ArrayList<>();
	private final List<ApiException> fatals = new ArrayList<>();
	private final EventSender sender = new EventSender(api, worker, HttpUrl.get("https://mock.invalid/"),
		"token", outcomes::add, fatals::add, 20, 100);

	@After
	public void tearDown() { worker.shutdownNow(); }

	@Test
	public void batchingPreservesOrderAndMatchesReorderedResultsById()
	{
		api.rejectedId = "2";
		api.rejection = "NOT_TRACKED";
		assertTrue(sender.submit(event(1)));
		assertTrue(sender.submit(event(2)));
		flush();
		assertEquals(Collections.singletonList(2), api.batchSizes);
		assertEquals("1", outcomes.get(0).getEnvelope().getEventId());
		assertEquals(SentEvent.Outcome.SENT, outcomes.get(0).getOutcome());
		assertEquals("2", outcomes.get(1).getEnvelope().getEventId());
		assertEquals(SentEvent.Outcome.REJECTED, outcomes.get(1).getOutcome());
	}

	@Test
	public void transientFailuresRetryTheSameEventAndRecover()
	{
		api.failure = ApiException.serverError(503, null);
		api.failuresRemaining = 2;
		sender.submit(event(1));
		flush();
		assertEquals(3, api.attempts);
		assertEquals(1, outcomes.size());
		assertEquals(SentEvent.Outcome.SENT, outcomes.get(0).getOutcome());
	}

	@Test
	public void outagesAndRateLimitsCannotRetryForever()
	{
		final ApiException[] failures = { ApiException.serverError(503, null),
			ApiException.rateLimited(0L, null), ApiException.rateLimited(301L, null) };
		final int[] attempts = { 3, 3, 1 };
		for (int index = 0; index < failures.length; index++)
		{
			api.failure = failures[index];
			api.failuresRemaining = Integer.MAX_VALUE;
			api.attempts = 0;
			outcomes.clear();
			sender.submit(event(index));
			flush();
			assertEquals(attempts[index], api.attempts);
			assertEquals(SentEvent.Outcome.FAILED, outcomes.get(0).getOutcome());
			assertTrue(fatals.isEmpty());
		}
	}

	@Test
	public void gameNotLiveRejectionStopsLaterSubmissions()
	{
		api.rejection = "GAME_NOT_LIVE";
		sender.submit(event(1));
		flush();
		assertFalse(sender.submit(event(2)));
		assertEquals(1, api.attempts);
		assertEquals(1, fatals.size());
	}

	@Test
	public void oversizedEventsAndFullQueuesAreRefusedBeforeSending()
	{
		assertFalse(sender.submit(event(1).withScreenshot("A".repeat(2_100_000), "image/jpeg")));
		for (int index = 0; index < 200; index++) { assertTrue(sender.submit(event(index))); }
		assertFalse(sender.submit(event(201)));
		worker.runReady();
		assertEquals(0, api.attempts);
		assertEquals(2, outcomes.size());
		assertTrue(outcomes.stream().allMatch(event -> event.getOutcome() == SentEvent.Outcome.FAILED));
	}

	@Test
	public void payloadLimitSplitsScreenshotBatches()
	{
		final String screenshot = "A".repeat(900_000);
		for (int index = 0; index < 3; index++)
		{
			sender.submit(event(index).withScreenshot(screenshot, "image/jpeg"));
		}
		flush();
		assertEquals(Arrays.asList(2, 1), api.batchSizes);
		assertEquals(3, outcomes.size());
	}

	@Test
	public void stoppingDiscardsPendingWorkAndCannotBeReversed()
	{
		sender.submit(event(1));
		sender.stop();
		assertFalse(sender.submit(event(2)));
		flush();
		assertEquals(0, api.attempts);
		assertEquals(SentEvent.Outcome.FAILED, outcomes.get(0).getOutcome());
	}

	private void flush()
	{
		for (int step = 0; step < 5; step++) { worker.advanceMillis(100); }
	}

	private static EventEnvelope event(int id)
	{
		return EventEnvelope.builder().eventId(Integer.toString(id)).gameId("game").accountHash("123")
			.metric(new MetricObservation("kc.vorkath", MetricType.COUNT, (long) id, 1L, false)).build();
	}

	private static class FakeApi extends BingoApi
	{
		private int attempts;
		private int failuresRemaining;
		private ApiException failure;
		private String rejection;
		private String rejectedId;
		private final List<Integer> batchSizes = new ArrayList<>();

		private FakeApi() { super(null, new Gson()); }

		@Override
		public EventBatchResponse postEvents(HttpUrl url, String token, List<EventEnvelope> events) throws ApiException
		{
			attempts++;
			if (failuresRemaining > 0)
			{
				failuresRemaining--;
				throw failure;
			}
			batchSizes.add(events.size());
			final List<EventResult> results = new ArrayList<>();
			for (EventEnvelope event : events)
			{
				final boolean rejected = rejection != null && (rejectedId == null || rejectedId.equals(event.getEventId()));
				results.add(new EventResult(event.getEventId(), !rejected, false, rejected ? rejection : null));
			}
			Collections.reverse(results);
			return new EventBatchResponse(results);
		}
	}
}
