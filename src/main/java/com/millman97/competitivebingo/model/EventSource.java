package com.millman97.competitivebingo.model;

/**
 * Where an event came from, so the server can weigh it accordingly.
 */
public enum EventSource
{
	/** The plugin saw it happen: a chat line, or a loot event. */
	OBSERVED,
	/**
	 * The player typed it into the panel and pressed send. The plugin vouches for the screenshot
	 * and for nothing else — what the value means is the server's to decide, and a game that
	 * accepts these should expect to review them.
	 */
	MANUAL
}
