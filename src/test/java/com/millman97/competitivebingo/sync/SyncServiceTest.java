package com.millman97.competitivebingo.sync;

import com.google.gson.Gson;
import com.millman97.competitivebingo.CompetitiveBingoConfig;
import com.millman97.competitivebingo.SessionState;
import com.millman97.competitivebingo.model.BoardView;
import com.millman97.competitivebingo.model.EventBatchResponse;
import com.millman97.competitivebingo.model.EventEnvelope;
import com.millman97.competitivebingo.model.EventResult;
import com.millman97.competitivebingo.model.GameConfig;
import com.millman97.competitivebingo.model.MetricObservation;
import com.millman97.competitivebingo.model.MetricType;
import com.millman97.competitivebingo.model.ReadinessReport;
import com.millman97.competitivebingo.model.SyncRequest;
import com.millman97.competitivebingo.model.SyncResponse;
import com.millman97.competitivebingo.parse.MetricFilter;
import com.millman97.competitivebingo.testing.ManualExecutor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import okhttp3.HttpUrl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyncServiceTest
{
	private final ManualExecutor worker = new ManualExecutor();
	private final ManualExecutor client = new ManualExecutor();
	private final TestConfig config = new TestConfig();
	private final FakeApi api = new FakeApi();
	private final SessionState session = new SessionState(123, "Tester");
	private ResumeStore resumes;
	private SyncService service;

	@Before
	public void setUp()
	{
		final Map<String, String> storage = new HashMap<>();
		resumes = new ResumeStore(new ResumeStore.Storage()
		{
			public String get(String key) { return storage.get(key); }
			public void set(String key, String value) { storage.put(key, value); }
			public void unset(String key) { storage.remove(key); }
		});
		service = new SyncService(api, resumes, config, new SyncService.ClientTasks()
		{
			public void invoke(Runnable task) { client.execute(task); }
			public List<ReadinessCheck> readiness(MetricFilter filter, List<String> worlds)
			{
				return Collections.singletonList(ReadinessCheck.ok(ReadinessCheck.Id.WORLD_TYPE, "World"));
			}
			public Integer hopTarget(GameConfig game) { return null; }
		});
		service.startUp(worker);
		service.setLoggedIn(true);
	}

	@After
	public void tearDown()
	{
		service.shutDown();
		client.shutdownNow();
	}

	@Test
	public void shutdownSuppressesHandshakeResumeBoardAndReadiness()
	{
		resumes.rememberGame("123", config.url, "code", System.currentTimeMillis() + 60_000);
		service.shutDown();
		service.sync(session);
		service.resumeIfPossible(session);
		service.refreshBoard();
		service.recheckReadiness();
		settle();
		assertEquals(0, api.requests);
		assertFalse(service.isEmitting());
	}

	@Test
	public void leaveInvalidatesAClientThreadReadinessAnswer()
	{
		service.sync(session);
		worker.runReady();
		service.leaveGame();
		settle();
		assertEquals(SyncStatus.Connection.READY_TO_SYNC, service.getStatus().getConnection());
		assertFalse(service.isEmitting());
		assertFalse(resumes.canResume("123", config.url, "code"));
	}

	@Test
	public void fatalTokenErrorCannotBeClearedByReadinessOrBoardRefresh()
	{
		connect();
		api.eventFailure = ApiException.client(401, null);
		assertTrue(service.send(event(), service.getGeneration()));
		worker.advanceMillis(600);
		assertEquals(SyncStatus.Connection.ERROR, service.getStatus().getConnection());
		service.recheckReadiness();
		service.refreshBoard();
		settle();
		assertEquals(SyncStatus.Connection.ERROR, service.getStatus().getConnection());
		assertFalse(service.isEmitting());
	}

	@Test
	public void destinationChangeDiscardsRetriesAndRejectsOldCaptureCallbacks()
	{
		connect();
		final long original = service.getGeneration();
		api.eventFailure = ApiException.serverError(503, null);
		service.send(event(), original);
		worker.advanceMillis(600);
		config.url = "https://other.example/";
		service.configurationChanged();
		connect();
		api.eventFailure = null;
		assertFalse(service.send(event(), original));
		worker.advanceMillis(10_000);
		assertEquals(Collections.singletonList("https://bingo.example/"), api.destinations);
	}

	@Test
	public void configurationChangeCancelsRetriesAndWaitsForExplicitSync()
	{
		connect();
		api.eventFailure = ApiException.serverError(503, null);
		service.send(event(), service.getGeneration());
		worker.advanceMillis(600);
		service.configurationChanged();
		final int requests = api.requests;
		worker.advanceMillis(10_000);
		assertEquals(requests, api.requests);
		settle();
		assertEquals(requests, api.requests);
		assertFalse(service.isEmitting());
	}

	@Test
	public void terminalHandshakeRejectionsForgetTheSavedGame()
	{
		for (int status : new int[]{403, 409, 410})
		{
			resumes.rememberGame("123", config.url, "code", System.currentTimeMillis() + 60_000);
			api.fetchFailure = ApiException.client(status, null);
			service.sync(session);
			settle();
			assertFalse(resumes.canResume("123", config.url, "code"));
			final int requests = api.requests;
			service.resumeIfPossible(session);
			settle();
			assertEquals(requests, api.requests);
		}
	}

	@Test
	public void unknownGameStateFailsBeforeTheSyncPost()
	{
		api.game = new Gson().fromJson(gameJson().replace("LIVE", "FUTURE_STATE"), GameConfig.class);
		service.sync(session);
		settle();
		assertEquals(0, api.syncPosts);
		assertEquals(SyncStatus.Connection.ERROR, service.getStatus().getConnection());
	}

	@Test
	public void invalidMetricsAndRulesFailBeforeTheSyncPost()
	{
		for (String json : new String[]{
			gameJson().replace("kc.vorkath", "kc.*"),
			gameJson().replace("\"screenshotsRequested\":false", "\"dropValueRules\":[null],\"screenshotsRequested\":false"),
			gameJson().replace("\"screenshotsRequested\":false", "\"dropValueRules\":[{\"metricId\":\"drop.value\",\"minValue\":2,\"maxValue\":1}],\"screenshotsRequested\":false")
		})
		{
			api.game = new Gson().fromJson(json, GameConfig.class);
			service.sync(session);
			settle();
			assertEquals(0, api.syncPosts);
			assertEquals(SyncStatus.Connection.ERROR, service.getStatus().getConnection());
			assertFalse(service.isEmitting());
		}
	}

	@Test
	public void wrongGameInSyncResponseDoesNotGrantResumePermission()
	{
		api.wrongGame = true;
		service.sync(session);
		settle();
		assertFalse(resumes.canResume("123", config.url, "code"));
		assertFalse(service.isEmitting());
	}

	@Test
	public void cancellationDuringFetchPreventsTheNextEndpoint()
	{
		api.afterFetch = service::leaveGame;
		service.sync(session);
		settle();
		assertEquals(0, api.syncPosts);
		assertFalse(service.isEmitting());
	}

	@Test
	public void logoutBlocksNewEventsAndLateReadinessCannotUndoIt()
	{
		connect();
		service.recheckReadiness();
		service.setLoggedIn(false);
		settle();
		assertFalse(service.isEmitting());
		assertFalse(service.send(event(), service.getGeneration()));
	}

	private void connect()
	{
		service.sync(session);
		settle();
		assertTrue(service.getStatus().getMessage(), service.isEmitting());
	}

	private void settle()
	{
		worker.runReady();
		client.runReady();
		worker.runReady();
	}

	private EventEnvelope event()
	{
		return EventEnvelope.builder().eventId("event").gameId("game").accountHash("123")
			.metric(new MetricObservation("kc.vorkath", MetricType.COUNT, 1L, 1L, true)).build();
	}

	private static String gameJson()
	{
		return "{\"gameId\":\"game\",\"name\":\"Test game\",\"state\":\"LIVE\",\"startsAt\":0,"
			+ "\"endsAt\":4102444800000,\"schemaVersion\":1,\"minPluginVersion\":\"0.0.0\","
			+ "\"trackedMetrics\":[\"kc.vorkath\"],\"screenshotsRequested\":false}";
	}

	private static class TestConfig implements CompetitiveBingoConfig
	{
		private String url = "https://bingo.example/";
		public String serverUrl() { return url; }
		public String joinCode() { return "code"; }
	}

	private static class FakeApi extends BingoApi
	{
		private GameConfig game = new Gson().fromJson(gameJson(), GameConfig.class);
		private int requests;
		private int syncPosts;
		private boolean wrongGame;
		private ApiException fetchFailure;
		private ApiException eventFailure;
		private Runnable afterFetch = () -> { };
		private final List<String> destinations = new ArrayList<>();
		private FakeApi() { super(null, new Gson()); }
		@Override
		public GameConfig fetchGame(HttpUrl url, String code) throws ApiException
		{
			requests++;
			afterFetch.run();
			if (fetchFailure != null) { throw fetchFailure; }
			return game;
		}
		@Override
		public SyncResponse sync(HttpUrl url, SyncRequest request)
		{
			requests++;
			syncPosts++;
			return new SyncResponse("token", wrongGame ? "wrong" : "game", "Team", System.currentTimeMillis() + 60_000);
		}
		@Override
		public BoardView fetchBoard(HttpUrl url, String token) { requests++; return null; }
		@Override
		public void postReady(HttpUrl url, String token, ReadinessReport readiness) { requests++; }
		@Override
		public EventBatchResponse postEvents(HttpUrl url, String token, List<EventEnvelope> events) throws ApiException
		{
			requests++;
			destinations.add(url.toString());
			if (eventFailure != null) { throw eventFailure; }
			return new EventBatchResponse(events.stream().map(event -> new EventResult(event.getEventId(), true, false, null)).collect(Collectors.toList()));
		}
	}
}
