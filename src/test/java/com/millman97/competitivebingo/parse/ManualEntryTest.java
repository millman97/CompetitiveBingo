package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.ManualDetail;
import org.junit.Test;
import static org.junit.Assert.*;

public class ManualEntryTest
{
	@Test
	public void countsAreAbsoluteWhileDropsAndCollectionUnlocksAreIncrements()
	{
		final Observation count = ManualEntry.of("kc.vorkath", "Vorkath", "137");
		assertEquals(Long.valueOf(137), count.getValue());
		assertEquals(Long.valueOf(1), count.getDelta());
		final Observation drop = ManualEntry.of("drop.shark", "Sharks", "250");
		assertNull(drop.getValue());
		assertEquals(Long.valueOf(250), drop.getDelta());
		assertEquals(Long.valueOf(1), ManualEntry.of("collection_log.dragon_pickaxe", "Pickaxe", "999").getDelta());
	}

	@Test
	public void durationsAndCoinSuffixesKeepTheirPrecision()
	{
		assertEquals(Long.valueOf(754_000), ManualEntry.readDuration("12:34"));
		assertEquals(Long.valueOf(3_753_500), ManualEntry.readDuration("1:02:33.5"));
		assertEquals(Long.valueOf(90_500), ManualEntry.readDuration("90.5 seconds"));
		assertEquals(Long.valueOf(1_500_000), ManualEntry.readNumber("1.5m"));
	}

	@Test
	public void invalidNumbersAreNotConvertedIntoPositiveOrOverflowedProgress()
	{
		assertNull(ManualEntry.readNumber("-5"));
		assertNull(ManualEntry.readNumber("1.5"));
		for (String input : new String[]{"9223372036854775807", "-1:05", "1:99"})
		{
			assertNull(input, ManualEntry.readDuration(input));
		}
		assertNull(ManualEntry.of("drop.shark", "Sharks", "-5").getDelta());
		final Observation raw = ManualEntry.of("kc.vorkath", "Vorkath", "See screenshot");
		assertNull(raw.getValue());
		assertEquals("See screenshot", ((ManualDetail) raw.getDetail()).getInput());
	}
}
