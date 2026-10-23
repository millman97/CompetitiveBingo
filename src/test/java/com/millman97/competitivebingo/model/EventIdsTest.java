package com.millman97.competitivebingo.model;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * The derivation is normative: a server that recomputes an event ID has to match byte for byte.
 * These tests pin the format so a refactor cannot quietly change it.
 */
public class EventIdsTest
{
	private static final String ACCOUNT = "-4234871239847123";
	private static final String SESSION = "6b1f0c2e-3a44-4d8b-9f10-27c5e8a1b3d4";

	@Test
	public void matchesTheWorkedExampleInTheContract()
	{
		// docs/api-contract.md section 6: SHA-256 of
		// "-4234871239847123|6b1f0c2e-3a44-4d8b-9f10-27c5e8a1b3d4|kc.vorkath|123456|342",
		// first 16 bytes as lowercase hex.
		assertEquals("777f44d8a5a8a5a82c72a18de4c026da",
			EventIds.forCount(ACCOUNT, SESSION, "kc.vorkath", 123456, 342));
	}

	@Test
	public void everyInputChangesTheId()
	{
		final String base = EventIds.forCount(ACCOUNT, SESSION, "kc.vorkath", 123456, 342);

		assertNotEquals(base, EventIds.forCount("-1", SESSION, "kc.vorkath", 123456, 342));
		assertNotEquals(base, EventIds.forCount(ACCOUNT, "other-session", "kc.vorkath", 123456, 342));
		assertNotEquals(base, EventIds.forCount(ACCOUNT, SESSION, "kc.zulrah", 123456, 342));
		assertNotEquals(base, EventIds.forCount(ACCOUNT, SESSION, "kc.vorkath", 123457, 342));
		assertNotEquals(base, EventIds.forCount(ACCOUNT, SESSION, "kc.vorkath", 123456, 343));
	}

	@Test
	public void sessionSeparatesOtherwiseIdenticalDropsAcrossSessions()
	{
		// The same drop at the same tick count in two sessions is two facts, not one.
		assertNotEquals(
			EventIds.forDrop(ACCOUNT, SESSION, "drop.abyssal_whip", 5000, 4151, 1, 0),
			EventIds.forDrop(ACCOUNT, "0f9e8d7c-6b5a-4948-3726-150413021100", "drop.abyssal_whip", 5000, 4151, 1, 0));
	}

	@Test
	public void simultaneousNpcKillsHaveDistinctIds()
	{
		assertNotEquals(
			EventIds.forKill(ACCOUNT, SESSION, "kill.abyssal_demon", 5000, 415, 0),
			EventIds.forKill(ACCOUNT, SESSION, "kill.abyssal_demon", 5000, 415, 1));
	}

	@Test
	public void simultaneousIdenticalDropsHaveDistinctIds()
	{
		assertNotEquals(
			EventIds.forDrop(ACCOUNT, SESSION, "drop.shark", 5000, 385, 1, 0),
			EventIds.forDrop(ACCOUNT, SESSION, "drop.shark", 5000, 385, 1, 1));
	}
}
