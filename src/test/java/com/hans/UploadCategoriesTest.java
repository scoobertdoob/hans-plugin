package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Item;
import net.runelite.api.PlayerComposition;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Filepath;
import org.junit.Test;

/**
 * The upload's categories (card #75): each one's shape as the website takes it, the privacy classes, batches under
 * 24 KiB with the bank in parts past that, sending only changes, a full snapshot at each login and new address, and
 * nothing at all while switched off. Synthetic only.
 */
public class UploadCategoriesTest
{
	private static final Gson GSON = new Gson();
	private static final ProfileKey ALPHA = new ProfileKey(-4611686018427387904L, "main", "Synth Alpha");
	private static final ProfileKey BETA = new ProfileKey(77L, "main", "Synth Beta");
	private static final Instant AT = Instant.parse("2026-01-01T00:00:03Z");

	private static JsonObject data(UploadEnvelope.Reading r)
	{
		return r.data;
	}

	// ------------------------------------------------------------------ shapes

	@Test
	public void everyCategoryHasTheShapeTheWebsiteTakes()
	{
		Map<Integer, Integer> varbits = new HashMap<>();
		Map<Integer, Integer> varps = new HashMap<>();
		varbits.put(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1);
		varbits.put(VarbitID.ARDOUGNE_EASY_COUNT, 10);
		varbits.put(VarbitID.ARDOUGNE_MED_COUNT, 6);
		varbits.put(VarbitID.KOUREND_ELITE_COUNT, 1);
		varbits.put(VarbitID.CA_POINTS, 42);
		varbits.put(VarbitID.CA_TIER_STATUS_EASY, 2);
		varps.put(ToolkitData.CA_TASK_VARPS[0], 0b101);
		varps.put(ToolkitData.CA_TASK_VARPS[1], 1 << 31); // task 63: the sign bit of a 32-bit variable
		varps.put(MusicTracks.VARPS[0], 7);

		JsonObject diaries = data(UploadEnvelope.diaries(id -> varbits.getOrDefault(id, 0), AT));
		JsonObject ardougne = diaries.getAsJsonObject("regions").getAsJsonObject("Ardougne");
		assertEquals(set("easy", "medium", "hard", "elite"), ardougne.keySet());
		assertTrue(ardougne.get("easy").getAsBoolean());
		assertFalse(ardougne.get("elite").getAsBoolean());
		assertEquals(12, diaries.getAsJsonObject("regions").size());
		// each tier's tasks done, raw from the game's <REGION>_<TIER>_COUNT varbits
		assertEquals(GSON.fromJson("{\"easy\":10,\"medium\":6,\"hard\":0,\"elite\":0}", JsonObject.class),
			diaries.getAsJsonObject("counts").getAsJsonObject("Ardougne"));
		assertEquals(1, diaries.getAsJsonObject("counts").getAsJsonObject("Kourend & Kebos").get("elite").getAsInt());
		assertEquals(diaries.getAsJsonObject("regions").keySet(), diaries.getAsJsonObject("counts").keySet());
		assertEquals(ToolkitData.DIARIES.length, ToolkitData.DIARY_COUNT_VARBITS.length);

		JsonObject ca = data(UploadEnvelope.combatAchievements(id -> varps.getOrDefault(id, 0),
			id -> varbits.getOrDefault(id, 0), AT));
		assertEquals(set("tasks", "points", "tiers"), ca.keySet());
		assertEquals(ToolkitData.CA_TASK_VARPS.length, ca.getAsJsonArray("tasks").size());
		assertEquals(Integer.MIN_VALUE, ca.getAsJsonArray("tasks").get(1).getAsInt());
		assertEquals(42, ca.get("points").getAsInt());
		assertEquals(set("easy", "medium", "hard", "elite", "master", "grandmaster"), ca.getAsJsonObject("tiers").keySet());
		assertEquals(2, ca.getAsJsonObject("tiers").get("easy").getAsInt());

		JsonObject cat = data(UploadEnvelope.caCatalogue(id -> varps.getOrDefault(id, 0), AT));
		assertEquals(GSON.fromJson("{\"completed\":[0,2,63]}", JsonObject.class), cat);

		// the bits, how many tracks the Music table has, and which are unlocked (an automatic one always)
		Map<Integer, MusicTracks.Track> tracks = new HashMap<>();
		tracks.put(40, new MusicTracks.Track("First", MusicTracks.VARPS[0], 0, false)); // bit 0 of 7: unlocked
		tracks.put(41, new MusicTracks.Track("Third", MusicTracks.VARPS[0], 3, false)); // bit 3: locked
		tracks.put(42, new MusicTracks.Track("Scape Main", MusicTracks.VARPS[26], 27, true));
		JsonObject counted = data(UploadEnvelope.music(id -> varps.getOrDefault(id, 0),
			new MusicTracks.Catalogue(tracks, 1), AT));
		assertEquals(set("bits", "total", "unlocked"), counted.keySet());
		assertEquals(27, counted.getAsJsonArray("bits").size());
		assertEquals(7, counted.getAsJsonArray("bits").get(0).getAsInt());
		assertEquals(3, counted.get("total").getAsInt());
		assertEquals(GSON.fromJson("[40,42]", JsonArray.class), counted.getAsJsonArray("unlocked"));

		assertEquals(GSON.fromJson("{\"ironman\":3}", JsonObject.class), data(UploadEnvelope.accountType(3, AT)));

		RsbuddyData.Quests q = new RsbuddyData.Quests();
		q.quest_points = 12;
		q.quests = Arrays.asList(quest("Cook's Assistant", RsbuddyData.FINISHED),
			quest("Dragon Slayer I", RsbuddyData.IN_PROGRESS), quest("Bad\u0001Name", RsbuddyData.NOT_STARTED),
			quest(null, RsbuddyData.NOT_STARTED));
		assertEquals(GSON.fromJson("{\"states\":{\"Cook's Assistant\":\"FINISHED\",\"Dragon Slayer I\":\"IN_PROGRESS\"},"
			+ "\"points\":12}", JsonObject.class), data(UploadEnvelope.quests(q, AT)));

		Map<Integer, Integer> log = new HashMap<>();
		log.put(4151, 1);
		log.put(11840, 3);
		assertEquals(GSON.fromJson("{\"logged\":2,\"total\":1500,\"items\":[[4151,1],[11840,3]]}", JsonObject.class),
			data(UploadEnvelope.collectionLog(2, 1500, log, AT)));

		Map<String, Integer> kc = new LinkedHashMap<>();
		kc.put("Zulrah", 101);
		kc.put(repeat('x', 81), 5); // longer than any name the website takes
		assertEquals(GSON.fromJson("{\"counts\":{\"Zulrah\":101}}", JsonObject.class),
			data(UploadEnvelope.killcounts(kc, AT)));

		Map<String, Integer> clues = new HashMap<>();
		clues.put("beginner", 3);
		clues.put("master", 1);
		clues.put("unknown", 9);
		assertEquals(GSON.fromJson("{\"counts\":{\"beginner\":3,\"master\":1}}", JsonObject.class),
			data(UploadEnvelope.clues(clues, AT)));

		int kit = PlayerComposition.KIT_OFFSET;
		int item = PlayerComposition.ITEM_OFFSET;
		int[] equipment = {kit + 0, item + 1052, 0, item + 4151, kit + 18, 0, kit + 26, kit + 36, kit + 7, kit + 33,
			kit + 42, kit + 14};
		JsonObject look = data(UploadEnvelope.appearance(1, new int[]{3, 5, 7, 0, 1}, equipment, AT));
		assertEquals(1, look.get("bodyType").getAsInt());
		assertEquals(5, look.getAsJsonArray("colors").size());
		JsonObject kits = look.getAsJsonObject("kits");
		assertEquals(12, kits.size());
		assertEquals(0, kits.get("head").getAsInt());
		assertEquals(-1, kits.get("cape").getAsInt()); // an item: what is worn is never sent here
		assertEquals(-1, kits.get("weapon").getAsInt());
		assertEquals(14, kits.get("jaw").getAsInt());

		Map<String, int[][]> stores = new LinkedHashMap<>();
		stores.put("seed_vault", new int[][]{{5295, -1}, {10, 0}});
		stores.put("rune_pouch", new int[][]{{554}, {500}});
		assertEquals(GSON.fromJson("{\"rune_pouch\":[[554,500]],\"seed_vault\":[[5295,10]]}", JsonObject.class),
			data(UploadEnvelope.storages(stores, AT)));

		Map<Integer, Integer> net = new HashMap<>();
		net.put(995, 1200);
		net.put(554, -30);
		net.put(379, 0);
		net.put(-5, 4); // a pouch's essence key, not an item
		assertEquals(GSON.fromJson("{\"items\":[[554,-30],[995,1200]]}", JsonObject.class),
			data(UploadEnvelope.itemGains(net, AT)));

		int[] worn = new int[14];
		Arrays.fill(worn, -1);
		worn[3] = 4151;
		int[] wornQty = new int[14];
		wornQty[3] = 1;
		JsonArray slots = data(UploadEnvelope.equipment(worn, wornQty, AT)).getAsJsonArray("items");
		assertEquals(14, slots.size());
		assertEquals(GSON.fromJson("[-1,0]", JsonArray.class), slots.get(0));
		assertEquals(GSON.fromJson("[4151,1]", JsonArray.class), slots.get(3));

		JsonObject bank = data(UploadEnvelope.bank(new int[]{995, 4151, -1}, new int[]{1000, 0, 0}, new int[]{1, 5}, AT));
		// a placeholder kept; a tab never counts more items than the bank holds
		assertEquals(GSON.fromJson("{\"items\":[[995,1000],[4151,0]],\"tabs\":[1,1]}", JsonObject.class), bank);
	}

