/*
 * Copyright (c) 2022, Jake Barter
 * Copyright (c) 2022, pajlads
 * Copyright (c) 2026, millman97
 * All rights reserved.
 *
 * The approach to blanking the chatbox and private messages — hiding the widgets before the
 * frame is drawn and restoring them afterwards, rather than painting over the result — is taken
 * from Dink (pajlads/DinkPlugin), BSD 2-Clause.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.millman97.competitivebingo.capture;

import com.millman97.competitivebingo.CompetitiveBingoConfig;
import java.awt.Image;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;

/** One capture owns widget redaction until its frame arrives, times out or is cancelled. */
@Slf4j
@Singleton
public class ScreenshotCapture
{
	interface FrameSource
	{
		void onClient(Runnable task);
		Runnable hideChat();
		boolean isChatHidden();
		void nextFrame(Consumer<Image> callback);
	}

	private static final long TIMEOUT_MS = 5_000L;
	private static final int MAX_WAITERS = 128;
	private static final int MAX_FRAME_LISTENERS = 2;
	private final FrameSource frames;
	private final CompetitiveBingoConfig config;
	private ScheduledExecutorService timer;
	private ExecutorService encoder;
	private boolean running;
	/** Retained until the encoder returns, so a stuck job cannot accumulate more frames. */
	private Capture encodingOwner;
	private int pendingFrameListeners;
	private Capture active;
	private int cachedTick = -1;
	private ImageEncoder.Encoded cached;

	@Inject
	public ScreenshotCapture(Client client, ClientThread clientThread, DrawManager drawManager,
		CompetitiveBingoConfig config)
	{
		this(new FrameSource()
		{
			private final int[] components = { InterfaceID.Chatbox.CHATAREA, InterfaceID.PmChat.CONTAINER };

			@Override
			public void onClient(Runnable task)
			{
				clientThread.invoke(task);
			}

			@Override
			public Runnable hideChat()
			{
				final List<Widget> changed = new ArrayList<>();
				for (int component : components)
				{
					final Widget widget = client.getWidget(component);
					if (widget != null && !widget.isHidden())
					{
						widget.setHidden(true);
						changed.add(widget);
					}
				}
				return () -> changed.forEach(widget -> widget.setHidden(false));
			}

			@Override
			public boolean isChatHidden()
			{
				for (int component : components)
				{
					final Widget widget = client.getWidget(component);
					if (widget != null && !widget.isHidden())
					{
						return false;
					}
				}
				return true;
			}

			@Override
			public void nextFrame(Consumer<Image> callback)
			{
				drawManager.requestNextFrameListener(callback);
			}
		}, config);
	}

	ScreenshotCapture(FrameSource frames, CompetitiveBingoConfig config)
	{
		this.frames = frames;
		this.config = config;
	}

