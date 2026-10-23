package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.CompetitiveBingoConfig;
import com.millman97.competitivebingo.SessionState;
import com.millman97.competitivebingo.model.BingoGameState;
import com.millman97.competitivebingo.model.BoardView;
import com.millman97.competitivebingo.model.DropValueRule;
import com.millman97.competitivebingo.model.EventEnvelope;
import com.millman97.competitivebingo.model.GameConfig;
import com.millman97.competitivebingo.model.MetricType;
import com.millman97.competitivebingo.model.Protocol;
import com.millman97.competitivebingo.model.ReadinessReport;
import com.millman97.competitivebingo.model.SyncRequest;
import com.millman97.competitivebingo.model.SyncResponse;
import com.millman97.competitivebingo.parse.DropValueRules;
import com.millman97.competitivebingo.parse.MetricFilter;
import com.millman97.competitivebingo.parse.MetricIds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import okhttp3.HttpUrl;

/** Serializes requests and binds callbacks to the connection that requested them. */
@Slf4j
@Singleton
public class SyncService
{
	interface ClientTasks
	{
		void invoke(Runnable task);
		List<ReadinessCheck> readiness(MetricFilter filter, @Nullable List<String> blockedWorlds);
		Integer hopTarget(GameConfig game);
	}

	private final BingoApi api;
	private final ResumeStore resumes;
	private final CompetitiveBingoConfig config;
	private final ClientTasks clientTasks;
	private volatile ScheduledExecutorService executor;
	private volatile Connection connection;
	private volatile long generation;
	private volatile boolean running;
	private volatile boolean loggedIn;
	private volatile boolean locallyReady;
	private volatile SyncStatus status = SyncStatus.notConfigured();
	private ScheduledFuture<?> lobbyWatch;
	private Consumer<SyncStatus> statusListener = s -> { };
	private Consumer<SentEvent> eventListener = e -> { };

	@Inject
	public SyncService(BingoApi api, ResumeStore resumes, ReadinessChecks checks, WorldHop worldHop,
		CompetitiveBingoConfig config, ClientThread clientThread)
	{
		this(api, resumes, config, new ClientTasks()
		{
			@Override
			public void invoke(Runnable task)
			{
				clientThread.invoke(task);
			}

			@Override
			public List<ReadinessCheck> readiness(MetricFilter filter, List<String> blockedWorlds)
			{
				return checks.evaluate(filter, blockedWorlds);
			}

			@Override
			public Integer hopTarget(GameConfig game)
			{
				return worldHop.targetFor(game);
			}
		});
	}

	SyncService(BingoApi api, ResumeStore resumes, CompetitiveBingoConfig config, ClientTasks clientTasks)
	{
		this.api = api;
		this.resumes = resumes;
		this.config = config;
		this.clientTasks = clientTasks;
	}

	public void setStatusListener(Consumer<SyncStatus> listener)
	{
		statusListener = listener;
	}

	public void setEventListener(Consumer<SentEvent> listener)
	{
		eventListener = listener;
	}

	public void startUp()
	{
		startUp(Executors.newSingleThreadScheduledExecutor(task -> {
			final Thread thread = new Thread(task, "competitive-bingo");
			thread.setDaemon(true);
			return thread;
		}));
	}

	synchronized void startUp(ScheduledExecutorService worker)
	{
		executor = worker;
		running = true;
		resetConnection();
		publishInitialStatus();
	}

	public synchronized void shutDown()
	{
		running = false;
		loggedIn = false;
		resetConnection();
		final ScheduledExecutorService worker = executor;
		executor = null;
		if (worker != null)
		{
			worker.shutdownNow();
		}
	}

	private synchronized long resetConnection()
	{
		generation++;
		api.setEnabled(false);
		cancelLobbyWatch();
		final Connection previous = connection;
		connection = null;
		locallyReady = false;
		if (previous != null)
		{
			previous.sender.stop();
		}
		api.setEnabled(running);
		return generation;
	}

