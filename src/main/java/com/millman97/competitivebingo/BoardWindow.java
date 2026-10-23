package com.millman97.competitivebingo;

import com.millman97.competitivebingo.model.BoardCell;
import com.millman97.competitivebingo.model.BoardSection;
import com.millman97.competitivebingo.model.BoardView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;

/** Draws the server-supplied board document; unfamiliar section kinds are skipped. */
class BoardWindow
{
	private static final DateTimeFormatter CLOCK =
		DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	/** Big enough for a 5x5 board of readable cells without being a second client window. */
	private static final Dimension PREFERRED = new Dimension(720, 620);

	private static final Dimension CELL = new Dimension(120, 88);

	/** Wrapping width for anything not confined to a grid square. */
	private static final int WIDE = PREFERRED.width - 60;

	private final JFrame frame = new JFrame("Competitive Bingo");
	private final JPanel content = new JPanel();
	private final JLabel titleLabel = new JLabel();
	private final JLabel subtitleLabel = new JLabel();
	private final JLabel capturedLabel = new JLabel();
	private final JButton refreshButton = new JButton("Refresh");

	/**
	 * The last board actually drawn. Kept so that a refresh coming back empty leaves what is on
	 * screen alone: blanking a good board because one request failed is worse than saying the
	 * board is a little older than it looks.
	 */
	@Nullable
	private BoardView shown;

