package com.millman97.competitivebingo;

import com.millman97.competitivebingo.model.BingoGameState;
import com.millman97.competitivebingo.model.EventSource;
import com.millman97.competitivebingo.model.ManualDetail;
import com.millman97.competitivebingo.model.MetricType;
import com.millman97.competitivebingo.sync.ReadinessCheck;
import com.millman97.competitivebingo.sync.SentEvent;
import com.millman97.competitivebingo.sync.SyncStatus;
import com.millman97.competitivebingo.sync.TrackedTarget;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.Timer;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel: sync controls, readiness checks, what the game is asking for, a way to submit
 * something by hand, and a short activity list.
 */
class CompetitiveBingoPanel extends PluginPanel
{
	/** Actions run on Swing; their implementations arrange any client-thread work. */
	interface Actions
	{
		void sync();
		void settingsChanged(String serverUrl, String joinCode);
		void leave();
		void hop(int world);
		/** A null result means queued; an error leaves the input available to correct. */
		void submitManual(String metricId, String label, String input, Consumer<String> onQueued);
		void refreshBoard();
	}

	/**
	 * How many recent events the activity list keeps.
	 */
	private static final int MAX_ACTIVITY_ROWS = 5;

	private static final DateTimeFormatter CLOCK =
		DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	private final Actions actions;

	private final JTextField serverUrlField = new JTextField();
	private final JTextField joinCodeField = new JTextField();
	private final JButton syncButton = new JButton("Sync");
	private final JButton leaveButton = new JButton("Leave game");
	private final JButton boardButton = new JButton("Show board");
	private final JButton hopButton = new JButton();
	private final JPanel setupFields = new JPanel(new DynamicGridLayout(0, 1, 0, 4));

	private final JLabel statusLabel = wrappingLabel("");
	private final JLabel gameLabel = wrappingLabel("");
	private final JLabel teamLabel = muted("");
	private final JLabel timeRemainingLabel = muted("");
	private final JLabel worldLabel = muted("");

	private final JPanel setupContent = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
	private final JPanel statusContent = new JPanel(new DynamicGridLayout(0, 1, 0, 4));

	private final JPanel readinessPanel = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
	private final JPanel trackedPanel = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
	private final JPanel manualPanel = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
	private final JPanel activityPanel = new JPanel(new DynamicGridLayout(0, 1, 0, 6));

	/** The tracked metrics the player may submit by hand, in the order the game listed them. */
	private final JComboBox<TrackedTarget> manualMetric = new JComboBox<>();
	private final JTextField manualInput = new JTextField();
	private final JButton manualSend = new JButton("Screenshot and send");
	private final JLabel manualHint = muted("");

	/**
	 * What {@link #manualHint} is saying, or empty when it has nothing to say. Tracked separately
	 * because the label's own text is never empty — it is always wrapped in the HTML that makes
	 * it wrap — so asking the label would add a blank row under the button forever.
	 */
	private String manualHintText = "";

	/** Newest last. Keyed by event ID so an upload result updates its row instead of adding one. */
	private final Map<String, SentEvent> activity = new LinkedHashMap<>();

	/** Labels from the game config, so the list reads in English rather than in metric IDs. */
	private Map<String, String> metricLabels = new LinkedHashMap<>();

	/**
	 * Sections are added and removed rather than shown and hidden because DynamicGridLayout
	 * reserves a full row for an invisible component.
	 */
	private final JPanel setupSection;
	private final JPanel statusSection;
	private final JPanel readinessSection;
	private final JPanel trackedSection;
	private final JPanel manualSection;
	private final JPanel activitySection;

	private SyncStatus status = SyncStatus.notConfigured();
	private boolean submissionPending;
	private boolean disposed;
	private final Timer countdown;

	/** Opened on demand and reused, so pressing the button twice does not stack windows. */
	@Nullable
	private BoardWindow boardWindow;