	public void startUp()
	{
		startUp(Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "bingo-capture-timeout")),
			Executors.newSingleThreadExecutor(task -> daemon(task, "bingo-image-encoder")));
	}

	synchronized void startUp(ScheduledExecutorService timer, ExecutorService encoder)
	{
		this.timer = timer;
		this.encoder = encoder;
		running = true;
	}

	public void shutDown()
	{
		final ScheduledExecutorService timeoutWorker;
		final ExecutorService imageWorker;
		synchronized (this)
		{
			running = false;
			timeoutWorker = timer;
			imageWorker = encoder;
		}
		cancelPending();
		if (timeoutWorker != null)
		{
			timeoutWorker.shutdownNow();
		}
		if (imageWorker != null)
		{
			imageWorker.shutdownNow();
		}
	}

	public void cancelPending()
	{
		final Capture capture;
		synchronized (this)
		{
			cached = null;
			cachedTick = -1;
			capture = active;
			if (capture != null)
			{
				capture.cancelled = true;
				cancelTasks(capture);
			}
		}
		if (capture != null)
		{
			finish(capture, null);
		}
	}

	/** Called on the client thread. Other ticks share a pending frame, never its redaction ownership. */
	public void capture(int tick, Consumer<ImageEncoder.Encoded> callback)
	{
		Capture requested = null;
		ImageEncoder.Encoded result = null;
		boolean immediate = false;
		synchronized (this)
		{
			if (!running)
			{
				immediate = true;
			}
			else if (cachedTick == tick && cached != null)
			{
				result = cached;
				immediate = true;
			}
			else if (active != null)
			{
				if (!active.cancelled && active.callbacks.size() < MAX_WAITERS
					&& (!active.frameArrived || active.tick == tick))
				{
					active.callbacks.add(callback);
					return;
				}
				immediate = true;
			}
			else if (encodingOwner != null)
			{
				immediate = true;
			}
			else
			{
				requested = new Capture(tick, config.hideChatbox());
				requested.callbacks.add(callback);
				active = requested;
			}
		}
		if (immediate)
		{
			callback.accept(result);
			return;
		}
		requestFrame(requested);
	}

	private synchronized void requestFrame(Capture capture)
	{
		if (!running || active != capture || capture.cancelled || pendingFrameListeners >= MAX_FRAME_LISTENERS)
		{
			finish(capture, null);
			return;
		}
		capture.restore = capture.redact ? frames.hideChat() : () -> { };
		capture.timeout = timer.schedule(() -> finish(capture, null), TIMEOUT_MS, TimeUnit.MILLISECONDS);
		pendingFrameListeners++;
		frames.nextFrame(frame -> {
			synchronized (ScreenshotCapture.this) { pendingFrameListeners--; }
			frames.onClient(() -> frameArrived(capture, frame));
		});
	}

	private synchronized void frameArrived(Capture capture, Image frame)
	{
		if (active != capture || capture.cancelled || !running)
		{
			return;
		}
		capture.frameArrived = true;
		final boolean redacted = !capture.redact || frames.isChatHidden();
		restore(capture);
		if (!redacted || frame == null || encodingOwner != null)
		{
			finish(capture, null);
			return;
		}
		encodingOwner = capture;
		capture.encoding = encoder.submit(() -> {
			synchronized (ScreenshotCapture.this)
			{
				if (active != capture || capture.cancelled || !running)
				{
					if (encodingOwner == capture) { encodingOwner = null; }
					return;
				}
				capture.encodingStarted = true;
			}
			ImageEncoder.Encoded result = null;
			try
			{
				result = ImageEncoder.encode(frame);
			}
			catch (RuntimeException e)
			{
				log.debug("Could not encode a captured frame", e);
			}
			finally
			{
				synchronized (ScreenshotCapture.this)
				{
					if (encodingOwner == capture) { encodingOwner = null; }
				}
			}
			finish(capture, result);
		});
	}

	private void finish(Capture capture, @Nullable ImageEncoder.Encoded image)
	{
		frames.onClient(() -> {
			final List<Consumer<ImageEncoder.Encoded>> callbacks;
			final ImageEncoder.Encoded result;
			synchronized (this)
			{
				if (active != capture)
				{
					return;
				}
				restore(capture);
				cancelTasks(capture);
				active = null;
				result = running && !capture.cancelled ? image : null;
				if (result != null)
				{
					cached = result;
					cachedTick = capture.tick;
				}
				callbacks = new ArrayList<>(capture.callbacks);
				capture.callbacks.clear();
			}
			for (Consumer<ImageEncoder.Encoded> callback : callbacks)
			{
				try
				{
					callback.accept(result);
				}
				catch (RuntimeException e)
				{
					log.debug("Screenshot callback failed", e);
				}
			}
		});
	}

	private static void restore(Capture capture)
	{
		if (capture.restore != null)
		{
			capture.restore.run();
			capture.restore = null;
		}
	}

	private void cancelTasks(Capture capture)
	{
		if (capture.timeout != null)
		{
			capture.timeout.cancel(false);
		}
		if (capture.encoding != null)
		{
			if (capture.encoding.cancel(true) && !capture.encodingStarted && encodingOwner == capture)
			{
				encodingOwner = null;
			}
		}
	}

	private static Thread daemon(Runnable task, String name)
	{
		final Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		return thread;
	}

	private static final class Capture
	{
		private final int tick;
		private final boolean redact;
		private final List<Consumer<ImageEncoder.Encoded>> callbacks = new ArrayList<>();
		private Runnable restore;
		private ScheduledFuture<?> timeout;
		private Future<?> encoding;
		private boolean frameArrived;
		private boolean encodingStarted;
		private boolean cancelled;

		private Capture(int tick, boolean redact)
		{
			this.tick = tick;
			this.redact = redact;
		}
	}
}