	public synchronized void configurationChanged()
	{
		resetConnection();
		publishInitialStatus();
	}

	private void publishInitialStatus()
	{
		publish(config.serverUrl().trim().isEmpty() || config.joinCode().trim().isEmpty()
			? SyncStatus.notConfigured() : SyncStatus.readyToSync());
	}

	public synchronized void setLoggedIn(boolean loggedIn)
	{
		this.loggedIn = loggedIn;
		if (!loggedIn)
		{
			locallyReady = false;
			if (connection == null && status.getConnection() == SyncStatus.Connection.WORKING)
			{
				resetConnection();
				publishInitialStatus();
			}
			if (connection != null && connection.failure == null)
			{
				publish(status.withConnection(SyncStatus.Connection.BLOCKED, "Log in to resume reporting."));
			}
		}
	}

	public void resumeIfPossible(@Nullable SessionState session)
	{
		if (session == null || !running || !loggedIn)
		{
			return;
		}
		final long expected = generation;
		execute(expected, () -> {
			try
			{
				final String url = BingoApi.parseBaseUrl(config.serverUrl()).toString();
				final String code = config.joinCode().trim();
				final Connection current = connection;
				if (current != null && current.failure == null
					&& current.session.getAccountHash().equals(session.getAccountHash())
					&& current.url.toString().equals(url) && current.joinCode.equals(code))
				{
					if (current.game.getState() == BingoGameState.UPCOMING
						&& current.game.getStartsAt() <= System.currentTimeMillis())
					{
						sync(session);
					}
					return;
				}
				if (resumes.canResume(session.getAccountHash(), url, code))
				{
					sync(session);
				}
			}
			catch (ApiException e)
			{
				log.debug("Cannot resume: {}", e.getMessage());
			}
		});
	}

	public synchronized void leaveGame()
	{
		final Connection current = connection;
		resetConnection();
		if (current != null)
		{
			resumes.forgetGame(current.session.getAccountHash());
		}
		publishInitialStatus();
	}

	public synchronized void forgetCurrentConnection()
	{
		resetConnection();
		publishInitialStatus();
	}

	public synchronized void sync(@Nullable SessionState session)
	{
		if (!running)
		{
			return;
		}
		final long expected = resetConnection();
		if (session == null || !loggedIn)
		{
			publish(SyncStatus.error("Log in first — your account is what joins the game."));
			return;
		}
		final String url = config.serverUrl().trim();
		final String code = config.joinCode().trim();
		if (url.isEmpty() || code.isEmpty())
		{
			publish(SyncStatus.notConfigured());
			return;
		}
		publish(SyncStatus.working("Joining game..."));
		execute(expected, () -> doSync(expected, url, code, session));
	}

	private void doSync(long expected, String address, String code, SessionState session)
	{
		try
		{
			final HttpUrl url = BingoApi.parseBaseUrl(address);
			if (!isCurrent(expected) || !loggedIn) { return; }
			final GameConfig game = api.fetchGame(url, code);
			ResponseValidation.game(game);
			final DropValueRules rules = DropValueRules.of(game.getDropValueRules());
			final MetricFilter filter = MetricFilter.of(game.getTrackedMetrics(), rules.metricIds());
			if (!isCurrent(expected) || !loggedIn)
			{
				return;
			}
			if (game.getState() == BingoGameState.ENDED)
			{
				throw ApiException.client(410, null);
			}
			final SyncResponse response = api.sync(url, new SyncRequest(code, session.getAccountHash(),
				session.getDisplayName(), Protocol.PLUGIN_VERSION, Protocol.SCHEMA_VERSION));
			ResponseValidation.sync(response, game);
			if (!isCurrent(expected))
			{
				return;
			}
			final ScheduledExecutorService worker = executor;
			if (worker == null)
			{
				return;
			}
			final EventSender sender = new EventSender(api, worker, url, response.getPlayerToken(),
				event -> eventOutcome(expected, event),
				error -> fatal(expected, session.getAccountHash(), error));
			final Connection next = new Connection(expected, url, code, session, game, response, sender, rules, filter);
			synchronized (this)
			{
				if (!isCurrent(expected))
				{
					sender.stop();
					return;
				}
				resumes.rememberGame(session.getAccountHash(), url.toString(), code, response.getTokenExpiresAt());
				connection = next;
			}
			next.board = loadBoard(next);
			if (isCurrent(expected))
			{
				watchLobby(next);
				recheckReadiness();
			}
		}
		catch (ApiException e)
		{
			fatal(expected, session.getAccountHash(), e);
		}
		catch (IllegalArgumentException e)
		{
			fatal(expected, session.getAccountHash(), ApiException.malformed(e.getMessage(), e));
		}
	}

