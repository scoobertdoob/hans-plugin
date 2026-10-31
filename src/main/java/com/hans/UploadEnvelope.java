package com.hans;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import net.runelite.api.PlayerComposition;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.kit.KitType;

/**
 * The one message the plugin sends to RSBuddy's website, version 1 (docs/plans/plan-115-connection.md). The same
 * envelope is used for the live upload and, inside an upload file, for a manual import. It says whose data it is (the
 * account hash and game mode, plus the name as an alias), which PC sent it (the device key id, never the secret),
 * the capture revision, a sequence number that only goes up for that PC, when it was sent, and one reading per
 * category. Every reading is the category's latest state (not a change since the last message), so a lost or
 * rejected message loses nothing that the next one does not carry. Values are raw, decoded by the website.
 * Built off the client thread from readings taken on it.
 */
final class UploadEnvelope
{
	static final String KIND = "rsbuddy-upload";
	static final String FILE_KIND = "rsbuddy-upload-file";
	static final int VERSION = 1;
	/** The shape of the category readings; raised when a reading's fields change meaning. */
	static final int CAPTURE_REVISION = 1;
	static final String SKILLS = "skills";
	static final String QUESTS = "quests";
	static final String DIARIES = "diaries";
	static final String COMBAT_ACHIEVEMENTS = "combat_achievements";
	static final String CA_CATALOGUE = "ca_catalogue";
	static final String COLLECTION_LOG = "collection_log";
	static final String KILLCOUNTS = "killcounts";
	static final String MUSIC = "music";
	static final String CLUES = "clues";
	static final String APPEARANCE = "appearance";
	static final String ACCOUNT_TYPE = "account_type";
	static final String SESSION_SUMMARY = "session_summary";
	static final String INVENTORY = "inventory";
	static final String EQUIPMENT = "equipment";
	static final String BANK = "bank";
	static final String STORAGES = "storages";
	static final String ITEM_GAINS = "item_gains";

	static final String[] CLUE_TIERS = {"beginner", "easy", "medium", "hard", "elite", "master"};
	static final int MAX_KILLCOUNTS = 500;
	static final int MAX_GAINS = 1000;
	/** The most entries the session summary's kills, drops and gains each take. */
	static final int MAX_SESSION_ENTRIES = 200;
	static final int MAX_TABS = 10;

	/**
	 * The largest JSON size of one reading's data the website stores, in bytes (its CATEGORY_MAX_BYTES); a bank in
	 * parts is checked part by part. A larger reading would be refused, so it is not sent.
	 */
	static final Map<String, Integer> MAX_BYTES;

	static
	{
		Map<String, Integer> m = new HashMap<>();
		m.put("skills", 2048);
		m.put("quests", 16384);
		m.put("diaries", 4096);
		m.put("combat_achievements", 4096);
		m.put("ca_catalogue", 12288);
		m.put("collection_log", 30720);
		m.put("killcounts", 16384);
		m.put("music", 12288);
		m.put("clues", 1024);
		m.put("inventory", 2048);
		m.put("equipment", 1024);
		m.put("bank", 30720);
		m.put("storages", 16384);
		m.put("appearance", 2048);
		m.put("session_summary", 12288);
		m.put("item_gains", 16384);
		m.put("account_type", 256);
		MAX_BYTES = Collections.unmodifiableMap(m);
	}

	static final String PUBLIC = "PUBLIC";
	static final String PRIVATE = "PRIVATE";

	/**
	 * Every category, in the order a batch is packed (docs/plans/plan-75.md), with its privacy class (owner, 31 Oct):
	 * progress is shareable; what the player holds, gains and where they are is only ever shown to themselves. The
	 * website keeps its own copy of these classes; this one is for the plugin's tests and disclosure.
	 */
	static final Map<String, String> PRIVACY;

	static
	{
		Map<String, String> p = new LinkedHashMap<>();
		for (String c : new String[]{SKILLS, ACCOUNT_TYPE, QUESTS, DIARIES, COMBAT_ACHIEVEMENTS, CA_CATALOGUE, KILLCOUNTS,
			MUSIC, CLUES, APPEARANCE, COLLECTION_LOG})
		{
			p.put(c, PUBLIC);
		}
		// a session's start, end and progress may be shown to others; its personal part (Slayer task, location) never
		for (String c : new String[]{SESSION_SUMMARY, INVENTORY, EQUIPMENT, STORAGES, ITEM_GAINS, BANK})
		{
			p.put(c, PRIVATE);
		}
		PRIVACY = Collections.unmodifiableMap(p);
	}

