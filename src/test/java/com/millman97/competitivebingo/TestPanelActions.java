package com.millman97.competitivebingo;

import java.util.function.Consumer;

class TestPanelActions implements CompetitiveBingoPanel.Actions
{
	@Override
	public void sync() { }
	@Override
	public void settingsChanged(String url, String code) { }
	@Override
	public void leave() { }
	@Override
	public void hop(int world) { }
	@Override
	public void refreshBoard() { }
	@Override
	public void submitManual(String metric, String label, String input, Consumer<String> onQueued)
	{
		onQueued.accept(null);
	}
}
