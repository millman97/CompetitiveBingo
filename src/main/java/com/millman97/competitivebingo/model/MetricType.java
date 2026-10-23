package com.millman97.competitivebingo.model;

/**
 * The metric families this plugin observes. Anything that does not fit one of these
 * is out of scope and must not be emitted.
 */
public enum MetricType
{
	/** A cumulative counter the game reports an absolute value for, e.g. a boss kill count. */
	COUNT,
	/** A server-attributed NPC kill for a monster with no authoritative cumulative count. */
	KILL,
	/** An item received, sourced from RuneLite's loot events rather than chat. */
	DROP,
	/** A completion time for a fight, raid or challenge. */
	DURATION,
	/** A new entry added to the player's collection log. */
	COLLECTION_LOG
}
