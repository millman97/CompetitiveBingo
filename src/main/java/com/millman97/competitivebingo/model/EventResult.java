package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * The server's verdict on one event within a batch. Per-event rejection is not a transport
 * failure: a batch containing rejected events still returns HTTP 200.
 */
@Value
public class EventResult
{
	/** Matches the {@code eventId} of the submitted envelope. Results are matched by ID, not by position. */
	String eventId;

	/** Whether the server recorded the event. */
	boolean accepted;

	/** True when this event ID was already recorded for this game; still {@code accepted}. */
	boolean duplicate;

	/** Machine-readable reason when {@code accepted} is false. Absent otherwise. */
	@Nullable
	String rejectionReason;
}
