/*
 * Copyright (c) 2017. l2-
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2022, Jake Barter
 * Copyright (c) 2022, pajlads
 * Copyright (c) 2026, millman97
 * All rights reserved.
 *
 * The message forms handled here are derived from RuneLite's ChatCommandsPlugin and from Dink
 * (pajlads/DinkPlugin), both BSD 2-Clause.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * Recognises the game's completion-time messages.
 *
 * Two kinds:
 * - Self-naming - the message states the activity, as raids and the Hallowed Sepulchre do.
 * - Anonymous - "Fight duration: 1:23", which {@link ChatObserver} pairs with a counter
 *   message on the same tick, or drops.
 */
public final class DurationParser
{
	private static final String T = Durations.TIME_GROUP;


	/**
	 * The personal-best tail. Both forms occur, and the full stop before "Personal best" is
	 * present at most bosses but absent inside the Chambers of Xeric raid message.
	 */
	private static final String PB_TAIL =
		"(?:\\.?\\s*\\((?<newpb>new personal best)\\)|\\.?\\s*Personal best:\\s*(?<pb>" + T + "))?";

	/** Hallowed Sepulchre states its floor, so it names itself. */
	private static final Pattern HS_FLOOR = Pattern.compile(
		"Floor (?<floor>\\d) time:\\s*(?<time>" + T + ")" + PB_TAIL);

	private static final Pattern HS_OVERALL = Pattern.compile(
		"Overall time:\\s*(?<time>" + T + ")" + PB_TAIL);

	/**
	 * Raids state their own name: "Theatre of Blood total completion time: 23:42.60".
	 * The Fractured Archive adds a colon between its name and "Total completion time".
	 * The activity is optional because a bare "Completion time:" also occurs, in which case
	 * this behaves as an anonymous duration.
	 */
	private static final Pattern COMPLETION_TIME = Pattern.compile(
		"(?:(?<activity>[^\\n]{1,60}?):? )?(?:total )?completion time:\\s*(?<time>" + T + ")" + PB_TAIL,
		Pattern.CASE_INSENSITIVE);

	private static final Pattern ANONYMOUS_DURATION = Pattern.compile(
		"(?:(?<kind>Fight|Lap|Challenge|Corrupted challenge|Speedrun)\\s)?duration:\\s*(?<time>" + T + ")" + PB_TAIL,
		Pattern.CASE_INSENSITIVE);

	/**
	 * The Barracuda Trials, which state a bare "Time: 4:04." on its own line and name the
	 * activity in a separate message.
	 *
	 * Anchored to the start of a line, unlike every other pattern here, because "Time:" on its
	 * own is far too generic to go looking for in the middle of arbitrary chat.
	 */
	private static final Pattern BARE_TIME = Pattern.compile(
		"(?m)^Time:\\s*(?<time>" + T + ")" + PB_TAIL);

	/** Wintertodt and Tempoross. */
	private static final Pattern SUBDUED_IN = Pattern.compile(
		"Subdued in\\s*(?<time>" + T + ")" + PB_TAIL,
		Pattern.CASE_INSENSITIVE);

	/**
	 * The sledding course names neither the course nor the activity in its result. The complete
	 * race wording is nevertheless unique enough to name directly; its shorter new-PB wording
	 * is kept anonymous and is paired with the separate landing announcement by ChatObserver.
	 */
	private static final Pattern SLEDDING_RACE = Pattern.compile(
		"(?m)^You completed the race in:\\s*(?<time>" + T + ")" + PB_TAIL,
		Pattern.CASE_INSENSITIVE);

