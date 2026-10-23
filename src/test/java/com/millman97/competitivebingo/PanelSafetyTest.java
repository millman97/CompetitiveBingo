package com.millman97.competitivebingo;

import com.millman97.competitivebingo.model.BingoGameState;
import com.millman97.competitivebingo.model.BoardSection;
import com.millman97.competitivebingo.model.MetricType;
import com.millman97.competitivebingo.sync.SyncStatus;
import com.millman97.competitivebingo.sync.TrackedTarget;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class PanelSafetyTest
{
	@Test
	public void pausedOrRefusedEntriesKeepInputWithoutSendingWhilePaused() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> {
			for (SyncStatus.Connection state : new SyncStatus.Connection[]{SyncStatus.Connection.LIVE, SyncStatus.Connection.BLOCKED})
			{
				final int[] calls = {0};
				final CompetitiveBingoPanel panel = new CompetitiveBingoPanel(new TestPanelActions()
				{
					@Override
					public void submitManual(String id, String label, String input, Consumer<String> queued)
					{
						calls[0]++;
						queued.accept("Refused");
					}
				});
				try
				{
					panel.setStatus(SyncStatus.builder().connection(state).message("Test").gameId("game")
						.gameName("Test game").gameState(BingoGameState.LIVE).manualEntryAllowed(true)
						.trackedTargets(Collections.singletonList(new TrackedTarget("kc.vorkath", "Vorkath", null, MetricType.COUNT)))
						.build());
					final JTextField input = input(panel);
					assertNotNull(input);
					input.setText("137");
					input.postActionEvent();
					assertEquals("137", input.getText());
					assertEquals(state == SyncStatus.Connection.LIVE ? 1 : 0, calls[0]);
				}
				finally { panel.dispose(); }
			}
		});
	}

	@Test
	public void serverLabelsAndBoardHeadingsCannotBecomeHtml() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> {
			final String markup = "<html><b>Title</b>";
			assertTrue(CompetitiveBingoPanel.literalHtml(markup).contains("&lt;html&gt;"));
			final JPanel section = BoardWindow.render(new BoardSection(BoardSection.TEXT, markup, null, null, null, "Body"));
			final JLabel heading = (JLabel) Arrays.stream(section.getComponents())
				.filter(component -> component instanceof JLabel).findFirst().get();
			assertTrue(heading.getText().contains("&lt;html&gt;&lt;b&gt;Title&lt;/b&gt;"));
		});
	}

	private static JTextField input(Container parent)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JTextField) { return (JTextField) child; }
			if (child instanceof Container)
			{
				final JTextField found = input((Container) child);
				if (found != null) { return found; }
			}
		}
		return null;
	}
}