	@Test
	public void theSessionSummaryKeepsProgressAndThePersonalPartApart()
	{
		RsbuddyData.Session s = new RsbuddyData.Session();
		s.start = "2026-01-01T00:00:00Z";
		// keyed as sessions.json keys them (Skill.getName()); sent as the skills category names them (first in-game
		// test, 31 Oct: the website refused "Attack", so every session that gained experience was rejected)
		s.xp_start.put("Attack", 100);
		s.xp_end.put("Attack", 600);
		s.xp_start.put("Cooking", 50);
		s.xp_end.put("Cooking", 50);
		s.levels_start.put("Attack", 1);
		s.levels_end.put("Attack", 5);
		s.levels_start.put("Cooking", 1);
		s.levels_end.put("Cooking", 1);
		JsonObject d = data(UploadEnvelope.sessionSummary(s, "Abyssal demons", 120, new int[]{3200, 3201, 1}, AT));
		assertEquals(set("start", "progress", "personal"), d.keySet()); // no end while it goes on
		assertEquals(GSON.fromJson("{\"xp\":{\"ATTACK\":500},\"levels\":{\"ATTACK\":5}}", JsonObject.class),
			d.getAsJsonObject("progress"));
		assertEquals(GSON.fromJson("{\"slayerTask\":{\"name\":\"Abyssal demons\",\"remaining\":120},"
			+ "\"location\":{\"x\":3200,\"y\":3201,\"plane\":1}}", JsonObject.class), d.getAsJsonObject("personal"));

		s.end = "2026-01-01T01:00:00Z";
		JsonObject ended = data(UploadEnvelope.sessionSummary(s, null, 0, null, AT));
		assertEquals("2026-01-01T01:00:00Z", ended.get("end").getAsString());
		assertTrue(ended.getAsJsonObject("personal").get("slayerTask").isJsonNull());
		assertFalse(ended.getAsJsonObject("personal").has("location"));
	}

