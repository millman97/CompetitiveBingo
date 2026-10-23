package com.millman97.competitivebingo.model;

import lombok.Value;

/**
 * Detail for {@link MetricType#COUNT}.
 */
@Value
public class CountDetail implements EventDetail
{
	/** Activity name exactly as the game spelled it, before normalisation, e.g. "Vorkath". */
	String activity;

	/** The full chat line the count was parsed from, for server-side auditing. */
	String rawMessage;
}
