package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * Detail for an event the player submitted by hand, whatever its {@code metric.type}.
 *
 * Manual entry exists for the things no parser can see — a raid time read off a screenshot, a
 * drop that arrived before the game started, a count that only ever appears in an interface. The
 * player picks a metric the game already tracks and types one free-form string; the plugin makes
 * a best-effort reading of it into {@code metric.value} and {@code metric.delta} and sends the
 * string itself here regardless, because the server is the one that decides what it means.
 */
@Value
public class ManualDetail implements EventDetail
{
	/** Label of the metric the player picked, as the panel showed it, e.g. "Araxxor kills". */
	String activity;

	/** Exactly what the player typed, untouched. */
	String input;

	/**
	 * How the plugin read {@link #input} — "count", "quantity", "duration" or "value" — or null
	 * when it could make nothing of it and the string is all the server has to go on.
	 */
	@Nullable
	String interpretedAs;
}
