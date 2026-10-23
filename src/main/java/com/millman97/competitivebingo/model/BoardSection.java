package com.millman97.competitivebingo.model;

import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/** One GRID, LIST or TEXT block in a board document. Unknown kinds may be skipped. */
@Value
public class BoardSection
{
	public static final String GRID = "GRID";
	public static final String LIST = "LIST";
	public static final String TEXT = "TEXT";

	String kind;

	/** Heading for the section, or null for an unheaded one. */
	@Nullable
	String title;

	/** GRID only: how many rows the cells are laid out over. */
	@Nullable
	Integer rows;

	/** GRID only: how many columns the cells are laid out over. */
	@Nullable
	Integer cols;

	/** GRID and LIST. */
	@Nullable
	List<BoardCell> cells;

	/** TEXT only. */
	@Nullable
	String body;

	public List<BoardCell> getCells()
	{
		return cells == null ? Collections.emptyList() : cells;
	}
}
