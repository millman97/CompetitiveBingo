package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * The metric an event reports against.
 *
 * {@code value} is the absolute figure the game stated; {@code delta} is what this one
 * observation adds. The server derives the authoritative increment from its own stored value.
 */
@Value
public class MetricObservation
{
	/** Normalised metric ID, e.g. "kc.vorkath". See docs/api-contract.md for the normalisation rules. */
	String id;

	MetricType type;

	/**
	 * The absolute figure the game stated, or null when the game does not give one.
	 *
	 * COUNT — the stated count, e.g. 342.
	 * DROP — always null.
	 * DURATION — the run time in milliseconds.
	 * COLLECTION_LOG — slots filled, when the game supplied it, else null.
	 *
	 */
	@Nullable
	Long value;

	/**
	 * The increment this single observation represents, or null where increments are meaningless.
	 * COUNT — always 1.
	 * DROP — the quantity received.
	 * DURATION — always null.
	 * COLLECTION_LOG — always 1.
	 */
	@Nullable
	Long delta;

	/**
	 * True when this is the first observation of this metric since the plugin started tracking
	 * the current account. Tells the server that any gap against its stored value is unexplained
	 * rather than a missed event, so it can decide whether to backfill.
	 */
	boolean firstObservation;
}
