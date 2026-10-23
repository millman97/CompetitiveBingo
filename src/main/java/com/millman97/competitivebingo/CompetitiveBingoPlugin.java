package com.millman97.competitivebingo;

import com.millman97.competitivebingo.capture.ScreenshotCapture;
import com.millman97.competitivebingo.model.EventEnvelope;
import com.millman97.competitivebingo.model.EventSource;
import com.millman97.competitivebingo.parse.ChatObserver;
import com.millman97.competitivebingo.parse.DropObserver;
import com.millman97.competitivebingo.parse.DropValueRules;
import com.millman97.competitivebingo.parse.ManualEntry;
import com.millman97.competitivebingo.parse.MetricFilter;
import com.millman97.competitivebingo.parse.NpcKillObserver;
import net.runelite.client.callback.ClientThread;
import com.millman97.competitivebingo.parse.Observation;
import com.millman97.competitivebingo.sync.SyncService;
import com.millman97.competitivebingo.sync.SyncStatus;
import com.millman97.competitivebingo.sync.WorldHop;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.AccountHashChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Competitive Bingo",
	description = "Reports tracked kills, drops, durations and collection log entries to your own bingo server.",
	tags = {"bingo", "clan", "event", "competition", "loot"}
)
public class CompetitiveBingoPlugin extends Plugin
{
	/**
	 * The message types the game uses for the announcements this plugin watches.
	 *
	 * MESBOX is a dialog rather than a chat line, and is here because some counters are only
	 * ever announced that way — Barbarian Assault's high level gamble count is one, and it is
	 * invisible to a plugin watching chat alone.
	 */
	private static final EnumSet<ChatMessageType> WATCHED_TYPES = EnumSet.of(
		ChatMessageType.GAMEMESSAGE,
		ChatMessageType.SPAM,
		ChatMessageType.TRADE,
		ChatMessageType.MESBOX,
		ChatMessageType.FRIENDSCHATNOTIFICATION);

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private CompetitiveBingoConfig config;

	@Inject
	private ChatObserver chatObserver;

	@Inject
	private DropObserver dropObserver;

	@Inject
	private NpcKillObserver npcKillObserver;

	@Inject
	private EventFactory eventFactory;

	@Inject
	private SyncService syncService;

	@Inject
	private WorldHop worldHop;

	@Inject
	private ScreenshotCapture screenshotCapture;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientThread clientThread;

	/**
	 * Prices for the value rules, from the client's own data. Reading either touches the item
	 * cache, so this is only ever called on the client thread.
	 */
	private final DropValueRules.Prices prices = new DropValueRules.Prices()
	{
		@Override
		public Long grandExchange(int itemId)
		{
			final int price = itemManager.getItemPrice(itemId);
			return price <= 0 ? null : (long) price;
		}

		@Override
		public Long highAlchemy(int itemId)
		{
			final int price = itemManager.getItemComposition(itemId).getHaPrice();
			return price <= 0 ? null : (long) price;
		}
	};

	private CompetitiveBingoPanel panel;
	private NavigationButton navButton;

	@Nullable
	private volatile SessionState session;
	private final Set<String> observedMetrics = new HashSet<>();
	private String observedAccount;
	private volatile boolean running;

	@Override
	protected void startUp()
	{
		running = true;

		panel = new CompetitiveBingoPanel(new PanelActions());
		panel.setSettings(config.serverUrl(), config.joinCode());

		syncService.setStatusListener(this::publishStatus);

		syncService.setEventListener(event -> {
			final long expected = syncService.getGeneration();
			final CompetitiveBingoPanel target = panel;
			SwingUtilities.invokeLater(() -> {
				if (target != null && target == panel && expected == syncService.getGeneration())
				{
					target.addActivity(event);
				}
			});
		});

		final BufferedImage icon = ImageUtil.loadImageResource(CompetitiveBingoPlugin.class, "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Competitive Bingo")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		syncService.startUp();
		screenshotCapture.startUp();

		clientThread.invoke(() -> {
			if (running && client.getGameState() == GameState.LOGGED_IN)
			{
				syncService.setLoggedIn(true);
				startSession();
				syncService.resumeIfPossible(session);
			}
		});
	}

	@Override
	protected void shutDown()
	{
		running = false;
		clientToolbar.removeNavigation(navButton);
		navButton = null;

		if (panel != null)
		{
			panel.dispose();
			panel = null;
		}

		syncService.shutDown();
		screenshotCapture.shutDown();
		worldHop.reset();

		clientThread.invoke(() -> {
			endSession();
			observedMetrics.clear();
			observedAccount = null;
		});

		log.debug("Competitive Bingo stopped");
	}

