package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.model.EventBatchResponse;
import com.millman97.competitivebingo.model.EventEnvelope;
import com.millman97.competitivebingo.model.EventResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;

/**
 * Sends events in the order they were observed, one request at a time.
 *
 * Connection failures, timeouts and 5xx are tried three times with exponential backoff.
 * A 429 schedules a bounded resume at the time the server asked for. Repeated rate limits, or an
 * unreasonable requested delay, abandon the batch instead of retaining and retrying it forever.
 * Every other 4xx fails immediately.
 *
 */
@Slf4j
public class EventSender
{
	private static final int MAX_ATTEMPTS = 3;
	private static final int MAX_RATE_LIMIT_RESUMES = 3;
	private static final long MAX_RETRY_AFTER_SECONDS = 5L * 60L;
	private static final long FIRST_BACKOFF_MS = 2_000L;

	/** The contract's batch ceiling. Reached only if events queue up behind an outage. */
	private static final int MAX_BATCH = 50;

	/**
	 * Ceiling on one request body. Screenshots ride inside the envelope, so a batch counted only
	 * by events could otherwise reach tens of megabytes.
	 */
	private static final long MAX_BATCH_BYTES = 2L * 1024 * 1024;

	private static final int MAX_QUEUED_EVENTS = 200;
	private static final long MAX_QUEUED_BYTES = 8L * 1024 * 1024;

	/**
	 * How long a new event waits for others before the batch is sent. One game tick, so a kill
	 * that yields a count, a drop and a personal best goes out as one request.
	 */
	private static final long COALESCE_MS = 600L;

	private final BingoApi api;
	private final ScheduledExecutorService executor;

	/** Protected by this sender's monitor; network I/O never holds it. */
	private final Deque<QueuedEvent> queue = new ArrayDeque<>();
	private long queuedBytes;
	private ScheduledFuture<?> drainTask;
	private final long requestGeneration;
	private boolean inFlight;

	private final Consumer<SentEvent> onOutcome;
	private final Consumer<ApiException> onFatal;

	private int attempts;
	private int rateLimitResumes;
	private boolean stopped;

	private final HttpUrl baseUrl;
	private final String token;

	private final long firstBackoffMs;
	private final long coalesceMs;

	public EventSender(BingoApi api, ScheduledExecutorService executor, HttpUrl baseUrl, String token,
		Consumer<SentEvent> onOutcome, Consumer<ApiException> onFatal)
	{
		this(api, executor, baseUrl, token, onOutcome, onFatal, FIRST_BACKOFF_MS, COALESCE_MS);
	}

	/** Both delays are injectable so the tests do not have to wait out real ones. */
	EventSender(BingoApi api, ScheduledExecutorService executor, HttpUrl baseUrl, String token,
		Consumer<SentEvent> onOutcome, Consumer<ApiException> onFatal,
		long firstBackoffMs, long coalesceMs)
	{
		this.api = api;
		this.executor = executor;
		this.baseUrl = baseUrl;
		this.token = token;
		this.requestGeneration = api.getRequestGeneration();
		this.onOutcome = onOutcome;
		this.onFatal = onFatal;
		this.firstBackoffMs = firstBackoffMs;
		this.coalesceMs = coalesceMs;
	}

	/**
	 * Stops sending and discards anything still queued. Called when the game ends, the token is
	 * rejected, or the player is removed — cases where continuing to send would be pointless.
	 */
	public synchronized void stop()
	{
		stopped = true;
		if (drainTask != null)
		{
			drainTask.cancel(false);
			drainTask = null;
		}
		while (!queue.isEmpty())
		{
			notifyOutcome(SentEvent.pending(queue.removeFirst().envelope)
				.with(SentEvent.Outcome.FAILED, "Connection closed before delivery."));
		}
		queuedBytes = 0;
	}

