package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * Detail for {@link MetricType#DROP}.
 */
@Value
public class DropDetail implements EventDetail
{
	int itemId;

	String itemName;

	int quantity;

	/** Name of the NPC, player or activity that produced the loot. */
	String source;

	/** RuneLite's {@code LootRecordType}: NPC, PLAYER, PICKPOCKET, EVENT or UNKNOWN. */
	String sourceType;

	/**
	 * Last-seen kill count for {@code source} this session, or null if no counter message was
	 * seen. Derived from chat rather than the loot event, so it is best-effort context.
	 */
	@Nullable
	Integer killCount;

	/**
	 * Grand Exchange price of one of these, from the client's own price data, or null when the
	 * item does not trade or no price was available.
	 *
	 * The client's prices lag the live market and differ between clients, so this is context for
	 * a human reviewing an event, not a figure to settle a tile on.
	 */
	@Nullable
	Long geValue;

	/** High alchemy value of one of these, or null when the item has none. */
	@Nullable
	Long haValue;

	/**
	 * The price a {@link DropValueRule} was tested against, multiplied by {@link #quantity}, or
	 * null on an event no rule produced. Says exactly what the plugin compared, so a server can
	 * see why a rule did or did not fire.
	 */
	@Nullable
	Long ruleValue;

	/** Distinguishes separate item stacks observed during the same game tick. */
	int observationOrdinal;
}
