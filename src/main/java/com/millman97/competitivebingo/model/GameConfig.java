package com.millman97.competitivebingo.model;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * Response of {@code GET /api/plugin/game}. Describes the one game a join code refers to.
 */
@Value
public class GameConfig
{
	String gameId;

	String name;

	BingoGameState state;

	/** Epoch millis. */
	long startsAt;

	/** Epoch millis. */
	long endsAt;

	/** Must equal {@link Protocol#SCHEMA_VERSION} or the plugin refuses to sync. */
	int schemaVersion;

	/** Semver. The plugin refuses to sync if {@link Protocol#PLUGIN_VERSION} is older. */
	String minPluginVersion;

	/**
	 * The only metric IDs the plugin may emit. Exact IDs, plus the two wildcards
	 * "collection_log.*" and "drop.*". No other wildcard or pattern form is supported.
	 */
	List<String> trackedMetrics;

	/** Metric ID to human-readable label, for the side panel. Display only; never behavioural. */
	@Nullable
	Map<String, String> metricLabels;

	/**
	 * Metric ID to how many of it this game wants, so the panel can say "5 Araxxor kills" rather
	 * than "Araxxor kills". Display only: the plugin never stops reporting because a target was
	 * met, and never reports progress towards one. A metric with no entry has no target, which is
	 * the normal case for the two wildcards.
	 */
	@Nullable
	Map<String, Long> metricTargets;

	/**
	 * Metrics to emit for drops falling in a value range, applied verbatim. Null or empty means
	 * this game prices nothing and the plugin looks up no prices at all.
	 *
	 * A rule's metric ID does not need to appear in {@link #trackedMetrics}: naming a rule is
	 * what asks for it. Listing it as well is harmless.
	 */
	@Nullable
	List<DropValueRule> dropValueRules;

	/**
	 * Whether this game accepts events the player typed in by hand. Null means it does — the
	 * panel offers manual entry unless a server says otherwise, so that a server which has never
	 * heard of the field behaves the way its players expect.
	 */
	@Nullable
	Boolean manualEntryAllowed;

	/** Whether the server wants screenshots for this game at all. */
	boolean screenshotsRequested;

	/**
	 * WorldType names this game refuses to accept events from, e.g. "SEASONAL". The plugin
	 * applies the list verbatim and has no opinion of its own; null or empty blocks nothing.
	 */
	@Nullable
	List<String> blockedWorldTypes;

	/**
	 * The world the organiser wants players on, or null if the game does not care. The panel
	 * offers a hop when the player is elsewhere.
	 */
	@Nullable
	Integer startWorld;
}
