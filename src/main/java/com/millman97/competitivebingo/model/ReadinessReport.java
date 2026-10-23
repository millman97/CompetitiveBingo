package com.millman97.competitivebingo.model;

import java.util.List;
import lombok.Value;

/**
 * Body of {@code POST /api/plugin/ready}. Tells the server whether this player's client is in a
 * state where it can actually observe the metrics the game asks for.
 *
 * Sent when a sync completes and whenever the answer changes.
 */
@Value
public class ReadinessReport
{
	/** True only when every applicable check passes. */
	boolean ready;

	/** Every check that applies to this game, so an organiser can see what to tell the player. */
	List<CheckState> checks;

	@Value
	public static class CheckState
	{
		/** Stable identifier, e.g. "LOOT_TRACKER". Safe to switch on. */
		String id;

		boolean satisfied;
	}
}
