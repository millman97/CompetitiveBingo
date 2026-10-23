package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.CollectionLogDetail;
import com.millman97.competitivebingo.model.CountDetail;
import com.millman97.competitivebingo.model.DurationDetail;
import com.millman97.competitivebingo.model.MetricType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns chat messages into {@link Observation}s.
 *
 * An anonymous duration ("Fight duration: 1:23") is held until something names it, and a namer
 * is held in case a duration follows; either order occurs. Unpaired durations are dropped.
 *
 * Two things can name one: a counter message, and a completion announcement such as the kind
 * the Barracuda Trials and sledding course print. The announcement wins where both arrive,
 * because it can carry more precise activity context (including a Barracuda rank).
 *
 * Takes no RuneLite types, so it is exercised directly in tests.
 */
@Slf4j
@Singleton
public class ChatObserver
{
	/**
	 * How long one half of a duration/counter pair waits for the other. Ten ticks, the same
	 * window Dink settled on.
	 */
	private static final int PAIR_WINDOW_TICKS = 10;

	private final MetricAliases aliases;

	@Nullable
	private Pending<Activity> pendingCount;

	@Nullable
	private Pending<DurationParser.ParsedDuration> pendingDuration;

	/**
	 * An activity named by a completion announcement rather than by a counter, and the rank it
	 * came with. Held separately from {@link #pendingCount} and preferred over it, because it is
	 * the only half of the pair that knows the rank — a counter message for the same trial would
	 * otherwise overwrite it and lose that.
	 */
	@Nullable
	private Pending<Activity> pendingCompletion;

	/** Lowercased activity name to its last-seen count, so a drop can report its kill count. */
	private final Map<String, Long> lastCounts = new HashMap<>();

	@Inject
	public ChatObserver(MetricAliases aliases)
	{
		this.aliases = aliases;
	}

	/**
	 * Clears everything remembered about the current session. Called on login, logout and
	 * account switch — a kill count from a previous session must never label a new drop.
	 */
	public void reset()
	{
		pendingCount = null;
		pendingDuration = null;
		pendingCompletion = null;
		lastCounts.clear();
	}

	/**
	 * The last count observed for an activity this session, for {@code DropDetail.killCount}.
	 *
	 * @return the count, or null if no counter message has been seen for it
	 */
	@Nullable
	public Integer lastCountFor(String activityName)
	{
		final Long count = lastCounts.get(activityName.toLowerCase(Locale.ENGLISH));
		return count == null ? null : Math.toIntExact(count);
	}

	/**
	 * @param rawMessage the message as the client delivered it, colour tags and all
	 * @param gameTick   {@code client.getTickCount()} at delivery
	 * @return every observation the message yields, in the order they should be reported
	 */
	public List<Observation> onChatMessage(String rawMessage, int gameTick)
	{
		final String message = ChatText.sanitise(rawMessage);
		final List<Observation> observations = new ArrayList<>();

		// Names an activity but reports nothing itself, so it is read before anything else and
		// only ever feeds the pairing.
		CompletionParser.parse(message).ifPresent(activity -> {
			pendingCompletion = new Pending<>(activity, gameTick, message);

			// The time may have arrived first, in which case it is sitting unnamed.
			if (pendingDuration != null && pendingDuration.isWithinWindow(gameTick))
			{
				final Pending<DurationParser.ParsedDuration> waiting = pendingDuration;
				pendingDuration = null;
				pendingCompletion = null;
				observations.add(durationObservation(
					waiting.value.withActivity(activity), waiting.message));
			}
		});

		final Optional<CountParser.ParsedCount> count = CountParser.parse(message);
		count.ifPresent(parsed -> {
			lastCounts.put(parsed.getActivity().getFull().toLowerCase(Locale.ENGLISH), parsed.getCount());
			observations.add(countObservation(parsed, message));

			// A duration may already be waiting for this name. It keeps its own message.
			if (pendingDuration != null
				&& pendingDuration.value.isCounterPairable()
				&& pendingDuration.isWithinWindow(gameTick))
			{
				final Pending<DurationParser.ParsedDuration> waiting = pendingDuration;
				pendingDuration = null;
				observations.add(durationObservation(
					waiting.value.withActivity(parsed.getActivity()), waiting.message));
			}

			pendingCount = new Pending<>(parsed.getActivity(), gameTick, message);
		});

		for (DurationParser.ParsedDuration duration : DurationParser.parse(message))
		{
			resolve(duration, gameTick, message)
				.ifPresent(named -> observations.add(durationObservation(named, message)));
		}

		if (observations.isEmpty())
		{
			CollectionLogParser.parse(message)
				.ifPresent(item -> observations.add(collectionLogObservation(item)));
		}

		return observations;
	}

