package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.ManualDetail;
import com.millman97.competitivebingo.model.MetricType;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a metric the player picked plus one line they typed into an {@link Observation}.
 *
 * The input is deliberately one free-form field: a time, a quantity, a count and a coin value all
 * go in the same box, and which of those it is follows from the metric's own namespace. The
 * reading is best-effort and never fatal — an input nothing can be made of still produces an
 * event, carrying the string itself, because the server and the organiser reviewing it can read
 * what a regex cannot.
 */
public final class ManualEntry
{
	public static final int MAX_INPUT_LENGTH = 2048;
	/** Digits with optional thousands separators, and an optional k/m/b suffix. */
	private static final Pattern NUMBER =
		Pattern.compile("(?<![\\p{Alnum}_.])(?<n>[+-]?\\d+(?:[ ,_]\\d{3})*(?:\\.\\d+)?)\\s*(?<unit>[kmb])?(?![\\p{Alnum}_.])",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern CONTAINS_TIME =
		Pattern.compile(".*?(?<time>" + Durations.TIME_GROUP + ").*", Pattern.DOTALL);
	private static final Pattern DIGIT = Pattern.compile("\\d");

	private ManualEntry()
	{
	}

	/**
	 * @param metricId the tracked metric the player chose; its prefix decides the metric type
	 * @param label    how the panel described that metric, kept for the event's detail
	 * @param input    exactly what the player typed, already trimmed
	 * @throws IllegalArgumentException if {@code metricId} is not one the plugin can emit, which
	 *                                  means the caller offered a choice it should not have
	 */
	public static Observation of(String metricId, String label, String input)
	{
		if (input == null || input.trim().isEmpty() || input.length() > MAX_INPUT_LENGTH)
		{
			throw new IllegalArgumentException("Enter between 1 and " + MAX_INPUT_LENGTH + " characters.");
		}
		final MetricType type = typeOf(metricId);
		final Long parsed = readNumber(input);

		Long value = null;
		Long delta = null;
		String interpretedAs = null;

		switch (type)
		{
			case DURATION:
			{
				final Long millis = readDuration(input);
				if (millis != null)
				{
					value = millis;
					interpretedAs = "duration";
				}
				break;
			}

			case COUNT:
				// A count is absolute by contract, so what they typed is the total, and this one
				// entry advances it by one.
				if (parsed != null)
				{
					value = parsed;
					delta = 1L;
					interpretedAs = "count";
				}
				break;

			case DROP:
			case KILL:
				// Delta-only metrics have no absolute; the number is how many.
				if (parsed != null)
				{
					delta = parsed;
					interpretedAs = "quantity";
				}
				else if (!DIGIT.matcher(input).find())
				{
					// "Dragon pickaxe" with no number still means one of them.
					delta = 1L;
				}
				break;

			case COLLECTION_LOG:
				// A slot is filled once; nothing typed can change that.
				delta = 1L;
				break;

			default:
				break;
		}

		return Observation.builder()
			.metricId(metricId)
			.type(type)
			.value(value)
			.delta(delta)
			.detail(new ManualDetail(label, input, interpretedAs))
			.build();
	}

	/**
	 * The metric type a metric ID belongs to.
	 *
	 * @throws IllegalArgumentException if it is not one the plugin can emit, which means the
	 *                                  caller offered a choice it should not have
	 */
	public static MetricType typeOf(String metricId)
	{
		final MetricType type = MetricIds.typeOf(metricId);
		if (type == null)
		{
			throw new IllegalArgumentException("'" + metricId + "' is not a metric this plugin can emit.");
		}
		return type;
	}

	/**
	 * Reads "12:34", "1:02:33.5" or a bare number of seconds.
	 *
	 * @return milliseconds, or null when there is no time in there
	 */
	@Nullable
	static Long readDuration(String input)
	{
		final Matcher m = CONTAINS_TIME.matcher(input.trim());
		if (m.matches())
		{
			try
			{
				if (m.start("time") > 0 && input.trim().charAt(m.start("time") - 1) == '-')
				{
					return null;
				}
				return Durations.toMillis(m.group("time"));
			}
			catch (IllegalArgumentException e)
			{
				return null;
			}
		}

		final BigDecimal seconds = readDecimal(input);
		try
		{
			return seconds == null ? null : seconds.multiply(BigDecimal.valueOf(1000)).longValueExact();
		}
		catch (ArithmeticException e)
		{
			return null;
		}
	}

	/**
	 * Reads the first number in the input, tolerating separators and a k/m/b suffix so "1.5m"
	 * and "1,500,000" both work.
	 *
	 * @return the value, or null when there is no number in there
	 */
	@Nullable
	static Long readNumber(String input)
	{
		final BigDecimal number = readDecimal(input);
		try
		{
			return number == null ? null : number.longValueExact();
		}
		catch (ArithmeticException e)
		{
			return null;
		}
	}

	@Nullable
	private static BigDecimal readDecimal(String input)
	{
		final Matcher m = NUMBER.matcher(input.trim());
		if (!m.find())
		{
			return null;
		}

		final BigDecimal base;
		try
		{
			base = new BigDecimal(m.group("n").replaceAll("[ ,_]", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
		if (base.signum() < 0)
		{
			return null;
		}

		final String unit = m.group("unit");
		final long multiplier;
		if (unit == null)
		{
			multiplier = 1L;
		}
		else
		{
			switch (Character.toLowerCase(unit.charAt(0)))
			{
				case 'k':
					multiplier = 1_000L;
					break;
				case 'm':
					multiplier = 1_000_000L;
					break;
				default:
					multiplier = 1_000_000_000L;
					break;
			}
		}

		return base.multiply(BigDecimal.valueOf(multiplier));
	}
}
