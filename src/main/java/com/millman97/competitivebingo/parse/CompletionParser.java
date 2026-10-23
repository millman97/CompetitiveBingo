package com.millman97.competitivebingo.parse;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises activity announcements that name a separately reported time.
 *
 * For example, the Barracuda Trials print:
 *
 * "You have completed the Tempor Tantrum and achieved the Marlin rank."
 *
 * It states no count and no time, so it produces no observation of its own. What it does is name
 * the activity, which the trials' own time message ("Time: 4:04.") does not, and state the rank,
 * which nothing else does. {@link ChatObserver} uses it to label the time that follows.
 *
 * The rank clause is required rather than optional. "You have completed ..." opens a great many
 * unrelated messages — quests, tasks, diaries — and matching those would let any of them name a
 * duration that had nothing to do with them.
 */
public final class CompletionParser
{
	private static final Pattern SLEDDING_FINISH = Pattern.compile(
		"\\bYou land softly in a pile of snow at the bottom of the slope\\b",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern COMPLETED_WITH_RANK = Pattern.compile(
		"\\bYou have completed (?:the )?(?<activity>.+?) and achieved the (?<rank>[^.!]+?) rank\\b",
		Pattern.CASE_INSENSITIVE);

	private CompletionParser()
	{
	}

	/**
	 * @param message chat text with colour tags already removed
	 * @return the activity, with a rank as its variant when the announcement supplies one, or
	 *         empty if this is not a recognised activity completion
	 */
	public static Optional<Activity> parse(String message)
	{
		if (SLEDDING_FINISH.matcher(message).find())
		{
			return Optional.of(Activity.of("Sledding"));
		}

		final Matcher m = COMPLETED_WITH_RANK.matcher(message);
		if (!m.find())
		{
			return Optional.empty();
		}

		final String activity = m.group("activity").trim();
		final String rank = m.group("rank").trim();

		if (activity.isEmpty() || MetricIds.slug(activity).isEmpty())
		{
			return Optional.empty();
		}

		// The metric ID comes from the activity alone, so a Marlin run and a Swordfish run of the
		// same trial are one metric. The rank rides along as the variant, which is where the
		// contract already puts a sub-mode, and is the server's to score on if it cares.
		return Optional.of(new Activity(activity, activity, rank.isEmpty() ? null : rank));
	}
}