	private UploadEnvelope()
	{
	}

	/** One category's latest reading. Immutable: its data is never changed after it is made. */
	static final class Reading
	{
		final String category;
		final String capturedAt;
		final JsonObject data; // compared, never changed

		private Reading(String category, String capturedAt, JsonObject data)
		{
			this.category = category;
			this.capturedAt = capturedAt;
			this.data = data;
		}

		JsonObject json()
		{
			JsonObject o = new JsonObject();
			o.addProperty("capturedAt", capturedAt);
			o.add("data", data.deepCopy());
			return o;
		}
	}

	/** Every skill's experience, by the game's skill name (ATTACK, ...), in the order given. */
	static Reading skills(Map<String, Integer> xp, Instant at)
	{
		JsonObject byName = new JsonObject();
		for (Map.Entry<String, Integer> e : xp.entrySet())
		{
			byName.addProperty(e.getKey(), e.getValue());
		}
		JsonObject data = new JsonObject();
		data.add("xp", byName);
		return new Reading(SKILLS, at.toString(), data);
	}

	/** The backpack, slot by slot: [item id, quantity], with [-1, 0] for an empty slot. */
	static Reading inventory(int[] ids, int[] quantities, Instant at)
	{
		return new Reading(INVENTORY, at.toString(), items(ids, quantities, true));
	}

	/** The worn items, slot by slot like the backpack: [item id, quantity], with [-1, 0] for an empty slot. */
	static Reading equipment(int[] ids, int[] quantities, Instant at)
	{
		return new Reading(EQUIPMENT, at.toString(), items(ids, quantities, true));
	}

	/**
	 * The bank in its own order: [item id, quantity] per slot, a placeholder as [its id, 0], empty slots left out (the
	 * bank keeps none in the middle), and how many items each of tabs 1 to 9 holds (the rest are in the main tab,
	 * after them). A bank too big for one batch is sent in parts ({@link #parts}).
	 */
	static Reading bank(int[] ids, int[] quantities, int[] tabs, Instant at)
	{
		JsonObject data = items(ids, quantities, false);
		int left = data.getAsJsonArray("items").size();
		int[] counts = new int[Math.min(tabs.length, MAX_TABS)];
		for (int i = 0; i < counts.length; i++)
		{
			counts[i] = Math.max(0, Math.min(tabs[i], left)); // never more items in tabs than in the bank
			left -= counts[i];
		}
		data.add("tabs", ints(counts));
		return new Reading(BANK, at.toString(), data);
	}

	/**
	 * A reading of {@code {items: [...]}} split into {@code of} parts {chunk, of, items}, in order and sharing its
	 * capturedAt, for the website to join back together. Its other fields (the bank's tabs) go with part 1.
	 */
	static List<Reading> parts(Reading whole, int of)
	{
		JsonArray items = whole.data.getAsJsonArray("items");
		List<Reading> out = new ArrayList<>();
		int size = items.size();
		for (int c = 0; c < of; c++)
		{
			JsonArray part = new JsonArray();
			for (int i = c * size / of; i < (c + 1) * size / of; i++)
			{
				part.add(items.get(i).deepCopy());
			}
			JsonObject data = new JsonObject();
			data.addProperty("chunk", c + 1);
			data.addProperty("of", of);
			data.add("items", part);
			if (c == 0)
			{
				for (Map.Entry<String, JsonElement> e : whole.data.entrySet())
				{
					if (!"items".equals(e.getKey()))
					{
						data.add(e.getKey(), e.getValue().deepCopy());
					}
				}
			}
			out.add(new Reading(whole.category, whole.capturedAt, data));
		}
		return out;
	}

	/** Each quest's state by the game's quest name (NOT_STARTED, IN_PROGRESS, FINISHED), and the quest points. */
	static Reading quests(RsbuddyData.Quests q, Instant at)
	{
		JsonObject states = new JsonObject();
		for (RsbuddyData.QuestState s : q.quests)
		{
			if (label(s.name) && s.state != null)
			{
				states.addProperty(s.name, s.state);
			}
		}
		JsonObject data = new JsonObject();
		data.add("states", states);
		data.addProperty("points", q.quest_points);
		return new Reading(QUESTS, at.toString(), data);
	}

