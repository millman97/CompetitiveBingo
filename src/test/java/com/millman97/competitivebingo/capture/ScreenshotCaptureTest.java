package com.millman97.competitivebingo.capture;

import com.millman97.competitivebingo.CompetitiveBingoConfig;
import com.millman97.competitivebingo.testing.ManualExecutor;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenshotCaptureTest
{
	private final ManualExecutor timer = new ManualExecutor();
	private final ManualExecutor encoder = new ManualExecutor();
	private final ManualExecutor client = new ManualExecutor();
	private final Frames frames = new Frames();
	private final List<ImageEncoder.Encoded> results = new ArrayList<>();
	private final ScreenshotCapture capture = new ScreenshotCapture(frames, new CompetitiveBingoConfig() { });

	@Before
	public void setUp() { capture.startUp(timer, encoder); }
	@After
	public void tearDown() { capture.shutDown(); client.runReady(); client.shutdownNow(); }

	@Test
	public void overlappingTicksShareOneRedactionOwnerAndTimeoutTogether()
	{
		capture.capture(1, results::add);
		capture.capture(2, results::add);
		assertEquals(1, frames.callbacks.size());
		assertTrue(frames.hidden);
		timer.advanceMillis(5000);
		client.runReady();
		assertFalse(frames.hidden);
		assertEquals(2, results.size());
		assertNull(results.get(0));
		assertNull(results.get(1));
		frames.deliver();
		client.runReady();
		encoder.runReady();
		assertEquals(2, results.size());
	}

	@Test
	public void aFrameWithVisibleChatIsRejected()
	{
		capture.capture(1, results::add);
		frames.hidden = false;
		frames.deliver();
		client.runReady();
		encoder.runReady();
		assertEquals(1, results.size());
		assertNull(results.get(0));
	}

	@Test
	public void shutdownRestoresChatAndIgnoresLateFrames()
	{
		capture.capture(1, results::add);
		capture.shutDown();
		client.runReady();
		assertFalse(frames.hidden);
		assertEquals(1, results.size());
		frames.deliver();
		client.runReady();
		assertEquals(1, results.size());
	}

	@Test
	public void encodingTimeoutDoesNotWaitForTheEncoderQueue()
	{
		capture.capture(1, results::add);
		frames.deliver();
		client.runReady();
		timer.advanceMillis(5000);
		client.runReady();
		encoder.runReady();
		assertEquals(1, results.size());
		assertNull(results.get(0));
	}

	private class Frames implements ScreenshotCapture.FrameSource
	{
		private boolean hidden;
		private final Queue<Consumer<Image>> callbacks = new ArrayDeque<>();
		public void onClient(Runnable task) { client.execute(task); }
		public Runnable hideChat() { hidden = true; return () -> hidden = false; }
		public boolean isChatHidden() { return hidden; }
		public void nextFrame(Consumer<Image> callback) { callbacks.add(callback); }
		private void deliver() { callbacks.remove().accept(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)); }
	}
}
