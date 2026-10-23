package com.millman97.competitivebingo.parse;

import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * An activity name as the game spelled it, split into a base name and an optional mode.
 *
 * The metric ID uses the whole name, so "Tombs of Amascut: Expert Mode" and "Tombs of Amascut"
 * are different metrics. The split is for display only.
 */
@Value
public class Activity
{
	/**
	 * Mode suffixes the game appends, longest first so a longer suffix wins the match.
	 */
	private static final List<String> MODES = Arrays.asList(
		"Challenge Mode",
		"Entry Mode",
		"Expert Mode",
		"Hard Mode",
		"Normal Mode",
		"Story Mode"
	);

	/** The whole name, exactly as the game spelled it. The metric ID derives from this. */
	String full;

	/** The name with any mode suffix removed, e.g. "Tombs of Amascut". */
	String base;

	/** The mode suffix, e.g. "Expert Mode", or null when the game named no mode. */
	@Nullable
	String variant;

	public static Activity of(String full)
	{
		final String trimmed = full.trim();
		for (String mode : MODES)
		{
			if (!trimmed.endsWith(mode))
			{
				continue;
			}

			// Trim the mode and any ":" or whitespace joining it to the base name.
			String base = trimmed.substring(0, trimmed.length() - mode.length()).trim();
			if (base.endsWith(":"))
			{
				base = base.substring(0, base.length() - 1).trim();
			}

			// "Normal Mode" on its own is not a mode suffix, it is the whole name.
			if (!base.isEmpty())
			{
				return new Activity(trimmed, base, mode);
			}
		}
		return new Activity(trimmed, trimmed, null);
	}
}