	CompetitiveBingoPanel(Actions actions)
	{
		this.actions = actions;

		syncButton.addActionListener(e -> {
			actions.settingsChanged(serverUrlField.getText().trim(), joinCodeField.getText().trim());
			actions.sync();
		});

		leaveButton.addActionListener(e -> actions.leave());
		boardButton.addActionListener(e -> showBoard());

		// Double-click, the same gesture RuneLite's own World Hopper uses: hopping is one server
		// action, and a stray single click should not move someone mid-fight.
		hopButton.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() == 2 && status.getHopWorld() != null)
				{
					actions.hop(status.getHopWorld());
				}
			}
		});

		// Typing a URL and clicking away should save it, not silently discard it.
		final FocusAdapter save = new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				actions.settingsChanged(serverUrlField.getText().trim(), joinCodeField.getText().trim());
			}
		};
		serverUrlField.addFocusListener(save);
		joinCodeField.addFocusListener(save);

		buildManualEntry();

		setupSection = buildSetup();
		statusSection = buildStatus();
		readinessSection = section("Readiness", readinessPanel);
		trackedSection = section("Targets", trackedPanel);
		manualSection = section("Add one yourself", manualPanel);
		activitySection = section("Recent activity", activityPanel);

		// Once a second, so the final minute counts down properly.
		countdown = new Timer(1_000, e -> updateTimeRemaining());
		countdown.start();

		setStatus(SyncStatus.notConfigured());
		renderActivity();
	}

	/** Stops the countdown timer and closes the board, which would otherwise outlive the panel. */
	void dispose()
	{
		disposed = true;
		countdown.stop();

		if (boardWindow != null)
		{
			boardWindow.dispose();
			boardWindow = null;
		}
	}

	void setSettings(String serverUrl, String joinCode)
	{
		if (!serverUrlField.getText().equals(serverUrl))
		{
			serverUrlField.setText(serverUrl);
		}
		if (!joinCodeField.getText().equals(joinCode))
		{
			joinCodeField.setText(joinCode);
		}
	}

	void setStatus(SyncStatus next)
	{
		if (next.getConnection() == SyncStatus.Connection.READY_TO_SYNC
			|| next.getConnection() == SyncStatus.Connection.NOT_CONFIGURED
			|| (next.getGameId() != null && status.getGameId() != null
				&& !next.getGameId().equals(status.getGameId())))
		{
			activity.clear();
			renderActivity();
			if (boardWindow != null)
			{
				boardWindow.dispose();
				boardWindow = null;
			}
		}
		this.status = next;
		this.metricLabels = next.getMetricLabels() == null
			? new LinkedHashMap<>()
			: new LinkedHashMap<>(next.getMetricLabels());

		// Clear previous game activity.
		if (next.getGameState() == BingoGameState.UPCOMING && !activity.isEmpty())
		{
			activity.clear();
			renderActivity();
		}

		statusLabel.setText(html(next.getMessage()));
		statusLabel.setForeground(colourFor(next.getConnection()));

		if (next.getGameName() != null)
		{
			gameLabel.setText(html(next.getGameName()));
		}
		if (next.getTeamName() != null)
		{
			teamLabel.setText(html("Team: " + next.getTeamName()));
		}
		if (next.getStartWorld() != null)
		{
			worldLabel.setText(html("Start world: " + next.getStartWorld()));
		}
		updateTimeRemaining();

		syncButton.setEnabled(next.getConnection() != SyncStatus.Connection.WORKING);
		syncButton.setText(next.getGameName() == null ? "Sync" : "Re-sync");

		if (next.getHopWorld() != null)
		{
			hopButton.setText(htmlButton("Double-click to hop to world " + next.getHopWorld()));
		}

		renderSetup(next);
		renderStatus(next);
		renderReadiness(next);
		renderTracked(next);
		renderManual(next);
		renderSections();

		if (boardWindow != null)
		{
			boardWindow.setBoard(next.getBoard());
		}

		revalidate();
		repaint();
	}

	/**
	 * Shows only the relevant sections.
	 */
	private void renderSections()
	{
		removeAll();

		add(setupSection);
		add(statusSection);

		// Only when something needs fixing.
		if (status.getReadiness().stream().anyMatch(check -> !check.isSatisfied()))
		{
			add(readinessSection);
		}

		// From the first sync until the game is over.
		final boolean inAGame = status.getGameName() != null
			&& status.getGameState() != BingoGameState.ENDED;

		if (inAGame)
		{
			add(trackedSection);
		}

		// Only while the game allows it and there is something to pick.
		if (inAGame && !status.getManualOptions().isEmpty())
		{
			add(manualSection);
		}

		// Only while there is something to show and an active game.
		if (!activity.isEmpty() && status.getGameState() != BingoGameState.ENDED)
		{
			add(activitySection);
		}
	}

	void addActivity(SentEvent event)
	{
		final String id = event.getEnvelope().getEventId();

		// Re-inserting keeps the row in place rather than jumping it to the newest position when
		// its screenshot finishes uploading.
		if (!activity.containsKey(id) && activity.size() >= MAX_ACTIVITY_ROWS)
		{
			final String oldest = activity.keySet().iterator().next();
			activity.remove(oldest);
		}
		activity.put(id, event);

		renderActivity();
		renderSections();
		revalidate();
		repaint();
	}


	// ---- sections ----

	private JPanel buildSetup()
	{
		setupFields.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setupFields.add(muted("Server address"));
		setupFields.add(field(serverUrlField));
		setupFields.add(muted("Join code"));
		setupFields.add(field(joinCodeField));

		return section("Setup", setupContent);
	}

	private JPanel buildStatus()
	{
		return section("Status", statusContent);
	}

	private void buildManualEntry()
	{
		manualMetric.setBackground(ColorScheme.DARK_GRAY_COLOR);
		manualMetric.setForeground(ColorScheme.TEXT_COLOR);
		manualMetric.setFocusable(false);

		// A dropdown cannot wrap, so the full wording goes in the tooltip for anything too long
		// for the width of the panel.
		final ListCellRenderer<? super TrackedTarget> base = manualMetric.getRenderer();
		manualMetric.setRenderer((JList<? extends TrackedTarget> list, TrackedTarget value,
			int index, boolean selected, boolean focused) -> {
			final Component rendered =
				base.getListCellRendererComponent(list, value, index, selected, focused);
			if (rendered instanceof JLabel && value != null)
			{
				final JLabel label = (JLabel) rendered;
				label.setText(literalHtml(value.getLabel()));
				label.setToolTipText(literalHtml(value.getLabel()));
			}
			return rendered;
		});

		manualSend.addActionListener(e -> submitManual());

		// Enter in the text box is the same gesture as pressing the button.
		manualInput.addActionListener(e -> submitManual());

		manualPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
	}

	private void submitManual()
	{
		if (submissionPending || !status.isEmitting())
		{
			setManualHint("Reporting is paused. Check the status before sending.", ColorScheme.PROGRESS_ERROR_COLOR);
			return;
		}
		final TrackedTarget selected = (TrackedTarget) manualMetric.getSelectedItem();
		if (selected == null)
		{
			return;
		}

		final String typed = manualInput.getText().trim();
		if (typed.isEmpty())
		{
			setManualHint("Type what you did first — a time, a count or a quantity.",
				ColorScheme.PROGRESS_ERROR_COLOR);
			return;
		}

		submissionPending = true;
		manualSend.setEnabled(false);
		actions.submitManual(selected.getMetricId(), selected.getLabel(), typed, error -> {
			final Runnable result = () -> {
				if (disposed) { return; }
				submissionPending = false;
				if (error == null)
				{
					if (manualInput.getText().trim().equals(typed)) { manualInput.setText(""); }
					setManualHint("Queued. Watch for the result below.", ColorScheme.LIGHT_GRAY_COLOR);
				}
				else
				{
					setManualHint(error, ColorScheme.PROGRESS_ERROR_COLOR);
				}
				renderManual(status);
			};
			if (SwingUtilities.isEventDispatchThread()) { result.run(); }
			else { SwingUtilities.invokeLater(result); }
		});
	}

	private void setManualHint(String text, Color colour)
	{
		manualHintText = text;
		manualHint.setText(html(text));
		manualHint.setForeground(colour);

		// The hint is only added to the panel when there is one, so its arrival has to redraw.
		renderManual(status);
		revalidate();
		repaint();

		// The redraw takes the field out and puts it back, which drops focus. Someone who just
		// pressed Enter is likely to have another one to add.
		manualInput.requestFocusInWindow();
	}

	/** Only the fields that have something to say get a row. */
	private void renderSetup(SyncStatus next)
	{
		setupContent.removeAll();

		// The fields collapse behind the button once a game is joined.
		if (next.getGameName() == null)
		{
			setupContent.add(setupFields);
		}

		setupContent.add(syncButton);

		// Only a server that publishes one has anything to pop out.
		if (next.getBoard() != null)
		{
			setupContent.add(boardButton);
		}

		if (next.getGameName() != null)
		{
			setupContent.add(leaveButton);
		}

		if (next.getHopWorld() != null)
		{
			setupContent.add(hopButton);
		}
	}

	private void renderStatus(SyncStatus next)
	{
		statusContent.removeAll();
		statusContent.add(statusLabel);

		if (next.getGameName() != null)
		{
			statusContent.add(gameLabel);
		}
		if (next.getTeamName() != null)
		{
			statusContent.add(teamLabel);
		}
		if (next.getStartWorld() != null)
		{
			statusContent.add(worldLabel);
		}
		if (timeRemainingText(next) != null)
		{
			statusContent.add(timeRemainingLabel);
		}
	}

	private void renderReadiness(SyncStatus next)
	{
		readinessPanel.removeAll();

		if (next.getReadiness().isEmpty())
		{
			readinessPanel.add(muted("Checked once you have joined a game."));
			return;
		}

		for (ReadinessCheck check : next.getReadiness())
		{
			final JPanel row = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

			final JLabel label = wrappingLabel((check.isSatisfied() ? "✓ " : "✗ ") + check.getLabel());
			label.setForeground(check.isSatisfied()
				? ColorScheme.PROGRESS_COMPLETE_COLOR
				: ColorScheme.PROGRESS_ERROR_COLOR);
			row.add(label);

			if (!check.isSatisfied())
			{
				row.add(muted(check.getFix()));
			}

			readinessPanel.add(row);
		}
	}

	private void renderTracked(SyncStatus next)
	{
		trackedPanel.removeAll();

		if (next.getTrackedTargets().isEmpty())
		{
			trackedPanel.add(muted("Shown once you have joined a game."));
			return;
		}

		for (TrackedTarget target : next.getTrackedTargets())
		{
			trackedPanel.add(muted("• " + describeTarget(target)));
		}
	}

	private void renderManual(SyncStatus next)
	{
		final List<TrackedTarget> options = next.getManualOptions();

		final TrackedTarget previous = (TrackedTarget) manualMetric.getSelectedItem();
		manualMetric.setModel(new DefaultComboBoxModel<>(options.toArray(new TrackedTarget[0])));

		// Keep the player's choice across a re-sync, so a status refresh mid-typing does not
		// silently move them onto a different metric.
		if (previous != null)
		{
			for (TrackedTarget option : options)
			{
				if (option.getMetricId().equals(previous.getMetricId()))
				{
					manualMetric.setSelectedItem(option);
					break;
				}
			}
		}

		manualPanel.removeAll();

		if (options.isEmpty())
		{
			return;
		}

		manualSend.setEnabled(next.isEmitting() && !submissionPending);
		manualSend.setText(next.isScreenshotsEnabled() ? "Screenshot and send" : "Send entry");
		manualPanel.add(muted(next.isScreenshotsEnabled()
			? "Submit what the plugin could not observe. A screenshot is requested; capture may fail."
			: "Submit what the plugin could not observe. No screenshot will be attached."));
		manualPanel.add(manualMetric);
		manualPanel.add(muted("Time, count, quantity or value"));
		manualPanel.add(field(manualInput));
		manualPanel.add(manualSend);

		if (!manualHintText.isEmpty())
		{
			manualPanel.add(manualHint);
		}
	}

	private void renderActivity()
	{
		activityPanel.removeAll();

		if (activity.isEmpty())
		{
			activityPanel.add(muted("Nothing yet. Events appear here as they happen."));
		}
		else
		{
			// Newest first: the thing that just happened is the thing being checked.
			final SentEvent[] events = activity.values().toArray(new SentEvent[0]);
			for (int i = events.length - 1; i >= 0; i--)
			{
				activityPanel.add(activityRow(events[i]));
			}
		}

		activityPanel.revalidate();
		activityPanel.repaint();
	}

	private JPanel activityRow(SentEvent event)
	{
		final JPanel row = new JPanel(new DynamicGridLayout(0, 1, 0, 1));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JPanel headline = new JPanel(new BorderLayout());
		headline.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Wrapped, less the room the clock takes: a long metric label and its value together are
		// wider than the panel more often than not.
		final JLabel what = wrappingLabel(icon(event.getOutcome()) + " " + describe(event),
			TEXT_WIDTH - CLOCK_WIDTH);
		what.setForeground(colourFor(event.getOutcome()));
		headline.add(what, BorderLayout.CENTER);

		final JLabel when = new JLabel(CLOCK.format(Instant.ofEpochMilli(event.getEnvelope().getClientTime())));
		when.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		when.setFont(when.getFont().deriveFont(when.getFont().getSize2D() - 1f));
		headline.add(when, BorderLayout.EAST);

		row.add(headline);

		final String note = noteFor(event);
		if (!note.isEmpty())
		{
			row.add(muted(note));
		}

		return row;
	}

	/** "Vorkath kills 342" or "Abyssal whip x1" — what the player would recognise. */
	private String describe(SentEvent event)
	{
		final String metricId = event.getEnvelope().getMetric().getId();
		final String label = metricLabels.getOrDefault(metricId, metricId);

		final MetricType type = event.getEnvelope().getMetric().getType();
		final Long value = event.getEnvelope().getMetric().getValue();
		final Long delta = event.getEnvelope().getMetric().getDelta();

		if ((type == MetricType.DROP || type == MetricType.KILL) && delta != null)
		{
			return label + " x" + delta;
		}
		if (type == MetricType.DURATION && value != null)
		{
			return label + " " + formatDuration(value);
		}
		if (value != null)
		{
			return label + " " + value;
		}
		return label;
	}

	private static String noteFor(SentEvent event)
	{
		if (!event.getDetail().isEmpty())
		{
			return event.getDetail();
		}

		// A hand-typed event says so, and says what was typed — it is the only kind the player
		// has no other way of checking.
		if (event.getEnvelope().getSource() == EventSource.MANUAL
			&& event.getEnvelope().getDetail() instanceof ManualDetail)
		{
			return "You entered: " + ((ManualDetail) event.getEnvelope().getDetail()).getInput();
		}

		return "";
	}

	/**
	 * "5 Araxxor kills", "1 Dragon pickaxe drop", "Chambers of Xeric under 30:00".
	 *
	 * The label is the game's wording and is never rewritten; the count and the singular are the
	 * only things composed here.
	 */
	static String describeTarget(TrackedTarget target)
	{
		final Long wanted = target.getTarget();
		if (wanted == null)
		{
			return target.getLabel();
		}

		// For a time metric the figure is a threshold, not a quantity.
		if (target.getType() == MetricType.DURATION)
		{
			return target.getLabel() + " under " + formatDuration(wanted);
		}

		return wanted + " " + (wanted == 1L ? singular(target.getLabel()) : target.getLabel());
	}

	/**
	 * Drops the plural from the last word, so a target of one reads properly.
	 *
	 * Deliberately shallow: "kills" to "kill" and "chests" to "chest" are the cases that occur,
	 * and anything it cannot do it leaves alone rather than mangling.
	 */
	static String singular(String label)
	{
		final String last = label.substring(label.lastIndexOf(' ') + 1).toLowerCase(Locale.ENGLISH);

		if (last.length() < 3 || !last.endsWith("s") || last.endsWith("ss"))
		{
			return label;
		}

		return label.substring(0, label.length() - 1);
	}

	private void showBoard()
	{
		if (boardWindow == null)
		{
			boardWindow = new BoardWindow(actions::refreshBoard);
		}

		boardWindow.setBoard(status.getBoard());
		boardWindow.open();
	}

	private void updateTimeRemaining()
	{
		final String text = timeRemainingText(status);
		if (text == null)
		{
			return;
		}

		final String markup = html(text);
		if (!markup.equals(timeRemainingLabel.getText()))
		{
			timeRemainingLabel.setText(markup);
		}
	}

	/** @return what to show, or null when there is no meaningful time to show at all */
	@Nullable
	private static String timeRemainingText(SyncStatus status)
	{
		if (status.getGameState() == BingoGameState.ENDED)
		{
			return null;
		}

		// Before kick-off, count down to the start time rather than the end time.
		if (status.getGameState() == BingoGameState.UPCOMING && status.getStartsAt() != null)
		{
			final long untilStart = status.getStartsAt() - System.currentTimeMillis();
			// Under a second is already due; counting "0s" for a whole second reads as stuck.
			return untilStart >= 1000L
				? "Starts in " + formatRemaining(untilStart)
				: "Waiting for the organiser to start it";
		}

		final Long endsAt = status.getEndsAt();
		if (endsAt == null)
		{
			return null;
		}

		final long remaining = endsAt - System.currentTimeMillis();
		return remaining <= 0 ? "Past scheduled end" : formatRemaining(remaining) + " remaining";
	}

	static String formatRemaining(long millis)
	{
		final long minutes = millis / 60_000L;
		if (minutes < 1)
		{
			return Math.max(0L, millis / 1000L) + "s";
		}
		if (minutes < 60)
		{
			return minutes + "m";
		}

		final long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + "h " + (minutes % 60) + "m";
		}
		return (hours / 24) + "d " + (hours % 24) + "h";
	}

	static String formatDuration(long millis)
	{
		final long totalSeconds = millis / 1000L;
		final long minutes = totalSeconds / 60;
		final long seconds = totalSeconds % 60;

		if (minutes >= 60)
		{
			return String.format("%d:%02d:%02d", minutes / 60, minutes % 60, seconds);
		}
		return String.format("%d:%02d", minutes, seconds);
	}

	// ---- presentation ----

	private static String icon(SentEvent.Outcome outcome)
	{
		switch (outcome)
		{
			case SENT:
				return "✓";
			case DUPLICATE:
				return "↻";
			case REJECTED:
				return "✗";
			case FAILED:
				return "✗";
			default:
				return "…";
		}
	}

	private static Color colourFor(SentEvent.Outcome outcome)
	{
		switch (outcome)
		{
			case SENT:
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
			case REJECTED:
			case FAILED:
				return ColorScheme.PROGRESS_ERROR_COLOR;
			case DUPLICATE:
				return ColorScheme.LIGHT_GRAY_COLOR;
			default:
				return ColorScheme.PROGRESS_INPROGRESS_COLOR;
		}
	}

	private static Color colourFor(SyncStatus.Connection connection)
	{
		switch (connection)
		{
			case LIVE:
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
			case ERROR:
				return ColorScheme.PROGRESS_ERROR_COLOR;
			case BLOCKED:
				return ColorScheme.PROGRESS_INPROGRESS_COLOR;
			default:
				return ColorScheme.TEXT_COLOR;
		}
	}

	private JPanel section(String title, JPanel content)
	{
		final JPanel panel = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final JLabel heading = new JLabel(title);
		heading.setForeground(ColorScheme.BRAND_ORANGE);
		heading.setFont(heading.getFont().deriveFont(Font.BOLD));
		panel.add(heading);

		content.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.add(content);
		return panel;
	}

	private static JTextField field(JTextField field)
	{
		field.setBackground(ColorScheme.DARK_GRAY_COLOR);
		field.setForeground(ColorScheme.TEXT_COLOR);
		field.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		field.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 24));
		return field;
	}

	/**
	 * Wraps via HTML; a plain JLabel truncates to an ellipsis at this width.
	 */
	private static JLabel wrappingLabel(String text)
	{
		return wrappingLabel(text, TEXT_WIDTH);
	}

	private static JLabel wrappingLabel(String text, int width)
	{
		final JLabel label = new JLabel(html(text, width));
		label.setForeground(ColorScheme.TEXT_COLOR);
		return label;
	}

	private static JLabel muted(String text)
	{
		final JLabel label = wrappingLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	/**
	 * Panel width less PluginPanel's 6px border each side and this panel's 8px section padding
	 * each side, with a little slack for the scrollbar.
	 */
	private static final int TEXT_WIDTH = PluginPanel.PANEL_WIDTH - (2 * 6) - (2 * 8) - 12;

	/** Room an activity row leaves for the clock on its right. */
	private static final int CLOCK_WIDTH = 50;

	static String html(String text)
	{
		return html(text, TEXT_WIDTH);
	}

	static String literalHtml(String text)
	{
		return "<html>" + escape(text) + "</html>";
	}

	private static String html(String text, int width)
	{
		return "<html><body style='width:" + width + "px'>" + escape(text) + "</body></html>";
	}

	/**
	 * Button text wraps too, centred — a button's label reads wrong ragged-right.
	 */
	private static String htmlButton(String text)
	{
		return "<html><body style='width:" + (TEXT_WIDTH - 20) + "px;text-align:center'>"
			+ escape(text) + "</body></html>";
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