	public synchronized boolean submit(EventEnvelope envelope)
	{
		if (stopped)
		{
			return false;
		}
		final int size = api.eventSize(envelope);
		if (size + 2 > MAX_BATCH_BYTES || queue.size() >= MAX_QUEUED_EVENTS
			|| queuedBytes + size > MAX_QUEUED_BYTES)
		{
			notifyOutcome(SentEvent.pending(envelope)
				.with(SentEvent.Outcome.FAILED, "The local upload queue or event size limit was reached."));
			return false;
		}
		queue.addLast(new QueuedEvent(envelope, size));
		queuedBytes += size;
		scheduleDrain(coalesceMs);
		return true;
	}

	private void scheduleDrain(long delayMs)
	{
		if (drainTask != null || inFlight || stopped || queue.isEmpty() || executor.isShutdown())
		{
			return;
		}
		drainTask = executor.schedule(() -> api.withGeneration(requestGeneration, this::drain), delayMs, TimeUnit.MILLISECONDS);
	}

	private void drain()
	{
		final List<EventEnvelope> batch;
		synchronized (this)
		{
			drainTask = null;
			if (stopped || queue.isEmpty())
			{
				return;
			}
			batch = takeBatch();
			inFlight = true;
		}

		try
		{
			final EventBatchResponse response = api.postEvents(baseUrl, token, batch);
			synchronized (this)
			{
				if (stopped)
				{
					return;
				}
				attempts = 0;
				inFlight = false;
				rateLimitResumes = 0;
				apply(batch, response);
				scheduleDrain(0);
			}
		}
		catch (ApiException e)
		{
			synchronized (this)
			{
				if (stopped)
				{
					return;
				}
				if (e.isRetryable())
				{
					inFlight = false;
					retry(batch, e);
				}
				else
				{
					inFlight = false;
					abandon(batch, e);
				}
			}
		}
	}

	/**
	 * Copies the head of the queue without removing it, so a batch that fails keeps its place
	 * and its order. The entries are dropped only once their fate is known.
	 */
	private List<EventEnvelope> takeBatch()
	{
		final List<EventEnvelope> batch = new ArrayList<>(Math.min(MAX_BATCH, queue.size()));
		long bytes = 2;

		for (QueuedEvent entry : queue)
		{
			final long size = entry.bytes + (batch.isEmpty() ? 0 : 1);
			if (bytes + size > MAX_BATCH_BYTES)
			{
				break;
			}

			batch.add(entry.envelope);
			bytes += size;

			if (batch.size() == MAX_BATCH)
			{
				break;
			}
		}
		return batch;
	}

	/** The batch is always the front of the queue, so discarding it is a poll per entry. */
	private void dropBatchFromQueue(int size)
	{
		for (int i = 0; i < size; i++)
		{
			final QueuedEvent removed = queue.removeFirst();
			queuedBytes -= removed.bytes;
		}
	}

	private void apply(List<EventEnvelope> batch, EventBatchResponse response)
	{
		// Results are matched by event ID, never by position — the contract lets the server
		// reorder them.
		final Map<String, EventResult> byId = new HashMap<>();
		if (response.getResults() != null)
		{
			for (EventResult result : response.getResults())
			{
				if (result != null)
				{
					byId.put(result.getEventId(), result);
				}
			}
		}

		dropBatchFromQueue(batch.size());

		boolean gameNotLive = false;
		for (EventEnvelope envelope : batch)
		{
			final EventResult result = byId.get(envelope.getEventId());
			if (result == null)
			{
				log.error("Server returned no result for event {} ({}). This is a server bug.",
					envelope.getEventId(), envelope.getMetric().getId());
				notifyOutcome(SentEvent.pending(envelope)
					.with(SentEvent.Outcome.FAILED, "No result returned for this event."));
				continue;
			}

			notifyOutcome(outcomeOf(envelope, result));
			gameNotLive |= !result.isAccepted() && "GAME_NOT_LIVE".equals(result.getRejectionReason());
		}
		if (gameNotLive)
		{
			stop();
			notifyFatal(ApiException.malformed("The server is not accepting events for this game. Press Re-sync.", null));
		}
	}

