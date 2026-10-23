package com.millman97.competitivebingo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;

/**
 * What the plugin remembers about the current play session.
 */
public class SessionState
{
	@Getter
	private final String sessionId = UUID.randomUUID().toString();

	/** Account this session belongs to, as a decimal string. */
	@Getter
	private final String accountHash;

	/**
	 * Display name at the time the session started. For logs and admin screens only — the
	 * account hash is the identity, and this can change without the account changing.
	 */
	@Getter
	private final String displayName;

	/** Shared across relogs for the same account, independently of the session UUID. */
	private final Set<String> seenMetrics;

	public SessionState(long accountHash, String displayName)
	{
		this(accountHash, displayName, new HashSet<>());
	}

	SessionState(long accountHash, String displayName, Set<String> seenMetrics)
	{
		this.accountHash = Long.toString(accountHash);
		this.displayName = displayName;
		this.seenMetrics = seenMetrics;
	}

	/**
	 * Records that a metric has now been observed.
	 *
	 * @return true if this is the first time tracking this account, meaning any gap against the server's
	 *         stored value is unexplained rather than a dropped event
	 */
	public boolean markObserved(String metricId)
	{
		return seenMetrics.add(metricId);
	}
}
