package com.hans;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.hans.RsbuddyData.SessionsFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.Item;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;

/**
 * Every Deadman season is a fresh character, so each gets its own folder, named by the season the world list gives
 * the world; a season that can't be named records nothing and quarantines what arrives, and the old shared
 * {@code deadman} folder is never written again (docs/plugin-mode-separation.md, "Deadman seasons").
 */
public class DeadmanSeasonsTest
{
	private static final String ANNIHILATION = "deadman-deadman_annihilation";
	private static final String ARMAGEDDON = "deadman-deadman_armageddon";
	private static final String LEAGUE = "seasonal-demonic_pacts_league";
	private static final int DMM_WORLD = 345;

	private static GameFake game() throws IOException
	{
		GameFake g = new GameFake();
		CharacterCapture character = new CharacterCapture(g.ctx);
		SessionCapture sessions = new SessionCapture(g.ctx, character, (a, s, x) -> { });
		g.captures.add(character);
		g.captures.add(sessions);
		g.captures.add(new ItemGainsCapture(g.ctx, sessions));
		g.captures.add(new LootCapture(g.ctx));
		g.containers.put(InventoryID.WORN, new Item[0]);
		g.containers.put(InventoryID.INV, new Item[0]);
		return g;
	}

	/** Logs in on a Deadman world running the given season (as the world list names it). */
	private static void deadmanLogin(GameFake g, String season)
	{
		g.world = DMM_WORLD;
		g.worldTypes = EnumSet.of(WorldType.DEADMAN);
		g.activities.put(DMM_WORLD, season);
		g.logIn();
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

	private static List<String> modeFolders(GameFake g) throws IOException
	{
		Path account = g.dir.resolve(JsonWriter.ACCOUNTS).resolve("1");
		if (!Files.exists(account))
		{
			return new ArrayList<>();
		}
		try (Stream<Path> s = Files.list(account))
		{
			return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted()
				.collect(Collectors.toList());
		}
	}

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

	private static void play(GameFake g, String boss, int kc)
	{
		g.ticks(3);
		kc(g, boss, kc);
		g.ticks(3);
		g.executor.runAll();
		g.tick();
		g.logOut();
		g.executor.runAll();
	}

	@Test
	public void twoDeadmanSeasonsGoToSeparateFolders() throws IOException
	{
		GameFake g = game();
		deadmanLogin(g, "Deadman: Annihilation");
		assertTrue(g.login.settled());
		assertEquals(ANNIHILATION, g.login.logoutKey().mode);
		play(g, "Vorkath", 40);

		// a year later, a new season on the same world number; the client sat on the login screen, so RuneLite's
		// list in hand still names the old season until the refresh the login asks for
		g.listNanos += TimeUnit.DAYS.toNanos(365);
		g.now = g.now.plusSeconds(TimeUnit.DAYS.toSeconds(365));
		deadmanLogin(g, "Deadman: Armageddon");
		assertTrue(g.login.settled());
		assertEquals(ARMAGEDDON, g.login.logoutKey().mode);
		play(g, "Vorkath", 1);

		assertEquals(40, counts(g, ANNIHILATION).get("Vorkath").count);
		assertEquals(1, counts(g, ARMAGEDDON).get("Vorkath").count);
		assertEquals(1, g.readIn(1L, ANNIHILATION, "sessions.json", SessionsFile.class).sessions.size());
		assertEquals(1, g.readIn(1L, ARMAGEDDON, "sessions.json", SessionsFile.class).sessions.size());
		Map<?, ?> mode = g.readIn(1L, ARMAGEDDON, "mode.json", Map.class);
		assertEquals(ARMAGEDDON, mode.get("mode"));
		assertEquals("Deadman: Armageddon", mode.get("world_activity")); // the evidence, to confirm in game
		assertEquals("DEADMAN", mode.get("profile_type"));
		assertEquals(ARMAGEDDON.length(), ARMAGEDDON.replaceAll("[^a-z0-9_-]", "").length());
	}

	@Test
	public void aStaleListOnTheLoginScreenNeverCreditsTheOldSeason() throws IOException
	{
		GameFake g = game();
		deadmanLogin(g, "Deadman: Annihilation");
		play(g, "Vorkath", 40);

		// RuneLite fetched the list on the login screen before the season changed; months later the player logs in
		g.refreshWorldList();
		g.listNanos += TimeUnit.DAYS.toNanos(200);
		g.world = DMM_WORLD;
		g.worldTypes = EnumSet.of(WorldType.DEADMAN);
		g.activities.put(DMM_WORLD, "Deadman: Armageddon"); // what a refresh now gives
		int before = g.listRefreshes;
		g.hub.loggedIn(g.now);
		assertEquals(GameMode.UNIDENTIFIED, g.login.mode()); // the list in hand is of unknown age
		assertEquals(before + 1, g.listRefreshes);
		kc(g, "Zulrah", 5); // before any season is named: never credited to the old one
		g.settle();
		assertEquals(ARMAGEDDON, g.login.logoutKey().mode);
		play(g, "Vorkath", 2);

		assertEquals(40, counts(g, ANNIHILATION).get("Vorkath").count);
		assertNull(counts(g, ANNIHILATION).get("Zulrah"));
		assertNull(counts(g, ARMAGEDDON).get("Zulrah"));
		assertEquals(2, counts(g, ARMAGEDDON).get("Vorkath").count);
		assertEquals(1, g.readIn(1L, ANNIHILATION, "sessions.json", SessionsFile.class).sessions.size());
	}

	@Test
	public void mainLeaguesAndDeadmanStayApart() throws IOException
	{
		GameFake g = game();
		g.logIn();
		play(g, "Zulrah", 10);
		g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL);
		g.world = 500;
		g.logIn();
		play(g, "Zulrah", 2);
		deadmanLogin(g, "Deadman: Annihilation");
		play(g, "Zulrah", 1);

		assertEquals(10, counts(g, GameMode.MAIN).get("Zulrah").count);
		assertEquals(2, counts(g, LEAGUE).get("Zulrah").count);
		assertEquals(1, counts(g, ANNIHILATION).get("Zulrah").count);
		List<String> folders = modeFolders(g);
		assertEquals(3, folders.size());
		assertTrue(folders.containsAll(java.util.Arrays.asList(GameMode.MAIN, LEAGUE, ANNIHILATION)));
	}

