package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.parse.MetricFilter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import net.runelite.api.WorldType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The block list comes from the server, so it can name types this client has never heard of.
 */
public class ReadinessChecksTest
{

	@Test
	public void skipsNamesThisClientDoesNotKnow()
	{
		// A newer server may block a world type added after this build shipped.
		final Set<WorldType> types =
			ReadinessChecks.toWorldTypes(Arrays.asList("SEASONAL", "WORLD_TYPE_FROM_THE_FUTURE"));

		assertEquals(Collections.singleton(WorldType.SEASONAL), types);
	}

	@Test
	public void countAndDurationMetricsRequireVisibleCountMessages()
	{
		assertTrue(ReadinessChecks.requiresKillCountMessages(
			MetricFilter.of(Collections.singletonList("kc.vorkath"))));
		assertTrue(ReadinessChecks.requiresKillCountMessages(
			MetricFilter.of(Collections.singletonList("time.vorkath"))));
	}

}
