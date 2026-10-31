package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hans.CombatAchievementsCapture.Catalogue;
import com.hans.CombatAchievementsCapture.Reading;
import com.hans.CombatAchievementsCapture.Task;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.runelite.api.WorldType;
import org.junit.Test;

/** The combat achievement task catalogue (ca_tasks.json), against a synthetic cache. */
public class CombatAchievementsCaptureTest
{
	private static final String NOW = "2026-10-30T12:00:00Z";
	private static final int MONSTER_JAD = 7;
	private static final int MONSTER_UNLISTED = 91;

	/** A cache with one task per tier (ids 0..5, struct ids 100..105) and a Grandmaster task with id 700. */
	private static final class FakeCache implements CombatAchievementsCapture.Source
	{
		final Map<Integer, int[]> enums = new HashMap<>();
		final Map<Integer, Map<Integer, String>> stringEnums = new HashMap<>();
		final Map<Integer, Map<Integer, Object>> structs = new HashMap<>();
		boolean loaded = true;
		int reads;

		FakeCache()
		{
			Map<Integer, String> monsters = new HashMap<>();
			monsters.put(MONSTER_JAD, "TzTok-Jad");
			stringEnums.put(CombatAchievementsCapture.MONSTER_ENUM, monsters);
			for (int t = 0; t < 6; t++)
			{
				int structId = 100 + t;
				enums.put(CombatAchievementsCapture.TIER_ENUMS[t], new int[]{structId});
				structs.put(structId, task(t, "Task " + t, t + 1, MONSTER_JAD));
			}
			enums.put(CombatAchievementsCapture.TIER_ENUMS[5], new int[]{105, 199});
			structs.put(199, task(700, "A new task", 6, MONSTER_JAD));
		}

		static Map<Integer, Object> task(int id, String name, int tier, int monster)
		{
			Map<Integer, Object> p = new HashMap<>();
			p.put(CombatAchievementsCapture.PARAM_ID, id);
			p.put(CombatAchievementsCapture.PARAM_NAME, name);
			p.put(CombatAchievementsCapture.PARAM_DESCRIPTION, "Do " + name + ".");
			p.put(CombatAchievementsCapture.PARAM_TIER, tier);
			p.put(CombatAchievementsCapture.PARAM_TYPE, 3);
			p.put(CombatAchievementsCapture.PARAM_MONSTER, monster);
			return p;
		}

		@Override
		public int[] enumInts(int enumId)
		{
			return loaded ? enums.get(enumId) : null;
		}

		@Override
		public Map<Integer, String> enumStrings(int enumId)
		{
			reads++;
			return loaded ? stringEnums.get(enumId) : null;
		}

		@Override
		public Integer structInt(int structId, int param)
		{
			Map<Integer, Object> s = loaded ? structs.get(structId) : null;
			return s == null ? null : (Integer) s.get(param);
		}

		@Override
		public String structString(int structId, int param)
		{
			Map<Integer, Object> s = loaded ? structs.get(structId) : null;
			return s == null ? null : (String) s.get(param);
		}
	}

	private static Reading read(FakeCache cache)
	{
		return CombatAchievementsCapture.read(cache, NOW, 235);
	}

	private static void assertFails(String expected, Consumer<FakeCache> change)
	{
		FakeCache cache = new FakeCache();
		change.accept(cache);
		Reading r = read(cache);
		assertNull(r.catalogue);
		assertTrue(r.problem, r.problem.contains(expected));
	}

	// ------------------------------------------------------------------ the reading

