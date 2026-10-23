package com.millman97.competitivebingo;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionStateTest
{
	@Test
	public void relogChangesTheSessionIdButRetainsFirstObservationHistory()
	{
		final Set<String> seen = new HashSet<>();
		final SessionState before = new SessionState(123, "Tester", seen);
		assertTrue(before.markObserved("kc.vorkath"));
		final SessionState after = new SessionState(123, "Tester", seen);
		assertNotEquals(before.getSessionId(), after.getSessionId());
		assertFalse(after.markObserved("kc.vorkath"));
	}

	@Test
	public void independentSessionsAndOtherAccountsHaveSeparateHistory()
	{
		final SessionState live = new SessionState(123, "Tester");
		final SessionState independent = new SessionState(123, "Tester");
		final SessionState other = new SessionState(456, "Other");
		independent.markObserved("kc.vorkath");
		assertTrue(live.markObserved("kc.vorkath"));
		assertTrue(other.markObserved("kc.vorkath"));
	}
}
