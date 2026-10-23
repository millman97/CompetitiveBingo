package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * Body accompanying any non-2xx response. The plugin shows {@code message} to the player, so it
 * must be written for a player rather than for a log.
 */
@Value
public class ApiError
{
	/** Stable machine-readable code, e.g. "UNKNOWN_JOIN_CODE". */
	String error;

	/** Human-readable explanation, shown verbatim in the side panel. */
	String message;

	/** For 409 only: the plugin or schema version the server requires. */
	@Nullable
	String requiredVersion;
}
