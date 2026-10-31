package com.hans;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.IterableHashTable;
import net.runelite.api.Node;
import net.runelite.api.Player;
import net.runelite.api.StructComposition;

/**
 * ca_tasks.json: the game's own combat achievement task list, read from the client's cache so that it always matches
 * the game version (and the mode's world). Each task's id is also its completion bit: bit {@code id % 32} of the
 * {@code id / 32}th CA_TASK_COMPLETED varp, which combat_achievements.json records; this file only names the tasks.
 *
 * Read once per account and mode in a login session (not again on a world hop or region load), on the client thread,
 * after the login has settled. The cache may not have every enum or struct loaded in the first ticks, so an
 * incomplete reading is retried a few times. Only a complete one is written, so a failed reading never replaces the
 * last complete file. "Complete" means: all six tier lists and the monster names are loaded and non-empty, every task
 * struct loads with its id, name, description and its own tier, that tier agrees with its tier list, and no id
 * appears twice. Ids are never capped: a task added after the current 0..654 is kept as it is.
 *
 * Ids (checked against OSRS cache 2752 on 2026-10-30: 655 tasks, ids 0..654 with no gaps, every struct with its
 * name, description and tier): tier enums 3981..3986 (Easy..Grandmaster) list the task structs; struct params 1306 id,
 * 1308 name, 1309 description, 1310 tier (1..6), 1311 type, 1312 monster; enum 3971 names the monsters. RuneLite's
 * gameval has no names for these enums or params, so they are not in {@link VarIds}.
 */
@Slf4j
final class CombatAchievementsCapture implements Capture
{
	static final String FILE = "ca_tasks.json";
	static final int FORMAT_VERSION = 1;

	static final int[] TIER_ENUMS = {3981, 3982, 3983, 3984, 3985, 3986};
	static final String[] TIER_NAMES = {"Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"};
	static final int MONSTER_ENUM = 3971;
	static final int PARAM_ID = 1306;
	static final int PARAM_NAME = 1308;
	static final int PARAM_DESCRIPTION = 1309;
	static final int PARAM_TIER = 1310;
	static final int PARAM_TYPE = 1311;
	static final int PARAM_MONSTER = 1312;

	/** Tries per account and mode in a session, {@link #RETRY_TICKS} apart (about half a minute in all). */
	static final int MAX_TRIES = 10;
	static final int RETRY_TICKS = 5;

	/** The game cache, as this capture reads it. Client thread only; null means "not loaded (yet)". */
	interface Source
	{
		/** The struct ids a tier enum lists, or null when the enum is not loaded. */
		int[] enumInts(int enumId);

		/** The names an enum lists by key, or null when the enum is not loaded. */
		Map<Integer, String> enumStrings(int enumId);

		/** A struct's int param, or null when the struct is not loaded or has no such param. */
		Integer structInt(int structId, int param);

		/** A struct's string param, or null when the struct is not loaded or has no such param. */
		String structString(int structId, int param);
	}

	private final CaptureContext ctx;
	private final BooleanSupplier enabled;
	private final Source source;
	/** The keys whose reading is finished in this session: written, or given up on after {@link #MAX_TRIES}. */
	private final Set<ProfileKey> done = new HashSet<>();
	private ProfileKey trying;
	private int tries;
	private int wait;

	CombatAchievementsCapture(CaptureContext ctx)
	{
		this(ctx, ctx.config::exportCharacter, new ClientSource(ctx.client));
	}