	private SentEvent outcomeOf(EventEnvelope envelope, EventResult result)
	{
		final SentEvent base = SentEvent.pending(envelope);

		if (!result.isAccepted())
		{
			final String supplied = result.getRejectionReason();
			final String reason = "NOT_TRACKED".equals(supplied) || "GAME_NOT_LIVE".equals(supplied)
				|| "OUT_OF_WINDOW".equals(supplied) ? supplied : "INVALID";
			if ("INVALID".equals(reason))
			{
				log.error("Server rejected event {} ({}): {}", envelope.getEventId(), envelope.getMetric(), supplied);
			}
			else
			{
				log.debug("Server rejected {}: {}", envelope.getMetric().getId(), reason);
			}
			return base.with(SentEvent.Outcome.REJECTED, reason);
		}

		final SentEvent.Outcome outcome = result.isDuplicate()
			? SentEvent.Outcome.DUPLICATE
			: SentEvent.Outcome.SENT;

		return base.with(outcome, "");
	}

	private void retry(List<EventEnvelope> batch, ApiException e)
	{
		final Long retryAfter = e.getRetryAfterSeconds();
		if (retryAfter != null)
		{
			rateLimitResumes++;
			if (rateLimitResumes >= MAX_RATE_LIMIT_RESUMES
				|| retryAfter > MAX_RETRY_AFTER_SECONDS)
			{
				log.warn("Giving up on {} event(s) after {} rate-limit responses", batch.size(),
					rateLimitResumes);
				abandon(batch, e);
				return;
			}
			log.debug("Rate limited, resuming in {}s", retryAfter);
			scheduleDrain(TimeUnit.SECONDS.toMillis(retryAfter));
			return;
		}
		rateLimitResumes = 0;

		attempts++;
		if (attempts >= MAX_ATTEMPTS)
		{
			log.warn("Giving up on {} event(s) after {} attempts: {}", batch.size(), attempts, e.getMessage());
			abandon(batch, e);
			return;
		}

		// 2s, then 4s. Scheduled rather than slept, so the thread stays free to be shut down.
		final long delay = firstBackoffMs << (attempts - 1);
		log.debug("Send failed ({}), retrying in {}ms", e.getMessage(), delay);
		scheduleDrain(delay);
	}

	private void abandon(List<EventEnvelope> batch, ApiException e)
	{
		attempts = 0;
		rateLimitResumes = 0;
		dropBatchFromQueue(batch.size());

		for (EventEnvelope envelope : batch)
		{
			notifyOutcome(SentEvent.pending(envelope)
				.with(SentEvent.Outcome.FAILED, e.playerMessage()));
		}

		if (isFatal(e))
		{
			stop();
			notifyFatal(e);
			return;
		}

		if (!queue.isEmpty())
		{
			scheduleDrain(0);
		}
	}

	/** Statuses that mean there is no point sending anything else until the player acts. */
	private static boolean isFatal(ApiException e)
	{
		switch (e.getStatus())
		{
			case 401:
			case 403:
			case 409:
			case 410:
				return true;
			default:
				return false;
		}
	}

	private void notifyOutcome(SentEvent event)
	{
		final SentEvent display = event.getEnvelope().getScreenshot() == null ? event
			: new SentEvent(event.getEnvelope().withScreenshot(null, null), event.getOutcome(), event.getDetail());
		notifyLater(() -> onOutcome.accept(display));
	}

	private void notifyFatal(ApiException error)
	{
		notifyLater(() -> onFatal.accept(error));
	}

	private void notifyLater(Runnable callback)
	{
		try
		{
			executor.execute(callback);
		}
		catch (RejectedExecutionException e)
		{
			if (!executor.isShutdown()) { throw e; }
		}
	}

	private static final class QueuedEvent
	{
		private final EventEnvelope envelope;
		private final int bytes;

		private QueuedEvent(EventEnvelope envelope, int bytes)
		{
			this.envelope = envelope;
			this.bytes = bytes;
		}
	}
}
