package com.millman97.competitivebingo.model;

import lombok.Value;

/**
 * Body of {@code POST /api/plugin/sync}. The join code is the credential.
 */
@Value
public class SyncRequest
{
	String joinCode;

	/** Decimal string, as in {@link EventEnvelope#getAccountHash()}. Requires the player to be logged in. */
	String accountHash;

	String displayName;

	String pluginVersion;

	int schemaVersion;
}
