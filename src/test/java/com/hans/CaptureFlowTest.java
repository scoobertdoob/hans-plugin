package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.hans.RsbuddyData.Session;
import com.hans.RsbuddyData.SessionsFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.runelite.api.Item;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/** Flow tests through onGameTick and onLogout, against {@link GameFake}. Synthetic accounts only. */
public class CaptureFlowTest
{
	private static final int TRANSMIT_A = 4771;

	// ------------------------------------------------------------------ farming

	private static GameFake farming(WorldPoint at) throws IOException
	{
		GameFake g = new GameFake();
		g.at = at;
		g.captures.add(new FarmingCapture(g.ctx));
		return g;
	}

	/** Ticks until the previous farming.json has been read and the first reading is written. */
	private static void settleFarming(GameFake g)
	{
		g.logIn();
		for (int i = 0; i < 4; i++)
		{
			g.tick();
			g.executor.runAll();
		}
	}

	private static FarmingCapture.Entry entry(GameFake g, String key) throws IOException
	{
		FarmingCapture.File f = g.read("farming.json", FarmingCapture.File.class);
		return f == null || f.regions == null ? null : f.regions.get(key);
	}

	@Test
	public void stairsAndOutOfBoundsKeepThePlantedState() throws IOException
	{
		GameFake g = farming(new WorldPoint(3700, 3800, 0)); // Fossil Island, 14651
		g.varbits.put(TRANSMIT_A, 10); // a hardwood tree growing
		settleFarming(g);
		assertEquals(Integer.valueOf(10), entry(g, "14651").varbits.get(TRANSMIT_A));

		// upstairs: the game stops sending the patches, every varbit reads 0
		g.at = new WorldPoint(3700, 3800, 1);
		g.setVarbit(TRANSMIT_A, 0);
		g.ticks(5);
		g.executor.runAll();
		assertEquals(Integer.valueOf(10), entry(g, "14651").varbits.get(TRANSMIT_A));

		// on the stairs to the house on the hill (14908), plane 0 but out of bounds
		g.at = new WorldPoint(3753, 3869, 0);
		g.ticks(5);
		g.executor.runAll();
		assertNull(entry(g, "14908"));

		// back on the ground, passive all-zero readings (also after the 5 minute refresh) wipe nothing
		g.at = new WorldPoint(3700, 3800, 0);
		g.ticks(400);
		g.executor.runAll();
		assertEquals(Integer.valueOf(10), entry(g, "14651").varbits.get(TRANSMIT_A));

		// Catherby 11062: planted on ground floor, then climbing upstairs where varbits read 0
		g.at = new WorldPoint(2805, 3465, 0);
		g.setVarbit(TRANSMIT_A, 15);
		g.ticks(3);
		g.executor.runAll();
		assertEquals(Integer.valueOf(15), entry(g, "11062").varbits.get(TRANSMIT_A));

		g.at = new WorldPoint(2805, 3465, 1); // upstairs in Catherby 11062: all zeros do not overwrite
		g.setVarbit(TRANSMIT_A, 0);
		g.ticks(5);
		g.executor.runAll();
		assertEquals(Integer.valueOf(15), entry(g, "11062").varbits.get(TRANSMIT_A));

		// back on Fossil Island: the patch cleared (raked and empty is non-zero, and recorded)
		g.at = new WorldPoint(3700, 3800, 0);
		g.setVarbit(TRANSMIT_A, 3);
		g.ticks(3);
		g.executor.runAll();
		assertEquals(Integer.valueOf(3), entry(g, "14651").varbits.get(TRANSMIT_A));
	}

	@Test
	public void crossingIntoAnotherAreaOfTheRegionWaitsATick() throws IOException
	{
		GameFake g = farming(new WorldPoint(3055, 3305, 0)); // Falador, 12083
		g.varbits.put(TRANSMIT_A, 7);
		settleFarming(g);
		assertEquals(Integer.valueOf(7), entry(g, "12083:falador").varbits.get(TRANSMIT_A));

		// south of y 3272 is Port Sarim, same region: on the first tick the varbits are still Falador's
		g.at = new WorldPoint(3055, 3268, 0);
		g.tick();
		g.executor.runAll();
		assertNull(entry(g, "12083:port_sarim"));

		g.setVarbit(TRANSMIT_A, 20); // now the spirit tree
		g.tick();
		g.executor.runAll();
		assertEquals(Integer.valueOf(20), entry(g, "12083:port_sarim").varbits.get(TRANSMIT_A));
		assertEquals("port_sarim", entry(g, "12083:port_sarim").area);
		assertEquals(Integer.valueOf(7), entry(g, "12083:falador").varbits.get(TRANSMIT_A));
	}

	@Test
	public void catherbyAllotmentsAndFruitTreeAreSeparate() throws IOException
	{
		GameFake g = farming(new WorldPoint(2830, 3445, 0)); // 11317, by the beach: allotments
		g.varbits.put(TRANSMIT_A, 12);
		settleFarming(g);
		assertEquals(Integer.valueOf(12), entry(g, "11317:catherby").varbits.get(TRANSMIT_A));

		g.at = new WorldPoint(2830, 3445, 1); // upstairs there: the fruit tree
		g.setVarbit(TRANSMIT_A, 40);
		g.ticks(2);
		g.executor.runAll();
		assertEquals(Integer.valueOf(40), entry(g, "11317:catherby_fruit_tree").varbits.get(TRANSMIT_A));
		assertEquals(Integer.valueOf(12), entry(g, "11317:catherby").varbits.get(TRANSMIT_A));
	}

	// ------------------------------------------------------------------ sessions

	private static GameFake sessions() throws IOException
	{
		GameFake g = new GameFake();
		CharacterCapture character = new CharacterCapture(g.ctx);
		g.captures.add(character);
		g.captures.add(new SessionCapture(g.ctx, character, (a, s, x) -> { }));
		return g;
	}

	private static Session last(SessionsFile f)
	{
		return f.sessions.get(f.sessions.size() - 1);
	}

	@Test
	public void logoutKeepsTheWorldAndTileOfTheLastIdleRefresh() throws IOException
	{
		GameFake g = sessions();
		g.logIn(); // world 301, at 3200, 3200
		g.tick();
		g.executor.runAll();

		g.world = 302; // hopped while idle, then walked
		g.at = new WorldPoint(3210, 3215, 0);
		g.ticks(120); // past one idle refresh, before the heartbeat write
		g.logOut();
		g.executor.runAll();

		Session s = last(g.read("sessions.json", SessionsFile.class));
		assertNotNull(s.end);
		assertEquals(Arrays.asList(301, 302), s.worlds);
		assertEquals(Integer.valueOf(3210), s.position_end.get("x"));
		assertEquals(Integer.valueOf(3215), s.position_end.get("y"));
	}

	@Test
	public void lateLogoutWriteOfOneAccountDoesNotLeakIntoTheNext() throws IOException
	{
		GameFake g = sessions();
		g.write(1L, GameMode.MAIN, "sessions.json", file("Alpha", "2025-01-01T00:00:00Z"));
		g.write(2L, GameMode.MAIN, "sessions.json", file("Bravo", "2025-06-01T00:00:00Z"));

		g.name = "Alpha";
		g.logIn();
		g.executor.runAll(); // Alpha's history is loaded with its first write

		g.logOut(); // Alpha's final write is queued, but the executor is busy...
		g.name = "Bravo";
		g.accountHash = 2L;
		g.logIn(); // ...while Bravo settles and queues its first write
		g.executor.runAll();

		SessionsFile bravo = g.readIn(2L, GameMode.MAIN, "sessions.json", SessionsFile.class);
		assertEquals("Bravo", bravo.account_name);
		assertEquals(2, bravo.sessions.size());
		assertEquals("2025-06-01T00:00:00Z", bravo.sessions.get(0).start);
		assertNull(last(bravo).end); // Bravo's live session

		SessionsFile alpha = g.readIn(1L, GameMode.MAIN, "sessions.json", SessionsFile.class);
		assertEquals(2, alpha.sessions.size());
		assertEquals("2025-01-01T00:00:00Z", alpha.sessions.get(0).start);
		assertNotNull(last(alpha).end);
	}

	private static SessionsFile file(String account, String start)
	{
		Session s = new Session();
		s.start = start;
		s.end = start;
		s.last_update = start;
		SessionsFile f = new SessionsFile();
		f.format_version = RsbuddyData.FORMAT_VERSION;
		f.account_name = account;
		f.sessions = new ArrayList<>(Arrays.asList(s));
		return f;
	}

	// ------------------------------------------------------------------ diaries & quests

