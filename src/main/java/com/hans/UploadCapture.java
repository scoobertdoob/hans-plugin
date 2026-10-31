package com.hans;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.runelite.api.EnumComposition;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The opt-in upload to RSBuddy's website (off by default): every category the website shows (see
 * {@link UploadEnvelope#PRIVACY}) when it changes, at most once a minute, every 5 seconds while someone has RSBuddy
 * open, and at logout (see {@link UploadSender}). Each settled login, and each new address or switch-on, sends every
 * category again in full. Game state is read on the client thread, under the key a reading may be taken for
 * ({@link LoginState#reading}), so nothing is read before a login settles, while changing world, on worlds the game
 * wipes, or for another account than the one logged in. What other captures work out (quests, the session, kill and
 * clue counts, the collection log, item gains, carried stores) is taken from them, under the key they hold it for.
 * Serialising and sending happen off the client thread, in the sender. Independent of the "Write files" switch and
 * the export switches: while uploading, the other captures keep their readings in memory ({@link CaptureContext#keeps})
 * and those settings decide only what is written to disk.
 */
final class UploadCapture implements Capture
{
	static final String ENABLE_KEY = "uploadLive";
	static final String URL_KEY = "uploadUrl";
	/**
	 * RuneLite shows this before the switch changes; the full disclosure is in docs/plans/plan-115-connection.md.
	 * Public-facing text: the owner reviews it before release.
	 */
	static final String WARNING = "This sends your account's progress (skills, quests, diaries, combat achievements,"
		+ " collection log, kill counts, music, clues, appearance and account type) to the Hans website, at the upload address in"
		+ " the settings. It also sends private data that only you can see: your backpack, worn items, bank, other"
		+ " storages, item gains, Slayer task and location. It sends what changed while you play, at most about once a"
		+ " minute (every few seconds while the website is open), and when you log out. Each upload includes an ID for"
		+ " this PC and your account, and the server sees your IP address. Your chat and login details are never sent."
		+ " Switching this off stops sending at once. Continue?";

	/** Game variables change often: the categories read from them are read at most this often (~3 s). */
	static final int VARS_TICKS = 5;
	/** How often what other captures work out is looked at (~3 s). */
	static final int DERIVED_TICKS = 5;
	/** Reads of the game's Music table per login; after that, no music is sent until the next login. */
	static final int MUSIC_TRIES = 3;
	/** How many items each bank tab (1 to 9) holds. */
	static final int[] BANK_TABS = {VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3, VarbitID.BANK_TAB_4,
		VarbitID.BANK_TAB_5, VarbitID.BANK_TAB_6, VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8, VarbitID.BANK_TAB_9};
	/** The game's Slayer task names, by the task variable (no RuneLite name for this enum). */
	static final int SLAYER_TASK_ENUM = 693;

	/** The item stores read here, by the name the website files them under. */
	static final Map<Integer, String> STORES;

	static
	{
		Map<Integer, String> s = new LinkedHashMap<>();
		s.put(InventoryID.SEED_VAULT, "seed_vault");
		s.put(InventoryID.LOOTING_BAG, "looting_bag");
		s.put(InventoryID.POH_COSTUMES, "poh_costumes");
		s.put(InventoryID.INV_GROUP_TEMP, "group_storage");
		STORES = Collections.unmodifiableMap(s);
	}

	/**
	 * What other captures hold, for the categories they work out. Client thread. Each answers null (or nothing) while
	 * it does not know.
	 */
	interface Sources
	{
		default RsbuddyData.Quests quests(ProfileKey key)
		{
			return null;
		}

		/** The live play session (a copy), or null. */
		default RsbuddyData.Session session()
		{
			return null;
		}

		/** The newest finished session, or null. */
		default RsbuddyData.Session endedSession()
		{
			return null;
		}

		default Map<String, Integer> killCounts(ProfileKey key)
		{
			return null;
		}

		default Map<String, Integer> clueCounts(ProfileKey key)
		{
			return null;
		}

		default CollectionLogCapture.Complete collectionLog()
		{
			return null;
		}

		/** This login's kills by NPC name, from the drops credited to {@code key}; null when none are held for it. */
		default Map<String, Integer> loginKills(ProfileKey key)
		{
			return null;
		}

		/** This login's dropped items by id and quantity, credited to {@code key}; null when none are held for it. */
		default Map<Integer, Integer> loginDrops(ProfileKey key)
		{
			return null;
		}

		/** This session's net change per item, keyed by the session's start; null before a session. */
		default Map.Entry<String, Map<Integer, Integer>> gains()
		{
			return null;
		}

		/** The latest complete reading of each carried store (rune pouch, quiver, potion storage) for the key. */
		default List<StorageSnapshot> carried(ProfileKey key)
		{
			return Collections.emptyList();
		}
	}

	private static final Sources NONE = new Sources()
	{
	};

	private final CaptureContext ctx;
	private final UploadSender sender;
	private final BooleanSupplier on;
	private final Sources sources;
	private final MusicTracks.Source musicSource;

	// ---- client thread only
	private final Map<String, JsonObject> offered = new HashMap<>(); // the last data offered per category
	private final Map<String, int[][]> stores = new LinkedHashMap<>(); // the item stores seen, for storesKey
	private ProfileKey storesKey;
	private ProfileKey snapKey; // the login a full snapshot was asked for
	private CollectionLogCapture.Complete logSent;
	private int[] logCounters; // the collection log's counters last offered
	private String sessionStart; // the session last offered, with the Slayer task and tile last read in it
	private String slayerTask;
	private int slayerLeft;
	private int[] tile;
	private boolean xpDirty = true;
	private boolean inventoryDirty = true;
	private boolean wornDirty = true;
	private boolean bankDirty = true;
	private boolean storesDirty = true;
	private boolean varsDirty = true;
	private boolean appearanceDirty = true;
	private int varsWait;
	private int derivedWait;
	private MusicTracks.Catalogue musicTable; // read once: it changes only with a game update, which restarts the client
	private int musicTries;

	UploadCapture(CaptureContext ctx, UploadSender sender, BooleanSupplier on)
	{
		this(ctx, sender, on, NONE);
	}

	UploadCapture(CaptureContext ctx, UploadSender sender, BooleanSupplier on, Sources sources)
	{
		this(ctx, sender, on, sources, new MusicTracks.ClientSource(ctx.client));
	}

	UploadCapture(CaptureContext ctx, UploadSender sender, BooleanSupplier on, Sources sources,
		MusicTracks.Source musicSource)
	{
		this.ctx = ctx;
		this.sender = sender;
		this.on = on;
		this.sources = sources;
		this.musicSource = musicSource;
	}

	/** The plugin's wiring: RuneLite's executor, OkHttp client and Gson, and this PC's key ({@link DeviceKey#shared}). */
	static UploadCapture create(CaptureContext ctx, ScheduledExecutorService executor, OkHttpClient http, Gson gson,
		UploadSender.Keys keys, Sources sources)
	{
		Random jitter = new Random();
		UploadSender.Scheduler onExecutor = new UploadSender.Scheduler()
		{
			@Override
			public void execute(Runnable task)
			{
				executor.execute(task);
			}

			@Override
			public Future<?> schedule(Runnable task, long delayMs)
			{
				return executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
			}
		};
		UploadSender sender = new UploadSender(okHttp(http), onExecutor, keys, ctx.config::uploadUrl,
			ctx.config::uploadLive, System::currentTimeMillis, jitter, gson);
		return new UploadCapture(ctx, sender, ctx.config::uploadLive, sources);
	}

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** POSTs with RuneLite's OkHttp client (no redirects followed, so the key goes only to the address set). */
	static UploadSender.Transport okHttp(OkHttpClient shared)
	{
		OkHttpClient http = shared.newBuilder().followRedirects(false).followSslRedirects(false).build();
		return (url, authorization, json, answer) ->
		{
			Request request = new Request.Builder().url(url).header("Authorization", authorization)
				.post(RequestBody.create(JSON, json)).build();
			Call call = http.newCall(request);
			call.enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					answer.failed(e);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					try (Response r = response)
					{
						answer.status(r.code(), r.header("Retry-After"), r.peekBody(4096).string());
					}
					catch (IOException e)
					{
						answer.failed(e);
					}
				}
			});
			return call::cancel;
		};
	}

	@Override
	public void onLogin()
	{
		// also after a hop or a region load: every game reading is taken again (unchanged ones are not sent)
		markAllDirty();
	}

	@Override
	public void onStatChanged(StatChanged event)
	{
		xpDirty = true;
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = ItemsCapture.normaliseContainerId(event.getContainerId());
		if (id == InventoryID.INV)
		{
			inventoryDirty = true;
		}
		else if (id == InventoryID.WORN)
		{
			wornDirty = true;
		}
		else if (id == InventoryID.BANK)
		{
			bankDirty = true;
		}
		else if (STORES.containsKey(id))
		{
			storesDirty = true;
		}
	}

	@Override
	public void onVarbitChanged(VarbitChanged event)
	{
		varsDirty = true;
	}

	@Override
	public void onPlayerChanged(PlayerChanged event)
	{
		if (event.getPlayer() != null && event.getPlayer() == ctx.client.getLocalPlayer())
		{
			appearanceDirty = true;
		}
	}

	@Override
	public void onConfigChanged(String key)
	{
		if (ENABLE_KEY.equals(key))
		{
			if (on.getAsBoolean())
			{
				fresh(); // a full reading of each, straight away
				sender.settingsChanged();
			}
			else
			{
				sender.clear();
			}
		}
		else if (URL_KEY.equals(key))
		{
			fresh(); // a new destination gets a full reading at the next tick, with no change in the game
			sender.settingsChanged();
		}
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null || !on.getAsBoolean())
		{
			return; // no reading may be taken now: stays dirty
		}
		if (!key.equals(snapKey))
		{
			snapKey = key; // the login settled: the website gets every category again
			musicTries = 0;
			fresh();
			sender.snapshot(key);
		}
		read(key, local, now);
		if (--derivedWait <= 0)
		{
			derivedWait = DERIVED_TICKS;
			derived(key, local, now);
		}
	}

	/**
	 * Logout: a last reading of what changed (only if one may still be taken for this login's key), the session and
	 * gains as they ended, and the unsent readings go now rather than after the usual gap.
	 */
	@Override
	public Future<?> onLogout(Instant now)
	{
		if (on.getAsBoolean())
		{
			ProfileKey key = ctx.login.reading();
			if (key != null)
			{
				varsWait = 0;
				read(key, null, now);
			}
			ProfileKey ended = ctx.login.logoutKey(); // what other captures already tagged with this login's key
			if (ended != null)
			{
				derived(ended, null, now);
			}
		}
		snapKey = null;
		sender.flushNow();
		return null;
	}

	/** Everything is read again and offered even if unchanged since the last offer. */
	private void fresh()
	{
		offered.clear();
		logSent = null;
		logCounters = null;
		markAllDirty();
		varsWait = 0;
		derivedWait = 0;
	}

	/** The game's Music table; null while it can't be read whole, tried a few times per login. Client thread. */
	private MusicTracks.Catalogue musicTable()
	{
		if (musicTable == null && musicTries < MUSIC_TRIES)
		{
			musicTries++;
			musicTable = MusicTracks.catalogue(musicSource);
		}
		return musicTable;
	}

	private void markAllDirty()
	{
		xpDirty = true;
		inventoryDirty = true;
		wornDirty = true;
		bankDirty = true;
		storesDirty = true;
		varsDirty = true;
		appearanceDirty = true;
	}

	/** The categories read straight from the game. Client thread, under a key a reading may be taken for. */
	private void read(ProfileKey key, Player local, Instant now)
	{
		if (xpDirty)
		{
			xpDirty = false;
			Map<String, Integer> xp = new LinkedHashMap<>();
			for (Skill skill : Skill.values())
			{
				if (skill != Skill.OVERALL)
				{
					xp.put(skill.name(), ctx.client.getSkillExperience(skill));
				}
			}
			offer(key, UploadEnvelope.skills(xp, now));
		}
		if (inventoryDirty)
		{
			int[][] items = container(InventoryID.INV);
			inventoryDirty = items == null; // not loaded yet: a backpack not seen is not an empty one
			if (items != null)
			{
				offer(key, UploadEnvelope.inventory(items[0], items[1], now));
			}
		}
		if (wornDirty)
		{
			int[][] items = container(InventoryID.WORN);
			wornDirty = items == null;
			if (items != null)
			{
				offer(key, UploadEnvelope.equipment(items[0], items[1], now));
			}
		}
		if (bankDirty)
		{
			int[][] items = container(InventoryID.BANK);
			bankDirty = items == null; // known only once the bank was opened this session
			if (items != null)
			{
				int[] tabs = new int[BANK_TABS.length];
				for (int i = 0; i < tabs.length; i++)
				{
					tabs[i] = ctx.client.getVarbitValue(BANK_TABS[i]);
				}
				offer(key, UploadEnvelope.bank(items[0], items[1], tabs, now));
			}
		}
		if (storesDirty)
		{
			storesDirty = false;
			for (Map.Entry<Integer, String> s : STORES.entrySet())
			{
				int[][] items = container(s.getKey());
				if (items != null)
				{
					store(key, s.getValue(), items, now);
				}
			}
		}
		if (varsDirty && --varsWait <= 0)
		{
			varsDirty = false;
			varsWait = VARS_TICKS;
			offer(key, UploadEnvelope.diaries(ctx.client::getVarbitValue, now));
			offer(key, UploadEnvelope.combatAchievements(ctx.client::getVarpValue, ctx.client::getVarbitValue, now));
			offer(key, UploadEnvelope.caCatalogue(ctx.client::getVarpValue, now));
			MusicTracks.Catalogue table = musicTable();
			if (table != null)
			{
				offer(key, UploadEnvelope.music(ctx.client::getVarpValue, table, now));
			}
			else if (musicTries < MUSIC_TRIES)
			{
				varsDirty = true; // the table again in VARS_TICKS (the rest is only sent if it changed)
			}
			// else: no music this login, rather than bits without the total the website already holds
			offer(key, UploadEnvelope.accountType(ctx.client.getVarbitValue(VarbitID.IRONMAN), now));
			collectionLog(key, now, false);
		}
		if (appearanceDirty && local != null)
		{
			PlayerComposition comp = local.getPlayerComposition();
			if (comp != null && comp.getColors() != null && comp.getColors().length == 5 && comp.getEquipmentIds() != null)
			{
				appearanceDirty = false;
				offer(key, UploadEnvelope.appearance(comp.getGender(), comp.getColors(), comp.getEquipmentIds(), now));
			}
		}
	}

	/**
	 * The categories other captures work out, each only under the key they hold it for. With no player (a logout),
	 * the session goes as it ended and nothing is read from the game.
	 */
	private void derived(ProfileKey key, Player local, Instant now)
	{
		RsbuddyData.Quests quests = sources.quests(key);
		if (local != null && quests != null && quests.quests != null)
		{
			offer(key, UploadEnvelope.quests(quests, now));
		}
		Map<String, Integer> kills = sources.killCounts(key);
		if (kills != null)
		{
			offer(key, UploadEnvelope.killcounts(kills, now));
		}
		Map<String, Integer> clues = sources.clueCounts(key);
		if (clues != null)
		{
			offer(key, UploadEnvelope.clues(clues, now));
		}
		CollectionLogCapture.Complete log = sources.collectionLog();
		if (local != null && log != null && log != logSent && key.equals(log.key) && key.equals(ctx.login.reading()))
		{
			logSent = log;
			collectionLog(key, now, true); // a new complete look: its items
		}
		for (StorageSnapshot s : sources.carried(key))
		{
			int[] ids = new int[s.items.size()];
			int[] qtys = new int[ids.length];
			for (int i = 0; i < ids.length; i++)
			{
				ids[i] = s.items.get(i).id;
				qtys[i] = s.items.get(i).quantity;
			}
			store(key, s.key, new int[][]{ids, qtys}, now);
		}
		session(key, local, now);
		Map.Entry<String, Map<Integer, Integer>> gains = sources.gains();
		if (gains != null && gains.getKey().equals(sessionStart))
		{
			offer(key, UploadEnvelope.itemGains(gains.getValue(), now)); // this login's session only
		}
	}

	/**
	 * The collection log's counters when the game has them (or the last complete look's), offered when they change;
	 * with the items of the last complete look of this key only for a new look ({@code newLook}: also each full
	 * re-send, which starts over). Client thread, under a key a reading may be taken for.
	 */
	private void collectionLog(ProfileKey key, Instant now, boolean newLook)
	{
		int total = ctx.client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		int logged = ctx.client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		CollectionLogCapture.Complete look = sources.collectionLog();
		Map<Integer, Integer> items = look != null && key.equals(look.key) ? look.items : null;
		if (total <= 0)
		{
			if (items == null || look.total <= 0)
			{
				return; // the counters are not loaded yet
			}
			total = look.total;
			logged = look.obtained;
		}
		if (!newLook && logCounters != null && logCounters[0] == logged && logCounters[1] == total)
		{
			return;
		}
		logCounters = new int[]{logged, total};
		if (newLook && items != null)
		{
			// a new complete look, or the full re-send after a login or new address: its items, at the look's own time
			offer(key, UploadEnvelope.collectionLog(logged, total, items, look.at));
		}
		else
		{
			// the counters alone: the website keeps the items it has, never dated by a look that was not taken
			offer(key, UploadEnvelope.collectionLog(logged, total, null, now));
		}
	}

	/** The session so far (or, at a logout, as it ended), with the Slayer task and tile only while in game. */
	private void session(ProfileKey key, Player local, Instant now)
	{
		RsbuddyData.Session s = sources.session();
		if (s == null && local == null)
		{
			RsbuddyData.Session ended = sources.endedSession();
			s = ended != null && ended.start != null && ended.start.equals(sessionStart) ? ended : null;
		}
		if (s == null || s.start == null)
		{
			return;
		}
		if (!s.start.equals(sessionStart))
		{
			sessionStart = s.start;
			slayerTask = null;
			slayerLeft = 0;
			tile = null;
		}
		if (local != null && key.equals(ctx.login.reading()))
		{
			slayerLeft = ctx.client.getVarpValue(VarPlayerID.SLAYER_COUNT);
			EnumComposition names = slayerLeft > 0 ? ctx.client.getEnum(SLAYER_TASK_ENUM) : null;
			slayerTask = names == null ? null : names.getStringValue(ctx.client.getVarpValue(VarPlayerID.SLAYER_TARGET));
			WorldPoint at = local.getWorldLocation();
			tile = at == null ? null : new int[]{at.getX(), at.getY(), at.getPlane()};
		}
		// at a logout, the task and tile last seen in game stay with the session as it ended
		UploadEnvelope.SessionExtras x = new UploadEnvelope.SessionExtras();
		x.slayerTask = slayerTask;
		x.slayerLeft = slayerLeft;
		x.tile = tile;
		x.kills = sources.loginKills(key);
		x.drops = sources.loginDrops(key);
		Map.Entry<String, Map<Integer, Integer>> gains = sources.gains();
		x.gains = gains != null && gains.getKey().equals(s.start) ? gains.getValue() : null;
		offer(key, UploadEnvelope.sessionSummary(s, x, now));
	}

	/** One item store's contents, filed with the others seen for this key; the stores go as one reading. */
	private void store(ProfileKey key, String name, int[][] items, Instant now)
	{
		if (!key.equals(storesKey))
		{
			stores.clear();
			storesKey = key;
		}
		stores.put(name, items);
		offer(key, UploadEnvelope.storages(stores, now));
	}

	/** Offers a reading unless its data is the one last offered for the category (the sender checks again). */
	private void offer(ProfileKey key, UploadEnvelope.Reading reading)
	{
		if (reading.data.equals(offered.get(reading.category)))
		{
			return;
		}
		offered.put(reading.category, reading.data);
		sender.offer(key, reading);
	}

	/** A container's ids and quantities, or null when the client has not loaded it (storage under its flagged id). */
	private int[][] container(int id)
	{
		ItemContainer container = ctx.client.getItemContainer(id);
		if (container == null && id != InventoryID.INV && id != InventoryID.WORN && id != InventoryID.BANK)
		{
			container = ctx.client.getItemContainer(id | 0x8000);
		}
		if (container == null)
		{
			return null;
		}
		Item[] items = container.getItems();
		int[] ids = new int[items.length];
		int[] qtys = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			qtys[i] = items[i].getQuantity();
		}
		return new int[][]{ids, qtys};
	}

	/** The plugin is stopping. */
	void close()
	{
		sender.close();
	}

	UploadSender sender()
	{
		return sender;
	}
}
