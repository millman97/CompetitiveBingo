package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.CompetitiveBingoConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResumeStoreTest
{
	private static final String ACCOUNT = "123";
	private static final String URL = "https://bingo.example/";
	private static final String CODE = "SWAMP-TOAD-42";
	private final Map<String, String> config = new HashMap<>();
	private final ResumeStore store = new ResumeStore(new ResumeStore.Storage()
	{
		public String get(String key) { return config.get(key); }
		public void set(String key, String value) { config.put(key, value); }
		public void unset(String key) { config.remove(key); }
	});

	private void synced(String account)
	{
		store.rememberGame(account, URL, CODE, System.currentTimeMillis() + 3_600_000);
	}

	@Test
	public void resumeRequiresTheSameAccountServerAndGame()
	{
		assertFalse(store.canResume(ACCOUNT, URL, CODE));
		synced(ACCOUNT);
		assertTrue(store.canResume(ACCOUNT, URL, CODE));
		assertFalse(store.canResume("456", URL, CODE));
		assertFalse(store.canResume(ACCOUNT, "https://other.example/", CODE));
		assertFalse(store.canResume(ACCOUNT, URL, "ANOTHER-GAME"));
	}

	@Test
	public void forgettingOneAccountLeavesTheOtherAlone()
	{
		synced(ACCOUNT);
		synced("456");
		store.forgetGame(ACCOUNT);
		assertFalse(store.canResume(ACCOUNT, URL, CODE));
		assertTrue(store.canResume("456", URL, CODE));
	}

	@Test
	public void expiredOrUnreadablePermissionRequiresAnotherExplicitSync()
	{
		for (String expiry : new String[]{"0", "not-a-number", ""})
		{
			synced(ACCOUNT);
			config.put(CompetitiveBingoConfig.KEY_RESUME_EXPIRES_PREFIX + ACCOUNT, expiry);
			assertFalse(store.canResume(ACCOUNT, URL, CODE));
			assertNull(config.get(CompetitiveBingoConfig.KEY_LAST_JOIN_CODE_PREFIX + ACCOUNT));
		}
		synced(ACCOUNT);
		config.remove(CompetitiveBingoConfig.KEY_RESUME_EXPIRES_PREFIX + ACCOUNT);
		assertFalse(store.canResume(ACCOUNT, URL, CODE));
	}

	@Test
	public void legacyTokenIsRemovedWhileExistingResumePermissionIsPreserved()
	{
		synced(ACCOUNT);
		config.put("playerToken." + ACCOUNT, "legacy-secret");
		assertTrue(store.canResume(ACCOUNT, URL, CODE));
		assertNull(config.get("playerToken." + ACCOUNT));
		assertTrue(config.values().stream().noneMatch("legacy-secret"::equals));
	}

	@Test
	public void aConfigWithoutTheServerBindingStillRequiresSync()
	{
		synced(ACCOUNT);
		config.remove(CompetitiveBingoConfig.KEY_LAST_SERVER_URL_PREFIX + ACCOUNT);
		assertFalse(store.canResume(ACCOUNT, URL, CODE));
	}

	@Test
	public void formerDryRunNeverBecomesAnAutomaticLiveConnection()
	{
		synced(ACCOUNT);
		config.put("dryRun", "true");
		config.put("debugLogging", "true");
		assertFalse(store.canResume(ACCOUNT, URL, CODE));
		synced(ACCOUNT);
		assertTrue(store.canResume(ACCOUNT, URL, CODE));
		assertNull(config.get("dryRun"));
		assertNull(config.get("debugLogging"));
	}
}