	@Nullable
	private BoardView loadBoard(Connection current)
	{
		try
		{
			return api.fetchBoard(current.url, current.response.getPlayerToken());
		}
		catch (ApiException e)
		{
			log.debug("Could not load the board: {}", e.getMessage());
			return null;
		}
	}

	public synchronized void refreshBoard()
	{
		final Connection current = connection;
		if (current == null || !running || current.failure != null)
		{
			publish(status);
			return;
		}
		execute(current.generation, () -> {
			final BoardView board = loadBoard(current);
			synchronized (this)
			{
				if (isCurrent(current.generation))
				{
					current.board = board;
					publish(statusFor(current));
				}
			}
		});
	}

	public synchronized void recheckReadiness()
	{
		final Connection current = connection;
		if (current == null || !running || current.failure != null || !loggedIn)
		{
			return;
		}
		locallyReady = false;
		final long readinessGeneration = ++current.readinessGeneration;
		publish(status.withConnection(SyncStatus.Connection.BLOCKED, "Checking readiness..."));
		clientTasks.invoke(() -> {
			if (!isCurrent(current.generation) || !loggedIn || current.readinessGeneration != readinessGeneration)
			{
				return;
			}
			final List<ReadinessCheck> checks = clientTasks.readiness(current.filter, current.game.getBlockedWorldTypes());
			final Integer hop = clientTasks.hopTarget(current.game);
			execute(current.generation, () -> {
				synchronized (this)
				{
					if (!isCurrent(current.generation) || current.failure != null || !loggedIn
						|| current.readinessGeneration != readinessGeneration)
					{
						return;
					}
					current.readiness = Collections.unmodifiableList(new ArrayList<>(checks));
					current.hopWorld = hop;
					current.checked = true;
					locallyReady = ReadinessChecks.allSatisfied(checks);
					publish(statusFor(current));
				}
				reportReadiness(current);
			});
		});
	}

	private synchronized void watchLobby(Connection current)
	{
		if (!isCurrent(current.generation) || current.game.getState() != BingoGameState.UPCOMING)
		{
			return;
		}
		final long delay = current.game.getStartsAt() - System.currentTimeMillis();
		if (delay <= 0)
		{
			current.failure = "This game is overdue but has not started. Ask your organiser, then press Re-sync.";
			publish(statusFor(current));
			return;
		}
		lobbyWatch = executor.schedule(() -> {
			if (isCurrent(current.generation) && loggedIn)
			{
				sync(current.session);
			}
		}, delay, TimeUnit.MILLISECONDS);
	}

	private synchronized void cancelLobbyWatch()
	{
		if (lobbyWatch != null)
		{
			lobbyWatch.cancel(false);
			lobbyWatch = null;
		}
	}

