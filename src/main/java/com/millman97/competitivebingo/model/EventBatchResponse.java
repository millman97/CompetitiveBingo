package com.millman97.competitivebingo.model;

import java.util.List;
import lombok.Value;

/**
 * Response of {@code POST /api/plugin/events}.
 */
@Value
public class EventBatchResponse
{
	/** One entry per submitted event. */
	List<EventResult> results;
}