	@Test
	public void consecutiveDiaryChangesFlushNewestSnapshotOnce() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(ProgressCaptures.diaries(g.ctx));
		g.logIn();
		g.executor.runAll();
		g.setVarbit(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1);
		g.tick();
		g.setVarbit(VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, 1);
		g.tick();
		assertEquals(0, g.executor.pendingCount());
		g.logOut();
		assertEquals(1, g.executor.pendingCount());
		g.executor.runAll();
		assertEquals(2, tiersComplete(g));
	}

	@Test
	public void consecutiveCombatChangesFlushNewestSnapshotOnce() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(ProgressCaptures.combatAchievements(g.ctx));
		g.logIn();
		g.executor.runAll();
		g.setVarbit(VarbitID.CA_TIER_STATUS_EASY, 1);
		g.tick();
		g.setVarbit(VarbitID.CA_TIER_STATUS_MEDIUM, 1);
		g.tick();
		assertEquals(0, g.executor.pendingCount());
		g.logOut();
		assertEquals(1, g.executor.pendingCount());
		g.executor.runAll();
		ToolkitData.CombatAchievements result = g.read("combat_achievements.json", ToolkitData.CombatAchievements.class);
		assertEquals(Integer.valueOf(1), result.tier_status_raw.get("easy"));
		assertEquals(Integer.valueOf(1), result.tier_status_raw.get("medium"));
	}

	@Test
	public void aDiaryChangeJustBeforeLogoutIsWritten() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(ProgressCaptures.diaries(g.ctx));
		g.logIn();
		g.ticks(5);
		g.executor.runAll();
		assertEquals(0, tiersComplete(g));

		g.defaultVarbit = 1; // every tier done, inside the 30 s window
		g.setVarbit(-1, 1); // tells the captures a variable changed
		g.tick();
		g.logOut();
		g.executor.runAll();
		assertTrue(tiersComplete(g) > 0);
	}

	@Test
	public void questStartOnUnknownVariableTriggersReread() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new QuestsCapture(g.ctx));
		g.logIn();
		g.executor.runAll();
		RsbuddyData.Quests initial = g.read("quests.json", RsbuddyData.Quests.class);
		assertEquals(0, initial.summary.in_progress);

		// A quest whose progress variable is not in QuestProgressVars starts:
		g.questStatus.put(1, 0); // IN_PROGRESS
		g.setVarbit(999999, 1);
		g.ticks(QuestsCapture.RECHECK_TICKS);
		g.executor.runAll();

		RsbuddyData.Quests updated = g.read("quests.json", RsbuddyData.Quests.class);
		assertEquals(1, updated.summary.in_progress);
	}

	@SuppressWarnings("unchecked")
	private static int tiersComplete(GameFake g) throws IOException
	{
		Map<String, Object> f = g.read("diaries.json", Map.class);
		Map<String, Object> summary = (Map<String, Object>) f.get("summary");
		return ((Number) summary.get("tiers_complete")).intValue();
	}

	@Test
	public void sessionEndsWithTheCharacterReadingOnlyWhenItIsNewer()
	{
		Instant t = Instant.parse("2026-01-01T00:00:00Z");
		RsbuddyData.Snapshot s = new RsbuddyData.Snapshot("A", 1L, 301, new ArrayList<>(), 3, new ArrayList<>());
		assertNull(SessionCapture.endReading(s, t, t));
		assertNull(SessionCapture.endReading(s, t, t.plusSeconds(60)));
		assertSame(s, SessionCapture.endReading(s, t.plusSeconds(1), t));
		assertNull(SessionCapture.endReading(null, t, null));
	}

	// ------------------------------------------------------------------ mode separation (P1)

	private static final String LEAGUE = "seasonal-demonic_pacts_league";
	private static final int ORE = 440;

	/** The captures a mode switch matters most to: sessions, item gains, kill counts, vars and the game's quests. */
	private static GameFake modes() throws IOException
	{
		GameFake g = new GameFake();
		CharacterCapture character = new CharacterCapture(g.ctx);
		SessionCapture sessions = new SessionCapture(g.ctx, character, (a, s, x) -> { });
		g.captures.add(character);
		g.captures.add(sessions);
		g.captures.add(new ItemGainsCapture(g.ctx, sessions));
		g.captures.add(new LootCapture(g.ctx));
		g.captures.add(new VarsCapture(g.ctx));
		g.captures.add(new GameQuestsCapture(g.ctx));
		g.containers.put(InventoryID.WORN, new Item[0]);
		g.containers.put(InventoryID.INV, new Item[]{new Item(ORE, 1)});
		return g;
	}

	private static void kc(GameFake g, String boss, int n)
	{
		g.chat("Your " + boss + " kill count is: <col=ff0000>" + n + "</col>.");
	}

	private static Map<String, LootCapture.Count> counts(GameFake g, String mode) throws IOException
	{
		LootCapture.KillFile f = g.readIn(1L, mode, "killcounts.json", LootCapture.KillFile.class);
		return f == null ? new TreeMap<>() : f.killcounts;
	}

	private static long files(GameFake g) throws IOException
	{
		try (Stream<Path> s = Files.walk(g.dir))
		{
			return s.filter(Files::isRegularFile).count();
		}
	}

	private static int slayerPoints(GameFake g, String mode) throws IOException
	{
		VarsCapture.File f = g.readIn(1L, mode, "vars.json", VarsCapture.File.class);
		return f.groups.get("slayer").varbits.get(VarbitID.SLAYER_POINTS).v;
	}

	@Test
	public void theFirstLoginWritesTheModeFileBeforeAnythingElse() throws IOException
	{
		GameFake g = sessions();
		g.logIn();
		g.executor.runOne();
		Map<?, ?> mode = g.readIn(1L, "main", "mode.json", Map.class);
		assertEquals(2.0, mode.get("layout"));
		assertEquals("1", mode.get("account_hash"));
		assertEquals("main", mode.get("mode"));
		assertEquals("Tester", mode.get("name"));
		assertEquals(Arrays.asList("MEMBERS"), mode.get("world_types"));
		assertEquals("STANDARD", mode.get("profile_type"));
		assertNotNull(mode.get("first_seen"));
		assertNull(g.read("sessions.json", SessionsFile.class));

		g.executor.runAll();
		assertEquals("Tester", g.read("sessions.json", SessionsFile.class).account_name);
		Map<?, ?> names = new Gson().fromJson(new String(Files.readAllBytes(g.dir.resolve("accounts").resolve("1")
			.resolve("names.json")), StandardCharsets.UTF_8), Map.class);
		assertEquals("Tester", ((Map<?, ?>) ((List<?>) names.get("names")).get(0)).get("name"));
		assertFalse(Files.exists(g.dir.resolve("Tester"))); // the old name folder is never written
	}

	@Test
	public void aHopIntoALeagueWorldFlushesEverythingPendingToTheMainFolder() throws IOException
	{
		GameFake g = modes();
		g.logIn();
		g.ticks(5); // past the quiet ticks: the inventory baseline is set
		g.setContainer(InventoryID.INV, new Item(ORE, 4)); // +3 ore, not written yet (every ~1 min)
		g.ticks(2);
		kc(g, "Zulrah", 12); // read, but the file had not loaded: still pending
		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL); // straight into the league
		g.settle();
		kc(g, "Vorkath", 1);
		g.ticks(2);
		// nothing ran on the executor until now: every task queued before the switch still writes to main
		g.executor.runAll();
		g.tick();
		g.logOut();
		g.executor.runAll();

		assertEquals(12, counts(g, "main").get("Zulrah").count);
		assertNull(counts(g, "main").get("Vorkath"));
		assertEquals(1, counts(g, LEAGUE).get("Vorkath").count);
		assertNull(counts(g, LEAGUE).get("Zulrah"));

		ItemGainsLog.GainsFile mainGains = g.readIn(1L, "main", ItemGainsCapture.FILE, ItemGainsLog.GainsFile.class);
		assertEquals("main", mainGains.mode);
		assertEquals("1", mainGains.accountHash);
		assertEquals(1, mainGains.sessions.size());
		assertEquals(3, mainGains.sessions.get(0).gains.get(0).qty);
		ItemGainsLog.GainsFile leagueGains = g.readIn(1L, LEAGUE, ItemGainsCapture.FILE, ItemGainsLog.GainsFile.class);
		assertTrue(leagueGains == null || leagueGains.sessions.get(0).gains.isEmpty()); // a new baseline, no "gain"

		SessionsFile mainSessions = g.readIn(1L, "main", "sessions.json", SessionsFile.class);
		SessionsFile leagueSessions = g.readIn(1L, LEAGUE, "sessions.json", SessionsFile.class);
		assertEquals(1, mainSessions.sessions.size());
		assertEquals(1, leagueSessions.sessions.size());
		assertNotNull(mainSessions.sessions.get(0).end);
		assertEquals(Arrays.asList(301), mainSessions.sessions.get(0).worlds);
		assertEquals(Arrays.asList(500), leagueSessions.sessions.get(0).worlds);
		assertNotEquals(mainSessions.sessions.get(0).start, leagueSessions.sessions.get(0).start);

		Map<?, ?> mode = g.readIn(1L, LEAGUE, "mode.json", Map.class);
		assertEquals(LEAGUE, mode.get("mode"));
		assertEquals("DEMONIC_PACTS_LEAGUE", mode.get("profile_type")); // what the resolver saw, to confirm in game
		assertEquals(Arrays.asList("MEMBERS", "SEASONAL"), mode.get("world_types"));
	}

	@Test
	public void theGameQuestTableIsWrittenOnlyFromTheMainGame() throws IOException
	{
		GameFake g = modes();
		g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL);
		g.logIn();
		g.ticks(10);
		g.executor.runAll();
		assertFalse(Files.exists(g.dir.resolve("game_quests.json")));
		assertNotNull(g.read("sessions.json", SessionsFile.class)); // the league itself is recorded

		g.hop(301, WorldType.MEMBERS);
		g.settle();
		g.ticks(2);
		g.executor.runAll();
		assertTrue(Files.exists(g.dir.resolve("game_quests.json")));
	}

	@Test
	public void theFileOfTheNewModeIsLoadedAfterASwitch() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new LootCapture(g.ctx));
		g.write(1L, "main", "killcounts.json", killFile("Zulrah", 10));
		g.write(1L, LEAGUE, "killcounts.json", killFile("Zulrah", 2));
		g.logIn();
		g.tick();
		g.executor.runAll(); // main's counts are loaded

		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL);
		g.settle();
		g.tick();
		g.executor.runAll(); // the league's own counts are loaded, not main's kept in memory
		kc(g, "Vorkath", 1);
		g.tick();
		g.executor.runAll();

		assertEquals(2, counts(g, LEAGUE).get("Zulrah").count);
		assertEquals(1, counts(g, LEAGUE).get("Vorkath").count);
		assertEquals(10, counts(g, "main").get("Zulrah").count);
		assertNull(counts(g, "main").get("Vorkath"));
	}

	private static LootCapture.KillFile killFile(String boss, int n)
	{
		LootCapture.Count c = new LootCapture.Count();
		c.count = n;
		c.observedAt = "2026-01-01T00:00:00Z";
		LootCapture.KillFile f = new LootCapture.KillFile();
		f.killcounts = new TreeMap<>();
		f.killcounts.put(boss, c);
		return f;
	}

	@Test
	public void eachLoginStampsVarsJsonEvenWhenNothingChanged() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new VarsCapture(g.ctx));
		g.varbits.put(VarbitID.SLAYER_POINTS, 100);
		g.logIn();
		g.tick();
		g.executor.runAll();
		g.tick();
		g.executor.runAll();
		VarsCapture.File first = g.readIn(1L, "main", "vars.json", VarsCapture.File.class);
		assertEquals(first.observedAt, first.confirmedAt);
		assertEquals(Integer.valueOf(230), first.clientRevision);
		assertEquals(VarsCapture.readingHash(first.groups), first.readingHash);

		g.setVarbit(VarbitID.SLAYER_POINTS, 120); // a change later in the same login: written, but not a login's confirmation
		g.tick();
		g.executor.runAll();
		VarsCapture.File changed = g.readIn(1L, "main", "vars.json", VarsCapture.File.class);
		assertNotEquals(first.observedAt, changed.observedAt);
		assertNull(changed.confirmedAt);
		assertNull(changed.readingHash);

		g.logOut();
		g.revision = 231; // the next login, on an updated client, with nothing changed
		g.logIn();
		g.tick();
		g.executor.runAll();
		g.tick();
		g.executor.runAll();
		VarsCapture.File again = g.readIn(1L, "main", "vars.json", VarsCapture.File.class);
		assertEquals(changed.observedAt, again.observedAt);
		assertTrue(java.time.Instant.parse(again.confirmedAt).isAfter(java.time.Instant.parse(changed.observedAt)));
		assertEquals(Integer.valueOf(231), again.clientRevision);
		assertEquals(VarsCapture.readingHash(again.groups), again.readingHash);
		assertEquals(120, (int) again.groups.get("slayer").varbits.get(VarbitID.SLAYER_POINTS).v);
	}

	@Test
	public void theReadingHashIsTheOneTheImporterComputes()
	{
		// the same text and vector as tests/test_plugin_progress.py (rsbuddy_core.importer.reading_hash)
		Map<String, VarsCapture.Group> groups = new java.util.LinkedHashMap<>();
		VarsCapture.Group slayer = new VarsCapture.Group();
		slayer.varps.put(1, new VarsCapture.Entry(7, "x"));
		VarsCapture.Group diaries = new VarsCapture.Group();
		diaries.varbits.put(3578, new VarsCapture.Entry(0, "x"));
		diaries.varbits.put(2423, new VarsCapture.Entry(4, "y"));
		groups.put("slayer", slayer);
		groups.put("diaries", diaries);
		assertEquals("9e619e4cc9fe2d17621a2d8799ebf1436027c467b596a5fccaf0fcaf442c7c28", VarsCapture.readingHash(groups));
	}

	@Test
	public void aVarbitSentDuringTheHopIsNotCreditedToTheOldMode() throws IOException
	{
		// 0: the varbit before LOGGED_IN; 1: after LOGGED_IN, before settle;
		// 2: LOGGED_IN still showing the old world types, the varbit, then the types (caught on the next tick)
		for (int order = 0; order < 3; order++)
		{
			GameFake g = new GameFake();
			g.captures.add(new VarsCapture(g.ctx));
			g.varbits.put(VarbitID.SLAYER_POINTS, 100);
			g.logIn();
			g.tick();
			g.executor.runAll(); // the previous vars.json is read...
			g.tick();
			g.executor.runAll(); // ...and then written
			assertEquals(100, slayerPoints(g, "main"));

			g.hub.hopping();
			if (order == 0)
			{
				g.setVarbit(VarbitID.SLAYER_POINTS, 7);
			}
			if (order != 2)
			{
				g.world = 500;
				g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL);
			}
			g.hub.loggedIn(g.now);
			if (order != 0)
			{
				g.setVarbit(VarbitID.SLAYER_POINTS, 7);
			}
			if (order == 2)
			{
				g.world = 500;
				g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL);
			}
			g.tick(); // order 2: the switch is caught here
			g.settle();
			g.executor.runAll();
			g.tick();
			g.executor.runAll();

			assertEquals("order " + order, 100, slayerPoints(g, "main"));
			assertEquals("order " + order, 7, slayerPoints(g, LEAGUE));
		}
	}

	@Test
	public void aKillCountReadDuringAHopIsQuarantinedWhicheverModeFollows() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new LootCapture(g.ctx));
		g.logIn();
		g.tick();
		g.executor.runAll();

		// a hop within the main game: before a tick confirms the world, not even main is credited
		g.hub.hopping();
		kc(g, "Zulrah", 5);
		g.world = 302;
		g.hub.loggedIn(g.now);
		g.tick();
		kc(g, "Zulrah", 6); // after the tick: main's again
		g.tick();
		g.executor.runAll();
		assertEquals(6, counts(g, "main").get("Zulrah").count);

		// a hop into the league: neither main nor the league gets the line
		g.hub.hopping();
		kc(g, "Vorkath", 7);
		g.world = 500;
		g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL);
		g.hub.loggedIn(g.now);
		g.settle();
		g.tick();
		g.executor.runAll();
		g.tick();
		g.logOut();
		g.executor.runAll();
		assertEquals(6, counts(g, "main").get("Zulrah").count);
		assertNull(counts(g, "main").get("Vorkath"));
		assertNull(counts(g, LEAGUE).get("Vorkath"));
		List<Map<?, ?>> q = quarantine(g);
		assertEquals(2, q.size());
		for (Map<?, ?> line : q)
		{
			assertEquals("kill_count", line.get("kind"));
			assertEquals(LoginState.CHANGING_WORLD, line.get("why"));
			assertEquals("main", ((Map<?, ?>) line.get("outgoing_key")).get("mode"));
		}
		assertEquals("5", value(q.get(0)).get("count"));
		assertEquals("7", value(q.get(1)).get("count"));
	}

	private static final int ZULRAH = 2042;
	private static final int VORKATH = 8061;
	private static final int KRAKEN = 494;

	private static List<String> lootNames(GameFake g, String mode) throws IOException
	{
		return lootNames(g, 1L, mode);
	}

	private static List<String> lootNames(GameFake g, long hash, String mode) throws IOException
	{
		Path p = g.folder(hash, mode).resolve("loot.jsonl");
		List<String> out = new ArrayList<>();
		if (Files.exists(p))
		{
			for (String line : Files.readAllLines(p, StandardCharsets.UTF_8))
			{
				Map<?, ?> m = new Gson().fromJson(line, Map.class);
				out.add(m.get("npc_name") + "/" + m.get("source"));
			}
		}
		return out;
	}

	/** The lines of the plugin root's quarantine file, unassigned.jsonl; empty when there is none. */
	private static List<Map<?, ?>> quarantine(GameFake g) throws IOException
	{
		Path p = g.dir.resolve(JsonWriter.QUARANTINE_FILE);
		List<Map<?, ?>> out = new ArrayList<>();
		if (Files.exists(p))
		{
			for (String line : Files.readAllLines(p, StandardCharsets.UTF_8))
			{
				out.add(new Gson().fromJson(line, Map.class));
			}
		}
		return out;
	}

	private static Map<?, ?> value(Map<?, ?> quarantined)
	{
		return (Map<?, ?>) quarantined.get("value");
	}

	/** "kind/what" for each quarantined line (the NPC for loot, the boss for a kill count), sorted. */
	private static List<String> quarantined(GameFake g) throws IOException
	{
		List<String> out = new ArrayList<>();
		for (Map<?, ?> line : quarantine(g))
		{
			Map<?, ?> v = value(line);
			out.add(line.get("kind") + "/" + (v.containsKey("npc_name") ? v.get("npc_name") + "/" + v.get("source")
				: v.get("boss")));
		}
		Collections.sort(out);
		return out;
	}

	@Test
	public void lootAndKillCountsSentDuringAHopAreQuarantinedInEveryOrder() throws IOException
	{
		// 0: the drops and the line before LOGGED_IN, which already shows the league's world types;
		// 1: before LOGGED_IN, which still shows main's types (caught on the next tick);
		// 2: LOGGED_IN still showing main's types, then the drops and the line, then the types
		for (int order = 0; order < 3; order++)
		{
			GameFake g = new GameFake();
			g.captures.add(new LootCapture(g.ctx));
			g.write(1L, "main", "killcounts.json", killFile("Zulrah", 10));
			g.logIn();
			g.tick();
			g.executor.runAll(); // main's counts are loaded
			g.serverLoot(ZULRAH, "Zulrah"); // a main kill, credited to main straight away
			g.tick();

			g.hub.hopping();
			if (order != 2)
			{
				g.serverLoot(VORKATH, "Vorkath");
				g.groundLoot(VORKATH, "Vorkath"); // the same kill seen on the ground (same token): one line
				g.groundLoot(KRAKEN, "Kraken"); // a ground drop no report covers: kept
				kc(g, "Vorkath", 3);
			}
			g.world = 500;
			if (order == 0)
			{
				g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL);
			}
			g.hub.loggedIn(g.now);
			if (order == 2)
			{
				g.serverLoot(VORKATH, "Vorkath");
				g.groundLoot(VORKATH, "Vorkath");
				g.groundLoot(KRAKEN, "Kraken");
				kc(g, "Vorkath", 3);
			}
			g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL);
			g.tick(); // orders 1 and 2: the switch is caught here, and main's logout runs
			g.settle();
			g.ticks(2);
			g.executor.runAll();
			g.logOut();
			g.executor.runAll();

			assertEquals("order " + order, Arrays.asList("Zulrah/server_npc_loot"), lootNames(g, "main"));
			assertEquals("order " + order, 10, counts(g, "main").get("Zulrah").count);
			assertNull("order " + order, counts(g, "main").get("Vorkath"));
			assertEquals("order " + order, Collections.emptyList(), lootNames(g, LEAGUE));
			assertNull("order " + order, counts(g, LEAGUE).get("Vorkath"));
			assertEquals("order " + order, Arrays.asList("kill_count/Vorkath", "loot/Kraken/npc_loot_received",
				"loot/Vorkath/server_npc_loot"), quarantined(g));
		}
	}

	@Test
	public void mainToLeagueAndBackBeforeATickCreditsTheLeaguesDropsToNoOne() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new LootCapture(g.ctx));
		g.logIn();
		g.tick();
		g.executor.runAll();
		g.serverLoot(ZULRAH, "Zulrah"); // main's

		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL); // LOGGED_IN shows the league: main's login ends
		g.serverLoot(VORKATH, "Vorkath");
		g.groundLoot(KRAKEN, "Kraken");
		kc(g, "Vorkath", 3);
		g.hop(301, WorldType.MEMBERS); // and straight back to main, before any tick
		g.serverLoot(VORKATH, "Vorkath");
		g.settle();
		g.serverLoot(ZULRAH, "Zulrah"); // main's again, once settled
		g.ticks(2);
		g.executor.runAll();
		g.logOut();
		g.executor.runAll();

		assertEquals(Arrays.asList("Zulrah/server_npc_loot", "Zulrah/server_npc_loot"), lootNames(g, "main"));
		assertNull(counts(g, "main").get("Vorkath"));
		assertFalse(Files.exists(g.folder(1L, LEAGUE)));
		assertEquals(Arrays.asList("kill_count/Vorkath", "loot/Kraken/npc_loot_received",
			"loot/Vorkath/server_npc_loot", "loot/Vorkath/server_npc_loot"), quarantined(g));
	}

	@Test
	public void anAccountChangeMidHopOrMidTickCreditsNeitherAccount() throws IOException
	{
		// during a hop
		GameFake g = new GameFake();
		g.captures.add(new LootCapture(g.ctx));
		g.logIn();
		g.tick();
		g.executor.runAll();
		g.hub.hopping();
		g.serverLoot(VORKATH, "Vorkath");
		g.groundLoot(KRAKEN, "Kraken");
		kc(g, "Vorkath", 3);
		g.accountHash = 2L;
		g.name = "Other";
		g.hub.loggedIn(g.now); // another account: the first one's login ends
		g.settle();
		g.ticks(2);
		g.executor.runAll();
		g.logOut();
		g.executor.runAll();
		for (long hash : new long[]{1L, 2L})
		{
			assertEquals(Collections.emptyList(), lootNames(g, hash, "main"));
			assertFalse(Files.exists(g.folder(hash, "main").resolve("killcounts.json")));
		}
		assertEquals(Arrays.asList("kill_count/Vorkath", "loot/Kraken/npc_loot_received",
			"loot/Vorkath/server_npc_loot"), quarantined(g));

		// with no hop at all: the client reports another account before the tick that would catch it
		g = new GameFake();
		g.captures.add(new LootCapture(g.ctx));
		g.logIn();
		g.tick();
		g.accountHash = 2L;
		g.serverLoot(VORKATH, "Vorkath");
		g.tick();
		g.executor.runAll();
		g.logOut();
		g.executor.runAll();
		assertEquals(Collections.emptyList(), lootNames(g, 1L, "main"));
		assertEquals(Collections.emptyList(), lootNames(g, 2L, "main"));
		List<Map<?, ?>> q = quarantine(g);
		assertEquals(1, q.size());
		assertEquals(LoginState.OTHER_ACCOUNT, q.get(0).get("why"));
		assertEquals("1", ((Map<?, ?>) q.get(0).get("outgoing_key")).get("account_hash"));
		assertEquals("2", ((Map<?, ?>) q.get(0).get("candidate")).get("account_hash"));
	}

	@Test
	public void aDestinationReportBeforeLoggedInNeverHidesTheOutgoingFallback() throws IOException
	{
		// 0: a hop into the league; 1: a hop within the main game
		for (int order = 0; order < 2; order++)
		{
			GameFake g = new GameFake();
			g.captures.add(new LootCapture(g.ctx));
			g.logIn();
			g.tick();
			g.executor.runAll();
			g.groundLoot(VORKATH, "Vorkath"); // main's kill, seen on the ground: held a tick
			g.hub.hopping();
			g.serverLoot(VORKATH, "Vorkath"); // the destination's report, before LOGGED_IN
			g.world = order == 0 ? 500 : 302;
			if (order == 0)
			{
				g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL);
			}
			g.hub.loggedIn(g.now);
			g.ticks(2);
			g.executor.runAll();
			g.logOut();
			g.executor.runAll();

			assertEquals("order " + order, Arrays.asList("Vorkath/npc_loot_received"), lootNames(g, "main"));
			assertEquals("order " + order, Arrays.asList("loot/Vorkath/server_npc_loot"), quarantined(g));
		}
	}

	@Test
	public void betaAndTournamentWorldsDropLootAndKillCountsOnArrival() throws IOException
	{
		WorldType[][] worlds = {{WorldType.SEASONAL, WorldType.BETA_WORLD}, {WorldType.NOSAVE_MODE},
			{WorldType.TOURNAMENT_WORLD}};
		for (WorldType[] types : worlds)
		{
			GameFake g = new GameFake();
			LootCapture loot = new LootCapture(g.ctx);
			g.captures.add(loot);
			g.worldTypes = EnumSet.of(types[0], types);
			g.logIn();
			for (int i = 0; i < 20; i++)
			{
				g.serverLoot(ZULRAH, "Zulrah");
				g.groundLoot(KRAKEN, "Kraken");
				kc(g, "Zulrah", i + 1);
				g.tick();
				assertEquals(Arrays.toString(types), 0, loot.held()); // nothing held, nothing to scan again
			}
			g.logOut();
			g.executor.runAll();
			assertEquals(Arrays.toString(types), 0, files(g));
		}
	}

	@Test
	public void evidenceWithoutAnAccountHashOrLoginIsQuarantinedNotDiscarded() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new LootCapture(g.ctx));
		g.accountHash = -1L;
		g.logIn(); // never settles
		g.serverLoot(ZULRAH, "Zulrah");
		g.groundLoot(KRAKEN, "Kraken");
		kc(g, "Zulrah", 3);
		g.ticks(2);
		g.logOut();
		g.serverLoot(VORKATH, "Vorkath"); // logged out
		g.executor.runAll();

		assertFalse(Files.exists(g.dir.resolve(JsonWriter.ACCOUNTS)));
		List<Map<?, ?>> q = quarantine(g);
		assertEquals(4, q.size());
		for (int i = 0; i < 3; i++)
		{
			assertEquals(LoginState.NO_HASH, q.get(i).get("why"));
			Map<?, ?> candidate = (Map<?, ?>) q.get(i).get("candidate");
			assertNull(candidate.get("account_hash"));
			assertEquals("main", candidate.get("mode"));
		}
		assertEquals(LoginState.LOGGED_OUT, q.get(3).get("why"));
		assertNull(q.get(3).get("candidate"));
		assertEquals(Arrays.asList("kill_count/Zulrah", "loot/Kraken/npc_loot_received", "loot/Vorkath/server_npc_loot",
			"loot/Zulrah/server_npc_loot"), quarantined(g));
	}

	@Test
	public void lootAndKillCountsWhoseModeIsNeverKnownAreQuarantined() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new LootCapture(g.ctx));
		g.logIn();
		g.tick();
		g.executor.runAll();

		// a hop that ends at the login screen: the destination was never confirmed
		g.hub.hopping();
		g.serverLoot(VORKATH, "Vorkath");
		kc(g, "Vorkath", 3);
		g.logOut();
		g.executor.runAll();
		assertEquals(0, lootNames(g, "main").size());
		assertNull(counts(g, "main").get("Vorkath"));
		List<Map<?, ?>> kept = quarantine(g);
		assertEquals(2, kept.size());
		assertEquals("loot", kept.get(0).get("kind"));
		assertEquals("Vorkath", value(kept.get(0)).get("npc_name"));
		assertEquals("kill_count", kept.get(1).get("kind"));
		assertEquals("3", value(kept.get(1)).get("count"));
		assertEquals("main", ((Map<?, ?>) kept.get(1).get("candidate")).get("login_mode"));
		assertEquals("Tester", ((Map<?, ?>) kept.get(1).get("outgoing_key")).get("name"));
		assertEquals(LoginState.CHANGING_WORLD, kept.get(1).get("why"));

		// a login that ends before it settles
		g.hub.loggedIn(g.now);
		g.tick();
		kc(g, "Zulrah", 4);
		g.logOut();
		g.executor.runAll();
		kept = quarantine(g);
		assertEquals(3, kept.size());
		assertEquals("Zulrah", value(kept.get(2)).get("boss"));
		assertEquals(LoginState.NOT_SETTLED, kept.get(2).get("why"));
		assertFalse(Files.exists(g.folder(1L, "main").resolve("killcounts.json")));
	}

	// ------------------------------------------------- readings after settle (P1 round 4)

	/** Most captures that sample the game on a tick: position, containers, variables, stats, sessions, item gains. */
	private static GameFake readers() throws IOException
	{
		GameFake g = new GameFake();
		CharacterCapture character = new CharacterCapture(g.ctx);
		SessionCapture sessions = new SessionCapture(g.ctx, character, (a, s, x) -> { });
		g.captures.add(new AccountCapture(g.ctx));
		g.captures.add(new PositionCapture(g.ctx));
		g.captures.add(new ItemsCapture(g.ctx, "bank", InventoryID.BANK, () -> true, "exportBank"));
		g.captures.add(new VarsCapture(g.ctx));
		g.captures.add(character);
		g.captures.add(sessions);
		g.captures.add(new ItemGainsCapture(g.ctx, sessions));
		g.containers.put(InventoryID.WORN, new Item[0]);
		g.containers.put(InventoryID.INV, new Item[]{new Item(ORE, 1)});
		g.containers.put(InventoryID.BANK, new Item[]{new Item(-1, 0)}); // no item names in the fake: empty slots
		g.varbits.put(VarbitID.SLAYER_POINTS, 5);
		g.xp = 100;
		return g;
	}

	// ------------------------------------------------- item gains at a reading boundary (P1 round 5)

	private static final int ARROW = 882;

	/** Item gains alone, logged in past the baseline, with 10 arrows in the inventory and nothing worn. */
	private static GameFake gains() throws IOException
	{
		GameFake g = new GameFake();
		CharacterCapture character = new CharacterCapture(g.ctx);
		SessionCapture sessions = new SessionCapture(g.ctx, character, (a, s, x) -> { });
		g.captures.add(character);
		g.captures.add(sessions);
		g.captures.add(new ItemGainsCapture(g.ctx, sessions));
		g.containers.put(InventoryID.WORN, new Item[0]);
		g.containers.put(InventoryID.INV, new Item[]{new Item(ARROW, 10), new Item(ORE, 1)});
		g.logIn();
		g.ticks(5); // past the quiet ticks and the baseline
		return g;
	}

	/** The item-gain entries written for account 1's main profile, as "id:qty"; empty when there are none. */
	private static List<String> gainsOf(GameFake g) throws IOException
	{
		List<String> out = new ArrayList<>();
		ItemGainsLog.GainsFile f = g.readIn(1L, "main", ItemGainsCapture.FILE, ItemGainsLog.GainsFile.class);
		if (f != null)
		{
			for (ItemGainsLog.SessionGains s : f.sessions)
			{
				for (ItemGainsLog.Gain x : s.gains)
				{
					out.add(x.id + ":" + x.qty);
				}
			}
		}
		return out;
	}

	/** The item-gain lines in the quarantine. */
	private static List<Map<?, ?>> heldGains(GameFake g) throws IOException
	{
		List<Map<?, ?>> out = new ArrayList<>();
		for (Map<?, ?> line : quarantine(g))
		{
			if ("item_gains".equals(line.get("kind")))
			{
				out.add(line);
			}
		}
		return out;
	}

	/** The hash goes to -1 for a tick, then the same account shows again and plays on until logout. */
	private static void unknownHashThenBack(GameFake g, Runnable duringPause)
	{
		g.accountHash = -1L;
		duringPause.run();
		g.tick();
		assertNull(g.login.reading());
		g.accountHash = 1L;
		g.ticks(5);
		g.logOut();
		g.executor.runAll();
	}

	@Test
	public void equippingAnArrowAcrossAnUnknownHashIsNoLoss() throws IOException
	{
		// inventory first: the bag loses the arrow on a readable tick, the worn update comes while the hash is -1
		GameFake g = gains();
		g.setContainer(InventoryID.INV, new Item(ARROW, 9), new Item(ORE, 1));
		g.tick();
		unknownHashThenBack(g, () -> g.setContainer(InventoryID.WORN, new Item(ARROW, 1)));
		assertEquals(Collections.emptyList(), gainsOf(g));
		List<Map<?, ?>> held = heldGains(g);
		assertEquals(1, held.size());
		assertEquals(LoginState.NO_HASH, held.get(0).get("why"));
		assertEquals(ItemGainsCapture.UNRESOLVED, held.get(0).get("reason"));
		assertEquals(-1.0, ((Map<?, ?>) value(held.get(0)).get("held")).get("882"));
		assertNotNull(held.get(0).get("outgoing_key"));

		// worn first: the worn arrow is a debit still open at the boundary, the bag changes while the hash is -1
		GameFake w = gains();
		w.setContainer(InventoryID.WORN, new Item(ARROW, 1));
		w.tick();
		unknownHashThenBack(w, () -> w.setContainer(InventoryID.INV, new Item(ARROW, 9), new Item(ORE, 1)));
		assertEquals(Collections.emptyList(), gainsOf(w));
		assertEquals(1.0, ((Map<?, ?>) value(heldGains(w).get(0)).get("debits")).get("882"));
	}

	@Test
	public void unequippingAnArrowAcrossAnUnknownHashIsNoGain() throws IOException
	{
		for (boolean inventoryFirst : new boolean[]{true, false})
		{
			GameFake g = gains();
			g.setContainer(InventoryID.WORN, new Item(ARROW, 1)); // put on earlier, matched on its own tick
			g.setContainer(InventoryID.INV, new Item(ARROW, 9), new Item(ORE, 1));
			g.ticks(3);
			g.executor.runAll();

			if (inventoryFirst)
			{
				g.setContainer(InventoryID.INV, new Item(ARROW, 10), new Item(ORE, 1));
			}
			else
			{
				g.setContainer(InventoryID.WORN);
			}
			g.tick();
			unknownHashThenBack(g, () ->
			{
				if (inventoryFirst)
				{
					g.setContainer(InventoryID.WORN);
				}
				else
				{
					g.setContainer(InventoryID.INV, new Item(ARROW, 10), new Item(ORE, 1));
				}
			});
			assertEquals("inventory first " + inventoryFirst, Collections.emptyList(), gainsOf(g));
			Map<?, ?> v = value(heldGains(g).get(0));
			assertEquals(1.0, ((Map<?, ?>) v.get(inventoryFirst ? "held" : "credits")).get("882"));
		}
	}

	@Test
	public void aGainDecidedBeforeTheBoundaryIsStillRecorded() throws IOException
	{
		GameFake g = gains();
		g.setContainer(InventoryID.INV, new Item(ARROW, 10), new Item(ORE, 3)); // +2 ore, held one tick
		g.tick();
		g.setContainer(InventoryID.INV, new Item(ARROW, 9), new Item(ORE, 3)); // an arrow on the way to the quiver
		g.tick(); // the ore gain is decided here
		unknownHashThenBack(g, () -> g.setContainer(InventoryID.WORN, new Item(ARROW, 1)));
		assertEquals(Arrays.asList(ORE + ":2"), gainsOf(g));
		assertEquals(1, heldGains(g).size()); // only the arrow, unresolved
	}

	// ------------------------- same account and mode across a region load or hop (P1 round 6): reconciled, not held

	/** Plays on for a while after the boundary, then logs out and writes everything. */
	private static void playOnAndLogOut(GameFake g)
	{
		g.ticks(6);
		g.logOut();
		g.executor.runAll();
	}

	@Test
	public void equippingAnArrowAcrossAWorldHopIsNoLoss() throws IOException
	{
		GameFake g = gains();
		g.setContainer(InventoryID.INV, new Item(ARROW, 9), new Item(ORE, 1));
		g.tick();
		g.hop(302, WorldType.MEMBERS); // same mode: the login goes on
		g.setContainer(InventoryID.WORN, new Item(ARROW, 1));
		playOnAndLogOut(g);
		assertEquals(Collections.emptyList(), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g)); // matched on the next reading, nothing left open
	}

	@Test
	public void equippingAnArrowAcrossARegionLoadIsNoLoss() throws IOException
	{
		GameFake g = gains();
		g.setContainer(InventoryID.INV, new Item(ARROW, 9), new Item(ORE, 1));
		g.tick();
		g.loadRegion();
		g.setContainer(InventoryID.WORN, new Item(ARROW, 1));
		playOnAndLogOut(g);
		assertEquals(Collections.emptyList(), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));
	}

	@Test
	public void aPickupHeldAtARegionLoadIsCredited() throws IOException
	{
		GameFake g = gains();
		g.setContainer(InventoryID.INV, new Item(ARROW, 15), new Item(ORE, 1)); // 5 arrows picked up, held one tick
		g.tick();
		g.loadRegion();
		playOnAndLogOut(g);
		assertEquals(Arrays.asList(ARROW + ":5"), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));
	}

	@Test
	public void aSkillingGainHeldAtARegionLoadIsCredited() throws IOException
	{
		GameFake g = gains();
		g.setContainer(InventoryID.INV, new Item(ARROW, 10), new Item(ORE, 2)); // an ore mined, held one tick
		g.tick();
		g.loadRegion();
		playOnAndLogOut(g);
		assertEquals(Arrays.asList(ORE + ":1"), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));
	}

	@Test
	public void aGainHeldAtASameModeHopIsCredited() throws IOException
	{
		GameFake g = gains();
		g.setContainer(InventoryID.INV, new Item(ARROW, 10), new Item(ORE, 2));
		g.tick();
		g.hop(302, WorldType.MEMBERS);
		playOnAndLogOut(g);
		assertEquals(Arrays.asList(ORE + ":1"), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));
	}

	@Test
	public void unequippingAnArrowAcrossARegionLoadIsNoGain() throws IOException
	{
		for (boolean inventoryFirst : new boolean[]{true, false})
		{
			GameFake g = gains();
			g.setContainer(InventoryID.WORN, new Item(ARROW, 1));
			g.setContainer(InventoryID.INV, new Item(ARROW, 9), new Item(ORE, 1));
			g.ticks(3);
			if (inventoryFirst)
			{
				g.setContainer(InventoryID.INV, new Item(ARROW, 10), new Item(ORE, 1));
			}
			else
			{
				g.setContainer(InventoryID.WORN);
			}
			g.tick();
			g.loadRegion();
			if (inventoryFirst)
			{
				g.setContainer(InventoryID.WORN);
			}
			else
			{
				g.setContainer(InventoryID.INV, new Item(ARROW, 10), new Item(ORE, 1));
			}
			playOnAndLogOut(g);
			assertEquals("inventory first " + inventoryFirst, Collections.emptyList(), gainsOf(g));
		}
	}

	@Test
	public void equippingAnArrowAcrossAHopIntoAnotherModeIsNoLoss() throws IOException
	{
		GameFake g = gains();
		g.setContainer(InventoryID.INV, new Item(ARROW, 9), new Item(ORE, 1));
		g.tick();
		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL); // a change of key: the arrow's worn half lands there
		g.setContainer(InventoryID.WORN, new Item(ARROW, 1));
		playOnAndLogOut(g);
		assertEquals(Collections.emptyList(), gainsOf(g));
		List<Map<?, ?>> held = heldGains(g);
		assertEquals(1, held.size());
		assertEquals(-1.0, ((Map<?, ?>) value(held.get(0)).get("held")).get("882"));
	}

	/** Every file under the folder, name to bytes. */
	private static Map<String, String> snapshot(Path folder) throws IOException
	{
		Map<String, String> out = new TreeMap<>();
		try (Stream<Path> s = Files.walk(folder))
		{
			for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator)
			{
				out.put(folder.relativize(p).toString(), new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
			}
		}
		return out;
	}

	private static String missing(Map<String, String> files, String... names)
	{
		StringBuilder out = new StringBuilder();
		for (String n : names)
		{
			if (!files.containsKey(n))
			{
				out.append(n).append(' ');
			}
		}
		return out.toString();
	}

	/** Another player shows while the hash is unknown: readings that would be theirs. */
	private static void becomeAnotherPlayer(GameFake g)
	{
		g.accountHash = -1L;
		g.name = "Other";
		g.world = 330;
		g.at = new WorldPoint(3300, 3300, 0);
		g.setContainer(InventoryID.INV, new Item(ORE, 50));
		g.setContainer(InventoryID.BANK, new Item(-1, 0), new Item(-1, 0)); // a rewrite would show in its time
		g.setVarbit(VarbitID.SLAYER_POINTS, 99);
		g.setXp(5000);
	}

	@Test
	public void anUnknownHashAfterSettleStopsEveryReading() throws IOException
	{
		GameFake g = readers();
		g.logIn();
		for (int i = 0; i < 3; i++)
		{
			g.tick();
			g.executor.runAll(); // the previous files are read, then written
		}
		Instant lastReadable = g.now;
		Map<String, String> before = snapshot(g.folder(1L, "main"));
		assertEquals("", missing(before, "account.json", "position.json", "bank.json", "vars.json", "character.json",
			"sessions.json"));

		becomeAnotherPlayer(g);
		g.ticks(5);
		g.executor.runAll();
		assertNotNull(g.login.logoutKey()); // the settled key is kept, for what was already tagged
		assertNull(g.login.reading()); // but nothing may be read for it
		assertEquals(before, snapshot(g.folder(1L, "main"))); // not one file of A's changed

		g.logOut();
		g.executor.runAll();
		Map<String, String> after = snapshot(g.folder(1L, "main"));
		for (String file : Arrays.asList("account.json", "position.json", "bank.json", "vars.json", "character.json"))
		{
			assertEquals(file, before.get(file), after.get(file));
		}
		Session s = last(g.readIn(1L, "main", "sessions.json", SessionsFile.class));
		assertEquals(lastReadable.toString(), s.end); // the session ends at the last tick that was A's
		assertEquals(Integer.valueOf(100), s.xp_end.get("Attack"));
		assertEquals(Arrays.asList(301), s.worlds);
		assertEquals(Integer.valueOf(3200), s.position_end.get("x"));
		String gains = after.get(ItemGainsCapture.FILE);
		assertTrue(gains == null || !gains.contains("\"qty\": 49")); // the other player's ore is no gain of A's
		assertFalse(Files.exists(g.dir.resolve(JsonWriter.ACCOUNTS).resolve("-1")));
	}

	@Test
	public void readingsResumeOnlyOnceATickConfirmsTheSameAccount() throws IOException
	{
		GameFake g = readers();
		g.logIn();
		g.ticks(5); // past the item-gain baseline
		g.executor.runAll();

		becomeAnotherPlayer(g);
		g.ticks(3);
		g.accountHash = 1L; // A again, with the inventory, bank and stats the client shows now
		g.name = "Tester";
		assertNull(g.login.reading()); // not before a tick has confirmed it
		g.tick();
		assertNotNull(g.login.reading());
		g.ticks(5);
		g.executor.runAll();
		PositionFile p = g.read("position.json", PositionFile.class);
		assertEquals(3300, p.x); // readings resumed for A
		g.setContainer(InventoryID.INV, new Item(ORE, 52)); // a real gain of A's
		g.ticks(3);
		g.logOut();
		g.executor.runAll();
		// the change seen while unconfirmed only set a new baseline: a gain of 2 ore, none of 49
		String gains = snapshot(g.folder(1L, "main")).get(ItemGainsCapture.FILE);
		assertTrue(gains.contains("\"qty\": 2"));
		assertFalse(gains.contains("\"qty\": 49"));
	}

	@Test
	public void theClientReportingAnotherModeStopsReadingsUntilTheSwitch() throws IOException
	{
		GameFake g = readers();
		g.logIn();
		for (int i = 0; i < 3; i++)
		{
			g.tick();
			g.executor.runAll();
		}
		Map<String, String> before = snapshot(g.folder(1L, "main"));
		g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL); // seen between ticks
		assertNull(g.login.reading());
		assertNotNull(g.login.logoutKey());
		g.at = new WorldPoint(3300, 3300, 0);
		g.tick(); // the switch: main's login ends, the league's starts (and is not settled yet)
		g.executor.runAll();
		assertEquals(before.get("position.json"), snapshot(g.folder(1L, "main")).get("position.json"));
		assertFalse(Files.exists(g.folder(1L, LEAGUE).resolve("position.json")));
	}

	private static final class PositionFile
	{
		int x;
	}

	@Test
	public void betaEvidenceBeforeTheFirstTickIsDropped() throws IOException
	{
		WorldType[][] worlds = {{WorldType.SEASONAL, WorldType.BETA_WORLD}, {WorldType.NOSAVE_MODE},
			{WorldType.TOURNAMENT_WORLD}};
		for (WorldType[] types : worlds)
		{
			GameFake g = new GameFake();
			LootCapture loot = new LootCapture(g.ctx);
			g.captures.add(loot);
			g.worldTypes = EnumSet.of(types[0], types);
			g.hub.loggedIn(g.now); // no tick yet
			g.serverLoot(ZULRAH, "Zulrah");
			g.groundLoot(KRAKEN, "Kraken");
			kc(g, "Zulrah", 1);
			assertEquals(Arrays.toString(types), 0, loot.held());
			g.logOut();
			g.executor.runAll();
			assertEquals(Arrays.toString(types), 0, files(g));
		}
	}

	@Test
	public void leavingABetaWorldDropsOnlyWhileTheClientStillReportsIt() throws IOException
	{
		GameFake g = new GameFake();
		LootCapture loot = new LootCapture(g.ctx);
		g.captures.add(loot);
		g.worldTypes = EnumSet.of(WorldType.BETA_WORLD);
		g.logIn();
		g.ticks(5); // the beta world is confirmed
		g.hub.hopping();
		g.serverLoot(ZULRAH, "Zulrah"); // the client still reports beta: dropped
		kc(g, "Zulrah", 1);
		assertEquals(0, loot.held());
		g.world = 301;
		g.worldTypes = EnumSet.of(WorldType.MEMBERS); // now it reports main, before LOGGED_IN
		g.serverLoot(VORKATH, "Vorkath");
		g.groundLoot(KRAKEN, "Kraken");
		kc(g, "Vorkath", 3);
		g.hub.loggedIn(g.now);
		g.settle();
		g.ticks(2);
		g.executor.runAll();
		g.logOut();
		g.executor.runAll();

		assertEquals(Collections.emptyList(), lootNames(g, "main"));
		assertNull(counts(g, "main").get("Vorkath"));
		assertEquals(Arrays.asList("kill_count/Vorkath", "loot/Kraken/npc_loot_received",
			"loot/Vorkath/server_npc_loot"), quarantined(g));
		for (Map<?, ?> line : quarantine(g))
		{
			assertEquals(LoginState.NO_SAVE, line.get("why"));
		}

		// a confirmed beta login whose client reports main between ticks: kept in quarantine, not discarded
		g = new GameFake();
		g.captures.add(new LootCapture(g.ctx));
		g.worldTypes = EnumSet.of(WorldType.BETA_WORLD);
		g.logIn();
		g.ticks(5);
		g.worldTypes = EnumSet.of(WorldType.MEMBERS);
		g.serverLoot(VORKATH, "Vorkath");
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList("loot/Vorkath/server_npc_loot"), quarantined(g));
	}

	@Test
	public void aChangeBeforeSettleIsWrittenOnceTheKeyIsKnown() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new ItemsCapture(g.ctx, "inventory", InventoryID.INV, () -> true, "exportItems"));
		g.hub.loggedIn(g.now);
		g.setContainer(InventoryID.INV);
		g.tick(); // no key yet: nothing is written, and the change is kept
		g.executor.runAll();
		assertEquals(0, files(g));
		g.settle();
		g.executor.runAll();
		assertNotNull(g.read("inventory.json", Map.class));
	}

	@Test
	public void aHopWithinTheSameModeKeepsTheSession() throws IOException
	{
		GameFake g = sessions();
		g.logIn();
		g.ticks(2);
		g.hop(302, WorldType.MEMBERS);
		assertTrue(g.login.settled());
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		SessionsFile f = g.read("sessions.json", SessionsFile.class);
		assertEquals(1, f.sessions.size());
		assertNotNull(f.sessions.get(0).end);
	}

	@Test
	public void anotherAccountAtLoggedInEndsTheFirstAccountsLogin() throws IOException
	{
		GameFake g = sessions();
		g.name = "Alpha";
		g.logIn();
		g.ticks(2);
		g.name = "Bravo";
		g.accountHash = 2L;
		g.hub.loggedIn(g.now);
		g.settle();
		g.logOut();
		g.executor.runAll();

		SessionsFile alpha = g.readIn(1L, "main", "sessions.json", SessionsFile.class);
		SessionsFile bravo = g.readIn(2L, "main", "sessions.json", SessionsFile.class);
		assertEquals("Alpha", alpha.account_name);
		assertEquals(1, alpha.sessions.size());
		assertEquals("Bravo", bravo.account_name);
		assertEquals(1, bravo.sessions.size());
		assertEquals("Bravo", g.readIn(2L, "main", "mode.json", Map.class).get("name"));
	}

	@Test
	public void anUnknownAccountHashDoesNotSettle() throws IOException
	{
		GameFake g = sessions();
		g.accountHash = -1L;
		g.logIn();
		assertFalse(g.login.settled());
		g.executor.runAll();
		assertEquals(0, files(g));
		g.accountHash = 1L;
		g.tick();
		assertTrue(g.login.settled());
	}

	@Test
	public void betaAndTournamentWorldsWriteNothing() throws IOException
	{
		WorldType[][] worlds = {{WorldType.SEASONAL, WorldType.BETA_WORLD}, {WorldType.NOSAVE_MODE},
			{WorldType.TOURNAMENT_WORLD}};
		for (WorldType[] types : worlds)
		{
			GameFake g = modes();
			g.worldTypes = EnumSet.of(types[0], types);
			g.logIn();
			g.ticks(10);
			assertFalse(g.login.settled());
			kc(g, "Zulrah", 3);
			g.setVarbit(VarbitID.SLAYER_POINTS, 9);
			g.ticks(3);
			g.logOut();
			g.executor.runAll();
			assertEquals(Arrays.toString(types), 0, files(g));
		}

		// hopping from the main game to a beta world and back: main's session is ended, nothing else written
		GameFake g = modes();
		g.logIn();
		g.ticks(2);
		g.executor.runAll();
		long before = files(g);
		g.hop(2000, WorldType.BETA_WORLD);
		g.ticks(10);
		g.executor.runAll();
		assertNotNull(g.readIn(1L, "main", "sessions.json", SessionsFile.class).sessions.get(0).end);
		assertFalse(Files.exists(g.dir.resolve("accounts").resolve("1").resolve("beta")));
		g.hop(301, WorldType.MEMBERS);
		g.settle();
		g.logOut();
		g.executor.runAll();
		assertEquals(2, g.readIn(1L, "main", "sessions.json", SessionsFile.class).sessions.size());
		assertTrue(files(g) >= before);
	}

	@Test
	public void aNameChangeKeepsTheFolder() throws IOException
	{
		GameFake g = sessions();
		g.name = "Old Name";
		g.logIn();
		g.logOut();
		g.name = "New Name";
		g.logIn();
		g.logOut();
		g.executor.runAll();

		SessionsFile f = g.read("sessions.json", SessionsFile.class);
		assertEquals(2, f.sessions.size());
		assertEquals("New Name", f.account_name);
		assertEquals("New Name", g.readIn(1L, "main", "mode.json", Map.class).get("name"));
		Map<?, ?> names = new Gson().fromJson(new String(Files.readAllBytes(g.dir.resolve("accounts").resolve("1")
			.resolve("names.json")), StandardCharsets.UTF_8), Map.class);
		assertEquals(2, ((List<?>) names.get("names")).size());
	}

	@Test
	public void aReservedWindowsNameIsRecorded() throws IOException
	{
		GameFake g = sessions();
		g.name = "Con";
		g.logIn();
		g.logOut();
		g.executor.runAll();
		assertEquals("Con", g.read("sessions.json", SessionsFile.class).account_name);
	}

	// ------------------------------------------------- counted skilling stores: essence pouches and the plank sack (#69)

	private static final int PURE_ESSENCE = 7936;
	private static final int BLOOD_RUNE = 565;
	private static final int GIANT_POUCH = 5514;
	private static final int PLANK_SACK = 24882;
	private static final int OAK_PLANK = 8778;

	/** Item gains alone, not yet logged in, carrying the given items and wearing nothing. */
	private static GameFake carrying(Item... inventory) throws IOException
	{
		GameFake g = new GameFake();
		CharacterCapture character = new CharacterCapture(g.ctx);
		SessionCapture sessions = new SessionCapture(g.ctx, character, (a, s, x) -> { });
		g.captures.add(character);
		g.captures.add(sessions);
		g.captures.add(new ItemGainsCapture(g.ctx, sessions));
		g.containers.put(InventoryID.WORN, new Item[0]);
		g.containers.put(InventoryID.INV, inventory);
		g.itemNames.put(ORE, "Iron ore");
		g.itemNames.put(PURE_ESSENCE, "Pure essence");
		g.itemNames.put(BLOOD_RUNE, "Blood rune");
		g.itemNames.put(GIANT_POUCH, "Giant pouch");
		g.itemNames.put(PLANK_SACK, "Plank sack");
		g.itemNames.put(OAK_PLANK, "Oak plank");
		g.itemNames.put(SEED_BOX, "Seed box");
		g.itemNames.put(RANARR_SEED, "Ranarr seed");
		g.itemNames.put(SNAPDRAGON_SEED, "Snapdragon seed");
		g.itemNames.put(TACKLE_BOX, "Tackle box");
		g.itemNames.put(HARPOON, "Harpoon");
		g.itemNames.put(RAW_SHARK, "Raw shark");
		g.itemNames.put(FLIPPERS, "Flippers");
		g.itemNames.put(MOLCH_PEARL, "Molch pearl");
		g.itemNames.put(MERFOLK_TRIDENT, "Merfolk trident");
		for (Object[] bag : BAGS)
		{
			int[] ids = (int[]) bag[0];
			String[] names = (String[]) bag[1];
			for (int i = 0; i < ids.length; i++)
			{
				g.itemNames.put(ids[i], names[i]);
			}
			g.itemNames.put((Integer) bag[2], (String) bag[3]);
		}
		g.itemNames.put(UNCUT_OPAL, "Uncut opal");
		g.itemNames.put(LEAPING_TROUT, "Leaping trout");
		g.enums.put(ItemGainsCapture.TACKLE_BOX_ENUM, new int[][] {{0, 1, 2, 3},
			{HARPOON, FLIPPERS, MOLCH_PEARL, MERFOLK_TRIDENT}});
		g.itemNames.put(BOLT_POUCH, "Bolt pouch");
		g.itemNames.put(BRONZE_BOLTS, "Bronze bolts");
		g.itemNames.put(IRON_BOLTS, "Iron bolts");
		g.itemNames.put(ADAMANT_BOLTS, "Adamant bolts");
		g.enums.put(ItemGainsCapture.BOLT_POUCH_ENUM, new int[][] {{1, 3, 6}, {BRONZE_BOLTS, IRON_BOLTS, ADAMANT_BOLTS}});
		return g;
	}

	@Test
	public void essencePouchMovesAreNotGainsAndCraftingStillCounts() throws IOException
	{
		GameFake g = carrying(new Item(GIANT_POUCH, 1), new Item(PURE_ESSENCE, 24));
		g.logIn();
		g.ticks(5);
		// fill: the count and the inventory on one tick
		g.setVarbit(VarbitID.GIANT_ESSENCE_POUCH, 12);
		g.setContainer(InventoryID.INV, new Item(GIANT_POUCH, 1), new Item(PURE_ESSENCE, 12));
		g.ticks(2);
		// craft the 12 in the inventory
		g.setContainer(InventoryID.INV, new Item(GIANT_POUCH, 1), new Item(BLOOD_RUNE, 12));
		g.ticks(2);
		// empty, the count a tick before the inventory, then craft those
		g.setVarbit(VarbitID.GIANT_ESSENCE_POUCH, 0);
		g.tick();
		g.setContainer(InventoryID.INV, new Item(GIANT_POUCH, 1), new Item(BLOOD_RUNE, 12), new Item(PURE_ESSENCE, 12));
		g.tick();
		g.setContainer(InventoryID.INV, new Item(GIANT_POUCH, 1), new Item(BLOOD_RUNE, 24));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(BLOOD_RUNE + ":12", PURE_ESSENCE + ":-12", BLOOD_RUNE + ":12",
			PURE_ESSENCE + ":-12"), gainsOf(g));
	}

	@Test
	public void storesAlreadyFullAtLoginAreNotGainsWhenEmptied() throws IOException
	{
		// a pouch and a sack full before login, emptied after the quiet ticks
		GameFake g = carrying(new Item(GIANT_POUCH, 1), new Item(PLANK_SACK, 1));
		g.varbits.put(VarbitID.GIANT_ESSENCE_POUCH, 12);
		g.varbits.put(VarbitID.PLANK_SACK_OAK, 10);
		g.logIn();
		g.ticks(5);
		g.setVarbit(VarbitID.GIANT_ESSENCE_POUCH, 0);
		g.setVarbit(VarbitID.PLANK_SACK_OAK, 0);
		g.setContainer(InventoryID.INV, new Item(GIANT_POUCH, 1), new Item(PLANK_SACK, 1),
			new Item(PURE_ESSENCE, 12), new Item(OAK_PLANK, 10));
		g.ticks(2);

		// the count sent only during the quiet ticks after login
		GameFake late = carrying(new Item(GIANT_POUCH, 1));
		late.hub.loggedIn(late.now);
		late.tick();
		late.setVarbit(VarbitID.GIANT_ESSENCE_POUCH, 12);
		late.settle();
		late.ticks(5);
		late.setVarbit(VarbitID.GIANT_ESSENCE_POUCH, 0);
		late.setContainer(InventoryID.INV, new Item(GIANT_POUCH, 1), new Item(PURE_ESSENCE, 12));
		late.ticks(2);

		for (GameFake x : Arrays.asList(g, late))
		{
			x.logOut();
			x.executor.runAll();
			assertEquals(Collections.emptyList(), gainsOf(x));
		}
	}

	@Test
	public void aFillOpenAcrossAnUnknownHashIsQuarantinedNotALoss() throws IOException
	{
		// the count first: the pouch's increase is still open when readings stop, the inventory changes during the pause
		GameFake g = carrying(new Item(GIANT_POUCH, 1), new Item(PURE_ESSENCE, 24));
		g.logIn();
		g.ticks(5);
		g.setVarbit(VarbitID.GIANT_ESSENCE_POUCH, 12);
		g.tick();
		unknownHashThenBack(g, () -> g.setContainer(InventoryID.INV, new Item(GIANT_POUCH, 1),
			new Item(PURE_ESSENCE, 12)));
		assertEquals(Collections.emptyList(), gainsOf(g));
		assertEquals(12.0, ((Map<?, ?>) value(heldGains(g).get(0)).get("debits")).get(String.valueOf(PURE_ESSENCE)));
	}

	@Test
	public void aFillUnmatchedAtAnOrdinaryLogoutIsQuarantinedNotALoss() throws IOException
	{
		// review round 1, finding 2: an ore gain is decided, then the inventory loses 12 essence for a fill and the
		// player logs out before the count update; another ore arrives on the last tick and is flushed as before
		GameFake g = carrying(new Item(GIANT_POUCH, 1), new Item(PURE_ESSENCE, 12), new Item(ORE, 1));
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(GIANT_POUCH, 1), new Item(PURE_ESSENCE, 12), new Item(ORE, 2));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(GIANT_POUCH, 1), new Item(ORE, 3));
		g.tick();
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1", ORE + ":1"), gainsOf(g));
		List<Map<?, ?>> held = heldGains(g);
		assertEquals(1, held.size());
		assertEquals(LoginState.LOGGED_OUT, held.get(0).get("why"));
		assertEquals(ItemGainsCapture.UNRESOLVED, held.get(0).get("reason"));
		assertEquals(-12.0, ((Map<?, ?>) value(held.get(0)).get("held")).get(String.valueOf(PURE_ESSENCE)));

		// the count first: the fill's window is still open at logout, but nothing is held, so nothing is set aside
		GameFake c = carrying(new Item(GIANT_POUCH, 1), new Item(PURE_ESSENCE, 12));
		c.logIn();
		c.ticks(5);
		c.setVarbit(VarbitID.GIANT_ESSENCE_POUCH, 12);
		c.tick();
		c.logOut();
		c.executor.runAll();
		assertEquals(Collections.emptyList(), gainsOf(c));
		assertEquals(Collections.emptyList(), heldGains(c));
	}

	@Test
	public void aFillOnTheLastCooldownTickAfterABankVisitIsNoLoss() throws IOException
	{
		// review round 4: open the bank and withdraw 10 oak planks, close it, fill the sack on the last cooldown tick;
		// the inventory follows on the first recorded tick. A pickup later still counts
		GameFake g = carrying(new Item(PLANK_SACK, 1));
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.BANK, new Item(-1, 0));
		g.setContainer(InventoryID.INV, new Item(PLANK_SACK, 1), new Item(OAK_PLANK, 10));
		g.tick(); // the bank's tick
		g.tick(); // first cooldown tick
		g.setVarbit(VarbitID.PLANK_SACK_OAK, 10);
		g.tick(); // last cooldown tick: the count moves
		g.setContainer(InventoryID.INV, new Item(PLANK_SACK, 1));
		g.tick(); // first recorded tick: the inventory follows
		g.ticks(3);
		g.setContainer(InventoryID.INV, new Item(PLANK_SACK, 1), new Item(OAK_PLANK, 1));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(OAK_PLANK + ":1"), gainsOf(g));
	}

	@Test
	public void aPouchCountResetByASameModeHopAndBackLateIsNothing() throws IOException
	{
		// review round 4: the count reads 0 through a same-mode world hop and comes back after the quiet ticks
		GameFake g = carrying(new Item(GIANT_POUCH, 1), new Item(PURE_ESSENCE, 5));
		g.varbits.put(VarbitID.GIANT_ESSENCE_POUCH, 12);
		g.logIn();
		g.ticks(5);
		g.varbits.put(VarbitID.GIANT_ESSENCE_POUCH, 0);
		g.hop(302, WorldType.MEMBERS);
		g.ticks(6);
		g.setVarbit(VarbitID.GIANT_ESSENCE_POUCH, 12);
		g.ticks(3);
		g.logOut();
		g.executor.runAll();
		assertEquals(Collections.emptyList(), gainsOf(g));
	}

	// ------------------------------------------------------ item-container stores: the seed box and tackle box (#69)

	private static final int SEED_BOX = 13639;
	private static final int RANARR_SEED = 5295;
	private static final int SNAPDRAGON_SEED = 5300;
	private static final int TACKLE_BOX = 25580;
	private static final int HARPOON = 311;

	@Test
	public void seedBoxDepositsAndWithdrawalsAreMovesAndAPickupStillCounts() throws IOException
	{
		GameFake g = carrying(new Item(SEED_BOX, 1), new Item(RANARR_SEED, 10), new Item(SNAPDRAGON_SEED, 2));
		g.containers.put(InventoryID.SEED_BOX, new Item[0]);
		g.logIn();
		g.ticks(5);
		// deposit all ranarr, the box and the inventory on one tick, with an ore mined on that tick: the ore counts
		g.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 10));
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(SNAPDRAGON_SEED, 2), new Item(ORE, 1));
		g.ticks(2);
		// take out 6, leaving 4 in the box
		g.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 4));
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(SNAPDRAGON_SEED, 2), new Item(ORE, 1),
			new Item(RANARR_SEED, 6));
		g.ticks(2);
		// a ranarr seed picked up with nothing moving counts
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(SNAPDRAGON_SEED, 2), new Item(ORE, 1),
			new Item(RANARR_SEED, 7));
		g.ticks(2);
		// deposit the 7 ranarr and a new kind, the box a tick behind the inventory
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(ORE, 1));
		g.tick();
		g.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 11), new Item(SNAPDRAGON_SEED, 2));
		g.ticks(2);
		// empty the box, the inventory a tick behind
		g.setContainer(InventoryID.SEED_BOX);
		g.tick();
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(ORE, 1), new Item(RANARR_SEED, 11),
			new Item(SNAPDRAGON_SEED, 2));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1", RANARR_SEED + ":1"), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));
	}

	@Test
	public void aBoxFirstLoadedAfterLoginNeverInventsAChange() throws IOException
	{
		// the tackle box is not loaded at login; the first fill sends it, on the tick the harpoon leaves
		GameFake g = carrying(new Item(TACKLE_BOX, 1), new Item(HARPOON, 1));
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.TACKLE_BOX, new Item(HARPOON, 1));
		g.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1));
		g.ticks(2);
		// the inventory first, the box loading a tick later
		GameFake late = carrying(new Item(TACKLE_BOX, 1), new Item(HARPOON, 1));
		late.logIn();
		late.ticks(5);
		late.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1));
		late.tick();
		late.setContainer(InventoryID.TACKLE_BOX, new Item(HARPOON, 1));
		late.ticks(2);
		// a Check loads a box that already held seeds; taking them out then is no gain, and the box dropping out
		// across a same-mode hop and coming back is nothing either
		GameFake check = carrying(new Item(SEED_BOX, 1));
		check.logIn();
		check.ticks(5);
		check.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 20));
		check.ticks(2);
		check.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 15));
		check.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(RANARR_SEED, 5));
		check.ticks(2);
		check.containers.remove(InventoryID.SEED_BOX);
		check.hop(302, WorldType.MEMBERS);
		check.ticks(6);
		check.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 15));
		check.ticks(3);

		for (GameFake x : Arrays.asList(g, late, check))
		{
			x.logOut();
			x.executor.runAll();
			assertEquals(Collections.emptyList(), gainsOf(x));
			assertEquals(Collections.emptyList(), heldGains(x));
		}
	}

	@Test
	public void aBoxKeptUnderItsFlaggedIdIsReadToo() throws IOException
	{
		int flagged = InventoryID.SEED_BOX | 0x8000;
		GameFake g = carrying(new Item(SEED_BOX, 1), new Item(RANARR_SEED, 10));
		g.containers.put(flagged, new Item[0]);
		g.logIn();
		g.ticks(5);
		g.setContainer(flagged, new Item(RANARR_SEED, 10));
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Collections.emptyList(), gainsOf(g));
	}

	@Test
	public void aBoxFillOnTheLastCooldownTickAfterABankVisitIsNoLoss() throws IOException
	{
		// withdraw 10 ranarr seeds at the bank, close it, fill the box on the last cooldown tick; the inventory follows
		// on the first recorded tick. Emptying the box straight into an open bank is nothing. A pickup later counts
		GameFake g = carrying(new Item(SEED_BOX, 1));
		g.containers.put(InventoryID.SEED_BOX, new Item[0]);
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.BANK, new Item(-1, 0));
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(RANARR_SEED, 10));
		g.tick(); // the bank's tick
		g.tick(); // first cooldown tick
		g.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 10));
		g.tick(); // last cooldown tick: the box moves
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1));
		g.tick(); // first recorded tick: the inventory follows
		g.ticks(3);
		g.setContainer(InventoryID.BANK, new Item(RANARR_SEED, 10));
		g.setContainer(InventoryID.SEED_BOX);
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(RANARR_SEED, 1));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(RANARR_SEED + ":1"), gainsOf(g));
	}

	@Test
	public void aBoxFillUnmatchedAtLogoutIsQuarantinedNotALoss() throws IOException
	{
		// the inventory loses a kind the box has never held, and the player logs out before the box update; an ore
		// mined on that last tick is set aside with it, as it too could be going into the box
		GameFake g = carrying(new Item(SEED_BOX, 1), new Item(SNAPDRAGON_SEED, 3), new Item(ORE, 1));
		g.containers.put(InventoryID.SEED_BOX, new Item[] {new Item(RANARR_SEED, 4)});
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(ORE, 2));
		g.tick();
		g.logOut();
		g.executor.runAll();
		assertEquals(Collections.emptyList(), gainsOf(g));
		List<Map<?, ?>> held = heldGains(g);
		assertEquals(1, held.size());
		assertEquals(LoginState.LOGGED_OUT, held.get(0).get("why"));
		Map<?, ?> setAside = (Map<?, ?>) value(held.get(0)).get("held");
		assertEquals(-3.0, setAside.get(String.valueOf(SNAPDRAGON_SEED)));
		assertEquals(1.0, setAside.get(String.valueOf(ORE)));

		// a box carried but never loaded: the ore is set aside, the harpoon never even held (its box is blind)
		GameFake u = carrying(new Item(TACKLE_BOX, 1), new Item(HARPOON, 1), new Item(ORE, 1));
		u.logIn();
		u.ticks(5);
		u.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1), new Item(ORE, 2));
		u.tick();
		u.logOut();
		u.executor.runAll();
		assertEquals(Collections.emptyList(), gainsOf(u));
		Map<?, ?> uHeld = (Map<?, ?>) value(heldGains(u).get(0)).get("held");
		assertEquals(1.0, uHeld.get(String.valueOf(ORE)));
		assertNull(uHeld.get(String.valueOf(HARPOON)));
	}

	// ------------------------------------------- #69 group 2, review round 1: one regression test per finding

	private static final int RAW_SHARK = 383;

	@Test
	public void aFillOfABoxNeverLoadedIsNoLoss() throws IOException
	{
		// P1: a seed box and a tackle box carried, their contents never sent; fills record nothing. A seed picked up
		// meanwhile is missed, while ore and fish still count
		GameFake g = carrying(new Item(SEED_BOX, 1), new Item(RANARR_SEED, 3), new Item(TACKLE_BOX, 1),
			new Item(HARPOON, 1));
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(TACKLE_BOX, 1), new Item(HARPOON, 1));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(TACKLE_BOX, 1));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(TACKLE_BOX, 1), new Item(RANARR_SEED, 1),
			new Item(ORE, 1), new Item(RAW_SHARK, 1));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(RAW_SHARK + ":1", ORE + ":1"), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));
	}

	@Test
	public void emptyingABoxWhoseFirstSnapshotIsEmptyIsNoGain() throws IOException
	{
		// P1: 13 ranarr come out of a box never loaded; its first snapshot arrives empty, on the same tick, on the
		// tick after, or the tick before the inventory
		for (int order = 0; order < 3; order++)
		{
			GameFake g = carrying(new Item(SEED_BOX, 1));
			g.logIn();
			g.ticks(5);
			if (order == 2)
			{
				g.setContainer(InventoryID.SEED_BOX);
				g.tick();
			}
			g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(RANARR_SEED, 13));
			if (order == 0)
			{
				g.setContainer(InventoryID.SEED_BOX);
			}
			g.tick();
			if (order == 1)
			{
				g.setContainer(InventoryID.SEED_BOX);
			}
			g.ticks(3);
			// known now: a ranarr picked up counts
			g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(RANARR_SEED, 14));
			g.ticks(2);
			g.logOut();
			g.executor.runAll();
			assertEquals("order " + order, Arrays.asList(RANARR_SEED + ":1"), gainsOf(g));
		}
	}

	@Test
	public void aBoxUsedFromAMenuWithoutAnUpdateIsNoMove() throws IOException
	{
		// P1: the box's contents were loaded once; a Fill and a use-on-box change the inventory but the box is not
		// sent again. Nothing counts, and a pickup once the menu window has passed does
		GameFake g = carrying(new Item(SEED_BOX, 1), new Item(RANARR_SEED, 5), new Item(SNAPDRAGON_SEED, 2));
		g.containers.put(InventoryID.SEED_BOX, new Item[] {new Item(RANARR_SEED, 4)});
		g.logIn();
		g.ticks(5);
		g.menu("Fill", "<col=ff9040>Seed box", SEED_BOX);
		g.tick();
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(SNAPDRAGON_SEED, 2));
		g.ticks(2);
		g.menu("Use", "<col=ff9040>Snapdragon seed<col=ffffff> -> <col=ff9040>Seed box", SEED_BOX);
		g.tick();
		g.tick();
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1));
		g.ticks(ItemGainsCapture.MENU_TICKS + 2);
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(RANARR_SEED, 1));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(RANARR_SEED + ":1"), gainsOf(g));
	}

	@Test
	public void aFlaggedBoxBesideAPlainOneIsReadToo() throws IOException
	{
		int flagged = InventoryID.SEED_BOX | 0x8000;
		// P2: an empty plain box kept while the flagged one receives the fill
		GameFake g = carrying(new Item(SEED_BOX, 1), new Item(RANARR_SEED, 3));
		g.containers.put(InventoryID.SEED_BOX, new Item[0]);
		g.containers.put(flagged, new Item[0]);
		g.logIn();
		g.ticks(5);
		g.setContainer(flagged, new Item(RANARR_SEED, 3));
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1));
		g.ticks(2);
		// both copies mirror a withdrawal: nothing, and a later pickup counts once
		g.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 1));
		g.setContainer(flagged, new Item(RANARR_SEED, 1));
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(RANARR_SEED, 2));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(RANARR_SEED, 3));
		g.ticks(2);

		// switching from the plain id to the flagged one, then a withdrawal seen only there
		GameFake s = carrying(new Item(SEED_BOX, 1));
		s.containers.put(InventoryID.SEED_BOX, new Item[] {new Item(RANARR_SEED, 6)});
		s.logIn();
		s.ticks(5);
		s.containers.remove(InventoryID.SEED_BOX);
		s.setContainer(flagged, new Item(RANARR_SEED, 6));
		s.ticks(2);
		s.setContainer(flagged, new Item(RANARR_SEED, 2));
		s.setContainer(InventoryID.INV, new Item(SEED_BOX, 1), new Item(RANARR_SEED, 4));
		s.ticks(2);
		// and back to the plain id, with a fill seen only there
		s.containers.remove(flagged);
		s.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 2));
		s.ticks(2);
		s.setContainer(InventoryID.SEED_BOX, new Item(RANARR_SEED, 6));
		s.setContainer(InventoryID.INV, new Item(SEED_BOX, 1));
		s.ticks(2);

		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(RANARR_SEED + ":1"), gainsOf(g));
		s.logOut();
		s.executor.runAll();
		assertEquals(Collections.emptyList(), gainsOf(s));
	}

	// ------------------------------------------- #69 group 2, review round 2: the tackle box's own item list

	private static final int FLIPPERS = 6666;
	private static final int MOLCH_PEARL = 22820;
	private static final int MERFOLK_TRIDENT = 21649;

	@Test
	public void tackleBoxItemsNoNameWordCatchesAreCoveredByTheGamesList() throws IOException
	{
		// a tackle box never loaded is filled with flippers, a Molch pearl and a Merfolk trident, one a tick: nothing.
		// An ore mined meanwhile counts (the list, not every item, is blind)
		GameFake g = carrying(new Item(TACKLE_BOX, 1), new Item(FLIPPERS, 1), new Item(MOLCH_PEARL, 1),
			new Item(MERFOLK_TRIDENT, 1));
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1), new Item(MOLCH_PEARL, 1), new Item(MERFOLK_TRIDENT, 1));
		g.tick();
		g.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1), new Item(MERFOLK_TRIDENT, 1));
		g.tick();
		g.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1), new Item(ORE, 1));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1"), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));

		// emptied out of a box never loaded whose first snapshot arrives empty: on the same tick, the tick after, or the
		// tick before the inventory. Once it is known, a Molch pearl picked up counts
		for (int order = 0; order < 3; order++)
		{
			GameFake e = carrying(new Item(TACKLE_BOX, 1));
			e.logIn();
			e.ticks(5);
			if (order == 2)
			{
				e.setContainer(InventoryID.TACKLE_BOX);
				e.tick();
			}
			e.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1), new Item(FLIPPERS, 1), new Item(MOLCH_PEARL, 1),
				new Item(MERFOLK_TRIDENT, 1));
			if (order == 0)
			{
				e.setContainer(InventoryID.TACKLE_BOX);
			}
			e.tick();
			if (order == 1)
			{
				e.setContainer(InventoryID.TACKLE_BOX);
			}
			e.ticks(3);
			e.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1), new Item(FLIPPERS, 1), new Item(MOLCH_PEARL, 2),
				new Item(MERFOLK_TRIDENT, 1));
			e.ticks(2);
			e.logOut();
			e.executor.runAll();
			assertEquals("order " + order, Arrays.asList(MOLCH_PEARL + ":1"), gainsOf(e));
		}

		// a loaded box, then flippers used on it with no update sent: nothing
		GameFake m = carrying(new Item(TACKLE_BOX, 1), new Item(FLIPPERS, 1));
		m.containers.put(InventoryID.TACKLE_BOX, new Item[] {new Item(HARPOON, 1)});
		m.logIn();
		m.ticks(5);
		m.menu("Use", "<col=ff9040>Flippers<col=ffffff> -> <col=ff9040>Tackle box", TACKLE_BOX);
		m.tick();
		m.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1));
		m.ticks(2);
		m.logOut();
		m.executor.runAll();
		assertEquals(Collections.emptyList(), gainsOf(m));
	}

	@Test
	public void withoutTheGamesTackleListABlindTackleBoxCoversEveryItem() throws IOException
	{
		// the enum not loaded: while the box is unread nothing counts, not even ore; once it is read, ore counts
		GameFake g = carrying(new Item(TACKLE_BOX, 1), new Item(FLIPPERS, 1));
		g.enums.clear();
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1), new Item(ORE, 1));
		g.ticks(2);
		g.setContainer(InventoryID.TACKLE_BOX, new Item(FLIPPERS, 1));
		g.ticks(3);
		g.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1), new Item(ORE, 2));
		g.ticks(2);
		// a list keyed by item: its keys are items too
		GameFake k = carrying(new Item(TACKLE_BOX, 1), new Item(FLIPPERS, 1));
		k.enums.put(ItemGainsCapture.TACKLE_BOX_ENUM, new int[][] {{HARPOON, FLIPPERS}, {1, 1}});
		k.logIn();
		k.ticks(5);
		k.setContainer(InventoryID.INV, new Item(TACKLE_BOX, 1), new Item(ORE, 1));
		k.ticks(2);
		for (GameFake x : Arrays.asList(g, k))
		{
			x.logOut();
			x.executor.runAll();
			assertEquals(Arrays.asList(ORE + ":1"), gainsOf(x));
		}
	}

	// ------------------------------------- #69 group 3: skilling bags whose contents the client is never sent

	private static final int HERB_SACK = 13226;
	private static final int GRIMY_RANARR = 207;
	private static final int GEM_BAG = 12020;
	private static final int GEM_POUCH = 33384;
	private static final int GEM_SACK = 33393;
	private static final int UNCUT_SAPPHIRE = 1623;
	private static final int UNCUT_OPAL = 1625;
	private static final int COAL_BAG = 12019;
	private static final int COAL = 453;
	private static final int LOG_BASKET = 28140;
	private static final int FORESTRY_BASKET_OPEN = 28145;
	private static final int YEW_LOGS = 1515;
	private static final int FISH_BARREL = 25582;
	private static final int FISH_SACK_BARREL_OPEN = 25587;
	private static final int LEAPING_TROUT = 11328;
	private static final int FISH_CRATE_EMPTY = 32366;
	private static final int FISH_CRATE_GIANT_KRILL = 32368;
	private static final int FISH_CRATE_MARLIN_VAR = 32385;
	private static final int RAW_GIANT_KRILL = 32309;
	private static final int RAW_MARLIN = 32349;
	/** Each bag's forms (closed, open, then the others), their names, and one item it holds. */
	private static final Object[][] BAGS = {
		{new int[] {HERB_SACK, 24478, 33135, 33137},
			new String[] {"Herb sack", "Open herb sack", "Silk-lined herb sack", "Open silk-lined herb sack"},
			GRIMY_RANARR, "Grimy ranarr weed"},
		{new int[] {GEM_BAG, 24481, 25628, GEM_SACK, 33395},
			new String[] {"Gem bag", "Open gem bag", "Gem bag", "Gem sack", "Open gem sack"},
			UNCUT_SAPPHIRE, "Uncut sapphire"},
		// the smaller upgrades take the semi-precious gems
		{new int[] {GEM_POUCH, 33386, 33387, 33389, 33390, 33392},
			new String[] {"Gem pouch", "Open gem pouch", "Gem satchel", "Open gem satchel", "Gem tote", "Open gem tote"},
			UNCUT_OPAL, "Uncut opal"},
		{new int[] {COAL_BAG, 24480, 25627}, new String[] {"Coal bag", "Open coal bag", "Coal bag"}, COAL, "Coal"},
		{new int[] {LOG_BASKET, 28142, 28143, FORESTRY_BASKET_OPEN},
			new String[] {"Log basket", "Open log basket", "Forestry basket", "Open forestry basket"},
			YEW_LOGS, "Yew logs"},
		{new int[] {FISH_BARREL, 25584, 25585, FISH_SACK_BARREL_OPEN},
			new String[] {"Fish barrel", "Open fish barrel", "Fish sack barrel", "Open fish sack barrel"},
			RAW_SHARK, "Raw shark"},
		// a sailing fish crate, empty first, then packed with each deep-sea fish (the test's own names)
		{new int[] {FISH_CRATE_EMPTY, FISH_CRATE_GIANT_KRILL, 32370, 32371, 32373, 32374, 32376, 32377, 32379, 32380,
			32382, 32383, FISH_CRATE_MARLIN_VAR},
			new String[] {"Fish crate", "Fish crate (giant krill)", "Fish crate (giant krill)", "Fish crate (haddock)",
				"Fish crate (haddock)", "Fish crate (yellowfin)", "Fish crate (yellowfin)", "Fish crate (halibut)",
				"Fish crate (halibut)", "Fish crate (bluefin)", "Fish crate (bluefin)", "Fish crate (marlin)",
				"Fish crate (marlin)"},
			RAW_GIANT_KRILL, "Raw giant krill"},
	};

	@Test
	public void fillingAndEmptyingASkillingBagIsNothingAndOtherItemsStillCount() throws IOException
	{
		for (Object[] bag : BAGS)
		{
			int[] ids = (int[]) bag[0];
			String name = ((String[]) bag[1])[0];
			int item = (Integer) bag[2];
			GameFake g = carrying(new Item(ids[0], 1), new Item(item, 5));
			g.logIn();
			g.ticks(5);
			// fill: the items leave and nothing shows where they went
			g.setContainer(InventoryID.INV, new Item(ids[0], 1));
			g.ticks(2);
			// empty, with an ore mined on that tick: the ore counts
			g.setContainer(InventoryID.INV, new Item(ids[0], 1), new Item(item, 5), new Item(ORE, 1));
			g.ticks(2);
			// fill from the menu, then empty over two ticks
			g.menu("Fill", "<col=ff9040>" + name, ids[0]);
			g.tick();
			g.setContainer(InventoryID.INV, new Item(ids[0], 1), new Item(ORE, 1));
			g.ticks(2);
			g.setContainer(InventoryID.INV, new Item(ids[0], 1), new Item(ORE, 1), new Item(item, 2));
			g.tick();
			g.setContainer(InventoryID.INV, new Item(ids[0], 1), new Item(ORE, 1), new Item(item, 5));
			g.ticks(ItemGainsCapture.MENU_TICKS + 2);
			// opened: the bag's forms are one item, so that is nothing too
			g.setContainer(InventoryID.INV, new Item(ids[1], 1), new Item(ORE, 1), new Item(item, 5));
			g.ticks(2);
			// one put in long after any menu window (an item used on it): still nothing while it is carried
			g.setContainer(InventoryID.INV, new Item(ids[1], 1), new Item(ORE, 1), new Item(item, 4));
			g.ticks(2);
			g.logOut();
			g.executor.runAll();
			assertEquals(name, Arrays.asList(ORE + ":1"), gainsOf(g));
			assertEquals(name, Collections.emptyList(), heldGains(g));
		}
	}

	@Test
	public void everyFormOfEachBagHidesItsItems() throws IOException
	{
		for (Object[] bag : BAGS)
		{
			int[] ids = (int[]) bag[0];
			int item = (Integer) bag[2];
			for (int i = 0; i < ids.length; i++)
			{
				GameFake g = carrying(new Item(ids[i], 1), new Item(item, 5));
				g.logIn();
				g.ticks(5);
				g.setContainer(InventoryID.INV, new Item(ids[i], 1));
				g.ticks(2);
				g.setContainer(InventoryID.INV, new Item(ids[i], 1), new Item(item, 3));
				g.ticks(2);
				g.logOut();
				g.executor.runAll();
				assertEquals(((String[]) bag[1])[i] + " " + ids[i], Collections.emptyList(), gainsOf(g));
			}
		}
		// the lists err wide: an uncut opal (not one of the gem bag's five gems) and a leaping trout (not named raw)
		// are hidden too
		GameFake g = carrying(new Item(GEM_BAG, 1), new Item(UNCUT_OPAL, 2), new Item(FISH_BARREL, 1),
			new Item(LEAPING_TROUT, 2));
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(GEM_BAG, 1), new Item(FISH_BARREL, 1));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Collections.emptyList(), gainsOf(g));
	}

	@Test
	public void theGemBagsUpgradesHideGemsAndAMenuOptionNamingThemCounts() throws IOException
	{
		// review round 1, P1: five sapphires put into a gem sack and taken out again logged -5 and +5. Every form of
		// the gem pouch, satchel and tote (with opals, which they hold) and of the sack (sapphires), closed and open,
		// now hides them
		int[][] forms = {{GEM_POUCH, UNCUT_OPAL}, {33386, UNCUT_OPAL}, {33387, UNCUT_OPAL}, {33389, UNCUT_OPAL},
			{33390, UNCUT_OPAL}, {33392, UNCUT_OPAL}, {GEM_SACK, UNCUT_SAPPHIRE}, {33395, UNCUT_SAPPHIRE}};
		for (int[] f : forms)
		{
			GameFake g = carrying(new Item(f[0], 1), new Item(f[1], 5));
			g.logIn();
			g.ticks(5);
			g.setContainer(InventoryID.INV, new Item(f[0], 1));
			g.ticks(2);
			g.setContainer(InventoryID.INV, new Item(f[0], 1), new Item(f[1], 5));
			g.ticks(2);
			g.logOut();
			g.executor.runAll();
			assertEquals(f[0] + "", Collections.emptyList(), gainsOf(g));
		}
		// a menu option named only by its target ("Empty Gem sack"): the gems arriving after the bag is dropped and
		// past the tick after are hidden; a gem picked up once the window has passed counts
		int[] closed = {GEM_POUCH, 33387, 33390, GEM_SACK};
		int[] gems = {UNCUT_OPAL, UNCUT_OPAL, UNCUT_OPAL, UNCUT_SAPPHIRE};
		String[] names = {"Gem pouch", "Gem satchel", "Gem tote", "Gem sack"};
		for (int i = 0; i < closed.length; i++)
		{
			GameFake g = carrying(new Item(closed[i], 1));
			g.logIn();
			g.ticks(5);
			g.menu("Empty", "<col=ff9040>" + names[i], -1);
			g.tick();
			g.setContainer(InventoryID.INV);
			g.tick();
			g.setContainer(InventoryID.INV, new Item(gems[i], 5));
			g.ticks(ItemGainsCapture.MENU_TICKS + 2);
			g.setContainer(InventoryID.INV, new Item(gems[i], 6));
			g.ticks(2);
			g.logOut();
			g.executor.runAll();
			assertEquals(names[i], Arrays.asList(closed[i] + ":-1", gems[i] + ":1"), gainsOf(g));
		}
	}

	@Test
	public void packingAFishCrateFromTheInventoryIsNoLoss() throws IOException
	{
		// review round 2, P1: ten raw giant krill packed by hand into an empty crate (one of a stack of three) logged
		// -10. The crate's forms are one item and its fish are hidden, packing and unpacking alike, by a menu option
		// too; an ore mined meanwhile counts. Once the crates are put away, krill caught counts
		GameFake g = carrying(new Item(FISH_CRATE_EMPTY, 3), new Item(RAW_GIANT_KRILL, 10), new Item(RAW_MARLIN, 10));
		g.itemNames.put(RAW_MARLIN, "Raw marlin");
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(FISH_CRATE_EMPTY, 2), new Item(FISH_CRATE_GIANT_KRILL, 1),
			new Item(RAW_MARLIN, 10));
		g.ticks(2);
		g.menu("Use", "<col=ff9040>Raw marlin<col=ffffff> -> <col=ff9040>Fish crate", RAW_MARLIN);
		g.tick();
		g.setContainer(InventoryID.INV, new Item(FISH_CRATE_EMPTY, 1), new Item(FISH_CRATE_GIANT_KRILL, 1),
			new Item(FISH_CRATE_MARLIN_VAR, 1), new Item(ORE, 1));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(FISH_CRATE_EMPTY, 2), new Item(FISH_CRATE_MARLIN_VAR, 1),
			new Item(ORE, 1), new Item(RAW_GIANT_KRILL, 10));
		g.ticks(2);
		g.setContainer(InventoryID.BANK, new Item(FISH_CRATE_EMPTY, 2), new Item(FISH_CRATE_MARLIN_VAR, 1),
			new Item(RAW_GIANT_KRILL, 10));
		g.setContainer(InventoryID.INV, new Item(ORE, 1));
		g.ticks(1 + ItemGainsCapture.COOLDOWN_TICKS + 2);
		g.setContainer(InventoryID.INV, new Item(ORE, 1), new Item(RAW_GIANT_KRILL, 1));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1", RAW_GIANT_KRILL + ":1"), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));
	}

	@Test
	public void aWornForestryBasketOrFishSackBarrelHidesItsItems() throws IOException
	{
		for (int[] w : new int[][] {{FORESTRY_BASKET_OPEN, YEW_LOGS}, {FISH_SACK_BARREL_OPEN, RAW_SHARK}})
		{
			GameFake g = carrying(new Item(w[1], 5));
			g.containers.put(InventoryID.WORN, new Item[] {new Item(w[0], 1)});
			g.logIn();
			g.ticks(5);
			// filled while worn, then emptied with an ore mined on that tick: only the ore counts
			g.setContainer(InventoryID.INV);
			g.ticks(2);
			g.setContainer(InventoryID.INV, new Item(w[1], 5), new Item(ORE, 1));
			g.ticks(2);
			// taken off (a move), filled from the inventory, worn again and emptied: nothing
			g.setContainer(InventoryID.WORN);
			g.setContainer(InventoryID.INV, new Item(w[1], 5), new Item(ORE, 1), new Item(w[0], 1));
			g.ticks(2);
			g.setContainer(InventoryID.INV, new Item(ORE, 1), new Item(w[0], 1));
			g.ticks(2);
			g.setContainer(InventoryID.WORN, new Item(w[0], 1));
			g.setContainer(InventoryID.INV, new Item(ORE, 1));
			g.ticks(2);
			g.setContainer(InventoryID.INV, new Item(ORE, 1), new Item(w[1], 5));
			g.ticks(2);
			g.logOut();
			g.executor.runAll();
			assertEquals(w[0] + "", Arrays.asList(ORE + ":1"), gainsOf(g));
			assertEquals(w[0] + "", Collections.emptyList(), heldGains(g));
		}
	}

	@Test
	public void aBagsItemsStillCountWhileItIsNotCarried() throws IOException
	{
		// a gem bag and a coal bag picked up count (a bag is not its own item); a grimy herb counts beside them
		GameFake g = carrying();
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(GEM_BAG, 1));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(GEM_BAG, 1), new Item(COAL_BAG, 1), new Item(GRIMY_RANARR, 1));
		g.ticks(2);
		// the gem bag dropped: an uncut gem on the tick after is missed, one later counts
		g.setContainer(InventoryID.INV, new Item(COAL_BAG, 1), new Item(GRIMY_RANARR, 1));
		g.tick();
		g.setContainer(InventoryID.INV, new Item(COAL_BAG, 1), new Item(GRIMY_RANARR, 1), new Item(UNCUT_SAPPHIRE, 1));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(COAL_BAG, 1), new Item(GRIMY_RANARR, 1), new Item(UNCUT_SAPPHIRE, 2));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(GEM_BAG + ":1", GRIMY_RANARR + ":1", COAL_BAG + ":1", GEM_BAG + ":-1",
			UNCUT_SAPPHIRE + ":1"), gainsOf(g));

		// herbs withdrawn at the bank and put in the sack on the first recorded tick: nothing. The sack then put in
		// the bank: a herb picked up after the cooldown counts
		GameFake h = carrying(new Item(HERB_SACK, 1));
		h.logIn();
		h.ticks(5);
		h.setContainer(InventoryID.BANK, new Item(-1, 0));
		h.setContainer(InventoryID.INV, new Item(HERB_SACK, 1), new Item(GRIMY_RANARR, 10));
		h.ticks(1 + ItemGainsCapture.COOLDOWN_TICKS);
		h.setContainer(InventoryID.INV, new Item(HERB_SACK, 1));
		h.ticks(3);
		h.setContainer(InventoryID.BANK, new Item(HERB_SACK, 1));
		h.setContainer(InventoryID.INV);
		h.ticks(1 + ItemGainsCapture.COOLDOWN_TICKS + 2);
		h.setContainer(InventoryID.INV, new Item(GRIMY_RANARR, 1));
		h.ticks(2);
		h.logOut();
		h.executor.runAll();
		assertEquals(Arrays.asList(GRIMY_RANARR + ":1"), gainsOf(h));
	}

	@Test
	public void aMenuOptionOnABagHidesItsItemsForAFewTicks() throws IOException
	{
		// Empty chosen on the sack, which is then dropped; its herbs arrive two ticks later, past the tick after it
		// left: nothing. A herb picked up once the window has passed counts. The same for a basket named only by the
		// menu's target
		int[][] cases = {{HERB_SACK, GRIMY_RANARR}, {LOG_BASKET, YEW_LOGS}};
		String[] targets = {"<col=ff9040>Herb sack", "<col=ff9040>Yew logs<col=ffffff> -> <col=ff9040>Log basket"};
		for (int c = 0; c < cases.length; c++)
		{
			int bag = cases[c][0];
			int item = cases[c][1];
			GameFake g = carrying(new Item(bag, 1));
			g.logIn();
			g.ticks(5);
			g.menu(c == 0 ? "Empty" : "Use", targets[c], c == 0 ? bag : item);
			g.tick();
			g.setContainer(InventoryID.INV);
			g.tick();
			g.setContainer(InventoryID.INV, new Item(item, 5));
			g.ticks(ItemGainsCapture.MENU_TICKS + 2);
			g.setContainer(InventoryID.INV, new Item(item, 6));
			g.ticks(2);
			g.logOut();
			g.executor.runAll();
			assertEquals(targets[c], Arrays.asList(bag + ":-1", item + ":1"), gainsOf(g));
		}
	}

	@Test
	public void aBagFilledOnTheLastTickBeforeLogoutIsNothingAndTheNextLoginCounts() throws IOException
	{
		// coal into the bag and an ore mined on the last tick: the ore is flushed as a gain, the coal is nothing and
		// nothing is set aside (the bag takes only coal)
		GameFake g = carrying(new Item(COAL_BAG, 1), new Item(COAL, 10));
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(COAL_BAG, 1), new Item(ORE, 1));
		g.tick();
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1"), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));
		// the next login, the bag left in the bank: coal mined counts
		g.containers.put(InventoryID.INV, new Item[] {new Item(ORE, 1)});
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(ORE, 1), new Item(COAL, 1));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1", COAL + ":1"), gainsOf(g));
	}

	// ------------------------------------------------------------------------- #69 group 4: the bolt pouch

	private static final int BOLT_POUCH = 9433;
	private static final int BRONZE_BOLTS = 877;
	private static final int IRON_BOLTS = 9140;
	private static final int ADAMANT_BOLTS = 9143;
	/** The pouch slot types of {@link ItemGainsCapture#BOLT_POUCH_ENUM} in these tests. */
	private static final int IRON_TYPE = 3;
	private static final int ADAMANT_TYPE = 6;
	private static final int[] BOLT_TYPES = {VarbitID.XBOWS_POUCH_SLOT1, VarbitID.XBOWS_POUCH_SLOT2,
		VarbitID.XBOWS_POUCH_SLOT3, VarbitID.XBOWS_POUCH_SLOT4};
	private static final int[] BOLT_COUNTS = {VarbitID.XBOWS_POUCH_NUM1, VarbitID.XBOWS_POUCH_NUM2,
		VarbitID.XBOWS_POUCH_NUM3, VarbitID.XBOWS_POUCH_NUM4};
	/** A button in the bolt pouch's window, as a menu option's param1. */
	private static final int POUCH_WINDOW = net.runelite.api.gameval.InterfaceID.XBOWS_POUCH << 16 | 5;

	/** Sets a bolt pouch slot (0 to 3) as the game would, the type before the count. */
	private static void pouchSlot(GameFake g, int slot, int type, int count)
	{
		g.setVarbit(BOLT_TYPES[slot], type);
		g.setVarbit(BOLT_COUNTS[slot], count);
	}

	/** A pouch slot already set before login. */
	private static void pouchAtLogin(GameFake g, int slot, int type, int count)
	{
		g.varbits.put(BOLT_TYPES[slot], type);
		g.varbits.put(BOLT_COUNTS[slot], count);
	}

	/**
	 * One move of the pouch and the inventory, in an event order: 0 on one tick, 1 the count a tick first, 2 the
	 * inventory a tick first. Then two quiet ticks.
	 */
	private static void pouchMove(GameFake g, int order, Runnable count, Runnable inventory)
	{
		(order == 2 ? inventory : count).run();
		if (order != 0)
		{
			g.tick();
		}
		(order == 2 ? count : inventory).run();
		g.tick();
		g.ticks(2);
	}

	@Test
	public void fillingPartlyEmptyingAndEmptyingTheBoltPouchIsNothingInEveryEventOrder() throws IOException
	{
		// no menu option at all, so only the counts' window covers the moves; an ore mined on the fill's inventory tick
		// and bolts picked up later still count
		for (int order = 0; order < 3; order++)
		{
			GameFake g = carrying(new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 100), new Item(ORE, 1));
			g.logIn();
			g.ticks(5);
			pouchMove(g, order, () -> pouchSlot(g, 0, IRON_TYPE, 100),
				() -> g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2)));
			pouchMove(g, order, () -> pouchSlot(g, 0, IRON_TYPE, 60),
				() -> g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2), new Item(IRON_BOLTS, 40)));
			// emptied: the type lingers with a count of 0
			pouchMove(g, order, () -> pouchSlot(g, 0, IRON_TYPE, 0),
				() -> g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2), new Item(IRON_BOLTS, 100)));
			g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2), new Item(IRON_BOLTS, 105));
			g.ticks(2);
			g.logOut();
			g.executor.runAll();
			assertEquals("order " + order, Arrays.asList(ORE + ":1", IRON_BOLTS + ":5"), gainsOf(g));
			assertEquals("order " + order, Collections.emptyList(), heldGains(g));
		}
	}

	@Test
	public void wieldingBoltsFromThePouchIsNothingAndFiringThemTooAndALaterPickupCounts() throws IOException
	{
		// iron bolts worn and adamant in the pouch: Wield from its window puts the adamant on and the iron into the
		// inventory, the worn update a tick behind or not; without the menu option too (the counts alone). Firing 10
		// is not logged, the worn and pouch iron move back into the pouch, and 7 adamant picked up later count
		for (int c = 0; c < 4; c++)
		{
			boolean menu = c < 2;
			boolean wornLate = c % 2 == 1;
			GameFake g = carrying(new Item(BOLT_POUCH, 1));
			g.containers.put(InventoryID.WORN, new Item[] {new Item(IRON_BOLTS, 20)});
			pouchAtLogin(g, 0, ADAMANT_TYPE, 50);
			g.logIn();
			g.ticks(5);
			if (menu)
			{
				g.menu("Wield", "<col=ff9040>Adamant bolts", ADAMANT_BOLTS, POUCH_WINDOW);
			}
			g.tick();
			pouchSlot(g, 0, ADAMANT_TYPE, 0);
			g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 20));
			if (wornLate)
			{
				g.tick();
			}
			g.setContainer(InventoryID.WORN, new Item(ADAMANT_BOLTS, 50));
			g.ticks(ItemGainsCapture.MENU_TICKS + 2);
			g.setContainer(InventoryID.WORN, new Item(ADAMANT_BOLTS, 40));
			g.ticks(2);
			// the inventory's iron into the pouch's second slot, no menu option
			pouchSlot(g, 1, IRON_TYPE, 20);
			g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1));
			g.ticks(3);
			g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ADAMANT_BOLTS, 7));
			g.ticks(2);
			g.logOut();
			g.executor.runAll();
			assertEquals("case " + c, Arrays.asList(ADAMANT_BOLTS + ":7"), gainsOf(g));
		}
	}

	@Test
	public void boltsGainedOrUsedWithNoPouchMovingStillCount() throws IOException
	{
		// no pouch at all: bolts picked up and dropped count, bolts fired from the ammo slot are not logged
		GameFake g = carrying(new Item(IRON_BOLTS, 10));
		g.containers.put(InventoryID.WORN, new Item[] {new Item(ADAMANT_BOLTS, 30)});
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(IRON_BOLTS, 20));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(IRON_BOLTS, 16));
		g.ticks(2);
		g.setContainer(InventoryID.WORN, new Item(ADAMANT_BOLTS, 25));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(IRON_BOLTS, 16), new Item(ADAMANT_BOLTS, 3));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(IRON_BOLTS + ":10", IRON_BOLTS + ":-4", ADAMANT_BOLTS + ":3"), gainsOf(g));

		// a pouch holding iron bolts, carried or banked, that does not move: iron picked up counts
		for (boolean carried : new boolean[] {true, false})
		{
			GameFake k = carried ? carrying(new Item(BOLT_POUCH, 1)) : carrying();
			pouchAtLogin(k, 0, IRON_TYPE, 40);
			k.logIn();
			k.ticks(5);
			k.setContainer(InventoryID.INV, carried ? new Item[] {new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 6)}
				: new Item[] {new Item(IRON_BOLTS, 6)});
			k.ticks(2);
			k.logOut();
			k.executor.runAll();
			assertEquals("carried " + carried, Arrays.asList(IRON_BOLTS + ":6"), gainsOf(k));
		}
	}

	@Test
	public void aPouchUsedFromAMenuWhoseCountComesLateIsNothingAndOtherItemsCount() throws IOException
	{
		// bolts used on the pouch, and Withdraw in its window: the count is sent three ticks after the inventory,
		// beyond the count's own window. Nothing, while an ore mined then and a bolt picked up after the window count
		GameFake g = carrying(new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 30), new Item(ORE, 1));
		pouchAtLogin(g, 0, IRON_TYPE, 10);
		g.logIn();
		g.ticks(5);
		g.menu("Use", "<col=ff9040>Iron bolts<col=ffffff> -> <col=ff9040>Bolt pouch", BOLT_POUCH);
		g.tick();
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2));
		g.ticks(3);
		pouchSlot(g, 0, IRON_TYPE, 40);
		g.ticks(3);
		g.menu("Withdraw-All", "<col=ff9040>Iron bolts", IRON_BOLTS, POUCH_WINDOW);
		g.tick();
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2), new Item(IRON_BOLTS, 40));
		g.ticks(3);
		pouchSlot(g, 0, IRON_TYPE, 0);
		g.ticks(ItemGainsCapture.MENU_TICKS + 2);
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2), new Item(IRON_BOLTS, 41));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1", IRON_BOLTS + ":1"), gainsOf(g));
	}

	@Test
	public void aPouchFullAtLoginOrFilledAfterABankVisitIsNothing() throws IOException
	{
		// full before login and emptied after the quiet ticks; and the count sent only during the quiet ticks
		GameFake g = carrying(new Item(BOLT_POUCH, 1));
		pouchAtLogin(g, 0, IRON_TYPE, 100);
		g.logIn();
		g.ticks(5);
		pouchSlot(g, 0, IRON_TYPE, 0);
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 100));
		g.ticks(2);

		GameFake late = carrying(new Item(BOLT_POUCH, 1));
		late.hub.loggedIn(late.now);
		late.tick();
		pouchSlot(late, 0, IRON_TYPE, 100);
		late.settle();
		late.ticks(5);
		pouchSlot(late, 0, IRON_TYPE, 0);
		late.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 100));
		late.ticks(2);

		// withdraw 50 iron bolts at the bank, close it, fill the pouch on the last cooldown tick; the inventory follows
		// on the first recorded tick. A pickup later counts
		GameFake b = carrying(new Item(BOLT_POUCH, 1));
		b.logIn();
		b.ticks(5);
		b.setContainer(InventoryID.BANK, new Item(-1, 0));
		b.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 50));
		b.tick(); // the bank's tick
		b.tick(); // first cooldown tick
		pouchSlot(b, 0, IRON_TYPE, 50);
		b.tick(); // last cooldown tick: the count moves
		b.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1));
		b.tick(); // first recorded tick: the inventory follows
		b.ticks(3);
		b.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 2));
		b.ticks(2);

		for (GameFake x : Arrays.asList(g, late))
		{
			x.logOut();
			x.executor.runAll();
			assertEquals(Collections.emptyList(), gainsOf(x));
		}
		b.logOut();
		b.executor.runAll();
		assertEquals(Arrays.asList(IRON_BOLTS + ":2"), gainsOf(b));
	}

	@Test
	public void aPouchFillUnmatchedAtLogoutIsQuarantinedNotALoss() throws IOException
	{
		// a pouch that holds iron bolts loses 20 more from the inventory on the last tick, with no menu option and no
		// count update before logout: set aside, not a loss; an ore mined on that tick is flushed as before
		GameFake g = carrying(new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 20), new Item(ORE, 1));
		pouchAtLogin(g, 0, IRON_TYPE, 10);
		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2));
		g.tick();
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1"), gainsOf(g));
		List<Map<?, ?>> held = heldGains(g);
		assertEquals(1, held.size());
		assertEquals(-20.0, ((Map<?, ?>) value(held.get(0)).get("held")).get(String.valueOf(IRON_BOLTS)));

		// a new kind used on the pouch just before logout: the menu window hides it, nothing to set aside
		GameFake m = carrying(new Item(BOLT_POUCH, 1), new Item(ADAMANT_BOLTS, 20));
		m.logIn();
		m.ticks(5);
		m.menu("Use", "<col=ff9040>Adamant bolts<col=ffffff> -> <col=ff9040>Bolt pouch", BOLT_POUCH);
		m.tick();
		m.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1));
		m.tick();
		m.logOut();
		m.executor.runAll();
		assertEquals(Collections.emptyList(), gainsOf(m));
		assertEquals(Collections.emptyList(), heldGains(m));
	}

	@Test
	public void aPouchSlotTakingAnotherBoltIsAnEmptyAndAFill() throws IOException
	{
		// one slot's 20 iron bolts swapped for 15 adamant on one tick, the inventory a tick behind: nothing. Iron
		// picked up later counts
		GameFake g = carrying(new Item(BOLT_POUCH, 1), new Item(ADAMANT_BOLTS, 15));
		pouchAtLogin(g, 0, IRON_TYPE, 20);
		g.logIn();
		g.ticks(5);
		pouchSlot(g, 0, ADAMANT_TYPE, 15);
		g.tick();
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 20));
		g.ticks(3);
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 23));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(IRON_BOLTS + ":3"), gainsOf(g));
	}

	@Test
	public void withoutTheGamesBoltListAMovingPouchHidesEveryItemForItsWindow() throws IOException
	{
		// enum 336 not loaded: a fill cannot name its bolt, so nothing on its ticks counts, not even ore; ore mined
		// once the window has passed counts, and a menu option on the pouch hides every item for a few ticks
		GameFake g = carrying(new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 20), new Item(ORE, 1));
		g.enums.remove(ItemGainsCapture.BOLT_POUCH_ENUM);
		g.logIn();
		g.ticks(5);
		pouchSlot(g, 0, IRON_TYPE, 20);
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2));
		g.ticks(3);
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 3));
		g.ticks(2);
		g.menu("Open", "<col=ff9040>Bolt pouch", BOLT_POUCH);
		g.tick();
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 4));
		g.ticks(ItemGainsCapture.MENU_TICKS + 2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1"), gainsOf(g));
	}

	@Test
	public void aPouchWindowWithdrawXOutlastingTheMenuWindowIsNothingAndOtherItemsCount() throws IOException
	{
		// the pouch window open, a Withdraw-X option chosen, and the player typing for 6+ ticks: the 4-tick menu
		// window has expired, but the open interface still hides the pouch's own bolts. The inventory gets the
		// bolts with the pouch count arriving late: nothing is recorded. Ore gained during the same open window
		// still counts. After the window closes and its grace tick passes, a real bolt gain and loss count again
		GameFake g = carrying(new Item(BOLT_POUCH, 1), new Item(ORE, 1));
		pouchAtLogin(g, 0, IRON_TYPE, 100);
		g.logIn();
		g.ticks(5);
		g.openInterface(net.runelite.api.gameval.InterfaceID.XBOWS_POUCH);
		g.menu("Withdraw-X", "<col=ff9040>Iron bolts", IRON_BOLTS, POUCH_WINDOW);
		g.tick();
		g.ticks(6);
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2));
		g.tick();
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2), new Item(IRON_BOLTS, 50));
		g.ticks(2);
		pouchSlot(g, 0, IRON_TYPE, 50);
		g.ticks(3);
		g.closeInterface(net.runelite.api.gameval.InterfaceID.XBOWS_POUCH);
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2), new Item(IRON_BOLTS, 52));
		g.ticks(2);
		g.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(ORE, 2), new Item(IRON_BOLTS, 51));
		g.ticks(2);
		g.logOut();
		g.executor.runAll();
		assertEquals(Arrays.asList(ORE + ":1", IRON_BOLTS + ":2", IRON_BOLTS + ":-1"), gainsOf(g));
		assertEquals(Collections.emptyList(), heldGains(g));

		// logging out while the window is open: on the next login, suppression does not persist
		GameFake l = carrying(new Item(BOLT_POUCH, 1));
		pouchAtLogin(l, 0, IRON_TYPE, 50);
		l.logIn();
		l.ticks(5);
		l.openInterface(net.runelite.api.gameval.InterfaceID.XBOWS_POUCH);
		l.tick();
		l.logOut();
		l.closeInterface(net.runelite.api.gameval.InterfaceID.XBOWS_POUCH);
		l.logIn();
		l.ticks(5);
		l.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 10));
		l.ticks(2);
		l.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 8));
		l.ticks(2);
		l.logOut();
		l.executor.runAll();
		assertEquals(Arrays.asList(IRON_BOLTS + ":10", IRON_BOLTS + ":-2"), gainsOf(l));
		assertEquals(Collections.emptyList(), heldGains(l));

		// a world hop while the window is open: on the new world, suppression does not persist
		GameFake h = carrying(new Item(BOLT_POUCH, 1));
		pouchAtLogin(h, 0, IRON_TYPE, 50);
		h.logIn();
		h.ticks(5);
		h.openInterface(net.runelite.api.gameval.InterfaceID.XBOWS_POUCH);
		h.tick();
		h.hop(302);
		h.closeInterface(net.runelite.api.gameval.InterfaceID.XBOWS_POUCH);
		h.ticks(5);
		h.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 10));
		h.ticks(2);
		h.setContainer(InventoryID.INV, new Item(BOLT_POUCH, 1), new Item(IRON_BOLTS, 8));
		h.ticks(2);
		h.logOut();
		h.executor.runAll();
		assertEquals(Arrays.asList(IRON_BOLTS + ":10", IRON_BOLTS + ":-2"), gainsOf(h));
		assertEquals(Collections.emptyList(), heldGains(h));
	}
}