	/**
	 * Each region's tiers done or not, and how many of each tier's tasks are done (raw), from the game's diary
	 * variables: {regions: {name: {easy, ..., elite}}, counts: {name: {easy: n, ..., elite: n}}}. The website knows
	 * each tier's task total (reference/diaries.json).
	 */
	static Reading diaries(IntUnaryOperator varbit, Instant at)
	{
		JsonObject regions = new JsonObject();
		JsonObject counts = new JsonObject();
		for (int r = 0; r < ToolkitData.DIARIES.length; r++)
		{
			Object[] d = ToolkitData.DIARIES[r];
			int[] ids = (int[]) d[1];
			JsonObject tiers = new JsonObject();
			JsonObject done = new JsonObject();
			for (int i = 0; i < ToolkitData.TIERS.length; i++)
			{
				tiers.addProperty(ToolkitData.TIERS[i], varbit.applyAsInt(ids[i]) != 0);
				done.addProperty(ToolkitData.TIERS[i], Math.max(0, varbit.applyAsInt(ToolkitData.DIARY_COUNT_VARBITS[r][i])));
			}
			regions.add((String) d[0], tiers);
			counts.add((String) d[0], done);
		}
		JsonObject data = new JsonObject();
		data.add("regions", regions);
		data.add("counts", counts);
		return new Reading(DIARIES, at.toString(), data);
	}

	/** The raw combat achievement variables: the task bitsets, the points, and each tier's raw status by name. */
	static Reading combatAchievements(IntUnaryOperator varp, IntUnaryOperator varbit, Instant at)
	{
		JsonObject tiers = new JsonObject();
		for (int i = 0; i < ToolkitData.CA_TIERS.length; i++)
		{
			tiers.addProperty(ToolkitData.CA_TIERS[i], Math.max(0, varbit.applyAsInt(ToolkitData.CA_TIER_VARBITS[i])));
		}
		JsonObject data = new JsonObject();
		data.add("tasks", ints(read(ToolkitData.CA_TASK_VARPS, varp)));
		data.addProperty("points", Math.max(0, varbit.applyAsInt(VarbitID.CA_POINTS)));
		data.add("tiers", tiers);
		return new Reading(COMBAT_ACHIEVEMENTS, at.toString(), data);
	}

	/** The ids of the combat achievement tasks done: a task's id is its bit across the task bitsets. */
	static Reading caCatalogue(IntUnaryOperator varp, Instant at)
	{
		JsonArray done = new JsonArray();
		int[] sets = ToolkitData.CA_TASK_VARPS;
		for (int i = 0; i < sets.length; i++)
		{
			int bits = varp.applyAsInt(sets[i]);
			for (int b = 0; b < 32; b++)
			{
				if ((bits >>> b & 1) != 0)
				{
					done.add(i * 32 + b);
				}
			}
		}
		JsonObject data = new JsonObject();
		data.add("completed", done);
		return new Reading(CA_CATALOGUE, at.toString(), data);
	}

	/**
	 * The collection log: the game's logged and total counters, and, from a complete look only, every obtained item as
	 * [item id, count], by id. {@code items} null: the counters alone (the website keeps the items it has).
	 */
	static Reading collectionLog(int logged, int total, Map<Integer, Integer> items, Instant at)
	{
		JsonObject data = new JsonObject();
		data.addProperty("logged", Math.max(0, Math.min(logged, total)));
		data.addProperty("total", total);
		if (items != null)
		{
			JsonArray list = new JsonArray();
			for (Map.Entry<Integer, Integer> e : new TreeMap<>(items).entrySet())
			{
				if (e.getKey() >= 0 && e.getValue() > 0)
				{
					list.add(pair(e.getKey(), e.getValue()));
				}
			}
			data.add("items", list);
		}
		return new Reading(COLLECTION_LOG, at.toString(), data);
	}

	/** Kill counts by the name the game's chat message gives (at most {@link #MAX_KILLCOUNTS}). */
	static Reading killcounts(Map<String, Integer> counts, Instant at)
	{
		JsonObject byName = new JsonObject();
		for (Map.Entry<String, Integer> e : counts.entrySet())
		{
			if (byName.size() < MAX_KILLCOUNTS && label(e.getKey()) && e.getValue() != null && e.getValue() >= 0)
			{
				byName.addProperty(e.getKey(), e.getValue());
			}
		}
		JsonObject data = new JsonObject();
		data.add("counts", byName);
		return new Reading(KILLCOUNTS, at.toString(), data);
	}

