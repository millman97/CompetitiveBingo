package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.model.BingoGameState;
import com.millman97.competitivebingo.model.BoardView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Value;

/**
 * Everything the player needs to know about whether this plugin is working.
 */
@Value
@Builder(toBuilder = true)
public class SyncStatus
{
	public enum Connection
	{
		/** No server URL or join code entered yet. */
		NOT_CONFIGURED,
		/** Configured but Sync has not been pressed. Nothing has touched the network. */
		READY_TO_SYNC,
		/** A sync or refresh is in flight. */
		WORKING,
		/** Synced, and events are being reported. */
		LIVE,
		/** Synced, but not reporting. {@link #message} says why. */
		BLOCKED,
		/** Something went wrong. {@link #message} says what. */
		ERROR
	}

	Connection connection;

	/** Shown verbatim to the player. Empty when there is nothing to say. */
	String message;

	@Nullable
	String gameName;

	@Nullable
	String gameId;

	@Nullable
	BingoGameState gameState;

	@Nullable
	String teamName;

	/** Epoch millis. Null before a game is loaded. Lets the panel say how long until kick-off. */
	@Nullable
	Long startsAt;

	/** Epoch millis, for the time-remaining display. Null before a game is loaded. */
	@Nullable
	Long endsAt;

	@Builder.Default
	List<ReadinessCheck> readiness = Collections.emptyList();

	/**
	 * Metric ID to display label, in the order the game listed them. Used to name a metric in the
	 * activity list; {@link #trackedTargets} is what the targets section reads from.
	 */
	@Builder.Default
	Map<String, String> metricLabels = Collections.emptyMap();

	/** What this game is asking for, in the order the game listed it. */
	@Builder.Default
	List<TrackedTarget> trackedTargets = Collections.emptyList();

	/** The world this game asks players to be on, or null when it does not name one. */
	@Nullable
	Integer startWorld;

	/** A world to offer a hop to, or null when the player is already where the game wants them. */
	@Nullable
	Integer hopWorld;

	/**
	 * The board as it stood at the last sync, or null when this server does not publish one.
	 * Deliberately a snapshot: the plugin does not poll, so what is shown is what was true then.
	 */
	@Nullable
	BoardView board;

	/** Whether this game accepts events the player typed in by hand. */
	@Builder.Default
	boolean manualEntryAllowed = false;

	@Builder.Default
	boolean screenshotsEnabled = false;

	public static SyncStatus notConfigured()
	{
		return SyncStatus.builder()
			.connection(Connection.NOT_CONFIGURED)
			.message("Enter a server address and join code in the plugin settings.")
			.build();
	}

	public static SyncStatus readyToSync()
	{
		return SyncStatus.builder()
			.connection(Connection.READY_TO_SYNC)
			.message("Press Sync to join the game.")
			.build();
	}

	public static SyncStatus working(String what)
	{
		return SyncStatus.builder().connection(Connection.WORKING).message(what).build();
	}

	public static SyncStatus error(String message)
	{
		return SyncStatus.builder().connection(Connection.ERROR).message(message).build();
	}

	public SyncStatus withConnection(Connection newConnection, String newMessage)
	{
		return toBuilder().connection(newConnection).message(newMessage).build();
	}

	/** Metrics the player may submit by hand: everything tracked except the wildcards. */
	public List<TrackedTarget> getManualOptions()
	{
		if (!manualEntryAllowed)
		{
			return Collections.emptyList();
		}

		final List<TrackedTarget> options = new ArrayList<>(trackedTargets.size());
		for (TrackedTarget target : trackedTargets)
		{
			if (target.isManuallyEnterable())
			{
				options.add(target);
			}
		}
		return options;
	}

	public boolean isEmitting()
	{
		return connection == Connection.LIVE;
	}
}
