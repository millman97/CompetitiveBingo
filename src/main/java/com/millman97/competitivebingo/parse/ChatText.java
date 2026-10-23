package com.millman97.competitivebingo.parse;

import java.util.regex.Pattern;
import net.runelite.client.util.Text;

/**
 * Turns a raw chat message into the plain text the parsers expect.
 */
public final class ChatText
{
	/** OSRS pads some names with a non-breaking space, which no parser here expects. */
	private static final char NBSP = '\u00A0';

	/**
	 * Jagex also sends named formatting tokens such as {@code @mes_hl_red@}. These are not
	 * HTML-like tags, so RuneLite's {@link Text#removeTags(String)} deliberately leaves them
	 * behind. Restrict this to token characters rather than stripping arbitrary text between
	 * at-signs (for example a player's chat message containing an email address).
	 */
	private static final Pattern NAMED_FORMATTING_TOKEN = Pattern.compile("@[A-Za-z0-9_]+@");

	private ChatText()
	{
	}

	/**
	 * Strips RuneLite colour and formatting tags, preserving line breaks.
	 */
	public static String sanitise(String rawMessage)
	{
		final String withoutNamedTokens = NAMED_FORMATTING_TOKEN.matcher(rawMessage).replaceAll("");
		return Text.removeTags(withoutNamedTokens.replace("<br>", "\n"))
			.replace(NBSP, ' ')
			.trim();
	}
}