	/** Clue scrolls completed by tier (beginner, easy, medium, hard, elite, master). */
	static Reading clues(Map<String, Integer> counts, Instant at)
	{
		JsonObject byTier = new JsonObject();
		for (String tier : CLUE_TIERS)
		{
			Integer n = counts.get(tier);
			if (n != null && n >= 0)
			{
				byTier.addProperty(tier, n);
			}
		}
		JsonObject data = new JsonObject();
		data.add("counts", byTier);
		return new Reading(CLUES, at.toString(), data);
	}

	/**
	 * The 27 raw music unlock variables (MUSICMULTI_1 to 27), how many tracks the game's Music table has, and the
	 * ids (Music table rows) of those unlocked, automatic ones included: {bits, total, unlocked: [row id, ...]}. Only
	 * with the table read: bits alone would leave the website without the total it holds.
	 */
	static Reading music(IntUnaryOperator varp, MusicTracks.Catalogue cat, Instant at)
	{
		Objects.requireNonNull(cat, "the Music table");
		JsonObject data = new JsonObject();
		int[] bits = read(MusicTracks.VARPS, varp);
		data.add("bits", ints(bits));
		Map<Integer, Integer> byVarp = new HashMap<>();
		for (int i = 0; i < bits.length; i++)
		{
			byVarp.put(MusicTracks.VARPS[i], bits[i]);
		}
		JsonArray unlocked = new JsonArray();
		for (Map.Entry<Integer, MusicTracks.Track> e : new TreeMap<>(cat.tracks).entrySet())
		{
			MusicTracks.Track t = e.getValue();
			if (t.auto || (byVarp.get(t.varp) >>> t.bit & 1) != 0)
			{
				unlocked.add(e.getKey());
			}
		}
		data.addProperty("total", cat.tracks.size());
		data.add("unlocked", unlocked);
		return new Reading(MUSIC, at.toString(), data);
	}

	/**
	 * The account type, raw: the IRONMAN varbit (0 none, 1 ironman, 2 ultimate, 3 hardcore, 4 group, 5 hardcore
	 * group, 6 unranked group), decoded by the website. It changes on the same character (de-ironing, a hardcore
	 * death), so it is a reading, not part of the game mode.
	 */
	static Reading accountType(int ironman, Instant at)
	{
		JsonObject data = new JsonObject();
		data.addProperty("ironman", ironman);
		return new Reading(ACCOUNT_TYPE, at.toString(), data);
	}

	/**
	 * How the character looks, without what it wears: the body type (0 or 1), the five colour indices, and the kit id
	 * per equipment slot by name (-1 where an item, or nothing, covers it).
	 */
	static Reading appearance(int bodyType, int[] colors, int[] equipmentIds, Instant at)
	{
		KitType[] slots = KitType.values();
		JsonObject kits = new JsonObject();
		for (int i = 0; i < slots.length && i < equipmentIds.length; i++)
		{
			int v = equipmentIds[i];
			kits.addProperty(slots[i].name().toLowerCase(Locale.ROOT),
				v >= PlayerComposition.KIT_OFFSET && v < PlayerComposition.ITEM_OFFSET ? v - PlayerComposition.KIT_OFFSET : -1);
		}
		JsonObject data = new JsonObject();
		data.addProperty("bodyType", bodyType);
		data.add("colors", ints(colors));
		data.add("kits", kits);
		return new Reading(APPEARANCE, at.toString(), data);
	}

	/**
	 * The stores seen, by name (seed_vault, looting_bag, poh_costumes, group_storage, rune_pouch, quiver,
	 * potion_storage), each as [item id, quantity] pairs with empty slots left out: {name: [[id, qty], ...]}. Only
	 * stores the plugin has read are listed: one left out is unknown, not empty.
	 */
	static Reading storages(Map<String, int[][]> stores, Instant at)
	{
		JsonObject data = new JsonObject();
		for (Map.Entry<String, int[][]> e : new TreeMap<>(stores).entrySet())
		{
			data.add(e.getKey(), items(e.getValue()[0], e.getValue()[1], false).get("items"));
		}
		return new Reading(STORAGES, at.toString(), data);
	}

