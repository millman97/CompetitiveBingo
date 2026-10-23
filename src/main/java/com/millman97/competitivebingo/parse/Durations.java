/*
 * Copyright (c) 2017. l2-
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2022, Jake Barter
 * Copyright (c) 2022, pajlads
 * Copyright (c) 2026, millman97
 * All rights reserved.
 *
 * The time parsing here is derived from Dink's TimeUtils (pajlads/DinkPlugin) and from the time
 * fragments in RuneLite's ChatCommandsPlugin, both BSD 2-Clause. The group structure and the
 * pad-then-truncate handling of the fractional second follow Dink's.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.millman97.competitivebingo.parse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the time strings OSRS puts in duration messages.
 *
 * Three shapes occur: {@code mm:ss}, {@code mm:ss.f} and {@code h:mm:ss(.f)}.
 */
final class Durations
{
	private static final Pattern TIME =
		Pattern.compile("(?:(?<h>\\d+):)?(?<m>\\d+):(?<s>\\d{1,2})(?:\\.(?<frac>\\d+))?");

	static final String TIME_GROUP = "[0-9]+(?::[0-9]+)+(?:\\.[0-9]+)?";

	private Durations()
	{
	}

	/**
	 * @return the duration in milliseconds
	 * @throws IllegalArgumentException if the string is not a shape the game produces, which means
	 *                                  the surrounding pattern matched something it should not have
	 */
	static long toMillis(String time)
	{
		final Matcher m = TIME.matcher(time);
		if (!m.matches())
		{
			throw new IllegalArgumentException("Unparseable duration '" + time + "'");
		}

		final String hours = m.group("h");
		final long minutes = Long.parseLong(m.group("m"));
		final long seconds = Long.parseLong(m.group("s"));
		if (seconds >= 60 || (hours != null && minutes >= 60))
		{
			throw new IllegalArgumentException("Invalid minute or second component.");
		}
		long millis;
		try
		{
			millis = hours == null ? 0L : Math.multiplyExact(Long.parseLong(hours), 3_600_000L);
			millis = Math.addExact(millis, Math.multiplyExact(minutes, 60_000L));
			millis = Math.addExact(millis, Math.multiplyExact(seconds, 1_000L));
		}
		catch (ArithmeticException e)
		{
			throw new IllegalArgumentException("Duration is too large.", e);
		}

		final String frac = m.group("frac");
		if (frac != null)
		{
			// ".5" is 500ms, ".50" is 500ms, ".057" is 57ms — pad, then truncate to three digits.
			try
			{
				millis = Math.addExact(millis, Integer.parseInt((frac + "000").substring(0, 3)));
			}
			catch (ArithmeticException e)
			{
				throw new IllegalArgumentException("Duration is too large.", e);
			}
		}
		return millis;
	}
}
