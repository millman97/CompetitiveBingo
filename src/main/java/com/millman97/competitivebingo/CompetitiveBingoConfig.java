package com.millman97.competitivebingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(CompetitiveBingoConfig.GROUP)
public interface CompetitiveBingoConfig extends Config
{
	String GROUP = "competitive-bingo";

	/** Resume expiry keeps its existing key so saved settings migrate without a reset. */
	String KEY_RESUME_EXPIRES_PREFIX = "tokenExpiresAt.";

	/** The join code this account last synced to, so a restart can rejoin the same game. */
	String KEY_LAST_JOIN_CODE_PREFIX = "lastJoinCode.";
	String KEY_LAST_SERVER_URL_PREFIX = "lastServerUrl.";

	@ConfigSection(
		name = "Connection",
		description = "Which bingo server to talk to, and which game to join",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "Screenshots",
		description = "What is captured when the server asks for evidence",
		position = 1
	)
	String screenshotSection = "screenshots";

	@ConfigItem(
		keyName = "serverUrl",
		name = "Server URL",
		description = "Base URL of the bingo server you were given, e.g. https://bingo.example.com. "
			+ "Nothing is sent anywhere until this is set and you press Sync.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = connectionSection,
		position = 0
	)
	default String serverUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "joinCode",
		name = "Join code",
		description = "The code for the bingo game you are joining",
		section = connectionSection,
		position = 1
	)
	default String joinCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "enableScreenshots",
		name = "Send screenshots",
		description = "Attach a screenshot to events when the server asks for one as evidence",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = screenshotSection,
		position = 0
	)
	default boolean enableScreenshots()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hideChatbox",
		name = "Hide chatbox",
		description = "Blank the chatbox and private message overlay in screenshots, so your "
			+ "conversations are not uploaded",
		section = screenshotSection,
		position = 1
	)
	default boolean hideChatbox()
	{
		return true;
	}

}
