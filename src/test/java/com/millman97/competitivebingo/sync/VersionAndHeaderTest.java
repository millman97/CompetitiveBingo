package com.millman97.competitivebingo.sync;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VersionAndHeaderTest
{
	@Test
	public void comparesVersionsNumericallyNotAsText()
	{
		// The string comparison trap: "0.10.0" sorts before "0.9.0" alphabetically.
		assertTrue(SyncService.isOlder("0.9.0", "0.10.0"));
		assertFalse(SyncService.isOlder("0.10.0", "0.9.0"));
	}

	@Test
	public void readsRetryAfterAsSeconds()
	{
		assertEquals(Long.valueOf(3L), BingoApi.retryAfterSeconds("3"));
		assertEquals(Long.valueOf(0L), BingoApi.retryAfterSeconds("0"));
		assertEquals("a negative wait is nonsense; treat it as now",
			Long.valueOf(0L), BingoApi.retryAfterSeconds("-5"));
	}

}
