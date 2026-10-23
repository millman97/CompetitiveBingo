package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.CompetitiveBingoConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/** Saves permission to rejoin, never the bearer token returned by a handshake. */
@Singleton
public class ResumeStore
{
	interface Storage
	{
		String get(String key);
		void set(String key, String value);
		void unset(String key);
	}

	private final Storage storage;
	private static final String LEGACY_TOKEN_PREFIX = "playerToken.";

	@Inject
	public ResumeStore(ConfigManager configManager)
	{
		this(new Storage()
		{
			@Override
			public String get(String key)
			{
				return configManager.getConfiguration(CompetitiveBingoConfig.GROUP, key);
			}

			@Override
			public void set(String key, String value)
			{
				configManager.setConfiguration(CompetitiveBingoConfig.GROUP, key, value);
			}

			@Override
			public void unset(String key)
			{
				configManager.unsetConfiguration(CompetitiveBingoConfig.GROUP, key);
			}
		});
	}

	ResumeStore(Storage storage)
	{
		this.storage = storage;
	}

	public void rememberGame(String account, String serverUrl, String joinCode, long expiresAt)
	{
		storage.set(CompetitiveBingoConfig.KEY_LAST_SERVER_URL_PREFIX + account, serverUrl);
		storage.set(CompetitiveBingoConfig.KEY_LAST_JOIN_CODE_PREFIX + account, joinCode);
		storage.set(CompetitiveBingoConfig.KEY_RESUME_EXPIRES_PREFIX + account, Long.toString(expiresAt));
		storage.unset(LEGACY_TOKEN_PREFIX + account);
		// An explicit successful Sync retires the former development settings.
		storage.unset("dryRun");
		storage.unset("debugLogging");
	}

	public boolean canResume(String account, String serverUrl, String joinCode)
	{
		storage.unset(LEGACY_TOKEN_PREFIX + account);
		// Removing dry run must not turn an older installation into a live connection.
		if (Boolean.parseBoolean(storage.get("dryRun"))
			|| !serverUrl.equals(storage.get(CompetitiveBingoConfig.KEY_LAST_SERVER_URL_PREFIX + account))
			|| !joinCode.equals(storage.get(CompetitiveBingoConfig.KEY_LAST_JOIN_CODE_PREFIX + account)))
		{
			return false;
		}
		try
		{
			final long expiry = Long.parseLong(storage.get(CompetitiveBingoConfig.KEY_RESUME_EXPIRES_PREFIX + account));
			if (expiry > System.currentTimeMillis()) { return true; }
		}
		catch (NumberFormatException ignored)
		{
			// Missing or hand-edited expiry requires another explicit Sync.
		}
		forgetGame(account);
		return false;
	}

	public void forgetGame(String account)
	{
		storage.unset(CompetitiveBingoConfig.KEY_LAST_SERVER_URL_PREFIX + account);
		storage.unset(CompetitiveBingoConfig.KEY_LAST_JOIN_CODE_PREFIX + account);
		storage.unset(CompetitiveBingoConfig.KEY_RESUME_EXPIRES_PREFIX + account);
		storage.unset(LEGACY_TOKEN_PREFIX + account);
	}
}
