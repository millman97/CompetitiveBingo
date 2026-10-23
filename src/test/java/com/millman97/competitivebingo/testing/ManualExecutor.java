package com.millman97.competitivebingo.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** A virtual clock: tasks run only when the test advances it, on the calling thread. */
public final class ManualExecutor extends AbstractExecutorService implements ScheduledExecutorService
{
	private final PriorityQueue<Task<?>> tasks = new PriorityQueue<>();
	private long now;
	private long sequence;
	private boolean shutdown;

	public void advanceMillis(long millis)
	{
		now += millis;
		runReady();
	}

	public void runReady()
	{
		int executed = 0;
		while (!tasks.isEmpty() && tasks.peek().deadline <= now)
		{
			if (++executed > 10_000) { throw new AssertionError("Unbounded scheduled work"); }
			final Task<?> task = tasks.remove();
			if (!task.isCancelled())
			{
				task.run();
				try { task.get(); }
				catch (Exception e) { throw new AssertionError("Scheduled task failed", e); }
			}
		}
	}

	@Override
	public void execute(Runnable task)
	{
		schedule(task, 0, TimeUnit.MILLISECONDS);
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit)
	{
		return schedule(Executors.callable(task, null), delay, unit);
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> task, long delay, TimeUnit unit)
	{
		if (shutdown) { throw new RejectedExecutionException(); }
		final Task<V> scheduled = new Task<>(task, now + unit.toMillis(delay), sequence++);
		tasks.add(scheduled);
		return scheduled;
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initial, long period, TimeUnit unit)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initial, long delay, TimeUnit unit)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public void shutdown() { shutdown = true; }
	@Override
	public boolean isShutdown() { return shutdown; }
	@Override
	public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }

	@Override
	public List<Runnable> shutdownNow()
	{
		shutdown = true;
		final List<Runnable> pending = new ArrayList<>(tasks);
		for (Task<?> task : tasks) { task.cancel(false); }
		tasks.clear();
		return pending;
	}

	private final class Task<V> extends FutureTask<V> implements ScheduledFuture<V>
	{
		private final long deadline;
		private final long order;

		private Task(Callable<V> task, long deadline, long order)
		{
			super(task);
			this.deadline = deadline;
			this.order = order;
		}

		@Override
		public long getDelay(TimeUnit unit) { return unit.convert(deadline - now, TimeUnit.MILLISECONDS); }

		@Override
		public int compareTo(Delayed other)
		{
			final Task<?> task = (Task<?>) other;
			final int time = Long.compare(deadline, task.deadline);
			return time == 0 ? Long.compare(order, task.order) : time;
		}
	}
}
