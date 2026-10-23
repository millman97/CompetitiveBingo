package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * Detail for {@link MetricType#DURATION}.
 */
@Value
public class DurationDetail implements EventDetail
{
	/** Activity name as the game spelled it, e.g. "Chambers of Xeric". */
	String activity;

	/** Sub-mode where the game distinguishes one, e.g. "Challenge Mode". Null when there is none. */
	@Nullable
	String variant;

	/** The time just achieved, in milliseconds. */
	long durationMs;

	/** True when the game announced this run as a personal best. */
	boolean isPersonalBest;

	/** The personal best the game reported alongside the run, or null if it did not report one. */
	@Nullable
	Long personalBestMs;

	/** The full chat line the duration was parsed from, for server-side auditing. */
	String rawMessage;
}
