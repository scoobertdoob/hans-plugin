package com.hans;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.WorldsFetch;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.LootManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

/**
 * Hans: writes your levels, quests, play sessions and the rest of your progress to local JSON files in
 * RuneLite's data folder (.runelite/plugin-data/hans/accounts/&lt;account hash&gt;/&lt;game mode&gt;/) for the
 * Hans app to read. Nothing is sent over the network unless the opt-in upload is switched on ({@link UploadCapture}, off by
 * default), or sign-in as well ({@link AuthClient}, off by default). Game data is read on the client thread; files are written on
 * the background executor.
 *
 * This class is only wiring: it builds the {@link Capture}s, forwards the game events to them, and owns the
 * side panel. Each category of data lives in its own Capture.
 */
@PluginDescriptor(
	name = "Hans",
	internalName = "hans", // the Hub slug; names the data folder .runelite/plugin-data/hans/
	description = "Your OSRS progress companion. Saves your progress to local files, and can send it to the Hans website if you switch that on",
	tags = {"companion", "progress", "sessions", "quests", "xp", "tracker", "export"}
)
public class RsbuddyPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ScheduledExecutorService executor;
	@Inject private Gson clientGson;
	@Inject private ClientThread clientThread;
	@Inject private ItemManager itemManager;
	// Never read: injecting it makes RuneLite create it, and it is what raises ServerNpcLoot and NpcLootReceived.
	// Without it, drops vanish whenever the Loot Tracker plugin is switched off.
	@Inject private LootManager lootManager;
	@Inject private SkillIconManager skillIconManager;
	@Inject private WorldService worldService;
	@Inject private RsbuddyConfig config;
	@Inject private ConfigManager configManager;
	@Inject private OkHttpClient okHttpClient;

	private RsbuddyPanel panel;
	private NavigationButton navButton;
	volatile LoginState login; // read by the world-list event, which RuneLite posts from its own thread
	private CaptureHub hub;
	private UploadCapture upload;
	private AuthClient auth;
	private List<Capture> captures = new CopyOnWriteArrayList<>();

	@Provides
	RsbuddyConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RsbuddyConfig.class);
	}

	@Override
	protected void startUp()
	{
		// once, after the rename: the settings saved under the old group carry over
		ConfigMigration.Settings settings = ConfigMigration.of(configManager);
		if (ConfigMigration.run(settings, RsbuddyConfig.GROUP, RsbuddyConfig.OLD_GROUPS) >= 0)
		{
			// only once the copy has finished, so a retry can't bring an old default address back afterwards
			ConfigMigration.moveAddress(settings, RsbuddyConfig.GROUP);
		}
		// one key per PC, shared by the upload and sign-in; both are opt-in and off by default
		UploadSender.Keys keys = DeviceKey.shared(this::getPluginDirectory, clientGson);
		auth = new AuthClient(UploadCapture.okHttp(okHttpClient), executor, keys, config::uploadUrl,
			() -> config.signIn() && config.uploadLive(), System::currentTimeMillis, clientGson);
		auth.onReset(() -> SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.signInReset();
			}
		}));
		panel = new RsbuddyPanel(skillIconManager, config::appUrl, new SignInFlow.Requests()
		{
			@Override
			public boolean on()
			{
				return config.signIn() && config.uploadLive();
			}

			@Override
			public void request(AuthClient.Listener done)
			{
				// the character is read on the client thread; AuthClient then works on the executor
				clientThread.invoke(() -> auth.request(login.reading(), done));
			}
		});
		panel.start();
		navButton = NavigationButton.builder().tooltip("Hans").icon(icon()).priority(7).panel(panel).build();
		clientToolbar.addNavigation(navButton);

		login = new LoginState(client, WorldSeasons.of(worldService));
		Consumer<Instant> onWritten = when -> SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.setLastWrite(when);
			}
		});
		JsonWriter json = new JsonWriter(executor, clientGson, this::getPluginDirectory, config::writeFiles, onWritten);
		CaptureContext ctx = new CaptureContext(client, config, json, itemManager, login, clientThread);
		hub = new CaptureHub(ctx);
		captures = hub.captures;

		// The registered order is the order of the hooks. The sessions use the character readings, so they follow them.
		CharacterCapture character = new CharacterCapture(ctx);
		captures.add(character);
		SessionCapture sessions = new SessionCapture(ctx, character, (account, start, xp) -> { });
		captures.add(sessions);
		QuestsCapture quests = new QuestsCapture(ctx);
		captures.add(quests);
		captures.add(ProgressCaptures.diaries(ctx));
		captures.add(ProgressCaptures.combatAchievements(ctx));
		captures.add(new CombatAchievementsCapture(ctx));
		captures.add(new ItemsCapture(ctx, "inventory", InventoryID.INV, config::exportItems, "exportItems"));
		captures.add(new ItemsCapture(ctx, "equipment", InventoryID.WORN, config::exportItems, "exportItems"));
		captures.add(new ItemsCapture(ctx, "bank", InventoryID.BANK, config::exportBank, "exportBank"));
		ItemGainsCapture gains = new ItemGainsCapture(ctx, sessions);
		captures.add(gains);
		captures.add(new GeOffersCapture(ctx));
		captures.add(new GameQuestsCapture(ctx));
		captures.add(new PositionCapture(ctx));

		// stage B: raw values, decoded later in the app
		captures.add(new AccountCapture(ctx));
		captures.add(new VarsCapture(ctx));
		captures.add(new FarmingCapture(ctx));
		captures.add(new BirdHousesCapture(ctx));
		LootCapture loot = new LootCapture(ctx);
		captures.add(loot);
		captures.add(new ItemsCapture(ctx, "seed_vault", InventoryID.SEED_VAULT, config::exportStorage, "exportStorage"));
		captures.add(new ItemsCapture(ctx, "looting_bag", InventoryID.LOOTING_BAG, config::exportStorage, "exportStorage"));
		captures.add(new ItemsCapture(ctx, "poh_storage", InventoryID.POH_COSTUMES, config::exportStorage, "exportStorage"));
		RunePouchCapture runePouch = new RunePouchCapture(ctx, config::exportStorage);
		captures.add(runePouch);
		QuiverCapture quiver = new QuiverCapture(ctx, config::exportStorage);
		captures.add(quiver);
		PotionStorageCapture potions = new PotionStorageCapture(ctx, config::exportStorage);
		captures.add(potions);
		captures.add(new ItemsCapture(ctx, "group_storage", InventoryID.INV_GROUP_TEMP, config::exportStorage, "exportStorage"));
		captures.add(new AppearanceCapture(ctx));
		CollectionLogCapture collectionLog = new CollectionLogCapture(ctx);
		captures.add(collectionLog);
		// opt-in, off by default; registered after every capture it takes worked-out categories from
		upload = UploadCapture.create(ctx, executor, okHttpClient, clientGson, keys, new UploadCapture.Sources()
		{
			@Override
			public RsbuddyData.Quests quests(ProfileKey key)
			{
				return quests.latest(key);
			}

			@Override
			public RsbuddyData.Session session()
			{
				return sessions.live();
			}

			@Override
			public RsbuddyData.Session endedSession()
			{
				return sessions.previous();
			}

			@Override
			public Map<String, Integer> killCounts(ProfileKey key)
			{
				return loot.killCounts(key);
			}

			@Override
			public Map<String, Integer> clueCounts(ProfileKey key)
			{
				return loot.clueCounts(key);
			}

			@Override
			public CollectionLogCapture.Complete collectionLog()
			{
				return collectionLog.latestComplete();
			}

			@Override
			public Map<String, Integer> loginKills(ProfileKey key)
			{
				return loot.loginKills(key);
			}

			@Override
			public Map<Integer, Integer> loginDrops(ProfileKey key)
			{
				return loot.loginDrops(key);
			}

			@Override
			public Map.Entry<String, Map<Integer, Integer>> gains()
			{
				return gains.sessionNet();
			}

			@Override
			public List<StorageSnapshot> carried(ProfileKey key)
			{
				List<StorageSnapshot> out = new ArrayList<>();
				for (StorageSnapshot s : new StorageSnapshot[]{runePouch.latest(key), quiver.latest(key), potions.latest(key)})
				{
					if (s != null)
					{
						out.add(s);
					}
				}
				return out;
			}
		});
		captures.add(upload);
		captures.add(auth); // cancels a sign-in request at every logout, change of character or mode, or settings change

		// the side panel reads what the captures above hold, so it comes last
		captures.add(new PanelCapture(ctx, clientGson, character, sessions, quests, m -> SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.show(m);
			}
		})));

		// startUp runs on Swing's thread; the game state and LoginState belong to the client thread
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				login.loggedIn(); // enabled while logged in: start a session from the next reading
			}
		});
	}

	@Override
	protected void shutDown()
	{
		// shutDown runs on Swing's thread, after RuneLite stopped sending this plugin events: the logout reads the game
		// (the upload's last reading), so it and everything the captures hold are handed to the client thread
		Instant now = Instant.now();
		CaptureHub stopping = hub;
		UploadCapture stoppingUpload = upload;
		AuthClient stoppingAuth = auth;
		LoginState stoppingLogin = login;
		clientThread.invoke(() ->
		{
			stopping.logoutAll(now);
			stoppingUpload.close();
			stoppingAuth.cancel();
			stopping.captures.clear();
			stoppingLogin.loggedOut();
		});
		clientToolbar.removeNavigation(navButton);
		panel.stop();
		panel = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			hub.loggedIn(Instant.now()); // a hop into another game mode ends the login under the old key first
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			hub.loggedOut(Instant.now());
		}
		else if (state == GameState.HOPPING || state == GameState.LOADING)
		{
			hub.hopping(); // the session goes on
		}
	}

	/** RuneLite fetched the world list (on its own thread): the Deadman season is named from it. */
	@Subscribe
	public void onWorldsFetch(WorldsFetch event)
	{
		LoginState current = login;
		if (current != null)
		{
			current.worldsFetched(event.getWorldResult());
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (RsbuddyConfig.GROUP.equals(event.getGroup()))
		{
			// posted on the thread that changed the setting (Swing's, from the settings panel): the captures' state
			// belongs to the client thread
			String key = event.getKey();
			CaptureHub current = hub;
			clientThread.invoke(() -> current.each(c -> c.onConfigChanged(key)));
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		hub.each(c -> c.onStatChanged(event));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		hub.each(c -> c.onVarbitChanged(event));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		hub.each(c -> c.onItemContainerChanged(event));
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		hub.each(c -> c.onGrandExchangeOfferChanged(event));
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		hub.each(c -> c.onChatMessage(event));
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		hub.each(c -> c.onPlayerChanged(event));
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		hub.each(c -> c.onServerNpcLoot(event));
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		hub.each(c -> c.onNpcLootReceived(event));
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		hub.each(c -> c.onMenuOptionClicked(event));
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		hub.each(c -> c.onScriptPreFired(event));
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		hub.each(c -> c.onScriptPostFired(event));
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		hub.each(c -> c.onWidgetLoaded(event));
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		hub.each(c -> c.onWidgetClosed(event));
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}
		hub.tick(local, Instant.now());
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		// posted on Swing's thread: the logout is handed to the client thread, and the client waits for it
		hub.closing(Instant.now(), clientThread::invoke, event::waitFor);
	}

	// ------------------------------------------------------------------ panel


	/** The toolbar icon: the Hans chathead (OSRS Wiki, as plugin/icon.png), 16 by 16, bundled with the plugin. */
	private static BufferedImage icon()
	{
		return ImageUtil.loadImageResource(RsbuddyPlugin.class, "nav_icon.png");
	}
}
