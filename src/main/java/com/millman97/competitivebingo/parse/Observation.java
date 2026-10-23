package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.EventDetail;
import com.millman97.competitivebingo.model.MetricType;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Value;

/**
 * Something the plugin noticed before it is decided whether the current game cares about it.
 */
@Value
@Builder
public class Observation
{
	String metricId;

	MetricType type;

	@Nullable
	Long value;

	@Nullable
	Long delta;

	EventDetail detail;
}