	/** Everything the side panel can ask for. Called on the Swing thread. */
	private final class PanelActions implements CompetitiveBingoPanel.Actions
	{
		@Override
		public void sync()
		{
			clientThread.invoke(() -> {
				if (running) { syncService.sync(session); }
			});
		}

		/** The panel writes straight to config, so both places to edit these stay in step. */
		@Override
		public void settingsChanged(String serverUrl, String joinCode)
		{
			if (!config.serverUrl().equals(serverUrl))
			{
				configManager.setConfiguration(CompetitiveBingoConfig.GROUP, "serverUrl", serverUrl);
			}
			if (!config.joinCode().equals(joinCode))
			{
				configManager.setConfiguration(CompetitiveBingoConfig.GROUP, "joinCode", joinCode);
			}
		}

		@Override
		public void leave()
		{
			syncService.leaveGame();
		}

		@Override
		public void hop(int world)
		{
			worldHop.hopTo(world);
		}

		@Override
		public void submitManual(String metricId, String label, String input, Consumer<String> onQueued)
		{
			// Building an event reads live client state, and the screenshot has to be requested
			// from the thread that draws frames.
			clientThread.invoke(() -> reportManual(metricId, label, input, onQueued));
		}

		@Override
		public void refreshBoard()
		{
			syncService.refreshBoard();
		}
	}