	@Test
	public void aHopBetweenMainAndDeadmanIsAChangeOfKey() throws IOException
	{
		GameFake g = game();
		g.logIn();
		g.ticks(2);
		kc(g, "Zulrah", 10);
		g.activities.put(DMM_WORLD, "Deadman: Annihilation");
		g.hop(DMM_WORLD, WorldType.DEADMAN);
		g.settle();
		assertEquals(ANNIHILATION, g.login.logoutKey().mode);
		g.ticks(2);
		kc(g, "Vorkath", 3);
		g.ticks(2);
		g.executor.runAll();
		g.tick();
		g.logOut();
		g.executor.runAll();

		assertEquals(10, counts(g, GameMode.MAIN).get("Zulrah").count);
		assertNull(counts(g, GameMode.MAIN).get("Vorkath"));
		assertEquals(3, counts(g, ANNIHILATION).get("Vorkath").count);
		assertNull(counts(g, ANNIHILATION).get("Zulrah"));
	}

	@Test
	public void aHopBetweenTwoWorldsOfOneSeasonKeepsTheSession() throws IOException
	{
		GameFake g = game();
		deadmanLogin(g, "Deadman: Annihilation");
		g.ticks(3);
		kc(g, "Vorkath", 5);
		g.activities.put(346, "Deadman: Annihilation");
		g.refreshWorldList();
		g.hop(346, WorldType.DEADMAN);
		g.tick();
		assertEquals(ANNIHILATION, g.login.mode());
		g.ticks(3);
		kc(g, "Vorkath", 7);
		g.ticks(3);
		g.executor.runAll();
		g.tick();
		g.logOut();
		g.executor.runAll();

		assertEquals(7, counts(g, ANNIHILATION).get("Vorkath").count);
		assertEquals(java.util.Collections.singletonList(ANNIHILATION), modeFolders(g));
		assertEquals(1, g.readIn(1L, ANNIHILATION, "sessions.json", SessionsFile.class).sessions.size());
	}

	@Test
	public void aDelayedRefreshNamesTheSeasonOnALaterTick() throws IOException
	{
		GameFake g = game();
		g.world = DMM_WORLD;
		g.worldTypes = EnumSet.of(WorldType.DEADMAN);
		g.logIn(); // no list yet: unidentified, a refresh is asked for
		assertEquals(GameMode.UNIDENTIFIED, g.login.mode());
		g.ticks(2);
		assertNull(g.login.reading());
		g.activities.put(DMM_WORLD, "Deadman: Annihilation");
		g.refreshWorldList(); // RuneLite's fetch arrives
		g.settle(); // the tick sees the mode change and starts a proper login
		assertEquals(ANNIHILATION, g.login.mode());
		assertTrue(g.login.settled());
		play(g, "Vorkath", 4);
		assertEquals(4, counts(g, ANNIHILATION).get("Vorkath").count);
	}

