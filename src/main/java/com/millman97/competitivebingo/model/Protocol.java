package com.millman97.competitivebingo.model;

import com.google.gson.Gson;

/**
 * Wire protocol constants shared by every request this plugin makes.
 */
public final class Protocol
{
	/**
	 * Version of the event envelope and game config shapes documented in {@code docs/api-contract.md}.
	 * Bumped only for breaking changes. The server rejects a mismatch with HTTP 409.
	 */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Sent as {@code pluginVersion} on sync so the server can enforce {@code minPluginVersion}.
	 * Keep in step with {@code version} in runelite-plugin.properties and build.gradle.
	 */
	public static final String PLUGIN_VERSION = "0.1.0";
	public static final int MAX_TEXT_LENGTH = 1024;

	/**
	 * The Gson configuration every outgoing body is built with.
	 *
	 * @param injected RuneLite's own Gson, so its settings are inherited rather than replaced
	 */
	public static Gson wireGson(Gson injected)
	{
		return injected.newBuilder().serializeNulls().create();
	}

	private Protocol()
	{
	}
}
