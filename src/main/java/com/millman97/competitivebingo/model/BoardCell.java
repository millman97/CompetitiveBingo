package com.millman97.competitivebingo.model;

import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * One square of a GRID section, or one row of a LIST section.
 *
 * Every string here is shown verbatim. The plugin does not compose wording, compute progress or
 * decide what is finished — the server sends what it wants read.
 */
@Value
public class BoardCell
{
	/** GRID only: zero-based position. A cell missing either is dropped from a grid. */
	@Nullable
	Integer row;

	@Nullable
	Integer col;

	/** The main line, e.g. "Dragon pickaxe" or "The Sharks". */
	String title;

	/** Further lines under the title, e.g. ["3 / 5", "The Sharks"]. */
	@Nullable
	List<String> lines;

	/**
	 * How the cell is shaded. Recognised: "COMPLETE", "IN_PROGRESS", "CONTESTED", "LOCKED",
	 * "EMPTY". Anything else, or null, draws neutral.
	 */
	@Nullable
	String state;

	/**
	 * A colour to key the cell by, as "#rrggbb" — a team colour, usually. Drawn as a stripe
	 * rather than a fill, so it never fights the state shading. Ignored if unparseable.
	 */
	@Nullable
	String accent;

	/** Shown on hover, for detail that will not fit in the cell. */
	@Nullable
	String tooltip;

	public List<String> getLines()
	{
		return lines == null ? Collections.emptyList() : lines;
	}
}