	private SyncStatus statusFor(Connection current)
	{
		final GameConfig game = current.game;
		final SyncStatus.Connection state;
		final String message;
		if (current.failure != null)
		{
			state = SyncStatus.Connection.ERROR;
			message = current.failure;
		}
		else if (game.getState() != BingoGameState.LIVE)
		{
			state = SyncStatus.Connection.BLOCKED;
			message = game.getState() == BingoGameState.UPCOMING ? "This game has not started yet."
				: game.getState() == BingoGameState.LOCKED ? "An admin has locked this game. Reporting is paused."
				: "This game has ended. Nothing more will be sent.";
		}
		else if (!loggedIn || !current.checked || !locallyReady)
		{
			state = SyncStatus.Connection.BLOCKED;
			message = loggedIn ? "Fix the checks below — nothing is being reported." : "Log in to resume reporting.";
		}
		else
		{
			state = SyncStatus.Connection.LIVE;
			message = current.response.getTeamName() == null ? "Reporting. You are not on a team yet."
				: "Reporting for " + current.response.getTeamName() + ".";
		}
		return gameStatus(game, current.rules, current.board)
			.connection(state).message(message).teamName(current.response.getTeamName())
			.readiness(current.readiness).hopWorld(current.hopWorld)
			.screenshotsEnabled(config.enableScreenshots() && game.isScreenshotsRequested()).build();
	}

	private static SyncStatus.SyncStatusBuilder gameStatus(GameConfig game, DropValueRules rules, @Nullable BoardView board)
	{
		final Map<String, String> labels = new LinkedHashMap<>();
		final List<TrackedTarget> targets = new ArrayList<>();
		for (String id : game.getTrackedMetrics())
		{
			final String label = game.getMetricLabels() == null ? null : game.getMetricLabels().get(id);
			final String display = label == null || label.isEmpty() ? id : label;
			labels.put(id, display);
			targets.add(new TrackedTarget(id, display, game.getMetricTargets() == null ? null
				: game.getMetricTargets().get(id), MetricIds.typeOf(id)));
		}
		for (DropValueRule rule : rules.asList())
		{
			final String id = rule.getMetricId();
			final String label = rule.getLabel() == null || rule.getLabel().isEmpty() ? id : rule.getLabel();
			labels.put(id, label);
			if (!game.getTrackedMetrics().contains(id))
			{
				targets.add(new TrackedTarget(id, label, game.getMetricTargets() == null ? null
					: game.getMetricTargets().get(id), MetricType.DROP));
			}
		}
		return SyncStatus.builder().gameId(game.getGameId()).gameName(game.getName()).gameState(game.getState())
			.startsAt(game.getStartsAt()).endsAt(game.getEndsAt()).startWorld(game.getStartWorld())
			.metricLabels(Collections.unmodifiableMap(labels)).trackedTargets(Collections.unmodifiableList(targets))
			.board(board).manualEntryAllowed(!Boolean.FALSE.equals(game.getManualEntryAllowed()));
	}

	public synchronized boolean send(EventEnvelope envelope, long expected)
	{
		final Connection current = connection;
		if (!isCurrent(expected) || current == null || current.failure != null || !status.isEmitting()
			|| !current.game.getGameId().equals(envelope.getGameId())
			|| !current.session.getAccountHash().equals(envelope.getAccountHash()))
		{
			return false;
		}
		eventListener.accept(SentEvent.pending(envelope.withScreenshot(null, null)));
		return current.sender.submit(envelope);
	}

	private void reportReadiness(Connection current)
	{
		if (!isCurrent(current.generation) || current.failure != null)
		{
			return;
		}
		final List<ReadinessReport.CheckState> checks = new ArrayList<>();
		final StringBuilder signature = new StringBuilder();
		for (ReadinessCheck check : current.readiness)
		{
			checks.add(new ReadinessReport.CheckState(check.getId().name(), check.isSatisfied()));
			signature.append(check.getId()).append('=').append(check.isSatisfied()).append('|');
		}
		if (signature.toString().equals(current.lastReadiness))
		{
			return;
		}
		try
		{
			api.postReady(current.url, current.response.getPlayerToken(),
				new ReadinessReport(ReadinessChecks.allSatisfied(current.readiness), checks));
			current.lastReadiness = signature.toString();
		}
		catch (ApiException e)
		{
			if (e.getStatus() == 401 || e.getStatus() == 403 || e.getStatus() == 409 || e.getStatus() == 410)
			{
				fatal(current.generation, current.session.getAccountHash(), e);
			}
			else
			{
				log.debug("Could not report readiness: {}", e.getMessage());
			}
		}
	}

