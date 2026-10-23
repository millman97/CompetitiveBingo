package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.model.GameConfig;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.WorldService;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

/**
 * Offers a hop to the world a game asks players to be on, and carries it out when asked.
 */
@Slf4j
@Singleton
public class WorldHop
{
	/** As many ticks as RuneLite's own hopper allows for the switcher to appear. */
	private static final int MAX_SWITCHER_ATTEMPTS = 3;

	private final Client client;
	private final WorldService worldService;
	private final ClientThread clientThread;

	/** The queued hop, and how many ticks we have spent waiting for the switcher to open. */
	@Nullable
	private net.runelite.api.World pending;

	private int attempts;

	@Inject
	public WorldHop(Client client, WorldService worldService, ClientThread clientThread)
	{
		this.client = client;
		this.worldService = worldService;
		this.clientThread = clientThread;
	}

	/**
	 * The world to offer, or null when there is nothing to offer: the game named no world, the
	 * player is already there, the world list has not loaded, or the world is one this game's
	 * own block list rejects.
	 *
	 * Reads live client state, so it must be called on the client thread.
	 */
	@Nullable
	public Integer targetFor(@Nullable GameConfig game)
	{
		if (game == null || game.getStartWorld() == null)
		{
			return null;
		}

		final int wanted = game.getStartWorld();
		if (client.getWorld() == wanted)
		{
			return null;
		}

		final World world = lookUp(wanted);
		if (world == null)
		{
			return null;
		}

		final Set<WorldType> blocked = ReadinessChecks.toWorldTypes(game.getBlockedWorldTypes());
		if (WorldUtil.toWorldTypes(world.getTypes()).stream().anyMatch(blocked::contains))
		{
			// Sending someone to a world the same game refuses to count would be a nasty trap.
			log.warn("Game names world {} as its start world, but its own block list rejects it", wanted);
			return null;
		}

		return wanted;
	}

	/**
	 * Queues a hop. Safe to call from Swing; the work happens on the client thread and over the
	 * following game ticks.
	 */
	public void hopTo(int worldId)
	{
		clientThread.invoke(() -> {
			final World world = lookUp(worldId);
			if (world == null)
			{
				log.warn("Cannot hop: world {} is not in the world list", worldId);
				return;
			}

			final net.runelite.api.World target = client.createWorld();
			target.setActivity(world.getActivity());
			target.setAddress(world.getAddress());
			target.setId(world.getId());
			target.setPlayerCount(world.getPlayers());
			target.setLocation(world.getLocation());
			target.setTypes(WorldUtil.toWorldTypes(world.getTypes()));

			pending = target;
			attempts = 0;
		});
	}

	/**
	 * Drives a queued hop. hopToWorld does nothing unless the world switcher is already open, so
	 * it is opened first and the hop retried on later ticks until it is there.
	 *
	 * Called from the plugin's GameTick handler, so it runs on the client thread.
	 */
	public void onGameTick()
	{
		if (pending == null)
		{
			return;
		}

		if (client.getWidget(InterfaceID.Worldswitcher.BUTTONS) == null)
		{
			client.openWorldHopper();

			if (++attempts >= MAX_SWITCHER_ATTEMPTS)
			{
				log.warn("Gave up hopping to world {} after {} attempts to open the world switcher",
					pending.getId(), attempts);
				reset();
			}
			return;
		}

		log.debug("Hopping to world {}", pending.getId());
		client.hopToWorld(pending);
		reset();
	}

	/** Drops any queued hop. Called on shutdown so a pending hop does not outlive the plugin. */
	public void reset()
	{
		pending = null;
		attempts = 0;
	}

	@Nullable
	private World lookUp(int worldId)
	{
		final WorldResult worlds = worldService.getWorlds();
		return worlds == null ? null : worlds.findWorld(worldId);
	}
}
