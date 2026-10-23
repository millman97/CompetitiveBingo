package com.millman97.competitivebingo;

import com.millman97.competitivebingo.model.DropDetail;
import com.millman97.competitivebingo.model.EventEnvelope;
import com.millman97.competitivebingo.model.EventIds;
import com.millman97.competitivebingo.model.EventSource;
import com.millman97.competitivebingo.model.ManualDetail;
import com.millman97.competitivebingo.model.KillDetail;
import com.millman97.competitivebingo.model.MetricObservation;
import com.millman97.competitivebingo.model.Protocol;
import com.millman97.competitivebingo.parse.Observation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldType;

/**
 * Wraps an {@link Observation} in the client context that makes it an event.
 *
 * Reads live client state, so it must be called on the client thread.
 */
@Singleton
public class EventFactory
{
	private final Client client;

	@Inject
	public EventFactory(Client client)
	{
		this.client = client;
	}

	public EventEnvelope build(Observation observation, String gameId, SessionState session)
	{
		return build(observation, gameId, session, EventSource.OBSERVED);
	}

	public EventEnvelope build(Observation observation, String gameId, SessionState session,
		EventSource source)
	{
		final int gameTick = client.getTickCount();
		final String accountHash = session.getAccountHash();
		final String sessionId = session.getSessionId();
		final String metricId = observation.getMetricId();

		final String eventId = source == EventSource.MANUAL
			? EventIds.forManual(accountHash, sessionId, metricId, gameTick,
				((ManualDetail) observation.getDetail()).getInput())
			: eventIdFor(observation, accountHash, sessionId, metricId, gameTick);
		final boolean firstObservation = session.markObserved(metricId);

		return EventEnvelope.builder()
			.schemaVersion(Protocol.SCHEMA_VERSION)
			.eventId(eventId)
			.gameId(gameId)
			.accountHash(accountHash)
			.displayName(displayName())
			.sessionId(sessionId)
			.clientTime(System.currentTimeMillis())
			.gameTick(gameTick)
			.world(client.getWorld())
			.worldTypes(worldTypes())
			.metric(new MetricObservation(
				metricId,
				observation.getType(),
				observation.getValue(),
				observation.getDelta(),
				firstObservation))
			.source(source)
			.detail(observation.getDetail())
			// Attached once the frame is encoded; see EventEnvelope.withScreenshot.
			.screenshot(null)
			.screenshotMediaType(null)
			.build();
	}

	private String eventIdFor(Observation observation, String accountHash, String sessionId,
		String metricId, int gameTick)
	{
		switch (observation.getType())
		{
			case COUNT:
				return EventIds.forCount(accountHash, sessionId, metricId, gameTick, required(observation.getValue()));

			case DURATION:
				return EventIds.forDuration(accountHash, sessionId, metricId, gameTick, required(observation.getValue()));

			case DROP:
			{
				final DropDetail drop = (DropDetail) observation.getDetail();
				return EventIds.forDrop(accountHash, sessionId, metricId, gameTick, drop.getItemId(),
					drop.getQuantity(), drop.getObservationOrdinal());
			}

			case KILL:
			{
				final KillDetail kill = (KillDetail) observation.getDetail();
				return EventIds.forKill(accountHash, sessionId, metricId, gameTick,
					kill.getNpcId(), kill.getObservationOrdinal());
			}

			case COLLECTION_LOG:
				// Deliberately independent of time and session: a slot is filled once, ever.
				return EventIds.forCollectionLog(accountHash, metricId);

			default:
				throw new IllegalStateException("No event ID derivation for " + observation.getType());
		}
	}

	private static long required(Long value)
	{
		if (value == null)
		{
			throw new IllegalStateException("Metric type requires an absolute value but none was observed");
		}
		return value;
	}

	private String displayName()
	{
		final Player local = client.getLocalPlayer();
		final String name = local == null ? null : local.getName();
		return name == null ? "" : name;
	}

	private List<String> worldTypes()
	{
		return client.getWorldType().stream()
			.map(WorldType::name)
			.sorted()
			.collect(Collectors.toCollection(ArrayList::new));
	}
}
