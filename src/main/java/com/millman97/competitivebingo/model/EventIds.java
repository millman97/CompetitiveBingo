package com.millman97.competitivebingo.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derives the deterministic {@code eventId} that makes a retried event dedupe server-side.
 *
 * The format is normative — see docs/api-contract.md section 6. Fields are joined with
 * {@code |}, integers in plain decimal.
 *
 * {@code sessionId} is included everywhere except {@code COLLECTION_LOG}, whose slots are
 * filled once ever and so must not depend on time or session.
 */
public final class EventIds
{
	private static final char SEPARATOR = '|';

	/** 16 bytes of SHA-256, rendered as 32 lowercase hex characters. */
	private static final int ID_BYTES = 16;

	private EventIds()
	{
	}

	public static String forCount(String accountHash, String sessionId, String metricId, int gameTick, long value)
	{
		return hash(accountHash, sessionId, metricId, Integer.toString(gameTick), Long.toString(value));
	}

	public static String forDuration(String accountHash, String sessionId, String metricId, int gameTick, long durationMs)
	{
		return hash(accountHash, sessionId, metricId, Integer.toString(gameTick), Long.toString(durationMs));
	}

	public static String forDrop(String accountHash, String sessionId, String metricId, int gameTick,
		int itemId, int quantity, int observationOrdinal)
	{
		return hash(accountHash, sessionId, metricId, Integer.toString(gameTick),
			Integer.toString(itemId), Integer.toString(quantity), Integer.toString(observationOrdinal));
	}

	public static String forKill(String accountHash, String sessionId, String metricId,
		int gameTick, int npcId, int observationOrdinal)
	{
		return hash(accountHash, sessionId, metricId, Integer.toString(gameTick),
			Integer.toString(npcId), Integer.toString(observationOrdinal));
	}

	/**
	 * Identical manual input dedupes within one game tick. A different string or tick is a new ID.
	 */
	public static String forManual(String accountHash, String sessionId, String metricId,
		int gameTick, String input)
	{
		return hash(accountHash, sessionId, metricId, Integer.toString(gameTick), "manual", input);
	}

	public static String forCollectionLog(String accountHash, String metricId)
	{
		return hash(accountHash, metricId);
	}

	private static String hash(String... fields)
	{
		final StringBuilder joined = new StringBuilder();
		for (int i = 0; i < fields.length; i++)
		{
			if (i > 0)
			{
				joined.append(SEPARATOR);
			}
			joined.append(fields[i]);
		}

		final MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			// SHA-256 is required of every Java platform, so this cannot happen.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}

		final byte[] full = digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8));

		final StringBuilder hex = new StringBuilder(ID_BYTES * 2);
		for (int i = 0; i < ID_BYTES; i++)
		{
			hex.append(Character.forDigit((full[i] >> 4) & 0xF, 16));
			hex.append(Character.forDigit(full[i] & 0xF, 16));
		}
		return hex.toString();
	}
}
