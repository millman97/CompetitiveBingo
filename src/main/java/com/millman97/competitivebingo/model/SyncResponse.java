package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * Response of {@code POST /api/plugin/sync}.
 */
@Value
public class SyncResponse
{
	/** Bearer token for this connection, kept in memory until it is replaced or closed. */
	String playerToken;

	String gameId;

	/** Null when the admin has not yet placed this player on a team. */
	@Nullable
	String teamName;

	/** Epoch millis. Past this the server answers 401 and the plugin must re-sync. */
	long tokenExpiresAt;
}