	@Test
	public void aListFetchedBeforeLoginNamesTheSeasonAtLogin() throws IOException
	{
		GameFake g = game();
		g.activities.put(DMM_WORLD, "Deadman: Annihilation");
		g.refreshWorldList(); // fetched on the login screen, through the plugin's WorldsFetch handler
		g.world = DMM_WORLD;
		g.worldTypes = EnumSet.of(WorldType.DEADMAN);
		g.logIn();
		assertEquals(ANNIHILATION, g.login.mode());
		assertTrue(g.login.settled());
	}

	@Test
	public void aListFetchedAfterAHopNamesTheNewSeason() throws IOException
	{
		GameFake g = game();
		deadmanLogin(g, "Deadman: Annihilation");
		g.activities.put(346, "Deadman: Armageddon");
		g.hop(346, WorldType.DEADMAN); // the list in hand doesn't know world 346 yet
		g.ticks(2);
		g.refreshWorldList(); // RuneLite's fetch arrives after the hop
		g.settle();
		g.ticks(3);
		assertEquals(ARMAGEDDON, g.login.mode());
		assertTrue(g.login.settled());
	}

	@Test
	public void anUnnamedActivityDoesNotBlockALaterName() throws IOException
	{
		GameFake g = game();
		g.world = DMM_WORLD;
		g.worldTypes = EnumSet.of(WorldType.DEADMAN);
		g.activities.put(DMM_WORLD, "-");
		g.refreshWorldList();
		g.logIn();
		assertEquals(GameMode.UNIDENTIFIED, g.login.mode());
		g.ticks(3);
		kc(g, "Vorkath", 3);
		g.ticks(3);
		g.executor.runAll();
		assertEquals(java.util.Collections.emptyList(), modeFolders(g)); // no folder, evidence quarantined
		assertFalse(quarantine(g).isEmpty());

		g.activities.put(DMM_WORLD, "Deadman: Annihilation");
		g.refreshWorldList(); // the season gets its name: no logout needed
		g.settle();
		assertEquals(ANNIHILATION, g.login.mode());
		assertTrue(g.login.settled());
	}

	@Test
	public void theOldSharedDeadmanFolderIsLeftUntouched() throws IOException
	{
		GameFake g = game();
		g.write(1L, "deadman", "killcounts.json", new TreeMap<>()); // written by a build before seasons were kept apart
		Path old = g.folder(1L, "deadman");
		byte[] before = Files.readAllBytes(old.resolve("killcounts.json"));
		long modified = Files.getLastModifiedTime(old.resolve("killcounts.json")).toMillis();

		deadmanLogin(g, "Deadman: Annihilation");
		play(g, "Vorkath", 7);

		assertEquals(7, counts(g, ANNIHILATION).get("Vorkath").count);
		try (Stream<Path> s = Files.list(old))
		{
			assertEquals(1, s.count()); // nothing added: no mode.json, no sessions
		}
		assertArrayEquals(before, Files.readAllBytes(old.resolve("killcounts.json")));
		assertEquals(modified, Files.getLastModifiedTime(old.resolve("killcounts.json")).toMillis());
	}

	@Test
	public void aSeasonThatCannotBeNamedRecordsNothingAndQuarantinesItsEvidence() throws IOException
	{
		GameFake g = game();
		g.write(1L, "deadman", "killcounts.json", new TreeMap<>());
		g.world = 399; // a world the list doesn't name (an unlisted world, or the list never loads)
		g.worldTypes = EnumSet.of(WorldType.DEADMAN);
		g.logIn();
		assertFalse(g.login.settled());
		assertNull(g.login.reading());
		assertEquals(GameMode.UNIDENTIFIED, g.login.mode());
		g.ticks(3);
		kc(g, "Vorkath", 3);
		g.serverLoot(8061, "Vorkath");
		g.ticks(3);
		g.executor.runAll();
		g.logOut();
		g.executor.runAll();

		assertEquals(java.util.Collections.singletonList("deadman"), modeFolders(g)); // no folder made or written
		List<Map<?, ?>> lines = quarantine(g);
		assertFalse(lines.isEmpty()); // kept raw, not discarded
		for (Map<?, ?> line : lines)
		{
			assertEquals(LoginState.NO_SEASON, line.get("why"));
		}
		assertTrue(g.listRefreshes <= 1); // asked once for a fresh list, not on every event
	}
}
