package com.millman97.competitivebingo.model;

import java.util.List;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Value;

/** One observation and its client context, serialized according to docs/api-contract.md. */
@Value
@Builder
public class EventEnvelope
{
	/** Always {@link Protocol#SCHEMA_VERSION}. */
	int schemaVersion;

	/**
	 * Deterministic idempotency key, so a retry of the same observation dedupes server-side.
	 * Unique within a game, not globally — the server must scope deduplication by
	 * (gameId, eventId). See docs/api-contract.md for the derivation per metric type.
	 */
	String eventId;

	String gameId;

	/**
	 * {@code client.getAccountHash()} rendered as a decimal string. It is a 64-bit value and is
	 * sent as a string so it survives JSON parsers that store numbers as doubles.
	 */
	String accountHash;

	/** Current display name, for human-readable logs. */
	String displayName;

	/**
	 * Lowercase RFC 4122 UUID minted once per play session — on login, and again on an account
	 * switch. It exists so that {@link #gameTick}, a per-client counter that repeats, cannot
	 * produce colliding event IDs across two sessions.
	 */
	String sessionId;

	/** Epoch milliseconds from the client clock. */
	long clientTime;

	/** {@code client.getTickCount()} at the moment of observation. */
	int gameTick;

	int world;

	/** Names from {@link net.runelite.api.WorldType}, e.g. ["MEMBERS"]. */
	List<String> worldTypes;

	MetricObservation metric;

	/**
	 * Whether the plugin observed this or the player typed it. Absent is not possible: every
	 * envelope states it, and a server may refuse {@link EventSource#MANUAL} outright.
	 */
	EventSource source;

	/**
	 * One of {@link CountDetail}, {@link DropDetail}, {@link DurationDetail} or
	 * {@link CollectionLogDetail}, matching {@code metric.type} — or {@link ManualDetail},
	 * whatever the type, when {@code source} is {@link EventSource#MANUAL}.
	 */
	EventDetail detail;

	/** Base64 of the captured image, or null when this event carries no evidence. */
	@Nullable
	String screenshot;

	/** Content type of {@code screenshot}, e.g. "image/jpeg". Null when there is no image. */
	@Nullable
	String screenshotMediaType;

	/** Attaches the frame captured for this event. */
	public EventEnvelope withScreenshot(String base64, String mediaType)
	{
		return new EventEnvelope(schemaVersion, eventId, gameId, accountHash, displayName,
			sessionId, clientTime, gameTick, world, worldTypes, metric, source, detail,
			base64, mediaType);
	}
}