	private synchronized void fatal(long expected, String account, ApiException error)
	{
		if (!isCurrent(expected))
		{
			return;
		}
		if (error.getStatus() == 401 || error.getStatus() == 403 || error.getStatus() == 409 || error.getStatus() == 410)
		{
			resumes.forgetGame(account);
		}
		cancelLobbyWatch();
		locallyReady = false;
		final Connection current = connection;
		if (current != null)
		{
			current.failure = error.playerMessage();
			current.sender.stop();
			publish(statusFor(current));
		}
		else
		{
			publish(SyncStatus.error(error.playerMessage()));
		}
		log.debug("Reporting stopped: {}", error.playerMessage());
	}

	private synchronized void eventOutcome(long expected, SentEvent event)
	{
		if (isCurrent(expected))
		{
			eventListener.accept(event);
		}
	}

	private void execute(long expected, Runnable task)
	{
		final ScheduledExecutorService worker = executor;
		if (worker == null || !isCurrent(expected))
		{
			return;
		}
		try
		{
			final long requestGeneration = api.getRequestGeneration();
			worker.execute(() -> {
				if (isCurrent(expected))
				{
					api.withGeneration(requestGeneration, task);
				}
			});
		}
		catch (RejectedExecutionException e)
		{
			if (!worker.isShutdown())
			{
				throw e;
			}
		}
	}

	private boolean isCurrent(long expected)
	{
		return running && generation == expected;
	}

	private void publish(SyncStatus next)
	{
		status = next;
		statusListener.accept(next);
	}

	public long getGeneration()
	{
		return generation;
	}

	public SyncStatus getStatus()
	{
		return status;
	}

	public boolean isEmitting()
	{
		return running && loggedIn && locallyReady && status.isEmitting();
	}

	public String getGameId()
	{
		final Connection current = connection;
		return current == null ? null : current.game.getGameId();
	}

	public boolean isScreenshotsRequested()
	{
		final Connection current = connection;
		return current != null && current.game.isScreenshotsRequested();
	}

	public MetricFilter getFilter()
	{
		final Connection current = connection;
		return current == null ? MetricFilter.empty() : current.filter;
	}

	public DropValueRules getDropValueRules()
	{
		final Connection current = connection;
		return current == null ? DropValueRules.empty() : current.rules;
	}

	static boolean isOlder(String version, String minimum)
	{
		if (version == null || minimum == null || !version.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,2}")
			|| !minimum.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,2}"))
		{
			throw new IllegalArgumentException("Invalid numeric version.");
		}
		final String[] left = version.split("\\.");
		final String[] right = minimum.split("\\.");
		for (int index = 0; index < Math.max(left.length, right.length); index++)
		{
			final int a = index < left.length ? Integer.parseInt(left[index]) : 0;
			final int b = index < right.length ? Integer.parseInt(right[index]) : 0;
			if (a != b)
			{
				return a < b;
			}
		}
		return false;
	}

	private static final class Connection
	{
		private final long generation;
		private final HttpUrl url;
		private final String joinCode;
		private final SessionState session;
		private final GameConfig game;
		private final SyncResponse response;
		private final EventSender sender;
		private final DropValueRules rules;
		private final MetricFilter filter;
		private volatile String failure;
		private volatile long readinessGeneration;
		private List<ReadinessCheck> readiness = Collections.emptyList();
		private boolean checked;
		private Integer hopWorld;
		private BoardView board;
		private String lastReadiness;

		private Connection(long generation, HttpUrl url, String joinCode, SessionState session,
			GameConfig game, SyncResponse response, EventSender sender, DropValueRules rules, MetricFilter filter)
		{
			this.generation = generation;
			this.url = url;
			this.joinCode = joinCode;
			this.session = session;
			this.game = game;
			this.response = response;
			this.sender = sender;
			this.rules = rules;
			this.filter = filter;
		}
	}
}
