package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * A metric the game wants reported for any drop whose value falls in a range.
 *
 * The plugin holds no opinion about what a valuable drop is: it prices what it received, applies
 * the bounds verbatim and emits the metric ID the rule names. Deciding which ranges matter, and
 * what they are worth, is the server's job — the same arrangement as
 * {@link GameConfig#getBlockedWorldTypes()}.
 */
@Value
public class DropValueRule
{
	/**
	 * The metric ID to emit when a drop matches, e.g. "drop.worth_1m_plus". Must be a valid
	 * drop metric ID; the plugin refuses a config whose rule names anything else.
	 */
	String metricId;

	/** Display label for the panel. Null falls back to {@code metricId}. */
	@Nullable
	String label;

	/** Inclusive lower bound in coins, or null for no lower bound. */
	@Nullable
	Long minValue;

	/** Inclusive upper bound in coins, or null for no upper bound. */
	@Nullable
	Long maxValue;

	/** Which price to compare against. Null means {@link Valuation#GRAND_EXCHANGE}. */
	@Nullable
	Valuation valuation;

	/** What one matching stack contributes. Null means {@link Counts#ITEMS}. */
	@Nullable
	Counts counts;

	public enum Valuation
	{
		/** RuneLite's Grand Exchange price for the item. */
		GRAND_EXCHANGE,
		/** The item's high alchemy value, for things that do not trade. */
		HIGH_ALCHEMY
	}

	public enum Counts
	{
		/** The event's delta is the number of items received. */
		ITEMS,
		/** The event's delta is the coin value of the stack, for "collect 50m" style games. */
		VALUE
	}
}
