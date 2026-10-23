/*
 * Copyright (c) 2017. l2-
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2022, Jake Barter
 * Copyright (c) 2022, pajlads
 * Copyright (c) 2026, millman97
 * All rights reserved.
 *
 * The message forms handled here are derived from RuneLite's ChatCommandsPlugin and from Dink
 * (pajlads/DinkPlugin), both BSD 2-Clause. The irregular counters in particular — the Guardians
 * of the Rift, the Grand Hallowed Coffin, the bird's egg offerings, the Hunter Guild rumours
 * and the openable chests — are taken from the patterns ChatCommandsPlugin has accumulated.
 * Barbarian Assault's gamble count comes from Dink's GambleNotifier.
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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * Recognises the game's cumulative counter messages.
 *
 * OSRS emits two shapes and almost every counter in the game uses one of them:
 * - {@code Your [completed|subdued ]<activity>[ <type>...] count is: <n>}
 * - {@code Your completion count for <activity> is: <n>}
 *
 * The activity is whatever sits between the fixed words, so a new boss needs no code change.
 *
 * A handful of counters predate that convention and word themselves differently. Those cannot be
 * generalised — each is its own sentence — so each gets a pattern and a fixed activity name in
 * {@link #IRREGULAR}. Adding one is a contract change in effect, because it decides the metric ID
 * a board admin has to type.
 */
public final class CountParser
{
	/**
	 * Words the game inserts between the activity and "count".
	 */
	private static final String TYPE_WORDS = "kill|chests|chest|completion|completions|harvest|success|opened|lap|Total Ticket";

	private static final Pattern COUNT_IS = Pattern.compile(
		"\\bYour (?:(?<pre>completed|subdued) )?(?<activity>.+?)(?: (?:" + TYPE_WORDS + "))* count is: ?(?<count>[\\d,]+)\\b");

	private static final Pattern COMPLETION_COUNT_FOR = Pattern.compile(
		"\\bYour completion count for (?<activity>.+?) is: ?(?<count>[\\d,]+)\\b");

	/**
	 * Hallowed Sepulchre's duration messages are special.
	 */
	private static final Pattern SEPULCHRE_COMPLETIONS = Pattern.compile(
		"You have completed Floor (?<floor>\\d) of the Hallowed Sepulchre! Total completions: ?(?<count>[\\d,]+)");

	/**
	 * Counters that word themselves their own way. The activity name is fixed here rather than
	 * captured, because the message does not contain one in a form worth deriving an ID from.
	 */
	private static final List<Irregular> IRREGULAR = Arrays.asList(
		// "Amount of Rifts you have closed: 412."
		new Irregular("Guardians of the Rift", Pattern.compile(
			"\\bAmount of Rifts you have closed: ?(?<count>[\\d,]+)", Pattern.CASE_INSENSITIVE)),

		// "You have opened the Grand Hallowed Coffin 87 times!"
		new Irregular("Grand Hallowed Coffin", Pattern.compile(
			"\\bYou have opened the Grand Hallowed Coffin ?(?<count>[\\d,]+) times?!")),

		// "You have completed 123 Hard Treasure Trails." The tier is part of the captured name,
		// so each tier is its own metric — which is what a board asking for "5 elite clues" needs.
		new Irregular(null, Pattern.compile(
			"\\bYou have completed (?<count>[\\d,]+) (?<activity>\\w+ Treasure Trails?)\\.")),

		// "You've completed 250 tasks..." and its Wilderness and Mortimer variants. The bare form
		// captures "tasks", which slugs to something useless, so the alias map renames it.
		new Irregular(null, Pattern.compile(
			"\\bYou['’]ve completed (?:at least )?(?<count>[\\d,]+) "
				+ "(?<activity>(?:Wilderness|Mortimer) tasks?|tasks?)\\b")),

		// "...! High level gamble count: 123." Delivered as a message box rather than a chat
		// line, which is why watching chat alone never saw it.
		new Irregular("Barbarian Assault High-level gambles", Pattern.compile(
			"\\bHigh level gamble count: ?(?<count>[\\d,]+)")),

		// "You have completed 32 rumours for the Hunter Guild."
		new Irregular("Hunter Guild rumours", Pattern.compile(
			"\\bYou have completed ?(?<count>[\\d,]+) rumours? for the Hunter Guild\\.")),

		// "You have made 240 offerings." — the bird's egg shrine.
		new Irregular("Bird's egg offerings", Pattern.compile(
			"\\bYou have made (?<count>[\\d,]+|one) offerings?\\.")),

		// "You have opened the crystal chest 42 times." Four chests share one sentence, so the
		// name is captured rather than fixed.
		new Irregular(null, Pattern.compile(
			"\\bYou have opened (?:the )?(?<activity>crystal chest|Larran's big chest|"
				+ "Larran's small chest|Brimstone chest) (?:(?<count>[\\d,]+) times|(?<once>once))\\.",
			Pattern.CASE_INSENSITIVE))
	);

	private CountParser()
	{
	}

	/**
	 * @param message chat text with colour tags already removed
	 * @return the observation, or empty if this is not a counter message
	 */
	public static Optional<ParsedCount> parse(String message)
	{
		Matcher m = COUNT_IS.matcher(message);
		if (m.find())
		{
			return build(m.group("activity"), m.group("count"));
		}

		m = COMPLETION_COUNT_FOR.matcher(message);
		if (m.find())
		{
			return build(m.group("activity"), m.group("count"));
		}

		m = SEPULCHRE_COMPLETIONS.matcher(message);
		if (m.find())
		{
			return build("Hallowed Sepulchre Floor " + m.group("floor"), m.group("count"));
		}

		for (Irregular irregular : IRREGULAR)
		{
			final Matcher matcher = irregular.pattern.matcher(message);
			if (!matcher.find())
			{
				continue;
			}

			// A fixed name where the message carries none worth deriving from, otherwise the one
			// the message named.
			final String activity = irregular.activity != null
				? irregular.activity
				: matcher.group("activity");

			return build(activity, countFrom(matcher));
		}

		return Optional.empty();
	}

	/**
	 * The count as written. Some of these say "once" rather than "1", because the game writes
	 * the first one out in words.
	 */
	private static String countFrom(Matcher matcher)
	{
		final String count = group(matcher, "count");
		if (count != null && !count.equalsIgnoreCase("one"))
		{
			return count;
		}
		return "1";
	}

	/** @return the group, or null when this pattern does not define it at all */
	@Nullable
	private static String group(Matcher matcher, String name)
	{
		try
		{
			return matcher.group(name);
		}
		catch (IllegalArgumentException e)
		{
			// The pattern has no group of that name, which is not an error — the irregular
			// patterns do not all share a shape.
			return null;
		}
	}

	private static Optional<ParsedCount> build(String activity, String count)
	{
		final String trimmed = activity.trim();
		if (trimmed.isEmpty() || MetricIds.slug(trimmed).isEmpty())
		{
			// The pattern matched but there is no usable activity name. Nothing sane to report.
			return Optional.empty();
		}
		return Optional.of(new ParsedCount(Activity.of(trimmed), Long.parseLong(count.replace(",", ""))));
	}

	/** One counter that words itself its own way. */
	private static final class Irregular
	{
		/** Fixed activity name, or null when the pattern captures one in an "activity" group. */
		@Nullable
		private final String activity;

		private final Pattern pattern;

		Irregular(@Nullable String activity, Pattern pattern)
		{
			this.activity = activity;
			this.pattern = pattern;
		}
	}

	@Value
	public static class ParsedCount
	{
		Activity activity;

		/** The absolute count the game stated. */
		long count;
	}
}
