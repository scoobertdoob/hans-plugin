package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.hans.LootCapture.ClueCount;
import com.hans.LootCapture.Count;
import com.hans.LootCapture.KillFile;
import com.hans.LootCapture.PendingKc;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.ChatMessageType;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;

/**
 * Clue completion counts per tier (#118): read from the game's "You have completed N hard Treasure Trails." line into
 * killcounts.json's {@code clues}, never going down, kept per account and mode. Synthetic accounts only.
 */
public class ClueCountTest
{
	private static final String LEAGUE = "seasonal-demonic_pacts_league";
	private static final ProfileKey ALPHA = new ProfileKey(1L, GameMode.MAIN, "Alpha");
	private static final ProfileKey ALPHA_LEAGUE = new ProfileKey(1L, LEAGUE, "Alpha");
	private static final ProfileKey BETA = new ProfileKey(2L, GameMode.MAIN, "Beta");

	private static GameFake game() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new LootCapture(g.ctx));
		return g;
	}

	/** Logs in and ticks until the file has loaded. */
	private static void ready(GameFake g)
	{
		g.logIn();
		g.tick();
		g.executor.runAll();
	}

	/** Applies what is pending and writes it. */
	private static void apply(GameFake g)
	{
		g.tick();
		g.executor.runAll();
	}

	/** The line as the game sends it: the count in red. */
	private static String line(int n, String tier)
	{
		return "You have completed <col=ff0000>" + n + "</col> " + tier + " Treasure Trail" + (n == 1 ? "" : "s") + ".";
	}

	private static void send(GameFake g, ChatMessageType type, String message)
	{
		ChatMessage e = new ChatMessage(null, type, "", message, null, 0);
		for (Capture c : g.captures)
		{
			c.onChatMessage(e);
		}
	}

	private static Map<String, ClueCount> clues(GameFake g, String mode) throws IOException
	{
		KillFile f = g.readIn(1L, mode, "killcounts.json", KillFile.class);
		return f == null || f.clues == null ? Collections.emptyMap() : f.clues;
	}

	private static int count(GameFake g, String mode, String tier) throws IOException
	{
		ClueCount c = clues(g, mode).get(tier);
		return c == null ? -1 : c.count;
	}

	private static List<Map<?, ?>> quarantine(GameFake g) throws IOException
	{
		Path p = g.dir.resolve(JsonWriter.QUARANTINE_FILE);
		List<Map<?, ?>> out = new ArrayList<>();
		if (Files.exists(p))
		{
			for (String l : Files.readAllLines(p, StandardCharsets.UTF_8))
			{
				out.add(new Gson().fromJson(l, Map.class));
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ the line

	@Test
	public void eachTiersLineIsRecorded() throws IOException
	{
		GameFake g = game();
		ready(g);
		String[] tiers = {"beginner", "easy", "medium", "hard", "elite", "master"};
		for (int i = 0; i < tiers.length; i++)
		{
			g.chat(line(10 + i, tiers[i]));
		}
		apply(g);
		for (int i = 0; i < tiers.length; i++)
		{
			assertEquals(tiers[i], 10 + i, count(g, "main", tiers[i]));
			assertNull(clues(g, "main").get(tiers[i]).lowerReadings);
		}
		// written in the game's tier order, not by name
		assertEquals(Arrays.asList(tiers), new ArrayList<>(clues(g, "main").keySet()));
		assertTrue(g.readIn(1L, "main", "killcounts.json", KillFile.class).killcounts.isEmpty());
	}

	@Test
	public void theWordingVariantsAreRecorded() throws IOException
	{
		GameFake g = game();
		ready(g);
		g.chat(line(1, "beginner")); // the first: "Treasure Trail", singular
		g.chat("You have completed <col=ff0000>1</col> master Treasure Trail.");
		g.chat("You have completed 4 easy Treasure Trails."); // no colour tags
		g.chat("You have completed <col=ff0000>1,234</col> medium Treasure Trails."); // a count with a comma
		g.chat("You have completed <col=ef1020>7</col> Hard Treasure Trails."); // another colour, a capital
		send(g, ChatMessageType.SPAM, line(3, "elite")); // the filtered tab
		apply(g);
		assertEquals(1, count(g, "main", "beginner"));
		assertEquals(1, count(g, "main", "master"));
		assertEquals(4, count(g, "main", "easy"));
		assertEquals(1234, count(g, "main", "medium"));
		assertEquals(7, count(g, "main", "hard"));
		assertEquals(3, count(g, "main", "elite"));

		send(g, ChatMessageType.MESBOX, line(5, "elite"));
		apply(g);
		assertEquals(5, count(g, "main", "elite"));
	}

	@Test
	public void unrelatedChatIsIgnored() throws IOException
	{
		GameFake g = game();
		ready(g);
		g.chat("Well done, you've completed the Treasure Trail!");
		g.chat("Your treasure is worth around <col=ffffff>12,345</col> coins!");
		g.chat("You have completed <col=ff0000>12</col> rumours for the Hunter Guild.");
		g.chat("Tester has completed a hard Treasure Trail and received a Ranger boots!"); // a broadcast
		g.chat("You have completed your master level challenge!");
		g.chat("You have completed <col=ff0000>3</col> hard Treasure Trails"); // cut short: no full stop
		send(g, ChatMessageType.PUBLICCHAT, line(99, "hard")); // a player typing it
		send(g, ChatMessageType.PRIVATECHAT, line(99, "hard"));
		send(g, ChatMessageType.CLAN_CHAT, line(99, "hard"));
		send(g, ChatMessageType.TRADE, line(99, "hard"));
		apply(g);
		g.logOut();
		g.executor.runAll();
		assertFalse(Files.exists(g.folder(1L, "main").resolve("killcounts.json")));
		assertTrue(quarantine(g).isEmpty());
	}

	@Test
	public void killCountsStillWorkAndNeitherKindWipesTheOther() throws IOException
	{
		GameFake g = game();
		ready(g);
		g.chat(line(5, "hard"));
		apply(g);
		g.chat("Your Zulrah kill count is: <col=ff0000>12</col>.");
		apply(g);
		KillFile f = g.readIn(1L, "main", "killcounts.json", KillFile.class);
		assertEquals(12, f.killcounts.get("Zulrah").count);
		assertEquals(5, f.clues.get("hard").count);
		assertEquals(1, f.killcounts.size()); // the clue line never became a "boss"

		g.chat(line(6, "hard"));
		apply(g);
		f = g.readIn(1L, "main", "killcounts.json", KillFile.class);
		assertEquals(12, f.killcounts.get("Zulrah").count);
		assertEquals(6, f.clues.get("hard").count);
	}

	@Test
	public void anOldFileWithoutCluesLoadsAndStaysWithoutTheFieldUntilTheFirst() throws IOException
	{
		GameFake g = game();
		g.write(1L, "main", "killcounts.json", killFile("Zulrah", 10));
		ready(g);
		g.chat("Your Zulrah kill count is: <col=ff0000>11</col>.");
		apply(g);
		String text = new String(Files.readAllBytes(g.folder(1L, "main").resolve("killcounts.json")),
			StandardCharsets.UTF_8);
		assertFalse(text.contains("clues"));
		g.chat(line(2, "easy"));
		apply(g);
		KillFile f = g.readIn(1L, "main", "killcounts.json", KillFile.class);
		assertEquals(11, f.killcounts.get("Zulrah").count);
		assertEquals(2, f.clues.get("easy").count);
	}

	// ------------------------------------------------------------------ never backwards

	@Test
	public void aLowerReadingKeepsTheHigherCountAndSaysSo() throws IOException
	{
		GameFake g = game();
		ready(g);
		g.chat(line(10, "hard"));
		apply(g);
		String first = clues(g, "main").get("hard").observedAt;

		g.chat(line(7, "hard"));
		apply(g);
		ClueCount c = clues(g, "main").get("hard");
		assertEquals(10, c.count);
		assertEquals(first, c.observedAt);
		assertEquals(1, c.lowerReadings.size());
		assertEquals(7, c.lowerReadings.get(0).count);

		g.chat(line(7, "hard")); // the same lower reading again: said once
		g.chat(line(10, "hard")); // the kept count again: nothing new
		apply(g);
		assertEquals(1, clues(g, "main").get("hard").lowerReadings.size());

		g.chat(line(11, "hard"));
		apply(g);
		c = clues(g, "main").get("hard");
		assertEquals(11, c.count);
		assertEquals(7, c.lowerReadings.get(0).count); // still on record
	}

	@Test
	public void aLowerReadingBeforeTheFileLoadedIsKeptAsideOnLogout() throws IOException
	{
		GameFake g = game();
		KillFile disk = killFile("Zulrah", 10);
		disk.clues = new TreeMap<>();
		disk.clues.put("elite", clue(20));
		g.write(1L, "main", "killcounts.json", disk);
		g.logIn(); // the file is never read before logout
		g.chat(line(3, "elite"));
		g.chat(line(1, "master"));
		g.logOut();
		g.executor.runAll();
		KillFile f = g.readIn(1L, "main", "killcounts.json", KillFile.class);
		assertEquals(20, f.clues.get("elite").count);
		assertEquals(3, f.clues.get("elite").lowerReadings.get(0).count);
		assertEquals(1, f.clues.get("master").count);
		assertEquals(10, f.killcounts.get("Zulrah").count);
	}

	@Test
	public void applyKeepsTheHigherCountAndNeverChangesAnEntryInPlace()
	{
		Map<String, ClueCount> clues = new TreeMap<>(LootCapture.TIER_ORDER);
		ClueCount saved = clue(10);
		clues.put("hard", saved);
		Map<String, ClueCount> copy = new TreeMap<>(clues); // what logout hands the executor
		List<PendingKc> pending = new ArrayList<>(Arrays.asList(new PendingKc(ALPHA, "hard", "4", "t1")));
		assertTrue(LootCapture.applyClueCounts(clues, new ArrayList<>(), pending, ALPHA));
		assertEquals(10, clues.get("hard").count);
		assertEquals(4, clues.get("hard").lowerReadings.get(0).count);
		assertNull(saved.lowerReadings);
		assertNull(copy.get("hard").lowerReadings);
		assertTrue(pending.isEmpty());
	}

	@Test
	public void cluesAreCreditedOnlyToTheirOwnAccountAndMode()
	{
		List<PendingKc> pending = new ArrayList<>(Arrays.asList(
			new PendingKc(ALPHA, "hard", "12", "t1"),
			new PendingKc(ALPHA_LEAGUE, "hard", "3", "t2"),
			new PendingKc(null, "hard", "8", "t2"),
			new PendingKc(BETA, "hard", "7", "t3"),
			new PendingKc(BETA, "easy", "x", "t3")));
		Map<String, ClueCount> clues = new TreeMap<>(LootCapture.TIER_ORDER);
		assertTrue(LootCapture.applyClueCounts(clues, new ArrayList<>(), pending, BETA));
		assertEquals(1, clues.size());
		assertEquals(7, clues.get("hard").count);
		assertNull(clues.get("hard").lowerReadings); // another key's 12 was never this one's reading
		assertTrue(pending.isEmpty());
	}

	// ------------------------------------------------------------------ a reset on the same key

	/** A saved main file: hard 100 and easy 50, from an earlier run of the account. */
	private static GameFake resetGame() throws IOException
	{
		GameFake g = game();
		KillFile disk = killFile("Zulrah", 10);
		disk.clues = new TreeMap<>();
		disk.clues.put("hard", clue(100));
		disk.clues.put("easy", clue(50));
		g.write(1L, "main", "killcounts.json", disk);
		return g;
	}

	private static KillFile saved(GameFake g) throws IOException
	{
		return g.readIn(1L, "main", "killcounts.json", KillFile.class);
	}

	@Test
	public void aRisingRunOfLowerReadingsAcrossRelogsStartsANewEpoch() throws IOException
	{
		GameFake g = resetGame();
		ready(g);
		g.chat(line(1, "hard"));
		apply(g);
		assertEquals(100, count(g, "main", "hard")); // one lower line alone is never a reset
		g.logOut();
		g.executor.runAll();

		g.logIn(); // relog; this time the line comes before the file loads, so logout saves it
		g.chat(line(2, "hard"));
		g.logOut();
		g.executor.runAll();
		assertEquals(100, count(g, "main", "hard")); // two rising: still not enough
		assertEquals(Integer.valueOf(2), clues(g, "main").get("hard").lowerRun);
		assertNull(saved(g).previousClueEpochs);

		ready(g);
		g.chat(line(3, "hard")); // the third rising reading: a new run of counts on this key
		apply(g);
		KillFile f = saved(g);
		assertEquals(3, f.clues.get("hard").count);
		assertNull(f.clues.get("hard").lowerReadings);
		assertNull(f.clues.get("easy")); // the old run's 50 no longer stands for this character
		assertEquals(1, f.previousClueEpochs.size());
		LootCapture.ClueEpoch old = f.previousClueEpochs.get(0);
		assertEquals(f.clues.get("hard").observedAt, old.endedAt);
		assertEquals(100, old.clues.get("hard").count); // archived, discarding nothing
		assertEquals(50, old.clues.get("easy").count);
		List<Integer> run = new ArrayList<>();
		for (Count c : old.clues.get("hard").lowerReadings)
		{
			run.add(c.count);
		}
		assertEquals(Arrays.asList(1, 2, 3), run);
		assertEquals(10, f.killcounts.get("Zulrah").count);

		g.chat(line(4, "hard"));
		g.chat(line(1, "easy")); // the new character's first easy: recorded as it is
		g.logOut();
		g.executor.runAll();
		f = saved(g);
		assertEquals(4, f.clues.get("hard").count);
		assertEquals(1, f.clues.get("easy").count);
		assertNull(f.clues.get("easy").lowerReadings);
		assertEquals(1, f.previousClueEpochs.size());

		ready(g); // and it survives a relog: the epoch is loaded back, not lost on the next write
		g.chat(line(5, "hard"));
		apply(g);
		assertEquals(1, saved(g).previousClueEpochs.size());
		assertEquals(5, count(g, "main", "hard"));
	}

	@Test
	public void strayOrFallingLowerReadingsNeverStartANewEpoch() throws IOException
	{
		GameFake g = resetGame();
		ready(g);
		g.chat(line(5, "hard")); // a stray
		g.chat(line(101, "hard")); // then the real count moves on: the run ends
		g.chat(line(6, "hard"));
		g.chat(line(7, "hard")); // only two rising since the count last rose
		apply(g);
		assertEquals(101, count(g, "main", "hard"));
		assertNull(saved(g).previousClueEpochs);

		g.chat(line(30, "easy"));
		g.chat(line(20, "easy"));
		g.chat(line(25, "easy")); // 30, 20, 25: not rising one after another
		g.chat(line(25, "easy")); // a repeat neither adds nor counts
		apply(g);
		assertEquals(50, count(g, "main", "easy"));
		assertEquals(3, clues(g, "main").get("easy").lowerReadings.size());
		assertEquals(Integer.valueOf(2), clues(g, "main").get("easy").lowerRun);
		assertNull(saved(g).previousClueEpochs);
	}

	// ------------------------------------------------------------------ the logout write

	private static String raw(GameFake g) throws IOException
	{
		return new String(Files.readAllBytes(g.folder(1L, "main").resolve("killcounts.json")), StandardCharsets.UTF_8);
	}

	@Test
	public void theLogoutWriteLeavesOptionalFieldsOutAsTheTickWriteDoes() throws IOException
	{
		for (boolean loaded : new boolean[]{false, true})
		{
			GameFake g = game();
			if (loaded)
			{
				ready(g);
			}
			else
			{
				g.logIn(); // logout composes from the disk
			}
			g.chat("Your Zulrah kill count is: <col=ff0000>12</col>.");
			g.logOut();
			g.executor.runAll();
			String text = raw(g);
			assertFalse(text, text.contains("clues"));
			assertFalse(text, text.contains("null"));

			if (loaded)
			{
				ready(g);
			}
			else
			{
				g.logIn();
			}
			g.chat(line(1, "hard"));
			g.logOut();
			g.executor.runAll();
			text = raw(g);
			assertTrue(text, text.contains("\"hard\""));
			assertFalse(text, text.contains("lower_readings"));
			assertFalse(text, text.contains("lower_run"));
			assertFalse(text, text.contains("previous_clue_epochs"));
			assertFalse(text, text.contains("null"));
		}
	}

	// ------------------------------------------------------------------ modes

	@Test
	public void mainAndLeagueCluesAreKeptApart() throws IOException
	{
		GameFake g = game();
		ready(g);
		g.chat(line(40, "hard"));
		apply(g);

		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL);
		g.settle();
		apply(g); // the league's own file is loaded, not main's counts kept in memory
		g.chat(line(1, "hard")); // the league's first: not a lower reading of main's 40
		g.chat(line(2, "easy"));
		apply(g);

		g.hop(301, WorldType.MEMBERS);
		g.settle();
		apply(g);
		g.chat(line(41, "hard"));
		g.logOut();
		g.executor.runAll();

		assertEquals(41, count(g, "main", "hard"));
		assertNull(clues(g, "main").get("hard").lowerReadings);
		assertEquals(-1, count(g, "main", "easy"));
		assertEquals(1, count(g, LEAGUE, "hard"));
		assertNull(clues(g, LEAGUE).get("hard").lowerReadings);
		assertEquals(2, count(g, LEAGUE, "easy"));
	}

	@Test
	public void aClueLineDuringAHopIsQuarantined() throws IOException
	{
		GameFake g = game();
		ready(g);
		g.hub.hopping();
		g.chat(line(9, "medium"));
		g.world = 302;
		g.hub.loggedIn(g.now);
		apply(g);
		g.logOut();
		g.executor.runAll();
		assertEquals(-1, count(g, "main", "medium"));
		List<Map<?, ?>> q = quarantine(g);
		assertEquals(1, q.size());
		assertEquals("clue_count", q.get(0).get("kind"));
		assertEquals(LoginState.CHANGING_WORLD, q.get(0).get("why"));
		Map<?, ?> value = (Map<?, ?>) q.get(0).get("value");
		assertEquals("medium", value.get("tier"));
		assertEquals("9", value.get("count"));
	}

	@Test
	public void aBetaWorldsClueLineIsDropped() throws IOException
	{
		GameFake g = new GameFake();
		LootCapture loot = new LootCapture(g.ctx);
		g.captures.add(loot);
		g.worldTypes = java.util.EnumSet.of(WorldType.SEASONAL, WorldType.BETA_WORLD);
		ready(g);
		g.chat(line(9, "medium"));
		assertEquals(0, loot.held()); // dropped on arrival, never held
		apply(g);
		g.logOut();
		g.executor.runAll();
		try (java.util.stream.Stream<Path> s = Files.walk(g.dir))
		{
			assertFalse(s.anyMatch(p -> p.getFileName().toString().equals("killcounts.json")));
		}
		assertTrue(quarantine(g).isEmpty());
	}

	// ------------------------------------------------------------------ the varps, raw

	@Test
	public void theClueVarpsAreRecordedRawInVars() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new VarsCapture(g.ctx));
		g.varps.put(VarPlayerID.COMPLETED_CLUES3, 12);
		g.varps.put(VarPlayerID.COMPLETED_CLUES4, 3);
		g.varps.put(VarPlayerID.COMPLETED_CLUES5, 30);
		g.logIn();
		for (int i = 0; i < 3; i++)
		{
			apply(g);
		}
		VarsCapture.Group group = g.read("vars.json", VarsCapture.File.class).groups.get("clue_counts");
		assertEquals(12, group.varps.get(VarPlayerID.COMPLETED_CLUES3).v);
		assertEquals(3, group.varps.get(VarPlayerID.COMPLETED_CLUES4).v);
		assertEquals(30, group.varps.get(VarPlayerID.COMPLETED_CLUES5).v);
		assertEquals(0, group.varps.get(VarPlayerID.COMPLETED_CLUES).v);
		assertEquals(7, group.varps.size());
	}

	private static KillFile killFile(String boss, int n)
	{
		Count c = new Count();
		c.count = n;
		c.observedAt = "2026-01-01T00:00:00Z";
		KillFile f = new KillFile();
		f.killcounts = new TreeMap<>();
		f.killcounts.put(boss, c);
		return f;
	}

	private static ClueCount clue(int n)
	{
		ClueCount c = new ClueCount();
		c.count = n;
		c.observedAt = "2026-01-01T00:00:00Z";
		return c;
	}
}