	@Test
	public void progressIsShareableAndWhatThePlayerHoldsIsPrivate()
	{
		Map<String, String> expected = new HashMap<>();
		for (String c : new String[]{"skills", "quests", "diaries", "combat_achievements", "ca_catalogue",
			"collection_log", "killcounts", "music", "clues", "appearance", "account_type"})
		{
			expected.put(c, "PUBLIC");
		}
		for (String c : new String[]{"inventory", "equipment", "bank", "storages", "item_gains", "session_summary"})
		{
			expected.put(c, "PRIVATE");
		}
		assertEquals(expected, new HashMap<>(UploadEnvelope.PRIVACY));
		List<String> order = new ArrayList<>(UploadEnvelope.PRIVACY.keySet());
		assertEquals("bank", order.get(order.size() - 1)); // packed last: it goes alone or in parts
	}

	// ------------------------------------------------------------------ the sender: batches and parts

	/** Records every POST and answers each at once with the given status. */
	private static final class Wire implements UploadSender.Transport
	{
		final List<JsonObject> bodies = new ArrayList<>();
		final List<Integer> sizes = new ArrayList<>();
		final List<Long> times = new ArrayList<>();
		AtomicLong clock;
		int status = 200;
		String body = "{\"ok\":true}";

		@Override
		public Runnable post(String url, String authorization, String json, UploadSender.Answer answer)
		{
			bodies.add(GSON.fromJson(json, JsonObject.class));
			sizes.add(json.getBytes(StandardCharsets.UTF_8).length);
			times.add(clock.get());
			answer.status(status, status == 429 ? "2" : null, body);
			return null;
		}

		Set<String> categories(int i)
		{
			return bodies.get(i).getAsJsonObject("categories").keySet();
		}

		Set<String> allCategories()
		{
			Set<String> all = new HashSet<>();
			for (int i = 0; i < bodies.size(); i++)
			{
				all.addAll(categories(i));
			}
			return all;
		}
	}

	/** Runs scheduled tasks only when asked, moving the clock to their time. */
	private static final class Timers implements UploadSender.Scheduler
	{
		final AtomicLong clock = new AtomicLong(1_000_000);
		final List<FutureTask<?>> tasks = new ArrayList<>();
		final List<Long> due = new ArrayList<>();

		@Override
		public void execute(Runnable task)
		{
			task.run();
		}

		@Override
		public FutureTask<?> schedule(Runnable task, long delayMs)
		{
			FutureTask<?> f = new FutureTask<>(task, null);
			tasks.add(f);
			due.add(clock.get() + delayMs);
			return f;
		}

		/** Runs every waiting timer, including those the run itself sets, until none is due within {@code ms}. */
		void runFor(long ms)
		{
			long until = clock.get() + ms;
			boolean ran = true;
			while (ran)
			{
				ran = false;
				for (int i = 0; i < tasks.size(); i++)
				{
					FutureTask<?> f = tasks.get(i);
					if (!f.isDone() && due.get(i) <= until)
					{
						clock.set(Math.max(clock.get(), due.get(i)));
						f.run();
						ran = true;
					}
				}
			}
			clock.set(Math.max(clock.get(), until));
		}
	}

	private static final class Rig
	{
		final Wire wire = new Wire();
		final Timers timers = new Timers();
		final AtomicBoolean on = new AtomicBoolean(true);
		String url = "https://upload.example.test/api/ingest";
		final UploadSender sender;

