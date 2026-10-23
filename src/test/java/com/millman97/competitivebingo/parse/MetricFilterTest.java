package com.millman97.competitivebingo.parse;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetricFilterTest
{
	@Test
	public void matchesExactIdsOnly()
	{
		final MetricFilter filter = MetricFilter.of(Arrays.asList("kc.vorkath", "time.zulrah"));

		assertTrue(filter.matches("kc.vorkath"));
		assertTrue(filter.matches("time.zulrah"));
		assertFalse(filter.matches("kc.zulrah"));
		assertFalse("a prefix is not a match without a wildcard", filter.matches("kc.vorkath_junior"));
	}

	@Test
	public void supportsTheTwoPermittedWildcards()
	{
		final MetricFilter filter = MetricFilter.of(Arrays.asList("drop.*", "collection_log.*"));

		assertTrue(filter.matches("drop.abyssal_whip"));
		assertTrue(filter.matches("collection_log.dragon_pickaxe"));
		assertFalse("a count is not a drop", filter.matches("kc.vorkath"));
		assertFalse(filter.matches("time.zulrah"));
	}

	@Test
	public void aValueRuleMetricIsTrackedWithoutBeingInTheList()
	{
		// Naming a rule is what asks for its metric. A filter that did not know about it would
		// drop every event the rule produced, which looks exactly like the rule never firing.
		final MetricFilter filter = MetricFilter.of(
			Collections.singletonList("kc.vorkath"),
			Collections.singletonList("drop.worth_1m_plus"));

		assertTrue(filter.matches("drop.worth_1m_plus"));
		assertTrue(filter.matches("kc.vorkath"));
		assertFalse("and it does not open the door to every other drop",
			filter.matches("drop.abyssal_whip"));
	}

}
