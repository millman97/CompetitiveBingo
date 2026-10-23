package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * Detail for {@link MetricType#COLLECTION_LOG}.
 */
@Value
public class CollectionLogDetail implements EventDetail
{
	/** Item name as the game spelled it, e.g. "Dragon pickaxe". */
	String itemName;

	/**
	 * Slots filled across the whole collection log, or null. The game only supplies this when
	 * the player has the collection log interface open, so null is the common answer.
	 */
	@Nullable
	Integer completed;

	/** Total slots in the collection log, or null, under the same caveat as {@link #completed}. */
	@Nullable
	Integer totalPossible;
}