	private static final Pattern ANONYMOUS_NEW_PERSONAL_BEST = Pattern.compile(
		"(?m)^New personal best:\\s*(?<time>" + T + ")\\.?\\s*$",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern THEATRE_OF_BLOOD_COMPLETED_WAVE = Pattern.compile(
		"Wave '(?<wave>[^'\\n]{1,60})' \\((?<mode>Normal Mode|Hard Mode|Entry Mode)\\) complete!"
			+ "\\s*Duration:\\s*(?<time>" + T + ")",
		Pattern.CASE_INSENSITIVE);

	private DurationParser()
	{
	}

	/**
	 * @param message chat text with colour tags removed and line breaks turned into newlines
	 * @return every reliably named duration the message reports; empty if it reports none. The
	 *         Hallowed Sepulchre and Theatre of Blood can return more than one result.
	 */
	public static List<ParsedDuration> parse(String message)
	{
		final List<ParsedDuration> theatre = parseTheatreOfBloodWaveAndRaid(message);
		if (!theatre.isEmpty())
		{
			return theatre;
		}

		final Matcher floor = HS_FLOOR.matcher(message);
		if (floor.find())
		{
			final List<ParsedDuration> results = new ArrayList<>(2);
			results.add(from(floor, Activity.of("Hallowed Sepulchre Floor " + floor.group("floor"))));

			final Matcher overall = HS_OVERALL.matcher(message);
			if (overall.find())
			{
				results.add(from(overall, Activity.of("Hallowed Sepulchre")));
			}
			return results;
		}

		// Outside a verified multi-time adapter, a self-naming completion time wins over an
		// anonymous duration in the same message. Emitting an unattributed sub-time would create
		// a plausible-looking but unstable metric.
		final Matcher completion = COMPLETION_TIME.matcher(message);
		if (completion.find())
		{
			final String activity = completion.group("activity");
			return Collections.singletonList(from(completion, named(activity)));
		}

		final Matcher duration = ANONYMOUS_DURATION.matcher(message);
		if (duration.find())
		{
			// A quest speedrun line does not name the quest and has no corresponding KC line.
			// Preserve it for a future quest-specific completion adapter, but never let a nearby,
			// unrelated boss counter claim it.
			final boolean counterPairable = !"Speedrun".equalsIgnoreCase(duration.group("kind"));
			return Collections.singletonList(from(duration, null, counterPairable));
		}

		final Matcher subdued = SUBDUED_IN.matcher(message);
		if (subdued.find())
		{
			return Collections.singletonList(from(subdued, null));
		}

		final Matcher sleddingRace = SLEDDING_RACE.matcher(message);
		if (sleddingRace.find())
		{
			return Collections.singletonList(from(sleddingRace, Activity.of("Sledding")));
		}

		final Matcher anonymousPersonalBest = ANONYMOUS_NEW_PERSONAL_BEST.matcher(message);
		if (anonymousPersonalBest.find())
		{
			final long durationMs = Durations.toMillis(anonymousPersonalBest.group("time"));
			return Collections.singletonList(new ParsedDuration(null, durationMs, true, durationMs, false));
		}

		final Matcher bare = BARE_TIME.matcher(message);
		if (bare.find())
		{
			return Collections.singletonList(from(bare, null, false));
		}

		return Collections.emptyList();
	}

	/**
	 * Parses the one verified multi-time shape: a completed Theatre of Blood wave followed by
	 * the overall raid completion. Results are deliberately ordered overall first for stable
	 * event emission. The wave ID convention is
	 * {@code time.theatre_of_blood_<wave>_<mode>}.
	 */
	private static List<ParsedDuration> parseTheatreOfBloodWaveAndRaid(String message)
	{
		final Matcher wave = THEATRE_OF_BLOOD_COMPLETED_WAVE.matcher(message);
		final Matcher overall = COMPLETION_TIME.matcher(message);
		if (!wave.find() || !overall.find())
		{
			return Collections.emptyList();
		}

		final Activity raid = named(overall.group("activity"));
		if (raid == null || !"Theatre of Blood".equalsIgnoreCase(raid.getBase()))
		{
			return Collections.emptyList();
		}

		final String waveName = wave.group("wave").trim();
		final String mode = wave.group("mode").trim();
		if (MetricIds.slug(waveName).isEmpty())
		{
			return Collections.emptyList();
		}

		final String waveFull = "Theatre of Blood " + waveName + " " + mode;
		final Activity completedWave = new Activity(
			waveFull,
			"Theatre of Blood - " + waveName,
			mode);

		final List<ParsedDuration> results = new ArrayList<>(2);
		results.add(from(overall, raid));
		results.add(new ParsedDuration(
			completedWave,
			Durations.toMillis(wave.group("time")),
			false,
			null,
			true));
		return results;
	}

	@Nullable
	private static Activity named(@Nullable String activity)
	{
		if (activity == null)
		{
			return null;
		}
		final String trimmed = activity.trim();
		return trimmed.isEmpty() || MetricIds.slug(trimmed).isEmpty() ? null : Activity.of(trimmed);
	}

	private static ParsedDuration from(Matcher m, @Nullable Activity activity)
	{
		return from(m, activity, true);
	}

	private static ParsedDuration from(Matcher m, @Nullable Activity activity, boolean counterPairable)
	{
		final long durationMs = Durations.toMillis(m.group("time"));
		final boolean newPb = m.group("newpb") != null;
		final String pb = m.group("pb");

		// Keep the nullable branches separate to avoid implicit unboxing of null.
		final Long personalBestMs;
		if (newPb)
		{
			// When the game calls this a new personal best it does not restate the time, because
			// the run just set it.
			personalBestMs = durationMs;
		}
		else if (pb != null)
		{
			personalBestMs = Durations.toMillis(pb);
		}
		else
		{
			personalBestMs = null;
		}

		return new ParsedDuration(activity, durationMs, newPb, personalBestMs, counterPairable);
	}

	@Value
	public static class ParsedDuration
	{
		/** Null when the message named no activity, meaning this needs pairing with a counter. */
		@Nullable
		Activity activity;

		long durationMs;

		boolean personalBest;

		@Nullable
		Long personalBestMs;

		/** Whether a nearby cumulative counter is a valid source of this duration's identity. */
		boolean counterPairable;

		public ParsedDuration withActivity(Activity resolved)
		{
			return new ParsedDuration(resolved, durationMs, personalBest, personalBestMs, counterPairable);
		}
	}

}
