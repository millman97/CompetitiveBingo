package com.millman97.competitivebingo.model;

import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/** Server-supplied display document returned by GET /api/plugin/board. */
@Value
public class BoardView
{
	/** Heading for the window, e.g. the game's name. */
	String title;

	/** One line under the title, e.g. "Classic · first line wins". */
	@Nullable
	String subtitle;

	/** Epoch millis the server built this at, so the window can say how stale it is. */
	long capturedAt;

	@Nullable
	List<BoardSection> sections;

	public List<BoardSection> getSections()
	{
		return sections == null ? Collections.emptyList() : sections;
	}
}
