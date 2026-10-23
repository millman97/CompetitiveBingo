package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.model.MetricType;
import javax.annotation.Nullable;
import lombok.Value;

/** A server-supplied metric label and target, without locally inferred progress. */
@Value
public class TrackedTarget
{
	String metricId;

	/** The game's label for it, falling back to the metric ID when the server gave none. */
	String label;

	/**
	 * How many the game wants, or null when it did not say. For a duration metric this is a
	 * threshold in milliseconds rather than a count.
	 */
	@Nullable
	Long target;

	/**
	 * The metric family, or null for a wildcard entry, which spans a whole namespace and so has
	 * no single type.
	 */
	@Nullable
	MetricType type;

	/** Whether the player can pick this from the manual entry dropdown. Wildcards cannot. */
	public boolean isManuallyEnterable()
	{
		return type != null;
	}
}
