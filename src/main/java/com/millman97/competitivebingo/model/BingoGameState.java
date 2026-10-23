package com.millman97.competitivebingo.model;

/**
 * Lifecycle of a bingo game, as reported by the server. Named to avoid colliding with
 * {@link net.runelite.api.GameState}.
 */
public enum BingoGameState
{
	/** Accepting sync, not yet accepting events. */
	UPCOMING,
	/** Accepting events. */
	LIVE,
	/** Finished. The plugin stops emitting. */
	ENDED,
	/** Temporarily frozen by an admin, e.g. during adjudication. The plugin stops emitting. */
	LOCKED
}
