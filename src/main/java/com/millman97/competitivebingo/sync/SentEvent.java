package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.model.EventEnvelope;
import lombok.Value;

/**
 * What became of one event. Feeds the panel's activity list, which is the primary place a player
 * finds out whether their kills are actually counting.
 */
@Value
public class SentEvent
{
	public enum Outcome
	{
		/** Waiting its turn, or in flight. */
		PENDING,
		/** The server recorded it. */
		SENT,
		/** The server had already recorded it; harmless, and expected after a lost response. */
		DUPLICATE,
		/** The server refused it. {@link #detail} carries the reason. */
		REJECTED,
		/** Three attempts failed, or the request was wrong. {@link #detail} carries why. */
		FAILED
	}

	EventEnvelope envelope;

	Outcome outcome;

	/** Rejection reason or error message; empty when there is nothing to explain. */
	String detail;

	public static SentEvent pending(EventEnvelope envelope)
	{
		return new SentEvent(envelope, Outcome.PENDING, "");
	}

	public SentEvent with(Outcome newOutcome, String newDetail)
	{
		return new SentEvent(envelope, newOutcome, newDetail);
	}
}