	@Test
	public void readsEveryTierWithPointsAndKeepsIdsPast654()
	{
		Catalogue c = read(new FakeCache()).catalogue;
		assertNotNull(c);
		assertEquals(CombatAchievementsCapture.FORMAT_VERSION, c.format_version);
		assertEquals(NOW, c.captured_at);
		assertEquals(235, c.client_revision);
		assertEquals(7, c.task_count);
		assertEquals(700, c.max_id);
		assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 700), c.tasks.stream().map(t -> t.id).collect(Collectors.toList()));
		List<String> tierNames = c.tasks.stream().map(t -> t.tier_name).collect(Collectors.toList());
		assertEquals(Arrays.asList("Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster", "Grandmaster"), tierNames);
		for (Task t : c.tasks)
		{
			assertEquals(t.tier, t.points); // Easy 1 .. Grandmaster 6
		}
		Task added = c.tasks.get(6);
		assertEquals(199, added.struct_id);
		assertEquals("A new task", added.name);
		assertEquals("Do A new task.", added.description);
		assertEquals(Integer.valueOf(MONSTER_JAD), added.monster_id);
		assertEquals("TzTok-Jad", added.monster);
		assertEquals(Integer.valueOf(3), added.type_id);
		assertEquals(6, c.tiers.size());
		assertEquals(3986, c.tiers.get(5).enum_id);
		assertEquals(2, c.tiers.get(5).task_count);
		assertEquals(6, c.tiers.get(5).points);
		assertEquals(694, c.id_gaps.size()); // 6..699: recorded, not treated as missing tasks
		assertEquals(Integer.valueOf(6), c.id_gaps.get(0));
	}

	@Test
	public void aMonsterTheEnumDoesNotListHasNoName()
	{
		FakeCache cache = new FakeCache();
		cache.structs.get(100).put(CombatAchievementsCapture.PARAM_MONSTER, MONSTER_UNLISTED);
		cache.structs.get(101).remove(CombatAchievementsCapture.PARAM_MONSTER);
		cache.structs.get(101).remove(CombatAchievementsCapture.PARAM_TYPE);
		Catalogue c = read(cache).catalogue;
		assertEquals(Integer.valueOf(MONSTER_UNLISTED), c.tasks.get(0).monster_id);
		assertNull(c.tasks.get(0).monster); // never the enum's default ("None") taken as a name
		assertNull(c.tasks.get(1).monster_id);
		assertNull(c.tasks.get(1).monster);
		assertNull(c.tasks.get(1).type_id);
	}

	@Test
	public void anIncompleteCacheGivesNoCatalogue()
	{
		assertFails("not loaded", c -> c.loaded = false);
		assertFails("monster enum 3971", c -> c.stringEnums.clear());
		assertFails("tier enum 3983", c -> c.enums.remove(3983));
		assertFails("tier enum 3984", c -> c.enums.put(3984, new int[0]));
		assertFails("task struct 102", c -> c.structs.remove(102));
		assertFails("task struct 102", c -> c.structs.get(102).remove(CombatAchievementsCapture.PARAM_ID));
		assertFails("task struct 102", c -> c.structs.get(102).remove(CombatAchievementsCapture.PARAM_NAME));
		assertFails("task struct 102", c -> c.structs.get(102).put(CombatAchievementsCapture.PARAM_DESCRIPTION, " "));
		assertFails("task struct 102", c -> c.structs.get(102).put(CombatAchievementsCapture.PARAM_ID, -1));
	}

	@Test
	public void duplicateIdsAndMisfiledTiersAreRejected()
	{
		assertFails("task id 1 in both struct 101 and 102",
			c -> c.structs.get(102).put(CombatAchievementsCapture.PARAM_ID, 1));
		assertFails("task id 0 in both struct 100 and 100", c -> c.enums.put(3981, new int[]{100, 100}));
		assertFails("says tier 4", c -> c.structs.get(102).put(CombatAchievementsCapture.PARAM_TIER, 4));
	}

	@Test
	public void aStructWithoutItsOwnTierIsRejected()
	{
		assertFails("task struct 103 has no tier", c -> c.structs.get(103).remove(CombatAchievementsCapture.PARAM_TIER));
	}

	// ------------------------------------------------------------------ the capture

	private static GameFake game(FakeCache cache) throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new CombatAchievementsCapture(g.ctx, () -> true, cache));
		return g;
	}

	private static void ticks(GameFake g, int n)
	{
		for (int i = 0; i < n; i++)
		{
			g.tick();
		}
	}

	@Test
	public void retriesUntilTheCacheLoadsThenWritesOnce() throws IOException
	{
		FakeCache cache = new FakeCache();
		cache.loaded = false;
		GameFake g = game(cache);
		g.logIn();
		ticks(g, CombatAchievementsCapture.RETRY_TICKS * 2);
		g.executor.runAll();
		assertNull(g.read(CombatAchievementsCapture.FILE, Map.class));
		int triesSoFar = cache.reads;
		assertTrue(triesSoFar >= 2 && triesSoFar <= 3); // a try every RETRY_TICKS ticks, not every tick

		cache.loaded = true;
		ticks(g, CombatAchievementsCapture.RETRY_TICKS);
		g.executor.runAll();
		Map<?, ?> file = g.read(CombatAchievementsCapture.FILE, Map.class);
		assertNotNull(file);
		assertEquals(Arrays.asList("captured_at", "format_version", "client_revision", "source", "task_count", "max_id",
			"id_gaps", "tiers", "tasks"), Arrays.asList(file.keySet().toArray()));
		assertEquals(7.0, file.get("task_count"));
		assertEquals(1.0, file.get("format_version"));

		int reads = cache.reads;
		ticks(g, 50);
		g.loadRegion();
		ticks(g, 20);
		assertEquals(reads, cache.reads); // once per account and mode in a session, not on a region load
	}

	@Test
	public void aCacheThatNeverLoadsKeepsTheLastCompleteFile() throws IOException
	{
		FakeCache cache = new FakeCache();
		cache.loaded = false;
		GameFake g = game(cache);
		String mode = GameMode.current(g.client, g.seasons);
		Map<String, Object> prior = new LinkedHashMap<>();
		prior.put("format_version", 1);
		prior.put("task_count", 655);
		g.write(g.accountHash, mode, CombatAchievementsCapture.FILE, prior);
		Map<?, ?> before = g.read(CombatAchievementsCapture.FILE, Map.class);

		g.logIn();
		ticks(g, CombatAchievementsCapture.MAX_TRIES * CombatAchievementsCapture.RETRY_TICKS + 20);
		g.executor.runAll();
		assertEquals(CombatAchievementsCapture.MAX_TRIES, cache.reads); // then it gives up for this session
		assertEquals(before, g.read(CombatAchievementsCapture.FILE, Map.class));

		// the next session tries again, and a complete reading replaces the old file
		g.logOut();
		cache.loaded = true;
		g.logIn();
		ticks(g, 2);
		g.executor.runAll();
		assertEquals(7.0, g.read(CombatAchievementsCapture.FILE, Map.class).get("task_count"));
	}

	@Test
	public void aMissingTierRetriesThenKeepsTheLastCompleteFile() throws IOException
	{
		FakeCache cache = new FakeCache();
		cache.structs.get(103).remove(CombatAchievementsCapture.PARAM_TIER);
		GameFake g = game(cache);
		Map<String, Object> prior = new LinkedHashMap<>();
		prior.put("format_version", 1);
		prior.put("task_count", 655);
		g.write(g.accountHash, GameMode.current(g.client, g.seasons), CombatAchievementsCapture.FILE, prior);
		Map<?, ?> before = g.read(CombatAchievementsCapture.FILE, Map.class);

		g.logIn();
		ticks(g, CombatAchievementsCapture.RETRY_TICKS * 2);
		g.executor.runAll();
		assertTrue(cache.reads >= 2); // retried, not given up at once
		ticks(g, CombatAchievementsCapture.MAX_TRIES * CombatAchievementsCapture.RETRY_TICKS + 20);
		g.executor.runAll();
		assertEquals(CombatAchievementsCapture.MAX_TRIES, cache.reads); // then it gives up for this session
		assertEquals(before, g.read(CombatAchievementsCapture.FILE, Map.class));
	}

	@Test
	public void eachAccountAndModeGetsItsOwnFile() throws IOException
	{
		FakeCache cache = new FakeCache();
		GameFake g = game(cache);
		g.logIn();
		ticks(g, 2);
		String main = GameMode.current(g.client, g.seasons);
		g.logOut();
		g.worldTypes = EnumSet.of(WorldType.SEASONAL);
		g.logIn();
		ticks(g, 2);
		String seasonal = GameMode.current(g.client, g.seasons);
		assertNotEquals(main, seasonal);
		g.logOut();
		g.worldTypes = EnumSet.of(WorldType.MEMBERS);
		g.accountHash = 2L;
		g.name = "Second";
		g.logIn();
		ticks(g, 2);
		g.executor.runAll();
		assertNotNull(g.readIn(1L, main, CombatAchievementsCapture.FILE, Map.class));
		assertNotNull(g.readIn(1L, seasonal, CombatAchievementsCapture.FILE, Map.class));
		assertNotNull(g.readIn(2L, main, CombatAchievementsCapture.FILE, Map.class));
		assertEquals(3, cache.reads);
	}
}