	BoardWindow(Runnable onRefresh)
	{
		refreshButton.addActionListener(e -> {
			refreshButton.setEnabled(false);
			refreshButton.setText("Refreshing...");
			onRefresh.run();
		});

		titleLabel.setForeground(ColorScheme.BRAND_ORANGE);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));

		subtitleLabel.setForeground(ColorScheme.TEXT_COLOR);
		capturedLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		final JPanel heading = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		heading.setBackground(ColorScheme.DARK_GRAY_COLOR);
		heading.add(titleLabel);
		heading.add(subtitleLabel);
		heading.add(capturedLabel);

		final JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		header.add(heading, BorderLayout.CENTER);
		header.add(refreshButton, BorderLayout.EAST);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(0, 14, 14, 14));

		final JScrollPane scroll = new JScrollPane(content,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		final JPanel root = new JPanel(new BorderLayout());
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.add(header, BorderLayout.NORTH);
		root.add(scroll, BorderLayout.CENTER);

		// Hidden rather than destroyed: the button opens this again, and rebuilding the frame
		// each time would lose wherever the player dragged it to.
		frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		frame.setContentPane(root);
		frame.setPreferredSize(PREFERRED);
		frame.pack();
		frame.setLocationRelativeTo(null);
	}

	/** Brings the window up, or forward if it is already open. */
	void open()
	{
		frame.setVisible(true);
		frame.toFront();
		frame.requestFocus();
	}

	void dispose()
	{
		frame.dispose();
	}

	/**
	 * Redraws for a new board. Null means the server has stopped publishing one, which is worth
	 * saying rather than leaving a stale board on screen looking current.
	 */
	void setBoard(@Nullable BoardView next)
	{
		refreshButton.setEnabled(true);
		refreshButton.setText("Refresh");

		if (next == null && shown != null)
		{
			// A re-sync that failed, or a game that stopped publishing. Leave the board up and
			// say it did not refresh, rather than replacing it with nothing.
			capturedLabel.setText("Could not refresh — this is still the board from "
				+ CLOCK.format(Instant.ofEpochMilli(shown.getCapturedAt())) + ".");
			return;
		}

		content.removeAll();

		if (next == null)
		{
			frame.setTitle("Competitive Bingo");
			titleLabel.setText("No board");
			subtitleLabel.setText("");
			capturedLabel.setText("This server does not publish one.");
			content.revalidate();
			content.repaint();
			return;
		}

		shown = next;
		frame.setTitle(next.getTitle());
		titleLabel.setText(wrap(next.getTitle(), WIDE));

		subtitleLabel.setText(wrap(next.getSubtitle() == null ? "" : next.getSubtitle(), WIDE));
		capturedLabel.setText(next.getCapturedAt() <= 0
			? "As of your last sync."
			: "As of " + CLOCK.format(Instant.ofEpochMilli(next.getCapturedAt()))
				+ " — press Refresh for the current state.");

		for (BoardSection section : next.getSections())
		{
			final JPanel rendered = render(section);
			if (rendered == null)
			{
				continue;
			}

			content.add(Box.createVerticalStrut(14));
			content.add(rendered);
		}

		content.revalidate();
		content.repaint();
	}

	/** @return the section drawn, or null for a kind this build does not know how to draw */
	@Nullable
	static JPanel render(BoardSection section)
	{
		final String kind = section.getKind().toUpperCase(Locale.ENGLISH);

		final JPanel body;
		switch (kind)
		{
			case BoardSection.GRID:
				body = grid(section);
				break;
			case BoardSection.LIST:
				body = list(section);
				break;
			case BoardSection.TEXT:
				body = text(section);
				break;
			default:
				// A newer server sending a kind this build has never heard of. Skipping it leaves
				// the rest of the board readable, which beats refusing to draw anything.
				return null;
		}

		final JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(0f);

		if (section.getTitle() != null && !section.getTitle().isEmpty())
		{
			final JLabel heading = new JLabel(wrap(section.getTitle(), WIDE));
			heading.setForeground(ColorScheme.BRAND_ORANGE);
			heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
			heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
			panel.add(heading, BorderLayout.NORTH);
		}

		panel.add(body, BorderLayout.CENTER);
		return panel;
	}

	private static JPanel grid(BoardSection section)
	{
		// The API validates dimensions and expands them to fit the cells before publication.
		final int rows = section.getRows();
		final int cols = section.getCols();

		final JPanel panel = new JPanel();
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(0f);

		if (rows <= 0 || cols <= 0)
		{
			panel.setLayout(new BorderLayout());
			panel.add(note("This board has no squares in it yet."), BorderLayout.CENTER);
			return panel;
		}

		panel.setLayout(new GridLayout(rows, cols, 6, 6));

		// Placed by position rather than in the order they arrived, so a board with a gap in it
		// still lines up.
		final BoardCell[][] placed = new BoardCell[rows][cols];
		for (BoardCell cell : section.getCells())
		{
			if (cell.getRow() == null || cell.getCol() == null)
			{
				continue;
			}

			final int row = cell.getRow();
			final int col = cell.getCol();
			placed[row][col] = cell;
		}

		for (int row = 0; row < rows; row++)
		{
			for (int col = 0; col < cols; col++)
			{
				panel.add(placed[row][col] == null ? blank() : cell(placed[row][col], true));
			}
		}

		return panel;
	}

	private static JPanel list(BoardSection section)
	{
		final JPanel panel = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(0f);

		if (section.getCells().isEmpty())
		{
			panel.add(note("Nothing here yet."));
			return panel;
		}

		for (BoardCell cell : section.getCells())
		{
			panel.add(cell(cell, false));
		}

		return panel;
	}

	private static JPanel text(BoardSection section)
	{
		final JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(0f);
		panel.add(note(section.getBody() == null ? "" : section.getBody()), BorderLayout.CENTER);
		return panel;
	}

	/**
	 * One square or one row.
	 *
	 * @param fixedSize true in a grid, where every square is the same size; false in a list,
	 *                  where a row is as tall as its own contents
	 */
	private static JPanel cell(BoardCell source, boolean fixedSize)
	{
		final JPanel panel = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		panel.setBackground(backgroundFor(source.getState()));

		final Color accent = parseColour(source.getAccent());
		panel.setBorder(BorderFactory.createCompoundBorder(
			// The team colour is a stripe rather than a fill, so it never fights the state.
			accent == null
				? BorderFactory.createMatteBorder(0, 0, 0, 0, ColorScheme.DARK_GRAY_COLOR)
				: BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));

		if (fixedSize)
		{
			panel.setPreferredSize(CELL);
		}

		if (source.getTooltip() != null && !source.getTooltip().isEmpty())
		{
			panel.setToolTipText(wrap(source.getTooltip(), WIDE));
		}

		final int width = fixedSize ? CELL.width - 24 : WIDE;

		final JLabel title = new JLabel(wrap(source.getTitle(), width));
		title.setForeground(ColorScheme.TEXT_COLOR);
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		panel.add(title);

		for (String line : source.getLines())
		{
			if (line.isEmpty())
			{
				continue;
			}

			final JLabel detail = new JLabel(wrap(line, width));
			detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			panel.add(detail);
		}

		return panel;
	}

	private static JPanel blank()
	{
		final JPanel panel = new JPanel();
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setPreferredSize(CELL);
		return panel;
	}

	private static JLabel note(String text)
	{
		final JLabel label = new JLabel(wrap(text, WIDE));
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	/** Anything the server has since invented shades neutral rather than failing. */
	private static Color backgroundFor(@Nullable String state)
	{
		if (state == null)
		{
			return ColorScheme.DARKER_GRAY_COLOR;
		}

		switch (state.toUpperCase(Locale.ENGLISH))
		{
			case "COMPLETE":
				return ColorScheme.PROGRESS_COMPLETE_COLOR.darker().darker();
			case "IN_PROGRESS":
				return ColorScheme.PROGRESS_INPROGRESS_COLOR.darker().darker();
			case "CONTESTED":
				return ColorScheme.BRAND_ORANGE.darker().darker();
			case "LOCKED":
				return ColorScheme.DARKER_GRAY_HOVER_COLOR;
			case "EMPTY":
			default:
				return ColorScheme.DARKER_GRAY_COLOR;
		}
	}

	/** @return the colour, or null if the server sent something that is not one */
	@Nullable
	static Color parseColour(@Nullable String hex)
	{
		if (hex == null || hex.length() != 7 || hex.charAt(0) != '#')
		{
			return null;
		}

		try
		{
			return new Color(Integer.parseInt(hex.substring(1), 16));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** Cells hold whole sentences often enough that everything in one wraps. */
	private static String wrap(String text, int width)
	{
		return "<html><body style='width:" + width + "px'>" + escape(text) + "</body></html>";
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
