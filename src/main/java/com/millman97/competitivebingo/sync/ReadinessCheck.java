package com.millman97.competitivebingo.sync;

import lombok.Value;

/**
 * One thing that has to be true before this plugin can see the metrics a game asked for.
 */
@Value
public class ReadinessCheck
{
	public enum Id
	{
		KILL_COUNT_FILTER,
		COLLECTION_LOG_NOTIFICATION,
		WORLD_TYPE,
		LOOT_TRACKER
	}

	Id id;

	/** Short label for the panel. */
	String label;

	boolean satisfied;

	/**
	 * What the player should do about it, in one line. Empty when satisfied. Written as an
	 * instruction, not a description of the problem.
	 */
	String fix;

	static ReadinessCheck ok(Id id, String label)
	{
		return new ReadinessCheck(id, label, true, "");
	}

	static ReadinessCheck failed(Id id, String label, String fix)
	{
		return new ReadinessCheck(id, label, false, fix);
	}
}