	/**
	 * This play session's net change per item, gains positive and losses negative, by id: {items: [[id, change]]}.
	 * Items back to 0 are left out; beyond {@link #MAX_GAINS}, the largest changes are kept.
	 */
	static Reading itemGains(Map<Integer, Integer> net, Instant at)
	{
		JsonObject data = new JsonObject();
		data.add("items", changes(net, MAX_GAINS, true));
		return new Reading(ITEM_GAINS, at.toString(), data);
	}

	/**
	 * [[item id, amount], ...] by id: real items only, no zero amounts (no negative ones unless {@code signed}); beyond
	 * {@code max}, the largest amounts are kept.
	 */
	private static JsonArray changes(Map<Integer, Integer> amounts, int max, boolean signed)
	{
		List<Map.Entry<Integer, Integer>> kept = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : new TreeMap<>(amounts).entrySet())
		{
			if (e.getKey() >= 0 && e.getValue() != null && (signed ? e.getValue() != 0 : e.getValue() > 0))
			{
				kept.add(e);
			}
		}
		if (kept.size() > max)
		{
			kept.sort(Comparator.comparingLong((Map.Entry<Integer, Integer> e) -> -Math.abs((long) e.getValue()))
				.thenComparing(Map.Entry::getKey));
			kept = new ArrayList<>(kept.subList(0, max));
			kept.sort(Map.Entry.comparingByKey());
		}
		JsonArray list = new JsonArray();
		kept.forEach(e -> list.add(pair(e.getKey(), e.getValue())));
		return list;
	}

	/** What a session summary carries besides the session itself; each part null when unknown. */
	static final class SessionExtras
	{
		Map<String, Integer> kills; // kills by NPC name (from the drops credited this login)
		Map<Integer, Integer> drops; // items dropped, by id
		Map<Integer, Integer> gains; // the net change per item this session
		String slayerTask;
		int slayerLeft;
		int[] tile; // x, y, plane
	}

	/**
	 * The play session so far, or the last one: start, end (absent while it goes on), its progress (experience gained
	 * and levels reached, per skill that moved, and kills) and the personal part only the player sees (drops, item
	 * gains, the Slayer task, null when there is none, and where the player is). The website shows only start, end
	 * and progress to anyone else.
	 */
	static Reading sessionSummary(RsbuddyData.Session s, String slayerTask, int slayerLeft, int[] tile, Instant at)
	{
		SessionExtras x = new SessionExtras();
		x.slayerTask = slayerTask;
		x.slayerLeft = slayerLeft;
		x.tile = tile;
		return sessionSummary(s, x, at);
	}

	static Reading sessionSummary(RsbuddyData.Session s, SessionExtras x, Instant at)
	{
		String slayerTask = x.slayerTask;
		int slayerLeft = x.slayerLeft;
		int[] tile = x.tile;
		JsonObject xp = new JsonObject();
		JsonObject levels = new JsonObject();
		for (Map.Entry<String, Integer> e : s.xp_end.entrySet())
		{
			String skill = skillKey(e.getKey());
			Integer before = s.xp_start.get(e.getKey());
			if (before != null && e.getValue() != null && e.getValue() > before)
			{
				xp.addProperty(skill, e.getValue() - before);
			}
			Integer from = s.levels_start.get(e.getKey());
			Integer to = s.levels_end.get(e.getKey());
			if (from != null && to != null && to > from)
			{
				levels.addProperty(skill, to);
			}
		}
		JsonObject progress = new JsonObject();
		progress.add("xp", xp);
		progress.add("levels", levels);
		if (x.kills != null)
		{
			JsonObject kills = new JsonObject();
			for (Map.Entry<String, Integer> e : x.kills.entrySet())
			{
				if (kills.size() < MAX_SESSION_ENTRIES && label(e.getKey()) && e.getValue() != null && e.getValue() > 0)
				{
					kills.addProperty(e.getKey(), e.getValue());
				}
			}
			progress.add("kills", kills);
		}
		JsonObject personal = new JsonObject();
		if (x.drops != null)
		{
			personal.add("drops", changes(x.drops, MAX_SESSION_ENTRIES, false));
		}
		if (x.gains != null)
		{
			personal.add("gains", changes(x.gains, MAX_SESSION_ENTRIES, true));
		}
		if (label(slayerTask) && slayerLeft > 0)
		{
			JsonObject task = new JsonObject();
			task.addProperty("name", slayerTask);
			task.addProperty("remaining", slayerLeft);
			personal.add("slayerTask", task);
		}
		else
		{
			personal.add("slayerTask", JsonNull.INSTANCE);
		}
		if (tile != null)
		{
			JsonObject location = new JsonObject();
			location.addProperty("x", tile[0]);
			location.addProperty("y", tile[1]);
			location.addProperty("plane", tile[2]);
			personal.add("location", location);
		}
		JsonObject data = new JsonObject();
		data.addProperty("start", s.start);
		if (s.end != null)
		{
			data.addProperty("end", s.end);
		}
		data.add("progress", progress);
		data.add("personal", personal);
		return new Reading(SESSION_SUMMARY, at.toString(), data);
	}

	// ------------------------------------------------------------------ shapes

	/**
	 * A skill as the skills category names it (ATTACK): sessions.json keeps the game's display name (Attack), which
	 * the website refuses (first in-game test, 31 Oct: every session that gained experience was rejected).
	 */
	static String skillKey(String name)
	{
		return name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
	}

	/** {items: [[id, quantity], ...]}: every slot ({@code keepEmpty}, [-1, 0] when empty) or only the filled ones. */
	private static JsonObject items(int[] ids, int[] quantities, boolean keepEmpty)
	{
		JsonArray items = new JsonArray();
		for (int i = 0; i < ids.length && i < quantities.length; i++)
		{
			if (ids[i] >= 0)
			{
				items.add(pair(ids[i], Math.max(0, quantities[i])));
			}
			else if (keepEmpty)
			{
				items.add(pair(-1, 0));
			}
		}
		JsonObject data = new JsonObject();
		data.add("items", items);
		return data;
	}

	/** A quest, boss or region name the website takes: 1 to 80 characters, none of them a control character. */
	private static boolean label(String s)
	{
		if (s == null || s.isEmpty() || s.length() > 80)
		{
			return false;
		}
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (c < 0x20 || c >= 0x7f && c <= 0x9f)
			{
				return false;
			}
		}
		return true;
	}

	private static JsonArray pair(int a, int b)
	{
		JsonArray p = new JsonArray();
		p.add(a);
		p.add(b);
		return p;
	}

	private static JsonArray ints(int[] values)
	{
		JsonArray a = new JsonArray();
		for (int v : values)
		{
			a.add(v);
		}
		return a;
	}

	private static int[] read(int[] ids, IntUnaryOperator var)
	{
		int[] out = new int[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			out[i] = var.applyAsInt(ids[i]);
		}
		return out;
	}

	/**
	 * The envelope for one account and mode.
	 *
	 * @param keyId  this PC's public key id (the secret travels only in the Authorization header)
	 * @param seq    this PC's sequence number, higher than any it sent before
	 * @param sentAt when the envelope was built
	 */
	static JsonObject build(ProfileKey key, String keyId, long seq, Instant sentAt, Collection<Reading> readings)
	{
		JsonObject account = new JsonObject();
		account.addProperty("hash", key.hashText());
		account.addProperty("mode", key.mode);
		account.addProperty("name", key.name.replace(' ', ' ')); // the client's names use non-breaking spaces
		JsonObject device = new JsonObject();
		device.addProperty("keyId", Objects.requireNonNull(keyId));
		JsonObject categories = new JsonObject();
		for (Reading r : readings)
		{
			categories.add(r.category, r.json());
		}
		JsonObject o = new JsonObject();
		o.addProperty("envelope", KIND);
		o.addProperty("version", VERSION);
		o.add("account", account);
		o.add("device", device);
		o.addProperty("captureRevision", CAPTURE_REVISION);
		o.addProperty("seq", seq);
		o.addProperty("sentAt", sentAt.toString());
		o.add("categories", categories);
		return o;
	}

	/** An upload file for a manual import: the same envelopes, oldest first. */
	static JsonObject file(List<JsonObject> envelopes)
	{
		JsonArray list = new JsonArray();
		for (JsonObject e : envelopes)
		{
			list.add(e);
		}
		JsonObject o = new JsonObject();
		o.addProperty("format", FILE_KIND);
		o.addProperty("version", VERSION);
		o.add("envelopes", list);
		return o;
	}
}