		Rig() throws IOException
		{
			wire.clock = timers.clock;
			Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("rsbuddy-upload"));
			sender = new UploadSender(wire, timers, () -> DeviceKey.loadOrCreate(root, GSON, new SecureRandom()),
				() -> url, on::get, timers.clock::get, new Random(3), GSON);
		}
	}

	/** A full bank: 1,440 slots of large ids and quantities, about 27 KiB. */
	private static UploadEnvelope.Reading bigBank(int seed)
	{
		int[] ids = new int[1440];
		int[] qtys = new int[1440];
		for (int i = 0; i < ids.length; i++)
		{
			ids[i] = 20000 + i;
			qtys[i] = 2_000_000_000 - i - seed;
		}
		return UploadEnvelope.bank(ids, qtys, new int[]{100, 200, 0, 0, 0, 0, 0, 0, 0}, AT);
	}

	private static UploadEnvelope.Reading bigLog()
	{
		Map<Integer, Integer> items = new HashMap<>();
		for (int i = 0; i < 1400; i++)
		{
			items.put(10000 + i, 1 + i % 50);
		}
		return UploadEnvelope.collectionLog(1400, 1600, items, AT);
	}

	private static List<UploadEnvelope.Reading> smallCategories()
	{
		Map<String, Integer> xp = new LinkedHashMap<>();
		xp.put("ATTACK", 13_034_431);
		Map<Integer, Integer> none = Collections.emptyMap();
		return Arrays.asList(UploadEnvelope.skills(xp, AT), UploadEnvelope.diaries(id -> 1, AT),
			UploadEnvelope.music(id -> -1, musicTable(3), AT), UploadEnvelope.caCatalogue(id -> -1, AT),
			UploadEnvelope.inventory(new int[]{995}, new int[]{5}, AT), UploadEnvelope.itemGains(none, AT));
	}

	@Test
	public void aFullSnapshotGoesInBatchesUnder24KiBWithTheBankInParts() throws IOException
	{
		Rig r = new Rig();
		for (UploadEnvelope.Reading reading : smallCategories())
		{
			r.sender.offer(ALPHA, reading);
		}
		r.sender.offer(ALPHA, bigLog());
		r.sender.offer(ALPHA, bigBank(0));
		r.timers.runFor(30_000);

		assertTrue(r.wire.bodies.size() >= 3 && r.wire.bodies.size() <= 5);
		for (int size : r.wire.sizes)
		{
			assertTrue("a batch of " + size + " bytes", size <= UploadSender.BATCH_BYTES);
		}
		for (int i = 1; i < r.wire.times.size(); i++)
		{
			// the website takes a burst of 4, then one per 2 s: these follow at the short gap, not the minute
			long gap = r.wire.times.get(i) - r.wire.times.get(i - 1);
			assertTrue(gap >= UploadSender.FLUSH_GAP_MS && gap < UploadSender.DEFAULT_INTERVAL_MS);
		}
		Set<String> expected = set("skills", "diaries", "music", "ca_catalogue", "inventory", "item_gains",
			"collection_log", "bank");
		assertEquals(expected, r.wire.allCategories());
		assertTrue(r.wire.categories(0).contains("skills")); // progress first

		// the bank's parts: each alone, numbered, sharing the bank's capturedAt, and together the whole bank
		JsonArray joined = new JsonArray();
		int of = -1;
		int seen = 0;
		for (JsonObject body : r.wire.bodies)
		{
			JsonObject bank = body.getAsJsonObject("categories").getAsJsonObject("bank");
			if (bank == null)
			{
				continue;
			}
			assertEquals(1, body.getAsJsonObject("categories").size());
			assertEquals(AT.toString(), bank.get("capturedAt").getAsString());
			JsonObject d = bank.getAsJsonObject("data");
			of = d.get("of").getAsInt();
			assertEquals(++seen, d.get("chunk").getAsInt());
			assertEquals(seen == 1, d.has("tabs")); // the tabs go with part 1 only
			joined.addAll(d.getAsJsonArray("items"));
		}
		assertTrue(of >= 2 && of <= UploadSender.MAX_PARTS);
		assertEquals(of, seen);
		assertEquals(bigBank(0).data.getAsJsonArray("items"), joined);

		int before = r.wire.bodies.size();
		r.sender.offer(ALPHA, bigBank(0)); // nothing changed: nothing goes
		r.timers.runFor(120_000);
		assertEquals(before, r.wire.bodies.size());
	}

	@Test
	public void aBankThatFitsGoesWhole() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, UploadEnvelope.bank(new int[]{995, 4151}, new int[]{1000, 1}, new int[]{1}, AT));
		r.timers.runFor(10_000);
		assertEquals(1, r.wire.bodies.size());
		JsonObject d = r.wire.bodies.get(0).getAsJsonObject("categories").getAsJsonObject("bank").getAsJsonObject("data");
		assertEquals(set("items", "tabs"), d.keySet());
	}

	@Test
	public void aFailedPartSendsTheWholeBankAgain() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, bigBank(0));
		r.timers.runFor(0); // part 1 goes and is accepted
		assertEquals(1, r.wire.bodies.size());
		r.wire.status = 503;
		r.timers.runFor(UploadSender.FLUSH_GAP_MS); // part 2 fails
		assertEquals(2, r.wire.bodies.size());
		r.wire.status = 200;
		r.timers.runFor(UploadSender.BACKOFF_MAX_MS);
		List<Integer> chunks = new ArrayList<>();
		for (JsonObject body : r.wire.bodies)
		{
			chunks.add(body.getAsJsonObject("categories").getAsJsonObject("bank").getAsJsonObject("data").get("chunk")
				.getAsInt());
		}
		assertEquals(Arrays.asList(1, 2, 1, 2), chunks); // started again from its first part, then done
	}

	@Test
	public void tooSoonWaitsAsTheWebsiteSays() throws IOException
	{
		Rig r = new Rig();
		r.wire.status = 429; // Retry-After: 2
		r.sender.offer(ALPHA, UploadEnvelope.skills(Collections.singletonMap("ATTACK", 1), AT));
		r.timers.runFor(0);
		r.wire.status = 200;
		r.timers.runFor(UploadSender.BACKOFF_MAX_MS);
		assertEquals(2, r.wire.bodies.size());
		assertTrue(r.wire.times.get(1) - r.wire.times.get(0) >= 2_000);
	}

	@Test
	public void aSnapshotSendsEveryKnownCategoryAgainAndOnlyForItsAccount() throws IOException
	{
		Rig r = new Rig();
		for (UploadEnvelope.Reading reading : smallCategories())
		{
			r.sender.offer(ALPHA, reading);
		}
		r.sender.offer(BETA, UploadEnvelope.skills(Collections.singletonMap("ATTACK", 5), AT));
		r.timers.runFor(300_000);
		int sent = r.wire.bodies.size();
		for (UploadEnvelope.Reading reading : smallCategories())
		{
			r.sender.offer(ALPHA, reading); // unchanged: nothing
		}
		r.timers.runFor(300_000);
		assertEquals(sent, r.wire.bodies.size());

		r.sender.snapshot(ALPHA); // a new login
		r.timers.runFor(300_000);
		assertEquals(sent + 1, r.wire.bodies.size());
		JsonObject again = r.wire.bodies.get(sent);
		assertEquals(ALPHA.hashText(), again.getAsJsonObject("account").get("hash").getAsString());
		assertEquals(set("skills", "diaries", "music", "ca_catalogue", "inventory", "item_gains"),
			again.getAsJsonObject("categories").keySet());
	}

	@Test
	public void nothingIsSentWhileSwitchedOffAndSwitchingOffForgetsWhatWasRead() throws IOException
	{
		Rig r = new Rig();
		r.on.set(false);
		r.sender.offer(ALPHA, bigBank(0));
		r.sender.snapshot(ALPHA);
		r.timers.runFor(300_000);
		assertEquals(0, r.wire.bodies.size());

		r.on.set(true);
		r.sender.settingsChanged();
		r.sender.offer(ALPHA, UploadEnvelope.skills(Collections.singletonMap("ATTACK", 1), AT));
		r.timers.runFor(300_000);
		assertEquals(1, r.wire.bodies.size()); // the bank offered while off is not sent

		r.on.set(false);
		r.sender.clear();
		r.on.set(true);
		r.sender.settingsChanged();
		r.sender.snapshot(ALPHA);
		r.timers.runFor(300_000);
		assertEquals(1, r.wire.bodies.size()); // switched off: nothing read before is sent later
	}

	// ------------------------------------------------------------------ the capture

	/** What the other captures hold, set by the test. */
	private static final class FakeSources implements UploadCapture.Sources
	{
		ProfileKey countsKey;
		Map<String, Integer> kills;
		CollectionLogCapture.Complete look;
		RsbuddyData.Session session;
		Map<String, Integer> loginKills;
		Map<Integer, Integer> loginDrops;
		Map.Entry<String, Map<Integer, Integer>> gains;

		@Override
		public Map<String, Integer> killCounts(ProfileKey key)
		{
			return key.equals(countsKey) ? kills : null;
		}

		@Override
		public CollectionLogCapture.Complete collectionLog()
		{
			return look;
		}

		@Override
		public RsbuddyData.Session session()
		{
			return session;
		}

		@Override
		public Map<String, Integer> loginKills(ProfileKey key)
		{
			return key.equals(countsKey) ? loginKills : null;
		}

		@Override
		public Map<Integer, Integer> loginDrops(ProfileKey key)
		{
			return key.equals(countsKey) ? loginDrops : null;
		}

		@Override
		public Map.Entry<String, Map<Integer, Integer>> gains()
		{
			return gains;
		}
	}

	/** A Music table of {@code n} tracks, row ids from 10000, spread over the 27 varps. */
	static MusicTracks.Catalogue musicTable(int n)
	{
		Map<Integer, MusicTracks.Track> tracks = new HashMap<>();
		for (int i = 0; i < n; i++)
		{
			tracks.put(10_000 + i, new MusicTracks.Track("Track " + i, MusicTracks.VARPS[i / 32 % 27], i % 32, false));
		}
		return new MusicTracks.Catalogue(tracks, 0);
	}

	/** The game's Music table as the client answers it: one track (row 40, MUSICMULTI_1 bit 0), or not loaded. */
	private static final class MusicSource implements MusicTracks.Source
	{
		boolean loaded = true;
		int reads;

		@Override
		public List<Integer> rows()
		{
			reads++;
			return loaded ? Collections.singletonList(40) : null;
		}

		@Override
		public Object[] cell(int row, int column, int tupleIndex)
		{
			if (column == net.runelite.api.gameval.DBTableID.Music.COL_DISPLAYNAME)
			{
				return new Object[]{"First"};
			}
			if (column == net.runelite.api.gameval.DBTableID.Music.COL_VARIABLE)
			{
				return new Object[]{tupleIndex == 0 ? 1 : 0};
			}
			return new Object[]{0};
		}
	}

	private static final class CaptureRig
	{
		final GameFake g = new GameFake();
		final Wire wire = new Wire();
		final Timers timers = new Timers();
		final AtomicBoolean on = new AtomicBoolean(true);
		final FakeSources sources = new FakeSources();
		final MusicSource music = new MusicSource();
		final UploadCapture capture;

		CaptureRig() throws IOException
		{
			wire.clock = timers.clock;
			Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("rsbuddy-upload"));
			UploadSender sender = new UploadSender(wire, timers,
				() -> DeviceKey.loadOrCreate(root, GSON, new SecureRandom()),
				() -> "https://upload.example.test/api/ingest", on::get, timers.clock::get, new Random(1), GSON);
			capture = new UploadCapture(g.ctx, sender, on::get, sources, music);
			g.captures.add(capture);
			g.containers.put(InventoryID.INV, new Item[]{new Item(995, 100)});
			g.containers.put(InventoryID.WORN, new Item[]{new Item(4151, 1)});
		}

		/** The categories sent from request {@code from} on. */
		Set<String> sentSince(int from)
		{
			Set<String> all = new HashSet<>();
			for (int i = from; i < wire.bodies.size(); i++)
			{
				all.addAll(wire.categories(i));
			}
			return all;
		}

		void send()
		{
			timers.runFor(UploadSender.MAX_INTERVAL_MS);
		}
	}

	@Test
	public void theCaptureSendsEachCategoryOnlyWhenItChanges() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.g.varbits.put(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 0);
		c.g.logIn();
		c.g.ticks(UploadCapture.VARS_TICKS);
		c.send();
		assertEquals(set("skills", "inventory", "equipment", "diaries", "combat_achievements", "ca_catalogue", "music",
			"account_type"), c.sentSince(0));
		JsonElement worn = c.wire.bodies.get(0).getAsJsonObject("categories").getAsJsonObject("equipment")
			.getAsJsonObject("data").getAsJsonArray("items").get(0);
		assertEquals(GSON.fromJson("[4151,1]", JsonArray.class), worn);

		int sent = c.wire.bodies.size();
		c.g.setVarp(VarPlayerID.SLAYER_COUNT, 0); // a variable no category holds changed
		c.g.ticks(2 * UploadCapture.VARS_TICKS);
		c.send();
		assertEquals(sent, c.wire.bodies.size());

		c.g.setVarbit(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1); // a diary tier done
		c.g.ticks(2 * UploadCapture.VARS_TICKS);
		c.send();
		assertEquals(set("diaries"), c.sentSince(sent));

		sent = c.wire.bodies.size();
		c.g.containers.put(InventoryID.BANK, new Item[]{new Item(995, 5000)}); // the bank opened
		c.g.setContainer(InventoryID.BANK, new Item(995, 5000));
		c.g.tick();
		c.send();
		assertEquals(set("bank"), c.sentSince(sent));
	}

	@Test
	public void eachLoginSendsAFullSnapshotEvenWithNothingChanged() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.g.containers.put(InventoryID.BANK, new Item[]{new Item(995, 5000)});
		c.g.logIn();
		c.g.ticks(UploadCapture.DERIVED_TICKS);
		c.send();
		Set<String> first = c.sentSince(0);
		assertTrue(first.contains("bank"));

		c.g.logOut();
		c.send();
		int sent = c.wire.bodies.size();
		c.g.containers.remove(InventoryID.BANK); // not opened yet in this login: last known bank is sent
		c.g.logIn();
		c.g.ticks(UploadCapture.DERIVED_TICKS);
		c.send();
		assertEquals(first, c.sentSince(sent));

		sent = c.wire.bodies.size();
		c.g.loadRegion(); // the same login: no new snapshot
		c.g.ticks(UploadCapture.DERIVED_TICKS);
		c.send();
		assertEquals(sent, c.wire.bodies.size());
	}

	@Test
	public void workedOutCategoriesGoOnlyUnderTheKeyTheyWereWorkedOutFor() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.sources.kills = Collections.singletonMap("Zulrah", 10);
		c.sources.countsKey = BETA; // another account's counts
		c.g.logIn();
		c.g.ticks(UploadCapture.DERIVED_TICKS);
		c.send();
		assertFalse(c.sentSince(0).contains("killcounts"));

		ProfileKey logged = c.g.login.reading();
		c.sources.countsKey = logged;
		c.g.ticks(UploadCapture.DERIVED_TICKS);
		c.send();
		assertTrue(c.sentSince(0).contains("killcounts"));
	}

	@Test
	public void nothingIsReadOrSentWhileSwitchedOff() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.on.set(false);
		c.sources.kills = Collections.singletonMap("Zulrah", 10);
		c.g.logIn();
		c.sources.countsKey = c.g.login.reading();
		c.g.setXp(500);
		c.g.containers.put(InventoryID.BANK, new Item[]{new Item(995, 5000)});
		c.g.setContainer(InventoryID.BANK, new Item(995, 5000));
		c.g.ticks(3 * UploadCapture.DERIVED_TICKS);
		c.g.logOut();
		c.send();
		assertEquals(0, c.wire.bodies.size());
		assertNull(c.capture.sender().refused() ? "refused" : null);
	}

	// ------------------------------------------------------------------ round 2 (review)

	private static JsonObject sent(Wire wire, String category)
	{
		for (int i = wire.bodies.size() - 1; i >= 0; i--)
		{
			JsonObject c = wire.bodies.get(i).getAsJsonObject("categories");
			if (c.has(category))
			{
				return c.getAsJsonObject(category).getAsJsonObject("data");
			}
		}
		return null;
	}

	@Test
	public void storesSeenEmptyAreSentEmptyAndUnseenOnesAreLeftOut() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.g.containers.put(InventoryID.SEED_VAULT, new Item[0]); // opened, empty
		c.g.logIn();
		c.g.tick();
		c.send();
		assertEquals(GSON.fromJson("{\"seed_vault\":[]}", JsonObject.class), sent(c.wire, "storages"));
	}

	@Test
	public void onlyKillAndClueCountsSeenAreSentNeverZerosForUnknownOnes()
	{
		Map<String, Integer> clues = new HashMap<>();
		clues.put("easy", 4); // a new PC: only the tier it has seen
		assertEquals(GSON.fromJson("{\"counts\":{\"easy\":4}}", JsonObject.class),
			data(UploadEnvelope.clues(clues, AT)));
		assertEquals(GSON.fromJson("{\"counts\":{}}", JsonObject.class),
			data(UploadEnvelope.killcounts(Collections.emptyMap(), AT)));
	}

	@Test
	public void theCollectionLogsCountersGoAloneAndItsItemsOnlyFromACompleteLook() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.g.varps.put(VarPlayerID.COLLECTION_COUNT, 200);
		c.g.varps.put(VarPlayerID.COLLECTION_COUNT_MAX, 1500);
		c.g.logIn();
		c.g.ticks(UploadCapture.VARS_TICKS);
		c.send();
		assertEquals(GSON.fromJson("{\"logged\":200,\"total\":1500}", JsonObject.class), sent(c.wire, "collection_log"));

		c.sources.look = new CollectionLogCapture.Complete(c.g.login.reading(), 200, 1500,
			Collections.singletonMap(4151, 1), Instant.parse("2026-01-01T00:00:09Z"));
		c.g.ticks(UploadCapture.DERIVED_TICKS);
		c.send();
		assertEquals(GSON.fromJson("{\"logged\":200,\"total\":1500,\"items\":[[4151,1]]}", JsonObject.class),
			sent(c.wire, "collection_log"));

		int sent = c.wire.bodies.size();
		c.g.setVarp(VarPlayerID.SLAYER_COUNT, 3); // another variable: the counters did not move, nothing goes
		c.g.ticks(2 * UploadCapture.VARS_TICKS);
		c.send();
		assertFalse(c.sentSince(sent).contains("collection_log"));
	}

	@Test
	public void aCounterChangeAfterACompleteLookSendsTheCountersOnly() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.g.varps.put(VarPlayerID.COLLECTION_COUNT, 200);
		c.g.varps.put(VarPlayerID.COLLECTION_COUNT_MAX, 1500);
		c.g.logIn();
		Instant lookAt = Instant.parse("2025-12-31T23:59:00Z");
		c.sources.look = new CollectionLogCapture.Complete(c.g.login.reading(), 200, 1500,
			Collections.singletonMap(4151, 1), lookAt);
		c.g.ticks(UploadCapture.DERIVED_TICKS);
		c.send();
		JsonObject withItems = lastReading(c.wire, "collection_log");
		assertTrue(withItems.getAsJsonObject("data").has("items"));
		assertEquals(lookAt.toString(), withItems.get("capturedAt").getAsString()); // the look's own time

		c.g.setVarp(VarPlayerID.COLLECTION_COUNT, 201); // a new slot, no new look
		c.g.ticks(2 * UploadCapture.VARS_TICKS);
		c.send();
		assertEquals(GSON.fromJson("{\"logged\":201,\"total\":1500}", JsonObject.class), sent(c.wire, "collection_log"));
	}

	@Test
	public void aMusicTableThatFailsIsTriedThreeTimesPerLoginAndNoMusicGoesWithoutIt() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.music.loaded = false;
		c.g.logIn();
		c.g.ticks(10 * UploadCapture.VARS_TICKS);
		c.send();
		assertEquals(UploadCapture.MUSIC_TRIES, c.music.reads); // a few tries, then none for the rest of the login
		assertFalse("never bits without the total", c.sentSince(0).contains("music"));
		assertTrue(c.sentSince(0).contains("diaries"));

		c.g.setVarbit(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1); // vars read again: the table is not
		c.g.ticks(2 * UploadCapture.VARS_TICKS);
		c.g.loadRegion();
		c.g.ticks(2 * UploadCapture.VARS_TICKS);
		assertEquals(UploadCapture.MUSIC_TRIES, c.music.reads);

		c.g.logOut();
		c.send();
		assertEquals(UploadCapture.MUSIC_TRIES, c.music.reads);
		int sent = c.wire.bodies.size();
		c.music.loaded = true;
		c.g.logIn(); // the next login tries again
		c.g.ticks(UploadCapture.VARS_TICKS);
		c.send();
		assertEquals(UploadCapture.MUSIC_TRIES + 1, c.music.reads);
		assertEquals(GSON.fromJson("{\"bits\":" + Collections.nCopies(27, 0) + ",\"total\":1,\"unlocked\":[]}",
			JsonObject.class), sent(c.wire, "music"));
		assertTrue(c.sentSince(sent).contains("music"));

		c.g.logOut();
		c.g.logIn(); // read once: not again
		c.g.ticks(UploadCapture.VARS_TICKS);
		assertEquals(UploadCapture.MUSIC_TRIES + 1, c.music.reads);
	}

	@Test
	public void aRejectionsErrorsAreParsedAndCut() throws IOException
	{
		Rig r = new Rig();
		String longer = "session_summary.data.progress.xp." + String.join("", Collections.nCopies(300, "x"));
		UploadSender.Reply reply = r.sender.reply("{\"ok\":false,\"result\":\"invalid\",\"errors\":["
			+ "\"session_summary.data.progress.xp.Attack is not valid\",\"" + longer + "\"],\"refused\":{\"x\":\"unknown-category\"}}");
		assertEquals(2, reply.errors.size());
		assertEquals("session_summary.data.progress.xp.Attack is not valid", reply.errors.get(0));
		assertEquals(longer.substring(0, UploadSender.MAX_ERROR) + "...", reply.errors.get(1));
		assertEquals(Collections.singletonMap("x", "unknown-category"), reply.refused);

		// sent and rejected: dropped, and the sender goes on with the next change
		r.wire.status = 400;
		r.wire.body = "{\"ok\":false,\"result\":\"invalid\",\"errors\":[\"" + longer + "\"]}";
		r.sender.offer(ALPHA, UploadEnvelope.skills(Collections.singletonMap("ATTACK", 1), AT));
		r.timers.runFor(10_000);
		assertEquals(1, r.wire.bodies.size());
		r.wire.status = 200;
		r.wire.body = "{\"ok\":true}";
		r.sender.offer(ALPHA, UploadEnvelope.skills(Collections.singletonMap("ATTACK", 2), AT));
		r.timers.runFor(UploadSender.MAX_INTERVAL_MS * 4);
		assertEquals(2, r.wire.bodies.size());
	}

	private static JsonObject lastReading(Wire wire, String category)
	{
		for (int i = wire.bodies.size() - 1; i >= 0; i--)
		{
			JsonObject c = wire.bodies.get(i).getAsJsonObject("categories");
			if (c.has(category))
			{
				return c.getAsJsonObject(category);
			}
		}
		return null;
	}

	@Test
	public void readingsOverTheWebsitesCapAreNotSent() throws IOException
	{
		Rig r = new Rig();
		Map<String, Integer> kc = new LinkedHashMap<>();
		for (int i = 0; i < 400; i++)
		{
			kc.put("A boss with a really rather long name, number " + i, i); // ~22 KiB, over killcounts' 16 KiB
		}
		r.sender.offer(ALPHA, UploadEnvelope.killcounts(kc, AT));
		r.sender.offer(ALPHA, UploadEnvelope.skills(Collections.singletonMap("ATTACK", 1), AT));
		r.timers.runFor(10_000);
		assertEquals(set("skills"), r.wire.allCategories());
	}

	@Test
	public void onlyWhatTheWebsiteStoredIsRememberedAndARefusalIsSentAgainAtItsNextOffer() throws IOException
	{
		Rig r = new Rig();
		r.wire.body = "{\"ok\":true,\"categories\":[\"skills\"],\"refused\":{\"music\":\"invalid\"}}";
		UploadEnvelope.Reading music = UploadEnvelope.music(id -> 1, musicTable(3), AT);
		r.sender.offer(ALPHA, UploadEnvelope.skills(Collections.singletonMap("ATTACK", 1), AT));
		r.sender.offer(ALPHA, music);
		r.timers.runFor(10_000);
		assertEquals(1, r.wire.bodies.size());

		r.wire.body = "{\"ok\":true,\"categories\":[\"music\"]}";
		r.sender.offer(ALPHA, UploadEnvelope.skills(Collections.singletonMap("ATTACK", 1), AT)); // stored: not again
		r.sender.offer(ALPHA, music); // refused: not remembered, so it goes again
		r.timers.runFor(UploadSender.MAX_INTERVAL_MS);
		assertEquals(2, r.wire.bodies.size());
		assertEquals(set("music"), r.wire.categories(1));
	}

	@Test
	public void bankPartsWaitingAreNotAcceptedUntilTheWholeBankIs() throws IOException
	{
		Rig r = new Rig();
		r.wire.body = "{\"ok\":true,\"categories\":[],\"waiting\":[\"bank\"]}"; // every part held, none joined
		r.sender.offer(ALPHA, bigBank(0));
		r.timers.runFor(30_000);
		int parts = r.wire.bodies.size();
		assertTrue(parts >= 2);
		r.wire.body = "{\"ok\":true,\"categories\":[\"bank\"]}";
		r.sender.offer(ALPHA, bigBank(0)); // the website never had it whole: sent again
		r.timers.runFor(UploadSender.MAX_INTERVAL_MS);
		assertEquals(2 * parts, r.wire.bodies.size());
		r.sender.offer(ALPHA, bigBank(0)); // now stored: nothing
		r.timers.runFor(UploadSender.MAX_INTERVAL_MS);
		assertEquals(2 * parts, r.wire.bodies.size());
	}

	/** Settings answering from a map; any other setting is off (false) or unset. */
	private static RsbuddyConfig config(Map<String, Boolean> on)
	{
		return (RsbuddyConfig) java.lang.reflect.Proxy.newProxyInstance(RsbuddyConfig.class.getClassLoader(),
			new Class<?>[]{RsbuddyConfig.class}, (self, m, args) -> m.getReturnType() == boolean.class
				? on.getOrDefault(m.getName(), false) : null);
	}

	@Test
	public void whileUploadingCapturesKeepTheirReadingsWithTheirFileSwitchedOff() throws IOException
	{
		GameFake g = new GameFake();
		Map<String, Boolean> settings = new HashMap<>();
		settings.put("uploadLive", true); // exportCharacter off
		CaptureContext ctx = new CaptureContext(g.client, config(settings), g.json, null, g.login);
		QuestsCapture quests = new QuestsCapture(ctx);
		g.captures.add(quests);
		g.logIn();
		g.tick();
		g.executor.runAll();
		ProfileKey key = g.login.reading();
		assertTrue(ctx.keeps(false));
		assertFalse(ctx.writes(false));
		assertEquals(2, quests.latest(key).quests.size()); // kept for the upload
		assertNull(g.read("quests.json", RsbuddyData.Quests.class)); // but no file

		settings.put("uploadLive", false);
		GameFake h = new GameFake();
		CaptureContext off = new CaptureContext(h.client, config(settings), h.json, null, h.login);
		QuestsCapture none = new QuestsCapture(off);
		h.captures.add(none);
		h.logIn();
		h.tick();
		assertNull(none.latest(h.login.reading())); // neither the file nor the upload wants it
	}

	@Test
	public void theSessionSummaryCarriesKillsDropsAndGains() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.g.logIn();
		ProfileKey key = c.g.login.reading();
		RsbuddyData.Session s = new RsbuddyData.Session();
		s.start = "2026-01-01T00:00:00Z";
		c.sources.session = s;
		c.sources.countsKey = key;
		c.sources.loginKills = Collections.singletonMap("Cow", 3);
		c.sources.loginDrops = Collections.singletonMap(526, 3);
		Map<Integer, Integer> net = new HashMap<>();
		net.put(526, 3);
		net.put(554, -10);
		c.sources.gains = new java.util.AbstractMap.SimpleImmutableEntry<>(s.start, net);
		c.g.ticks(UploadCapture.DERIVED_TICKS);
		c.send();
		JsonObject d = sent(c.wire, "session_summary");
		assertEquals(GSON.fromJson("{\"Cow\":3}", JsonObject.class), d.getAsJsonObject("progress").getAsJsonObject("kills"));
		JsonObject personal = d.getAsJsonObject("personal");
		assertEquals(GSON.fromJson("[[526,3]]", JsonArray.class), personal.getAsJsonArray("drops"));
		assertEquals(GSON.fromJson("[[526,3],[554,-10]]", JsonArray.class), personal.getAsJsonArray("gains"));
		assertFalse(d.getAsJsonObject("progress").has("quests")); // not tracked per session: left out
	}

	@Test
	public void lootIsTalliedForTheUploadWithTheLootFileOff() throws IOException
	{
		GameFake g = new GameFake();
		Map<String, Boolean> settings = new HashMap<>();
		settings.put("uploadLive", true); // exportLoot off
		CaptureContext ctx = new CaptureContext(g.client, config(settings), g.json, null, g.login);
		LootCapture loot = new LootCapture(ctx);
		g.captures.add(loot);
		g.logIn();
		ProfileKey key = g.login.reading();
		g.serverLoot(2805, "Cow");
		g.serverLoot(2805, "Cow");
		g.tick();
		g.executor.runAll();
		assertEquals(Collections.singletonMap("Cow", 2), loot.loginKills(key)); // tallied for the upload
		assertNull(g.read("loot.jsonl", Object.class)); // no file: its switch is off
		assertNull(loot.loginKills(BETA));
	}

	// ------------------------------------------------------------------ helpers

	private static RsbuddyData.QuestState quest(String name, String state)
	{
		RsbuddyData.QuestState s = new RsbuddyData.QuestState();
		s.name = name;
		s.state = state;
		return s;
	}

	private static Set<String> set(String... values)
	{
		return new HashSet<>(Arrays.asList(values));
	}

	private static String repeat(char c, int n)
	{
		char[] a = new char[n];
		Arrays.fill(a, c);
		return new String(a);
	}
}
