package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.DropDetail;
import com.millman97.competitivebingo.model.DropValueRule;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class DropValueRulesTest
{
	private static final DropValueRules.Prices PRICES = prices(100_000L, 72_000L);

	@Test
	public void bandsApplyToTheWholeStackWithInclusiveBounds()
	{
		final DropValueRules rules = rules(100_000L, 500_000L, null, null);
		assertEquals(Long.valueOf(1), rules.apply(drop(1), PRICES).get(0).getDelta());
		assertEquals(Long.valueOf(5), rules.apply(drop(5), PRICES).get(0).getDelta());
		assertTrue(rules.apply(drop(6), PRICES).isEmpty());
	}

	@Test
	public void coinTargetsUseTheRequestedPriceRatherThanItemQuantity()
	{
		final Observation ge = rules(1L, null, null, DropValueRule.Counts.VALUE).apply(drop(3), PRICES).get(0);
		assertEquals("drop.value", ge.getMetricId());
		assertEquals(Long.valueOf(300_000), ge.getDelta());
		final Observation ha = rules(1L, null, DropValueRule.Valuation.HIGH_ALCHEMY,
			DropValueRule.Counts.VALUE).apply(drop(3), PRICES).get(0);
		assertEquals(Long.valueOf(216_000), ha.getDelta());
	}

	@Test
	public void unknownPricesCannotAwardProgress()
	{
		assertTrue(rules(null, null, null, null).apply(drop(1), prices(null, null)).isEmpty());
	}

	private static DropValueRules rules(Long min, Long max, DropValueRule.Valuation price, DropValueRule.Counts counts)
	{
		return DropValueRules.of(Collections.singletonList(new DropValueRule("drop.value", null, min, max, price, counts)));
	}

	private static DropDetail drop(int quantity)
	{
		return new DropDetail(4151, "Abyssal whip", quantity, "Abyssal demon", "NPC", null, null, null, null, 0);
	}

	private static DropValueRules.Prices prices(Long ge, Long ha)
	{
		return new DropValueRules.Prices()
		{
			public Long grandExchange(int id) { return ge; }
			public Long highAlchemy(int id) { return ha; }
		};
	}
}