	CombatAchievementsCapture(CaptureContext ctx, BooleanSupplier enabled, Source source)
	{
		this.ctx = ctx;
		this.enabled = enabled;
		this.source = source;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null || done.contains(key) || !enabled.getAsBoolean() || !ctx.json.enabled())
		{
			return;
		}
		if (!key.equals(trying))
		{
			trying = key;
			tries = 0;
			wait = 0;
		}
		if (wait > 0)
		{
			wait--;
			return;
		}
		Reading r = read(source, now.toString(), ctx.client.getRevision());
		if (r.catalogue == null)
		{
			if (++tries >= MAX_TRIES)
			{
				done.add(key);
				log.warn("Hans: the combat achievement tasks could not be read ({}); the last file is kept", r.problem);
			}
			wait = RETRY_TICKS - 1;
			return;
		}
		done.add(key);
		ctx.json.write(key, FILE, r.catalogue);
	}

	@Override
	public Future<?> onLogout(Instant now)
	{
		done.clear();
		trying = null;
		return null;
	}

	/** A reading: the catalogue when complete, else null and what was missing. */
	static final class Reading
	{
		final Catalogue catalogue;
		final String problem;

		private Reading(Catalogue catalogue, String problem)
		{
			this.catalogue = catalogue;
			this.problem = problem;
		}
	}

	/** Reads and checks the whole catalogue. Client thread (through the source). */
	static Reading read(Source src, String now, int revision)
	{
		Map<Integer, String> monsters = src.enumStrings(MONSTER_ENUM);
		if (monsters == null || monsters.isEmpty())
		{
			return fail("monster enum " + MONSTER_ENUM + " not loaded");
		}
		List<Tier> tiers = new ArrayList<>();
		List<Task> tasks = new ArrayList<>();
		Map<Integer, Integer> structById = new HashMap<>();
		for (int t = 0; t < TIER_ENUMS.length; t++)
		{
			int enumId = TIER_ENUMS[t];
			int tierNumber = t + 1;
			int[] structs = src.enumInts(enumId);
			if (structs == null || structs.length == 0)
			{
				return fail("tier enum " + enumId + " not loaded");
			}
			for (int structId : structs)
			{
				Integer id = src.structInt(structId, PARAM_ID);
				String name = src.structString(structId, PARAM_NAME);
				String description = src.structString(structId, PARAM_DESCRIPTION);
				if (id == null || id < 0 || isBlank(name) || isBlank(description))
				{
					return fail("task struct " + structId + " not loaded");
				}
				Integer ownTier = src.structInt(structId, PARAM_TIER);
				if (ownTier == null)
				{
					return fail("task struct " + structId + " has no tier");
				}
				if (ownTier != tierNumber)
				{
					return fail("task struct " + structId + " says tier " + ownTier + " but is listed in " + enumId);
				}
				Integer twin = structById.put(id, structId);
				if (twin != null)
				{
					return fail("task id " + id + " in both struct " + twin + " and " + structId);
				}
				Task task = new Task();
				task.id = id;
				task.struct_id = structId;
				task.name = name;
				task.description = description;
				task.tier = tierNumber;
				task.tier_name = TIER_NAMES[t];
				task.points = tierNumber;
				task.type_id = src.structInt(structId, PARAM_TYPE);
				task.monster_id = src.structInt(structId, PARAM_MONSTER);
				// a monster id the enum does not list has no name in the game either (it shows the default, "None")
				task.monster = task.monster_id == null ? null : monsters.get(task.monster_id);
				tasks.add(task);
			}
			Tier tier = new Tier();
			tier.tier = tierNumber;
			tier.name = TIER_NAMES[t];
			tier.enum_id = enumId;
			tier.points = tierNumber;
			tier.task_count = structs.length;
			tiers.add(tier);
		}
		tasks.sort(Comparator.comparingInt(task -> task.id));
		Catalogue c = new Catalogue();
		c.captured_at = now;
		c.format_version = FORMAT_VERSION;
		c.client_revision = revision;
		c.source = "game cache: tier enums 3981-3986, task struct params 1306/1308/1309/1310/1311/1312, monster enum 3971";
		c.task_count = tasks.size();
		c.max_id = tasks.get(tasks.size() - 1).id;
		c.id_gaps = gaps(tasks);
		c.tiers = tiers;
		c.tasks = tasks;
		return new Reading(c, null);
	}

	/** Ids below the highest that no task has (none in cache 2752): recorded, not treated as missing tasks. */
	private static List<Integer> gaps(List<Task> sorted)
	{
		List<Integer> out = new ArrayList<>();
		int next = 0;
		for (Task t : sorted)
		{
			for (; next < t.id; next++)
			{
				out.add(next);
			}
			next = t.id + 1;
		}
		return out;
	}

	private static Reading fail(String problem)
	{
		return new Reading(null, problem);
	}

	private static boolean isBlank(String s)
	{
		return s == null || s.trim().isEmpty();
	}

	/** The game cache, read from the client. Client thread only. */
	private static final class ClientSource implements Source
	{
		private final Client client;

		ClientSource(Client client)
		{
			this.client = client;
		}

		@Override
		public int[] enumInts(int enumId)
		{
			try
			{
				EnumComposition e = client.getEnum(enumId);
				return e == null ? null : e.getIntVals();
			}
			catch (RuntimeException e)
			{
				return null; // the enum is not loaded (yet)
			}
		}

		@Override
		public Map<Integer, String> enumStrings(int enumId)
		{
			try
			{
				EnumComposition e = client.getEnum(enumId);
				if (e == null || e.getKeys() == null || e.getStringVals() == null
					|| e.getKeys().length != e.getStringVals().length)
				{
					return null;
				}
				Map<Integer, String> out = new HashMap<>();
				int[] keys = e.getKeys();
				String[] values = e.getStringVals();
				for (int i = 0; i < keys.length; i++)
				{
					out.put(keys[i], values[i]);
				}
				return Collections.unmodifiableMap(out);
			}
			catch (RuntimeException e)
			{
				return null;
			}
		}

		@Override
		public Integer structInt(int structId, int param)
		{
			StructComposition s = struct(structId, param);
			return s == null ? null : s.getIntValue(param);
		}

		@Override
		public String structString(int structId, int param)
		{
			StructComposition s = struct(structId, param);
			return s == null ? null : s.getStringValue(param);
		}

		/** The struct when it is loaded and has the param itself (so the param's default is never taken as data). */
		private StructComposition struct(int structId, int param)
		{
			try
			{
				StructComposition s = client.getStructComposition(structId);
				IterableHashTable<Node> params = s == null ? null : s.getParams();
				return params != null && params.get(param) != null ? s : null;
			}
			catch (RuntimeException e)
			{
				return null;
			}
		}
	}

	// ---------------------------------------------------------------- json models

	@SuppressWarnings("unused")
	static class Catalogue
	{
		String captured_at;
		int format_version;
		int client_revision;
		String source;
		int task_count;
		int max_id;
		List<Integer> id_gaps;
		List<Tier> tiers;
		List<Task> tasks;
	}

	@SuppressWarnings("unused")
	static class Tier
	{
		int tier;
		String name;
		int enum_id;
		int points;
		int task_count;
	}

	@SuppressWarnings("unused")
	static class Task
	{
		int id;
		int struct_id;
		String name;
		String description;
		int tier;
		String tier_name;
		int points;
		Integer type_id;
		Integer monster_id;
		String monster;
	}
}