	/**
	 * Names an anonymous duration from a nearby counter message, or parks it to be named by one
	 * that has not arrived yet.
	 */
	private Optional<DurationParser.ParsedDuration> resolve(DurationParser.ParsedDuration duration, int gameTick, String message)
	{
		if (duration.getActivity() != null)
		{
			return Optional.of(duration);
		}

		// A completion announcement wins over a counter: both name the activity, but only it
		// knows which rank the run was.
		if (pendingCompletion != null && pendingCompletion.isWithinWindow(gameTick))
		{
			final Activity activity = pendingCompletion.value;
			pendingCompletion = null;
			return Optional.of(duration.withActivity(activity));
		}

		if (duration.isCounterPairable()
			&& pendingCount != null
			&& pendingCount.isWithinWindow(gameTick))
		{
			final Activity activity = pendingCount.value;
			pendingCount = null;
			return Optional.of(duration.withActivity(activity));
		}

		log.debug("Holding an unnamed duration of {}ms at tick {}, waiting for a counter message",
			duration.getDurationMs(), gameTick);
		pendingDuration = new Pending<>(duration, gameTick, message);
		return Optional.empty();
	}

	private Observation countObservation(CountParser.ParsedCount parsed, String message)
	{
		return Observation.builder()
			.metricId(aliases.resolve(MetricType.COUNT, parsed.getActivity()))
			.type(MetricType.COUNT)
			.value(parsed.getCount())
			// A counter message announces exactly one increment, even the first one we see.
			.delta(1L)
			.detail(new CountDetail(parsed.getActivity().getFull(), message))
			.build();
	}

	private Observation durationObservation(DurationParser.ParsedDuration duration, String message)
	{
		final Activity activity = duration.getActivity();
		return Observation.builder()
			.metricId(aliases.resolve(MetricType.DURATION, activity))
			.type(MetricType.DURATION)
			// Durations are absolute times, with no incremental value.
			.value(duration.getDurationMs())
			.delta(null)
			.detail(new DurationDetail(
				activity.getBase(),
				activity.getVariant(),
				duration.getDurationMs(),
				duration.isPersonalBest(),
				duration.getPersonalBestMs(),
				message))
			.build();
	}

	private Observation collectionLogObservation(String itemName)
	{
		return Observation.builder()
			.metricId(MetricIds.of(MetricType.COLLECTION_LOG, itemName))
			.type(MetricType.COLLECTION_LOG)
			// The game only states these while the collection log interface is open.
			.value(null)
			.delta(1L)
			.detail(new CollectionLogDetail(itemName, null, null))
			.build();
	}

	/** A half-pair, remembered with the tick it arrived on. */
	private static final class Pending<T>
	{
		private final T value;
		private final int gameTick;
		// The sanitised message this half came from, so the event reports its own source line.
		private final String message;

		Pending(T value, int gameTick, String message)
		{
			this.value = value;
			this.gameTick = gameTick;
			this.message = message;
		}

		boolean isWithinWindow(int now)
		{
			return now - gameTick >= 0 && now - gameTick <= PAIR_WINDOW_TICKS;
		}
	}
}