	/**
	 * Sends one hand-typed event. Runs on the client thread.
	 *
	 * A screenshot goes with it only when both the player opted in and the game asks for evidence.
	 */
	private void reportManual(String metricId, String label, String input, Consumer<String> onQueued)
	{
		final ObservationContext context = observationContext();
		if (context == null)
		{
			onQueued.accept("Not reporting right now. Check the panel before sending.");
			return;
		}
		if (!context.manualAllowed)
		{
			onQueued.accept("This game no longer accepts manual entries.");
			return;
		}

		if (!context.filter.matches(metricId))
		{
			// The dropdown only offers tracked metrics, so this means the game changed under us.
			onQueued.accept("This game is no longer tracking " + label + ". Press Re-sync.");
			return;
		}

		final Observation observation;
		try
		{
			observation = ManualEntry.of(metricId, label, input);
		}
		catch (IllegalArgumentException e)
		{
			onQueued.accept(e.getMessage());
			return;
		}

		final EventEnvelope pending =
			eventFactory.build(observation, context.gameId, context.session, EventSource.MANUAL);
		sendWithEvidence(pending, context, accepted -> onQueued.accept(accepted ? null
			: "The connection changed or the upload queue is full. Your input was kept."));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CompetitiveBingoConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("serverUrl".equals(event.getKey()) || "joinCode".equals(event.getKey()))
		{
			syncService.configurationChanged();
			screenshotCapture.cancelPending();
			clientThread.invoke(() -> {
				chatObserver.reset();
				npcKillObserver.reset();
			});
		}
		if ("enableScreenshots".equals(event.getKey()) || "hideChatbox".equals(event.getKey()))
		{
			screenshotCapture.cancelPending();
			syncService.recheckReadiness();
		}

		// Editing these in RuneLite's own config screen should show up in the panel too.
		if ("serverUrl".equals(event.getKey()) || "joinCode".equals(event.getKey()))
		{
			SwingUtilities.invokeLater(() -> {
				if (panel != null)
				{
					panel.setSettings(config.serverUrl(), config.joinCode());
				}
			});
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			syncService.setLoggedIn(true);
			startSession();
			// Rejoin a game already in progress, so a crash mid-event does not silently stop
			// someone reporting. Does nothing if there is no such game, or if already connected.
			syncService.resumeIfPossible(session);
			syncService.recheckReadiness();
		}
		else
		{
			syncService.setLoggedIn(false);
			if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
			{
				endSession();
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Returns immediately unless a hop is queued.
		worldHop.onGameTick();
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		// Hopping onto a Leagues or beta world has to stop reporting immediately.
		syncService.recheckReadiness();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		final int id = event.getVarbitId();
		if (id == VarbitID.BOSS_KILLCOUNT_FILTERED || id == VarbitID.OPTION_COLLECTION_NEW_ITEM)
		{
			// A chat setting changed mid-game; the checklist has to follow.
			syncService.recheckReadiness();
		}
	}

	@Subscribe
	public void onAccountHashChanged(AccountHashChanged event)
	{
		// A different account is a different session: baselines reset and its own token applies.
		log.debug("Account changed, restarting session");
		syncService.forgetCurrentConnection();
		endSession();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			startSession();
			syncService.resumeIfPossible(session);
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (event.getPlugin() instanceof LootTrackerPlugin) { syncService.recheckReadiness(); }
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!WATCHED_TYPES.contains(event.getType()) || !isEmitting())
		{
			return;
		}

		report(chatObserver.onChatMessage(event.getMessage(), client.getTickCount()));
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (!isEmitting())
		{
			return;
		}
		final Observation kill = npcKillObserver.onLootReceived(event.getType().name(), event.getMetadata(),
			event.getName(), event.getCombatLevel(), client.getTickCount(), syncService.getFilter());
		if (kill != null) { report(Collections.singletonList(kill)); }

		report(dropObserver.onLootReceived(
			event.getName(),
			event.getType().name(),
			client.getTickCount(),
			event.getItems(),
			new DropObserver.Items()
			{
				@Override
				public int canonicalize(int itemId)
				{
					return itemManager.canonicalize(itemId);
				}

				@Override
				public String nameOf(int itemId)
				{
					return itemManager.getItemComposition(itemId).getName();
				}
			},
			syncService.getDropValueRules(),
			prices));
	}

	private boolean isEmitting()
	{
		return running && session != null && client.getGameState() == GameState.LOGGED_IN
			&& syncService.isEmitting();
	}

	private void report(List<Observation> observations)
	{
		final ObservationContext context = observationContext();
		if (context == null) { return; }

		for (Observation observation : observations)
		{
			if (!context.filter.matches(observation.getMetricId()))
			{
				log.debug("Ignoring {}: not tracked by this game", observation.getMetricId());
				continue;
			}

			sendWithEvidence(eventFactory.build(observation, context.gameId, context.session), context);
		}
	}

	@Nullable
	private ObservationContext observationContext()
	{
		if (!isEmitting()) { return null; }
		final long expected = syncService.getGeneration();
		final String gameId = syncService.getGameId();
		if (gameId == null) { return null; }
		final ObservationContext context = new ObservationContext(gameId, session, syncService.getFilter(),
			expected, config.enableScreenshots() && syncService.isScreenshotsRequested(),
			syncService.getStatus().isManualEntryAllowed());
		return expected == syncService.getGeneration() ? context : null;
	}

	private void sendWithEvidence(EventEnvelope pending, ObservationContext context)
	{
		sendWithEvidence(pending, context, accepted -> { });
	}

	private void sendWithEvidence(EventEnvelope pending, ObservationContext context, Consumer<Boolean> onQueued)
	{
		if (!context.screenshot || !config.enableScreenshots())
		{
			onQueued.accept(dispatch(pending, context.generation));
			return;
		}
		final boolean hideChat = config.hideChatbox();
		screenshotCapture.capture(client.getTickCount(), image -> onQueued.accept(dispatch(image == null
			|| !config.enableScreenshots() || config.hideChatbox() != hideChat ? pending
			: pending.withScreenshot(image.base64(), image.getMediaType()), context.generation)));
	}

	private boolean dispatch(EventEnvelope envelope, long expected)
	{
		return running && syncService.send(envelope, expected);
	}

	private void startSession()
	{
		final long accountHash = client.getAccountHash();
		if (accountHash == -1)
		{
			return;
		}

		if (session != null && session.getAccountHash().equals(Long.toString(accountHash)))
		{
			return;
		}

		chatObserver.reset();
		npcKillObserver.reset();
		if (!Long.toString(accountHash).equals(observedAccount))
		{
			observedMetrics.clear();
			observedAccount = Long.toString(accountHash);
		}
		session = new SessionState(accountHash, displayName(), observedMetrics);
		log.debug("Session {} started for account {}", session.getSessionId(), accountHash);
	}

	private String displayName()
	{
		final Player local = client.getLocalPlayer();
		final String name = local == null ? null : local.getName();
		return name == null ? "" : name;
	}

	private void endSession()
	{
		if (session != null)
		{
			log.debug("Session {} ended", session.getSessionId());
		}
		session = null;
		screenshotCapture.cancelPending();
		npcKillObserver.reset();
		chatObserver.reset();
	}

	private void publishStatus(SyncStatus status)
	{
		final CompetitiveBingoPanel target = panel;
		final long expected = syncService.getGeneration();
		SwingUtilities.invokeLater(() -> {
			if (target != null && target == panel && running && expected == syncService.getGeneration())
			{
				target.setStatus(status);
			}
		});
	}

	@Provides
	CompetitiveBingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CompetitiveBingoConfig.class);
	}

	private static final class ObservationContext
	{
		private final String gameId;
		private final SessionState session;
		private final MetricFilter filter;
		private final long generation;
		private final boolean screenshot;
		private final boolean manualAllowed;

		private ObservationContext(String gameId, SessionState session, MetricFilter filter,
			long generation, boolean screenshot, boolean manualAllowed)
		{
			this.gameId = gameId;
			this.session = session;
			this.filter = filter;
			this.generation = generation;
			this.screenshot = screenshot;
			this.manualAllowed = manualAllowed;
		}
	}
}
