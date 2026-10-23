package com.millman97.competitivebingo.parse;

import com.millman97.competitivebingo.model.DropDetail;
import com.millman97.competitivebingo.model.MetricType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemStack;

/**
 * Turns RuneLite's loot events into {@link Observation}s.
 */
@Slf4j
@Singleton
public class DropObserver
{
	/**
	 * Resolves an item ID to its name. A seam rather than a direct {@code ItemManager}
	 * dependency, so the mapping can be tested without a running client.
	 */
	public interface Items
	{
		int canonicalize(int itemId);

		@Nullable
		String nameOf(int canonicalItemId);
	}

	private final ChatObserver chatObserver;
	private int ordinalTick = Integer.MIN_VALUE;
	private int nextOrdinal;

	@Inject
	public DropObserver(ChatObserver chatObserver)
	{
		this.chatObserver = chatObserver;
	}

	/**
	 * @param source     the NPC, player or activity that produced the loot
	 * @param sourceType RuneLite's {@code LootRecordType} name
	 * @param items      the stacks received; one observation is produced per stack
	 * @param rules      the game's drop value rules; each stack is offered to all of them
	 * @param prices     the client's price data, consulted only when {@code rules} is non-empty
	 */
	public List<Observation> onLootReceived(String source, String sourceType, int gameTick,
		Collection<ItemStack> items, Items itemResolver, DropValueRules rules,
		DropValueRules.Prices prices)
	{
		if ("PLAYER".equals(sourceType))
		{
			return java.util.Collections.emptyList();
		}
		final List<Observation> observations = new ArrayList<>(items.size());

		// The kill count comes from chat, so it is context rather than fact, and is often absent.
		final Integer killCount = chatObserver.lastCountFor(source);

		for (ItemStack stack : items)
		{
			final int itemId = itemResolver.canonicalize(stack.getId());
			final String itemName = itemResolver.nameOf(itemId);
			if (itemName == null || itemName.isEmpty() || MetricIds.slug(itemName).isEmpty())
			{
				log.debug("Skipping drop of item {} (canonical {}) from {}: no usable name",
					stack.getId(), itemId, source);
				continue;
			}
			if (ordinalTick != gameTick)
			{
				ordinalTick = gameTick;
				nextOrdinal = 0;
			}
			final int observationOrdinal = nextOrdinal++;

			// Unpriced unless this game asked for value tracking, so a game that does not care
			// costs no price lookups at all.
			final DropDetail detail = new DropDetail(
				itemId,
				itemName,
				stack.getQuantity(),
				source,
				sourceType,
				killCount,
				null,
				null,
				null,
				observationOrdinal);

			observations.add(Observation.builder()
				.metricId(MetricIds.of(MetricType.DROP, itemName))
				.type(MetricType.DROP)
				// A drop is not a counter, so there is no absolute value to report.
				.value(null)
				.delta((long) stack.getQuantity())
				.detail(detail)
				.build());

			// A stack can be both "Dragon pickaxe" and "a drop worth over 1m": the item metric
			// and every matching value metric are separate events for the same moment.
			observations.addAll(rules.apply(detail, prices));
		}

		return observations;
	}
}
