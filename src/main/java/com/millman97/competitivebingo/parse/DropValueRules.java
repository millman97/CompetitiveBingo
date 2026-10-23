package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.DropDetail;
import com.millman97.competitivebingo.model.DropValueRule;
import com.millman97.competitivebingo.model.MetricType;
import com.millman97.competitivebingo.model.Protocol;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Applies a game's {@link DropValueRule}s to a drop.
 *
 * The rules come from the server and are applied exactly as written. The plugin's only additions
 * are the prices, which come from the client, and the refusal to accept a rule naming a metric ID
 * it could never emit.
 */
public final class DropValueRules
{
	/**
	 * Prices for one item, from whatever the client knows. Both may be null; an item with no
	 * usable price simply matches no rule.
	 */
	public interface Prices
	{
		@Nullable
		Long grandExchange(int itemId);

		@Nullable
		Long highAlchemy(int itemId);
	}

	private final List<DropValueRule> rules;

	private DropValueRules(List<DropValueRule> rules)
	{
		this.rules = rules;
	}

	/** A set of rules that matches nothing, for a game that asked for no value tracking. */
	public static DropValueRules empty()
	{
		return new DropValueRules(Collections.emptyList());
	}

	/**
	 * @param configured the game's list, verbatim
	 * @throws IllegalArgumentException if a rule is unusable — no metric ID, a metric ID outside
	 *                                  the drop namespace, or a range that cannot match. A
	 *                                  malformed config is refused rather than quietly ignored,
	 *                                  because a rule that silently never fires looks exactly
	 *                                  like a player not getting the drop.
	 */
	public static DropValueRules of(@Nullable Collection<DropValueRule> configured)
	{
		if (configured == null || configured.isEmpty())
		{
			return empty();
		}

		final List<DropValueRule> checked = new ArrayList<>(configured.size());
		final Set<String> metricIds = new HashSet<>();
		if (configured.size() > 32)
		{
			throw new IllegalArgumentException("A game may configure at most 32 drop value rules.");
		}
		for (DropValueRule rule : configured)
		{
			if (rule == null)
			{
				throw new IllegalArgumentException("A drop value rule cannot be null.");
			}
			if (rule.getLabel() != null && rule.getLabel().length() > Protocol.MAX_TEXT_LENGTH)
			{
				throw new IllegalArgumentException("A drop value label exceeds 1024 characters.");
			}
			final String metricId = rule.getMetricId();
			if (!MetricIds.isExact(metricId) || !metricId.startsWith(MetricIds.DROP_PREFIX))
			{
				throw new IllegalArgumentException("A drop value rule must name a metric ID under '"
					+ MetricIds.DROP_PREFIX + "', not '" + metricId + "'.");
			}
			if (!metricIds.add(metricId))
			{
				throw new IllegalArgumentException("Duplicate drop value metric: " + metricId);
			}
			if ((rule.getMinValue() != null && rule.getMinValue() < 0)
				|| (rule.getMaxValue() != null && rule.getMaxValue() < 0))
			{
				throw new IllegalArgumentException("Drop value bounds must not be negative.");
			}

			if (rule.getMinValue() != null && rule.getMaxValue() != null
				&& rule.getMinValue() > rule.getMaxValue())
			{
				throw new IllegalArgumentException("Drop value rule '" + metricId + "' has a minimum ("
					+ rule.getMinValue() + ") above its maximum (" + rule.getMaxValue() + ").");
			}

			checked.add(rule);
		}

		return new DropValueRules(Collections.unmodifiableList(checked));
	}

	/** Whether any rule exists, and so whether a drop is worth pricing at all. */
	public boolean isEmpty()
	{
		return rules.isEmpty();
	}

	public List<DropValueRule> asList()
	{
		return rules;
	}

	/**
	 * The metric IDs these rules ask for, so they can be added to the filter. Naming a rule is
	 * what asks for its metric; it need not appear in {@code trackedMetrics} as well.
	 */
	public List<String> metricIds()
	{
		final List<String> ids = new ArrayList<>(rules.size());
		for (DropValueRule rule : rules)
		{
			ids.add(rule.getMetricId());
		}
		return ids;
	}

	/**
	 * One observation per rule the stack matches. A stack can match several — overlapping bands
	 * are the server's business, and a game may well want both "1m+" and "10m+" to fire.
	 *
	 * @param drop   the drop as already observed, whose detail is reused with prices attached
	 * @param prices the client's price data
	 */
	public List<Observation> apply(DropDetail drop, Prices prices)
	{
		if (rules.isEmpty())
		{
			return Collections.emptyList();
		}

		final Long ge = prices.grandExchange(drop.getItemId());
		final Long ha = prices.highAlchemy(drop.getItemId());

		final List<Observation> matched = new ArrayList<>();
		for (DropValueRule rule : rules)
		{
			final Long unit = rule.getValuation() == DropValueRule.Valuation.HIGH_ALCHEMY ? ha : ge;
			if (unit == null)
			{
				continue;
			}

			// Priced per stack: ten items worth 200k each clear a 1m band, and one worth 200k
			// does not.
			final long stackValue = unit * drop.getQuantity();

			if (rule.getMinValue() != null && stackValue < rule.getMinValue())
			{
				continue;
			}
			if (rule.getMaxValue() != null && stackValue > rule.getMaxValue())
			{
				continue;
			}

			final long delta = rule.getCounts() == DropValueRule.Counts.VALUE
				? stackValue
				: drop.getQuantity();

			matched.add(Observation.builder()
				.metricId(rule.getMetricId())
				.type(MetricType.DROP)
				.value(null)
				.delta(delta)
				.detail(new DropDetail(
					drop.getItemId(),
					drop.getItemName(),
					drop.getQuantity(),
					drop.getSource(),
					drop.getSourceType(),
					drop.getKillCount(),
					ge,
					ha,
					stackValue,
					drop.getObservationOrdinal()))
				.build());
		}

		return matched;
	}
}
