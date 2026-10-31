package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import org.junit.Test;

/**
 * The one-time copy of the old {@code rsbuddy/<RSN>/} folder into {@code accounts/<hash>/main/} (task P2,
 * docs/plugin-mode-separation.md §5): each gate condition both ways, no overwrite, the old folder byte-identical
 * afterwards, the marker last, a damaged or unreadable file, the order before PriorFile, a copy cut short and
 * finished next login, and no second copy. Round 2: a file that failed is retried at a later login, a failed seed
 * is retried without locking itself out, and only files provably this account's are copied. Synthetic accounts only.
 */
public class LegacySeedTest
{
	private static final long HASH = -9090909090909090909L;
	private static final String NAME = "Synthetic Seed";
	private static final ProfileKey MAIN = new ProfileKey(HASH, GameMode.MAIN, NAME);
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private final Path dir;
	private GameFake.ManualExecutor executor = new GameFake.ManualExecutor();
	private final AtomicBoolean on = new AtomicBoolean(true);
	// the plugin folder cannot be found once (the next call works)
	private final AtomicBoolean rootFails = new AtomicBoolean();
	private JsonWriter json;

	public LegacySeedTest() throws IOException
	{
		dir = Files.createTempDirectory("rsbuddy-seed");
		json = writer();
	}

	/** A new session's writer on the same plugin folder (a restart or the next login after a crash). */
	private JsonWriter writer()
	{
		executor = new GameFake.ManualExecutor();
		return new JsonWriter(executor, new Gson(), () ->
		{
			if (rootFails.getAndSet(false))
			{
				throw new IOException("the plugin folder is unavailable");
			}
			return Filepath.Unchecked.getRooted(dir);
		}, on::get, t -> { });
	}

	private Path legacy()
	{
		return dir.resolve(NAME);
	}

	private Path main()
	{
		return dir.resolve(JsonWriter.ACCOUNTS).resolve(Long.toString(HASH)).resolve(GameMode.MAIN);
	}

	private static void put(Path folder, String file, String text) throws IOException
	{
		Files.createDirectories(folder);
		Files.write(folder.resolve(file), text.getBytes(StandardCharsets.UTF_8));
	}

	// two sessions of today's legacy folder: the second starts with the XP the first ended on, so both are proven
	private static final String S1 = "2026-10-26T10:00:00Z";
	private static final String IN_S1 = "2026-10-26T10:30:00Z";
	private static final String S2 = "2026-10-27T10:00:00Z";
	private static final String IN_S2 = "2026-10-27T10:30:00Z";

	private static String session(String start, String end, int xpStart, int xpEnd)
	{
		return "{\"start\": \"" + start + "\", \"end\": \"" + end + "\", \"xp_start\": {\"Attack\": " + xpStart
			+ ", \"Hitpoints\": 1154}, \"xp_end\": {\"Attack\": " + xpEnd + ", \"Hitpoints\": 1154}, \"first\": {"
			+ "\"world\": 302, \"world_types\": [\"MEMBERS\", \"PVP\"]}}";
	}

	private static String sessions(long hash, String... sessions)
	{
		return "{\"account_hash\": " + hash + ", \"sessions\": [" + String.join(", ", sessions) + "]}\n";
	}

	/** Today's legacy folder for a main-game player: its own hash, main worlds only, every time in its sessions. */
	private void legacyFolder() throws IOException
	{
		put(legacy(), "account.json", "{\"account_hash\": \"" + HASH + "\", \"name\": \"" + NAME
			+ "\", \"profile_type\": \"STANDARD\", \"world\": 302, \"world_types\": [\"MEMBERS\"]}\n");
		put(legacy(), "character.json", "{\"account_name\": \"" + NAME + "\", \"account_hash\": " + HASH
			+ ", \"world\": 302, \"world_types\": [\"MEMBERS\"]}\n");
		put(legacy(), "sessions.json", sessions(HASH, session(S1, "2026-10-26T11:00:00Z", 100, 200),
			session(S2, "2026-10-27T11:00:00Z", 200, 300)));
		put(legacy(), "killcounts.json", "{\"observed_at\": \"" + IN_S2 + "\", \"killcounts\": {\"Zulrah\": {\"count\": 11,"
			+ " \"observed_at\": \"" + IN_S1 + "\"}, \"Vorkath\": {\"count\": 5, \"observed_at\": \"" + IN_S2 + "\"}}}");
		put(legacy(), "loot.jsonl", "{\"observed_at\": \"" + IN_S1 + "\", \"npc_name\": \"Zulrah\", \"items\": []}\r\n"
			+ "{\"observed_at\": \"2026-10-27T11:00:30Z\", \"npc_name\": \"Vorkath\"}\r\n"); // flushed after logout
		put(legacy(), "item_gains_archive_2026-10-27T10-00-00Z_1.json", "{\"sessions\": [{\"session_start\": \"" + S1
			+ "\", \"gains\": [{\"time\": \"" + IN_S1 + "\", \"id\": 995, \"qty\": 10}]}]}");
	}

	private static Map<String, String> digest(Path folder) throws IOException
	{
		Map<String, String> out = new TreeMap<>();
		if (!Files.isDirectory(folder))
		{
			return out;
		}
		try (Stream<Path> s = Files.walk(folder))
		{
			for (Path p : (Iterable<Path>) s::iterator)
			{
				if (Files.isRegularFile(p))
				{
					out.put(folder.relativize(p).toString(), sha(Files.readAllBytes(p)));
				}
			}
		}
		return out;
	}

	private static String sha(byte[] b)
	{
		try
		{
			return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(b));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError(e);
		}
	}

	private JsonObject marker() throws IOException
	{
		Path p = main().resolve(LegacySeed.MARKER);
		return Files.exists(p) ? new JsonParser().parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
			.getAsJsonObject() : null;
	}

	private static List<String> strings(JsonArray a)
	{
		List<String> out = new ArrayList<>();
		for (JsonElement e : a)
		{
			out.add(e.isJsonObject() ? e.getAsJsonObject().get("file").getAsString() : e.getAsString());
		}
		return out;
	}

	private static JsonObject skipped(JsonObject marker, String file)
	{
		for (JsonElement e : marker.getAsJsonArray("skipped"))
		{
			if (e.getAsJsonObject().get("file").getAsString().equals(file))
			{
				return e.getAsJsonObject();
			}
		}
		return null;
	}

	private LegacySeed.Outcome runDirect(ProfileKey key) throws IOException
	{
		return json.legacy.run(Filepath.Unchecked.getRooted(dir), key, T0.toString());
	}

	/** The files only the seed would have put into main: everything but the identity files. */
	private Map<String, String> copiedInto() throws IOException
	{
		Map<String, String> m = digest(main());
		m.remove(JsonWriter.MODE_FILE);
		m.remove(LegacySeed.MARKER);
		m.remove(LegacySeed.JOURNAL);
		return m;
	}

	private static Map<String, Object> observed()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("world_types", Collections.singletonList("MEMBERS"));
		m.put("profile_type", "STANDARD");
		return m;
	}

	// ---- the copy itself

	@Test
	public void theFirstSettleCopiesEveryFileUnchangedAndMarksItLast() throws IOException
	{
		legacyFolder();
		Map<String, String> before = digest(legacy());
		List<String> seenAtEachCopy = new ArrayList<>();
		json.legacy.afterCopy = f ->
		{
			assertFalse("the marker must wait for every copy", Files.exists(main().resolve(LegacySeed.MARKER)));
			assertTrue("each copy is in place before the next", Files.exists(main().resolve(f)));
			seenAtEachCopy.add(f);
		};
		json.identify(MAIN, observed(), T0, true);
		executor.runAll();

		assertEquals(before, digest(legacy())); // the old folder is byte-identical
		assertEquals(before, copiedInto()); // and the main folder holds exactly its files
		JsonObject marker = marker();
		assertEquals(NAME, marker.get("legacy_folder").getAsString()); // the field I1's importer reads
		assertEquals(Long.toString(HASH), marker.get("account_hash").getAsString());
		assertEquals(new ArrayList<>(before.keySet()), strings(marker.getAsJsonArray("copied")));
		assertEquals(0, marker.getAsJsonArray("skipped").size());
		assertFalse(marker.get("resumed").getAsBoolean());
		assertEquals(new ArrayList<>(before.keySet()), seenAtEachCopy);
		assertTrue(Files.exists(main().resolve(JsonWriter.MODE_FILE))); // identity still written, after the seed
	}

	@Test
	public void theSeedRunsBeforeTheKeysFirstCaptureWriteAndIdentity() throws IOException
	{
		legacyFolder();
		List<String> present = new ArrayList<>();
		json.legacy.afterCopy = f ->
		{
			for (String other : Arrays.asList("position.json", JsonWriter.MODE_FILE))
			{
				if (Files.exists(main().resolve(other)))
				{
					present.add(other);
				}
			}
		};
		json.write(MAIN, "position.json", Collections.singletonMap("x", 1)); // the first touch of the key is a write
		json.identify(MAIN, observed(), T0, true);
		executor.runOne();
		assertNotNull("the seed ran in the key's first task", marker());
		assertTrue(Files.exists(main().resolve("position.json")));
		assertEquals(Collections.emptyList(), present);
		executor.runAll();
		assertTrue(Files.exists(main().resolve(JsonWriter.MODE_FILE)));
	}

	@Test
	public void theSeedFinishesBeforeTheFirstPriorFileRead() throws IOException
	{
		GameFake g = new GameFake();
		Path old = g.dir.resolve(g.name);
		put(old, "account.json", "{\"account_hash\": \"" + g.accountHash + "\"}");
		put(old, "sessions.json", sessions(g.accountHash, session(S1, "2026-10-26T11:00:00Z", 100, 200)));
		put(old, "killcounts.json", "{\"killcounts\": {\"Zulrah\": {\"count\": 11, \"observed_at\": \"" + IN_S1 + "\"}}}");
		ProfileKey key = new ProfileKey(g.accountHash, GameMode.MAIN, g.name);
		PriorFile<LootCapture.KillFile> prior = new PriorFile<>(g.ctx, "killcounts.json", LootCapture.KillFile.class);
		prior.ensure(key); // the very first thing to touch the key
		g.executor.runAll();
		assertEquals(11, prior.get().killcounts.get("Zulrah").count);
	}

	@Test
	public void killCountsCarryOnFromTheOldFolderThroughTheCaptureFlow() throws IOException
	{
		GameFake g = new GameFake();
		Path old = g.dir.resolve(g.name);
		put(old, "account.json", "{\"account_hash\": \"" + g.accountHash + "\", \"world_types\": [\"MEMBERS\"]}");
		put(old, "sessions.json", sessions(g.accountHash, session(S1, "2026-10-26T11:00:00Z", 100, 200)));
		put(old, "killcounts.json", "{\"observed_at\": \"" + IN_S1 + "\", \"killcounts\": {\"Zulrah\": {\"count\": 11},"
			+ " \"Vorkath\": {\"count\": 5}}}");
		Map<String, String> before = digest(old);
		g.captures.add(new LootCapture(g.ctx));
		g.logIn();
		g.executor.runAll();
		g.tick();
		g.chat("Your Zulrah kill count is: <col=ff0000>12</col>.");
		g.tick();
		g.logOut();
		g.executor.runAll();

		LootCapture.KillFile kc = g.readIn(g.accountHash, GameMode.MAIN, "killcounts.json", LootCapture.KillFile.class);
		assertEquals(12, kc.killcounts.get("Zulrah").count);
		assertEquals(5, kc.killcounts.get("Vorkath").count); // carried on from the old folder
		assertTrue(Files.exists(g.folder(g.accountHash, GameMode.MAIN).resolve(LegacySeed.MARKER)));
		assertEquals(before, digest(old));
	}

	// ---- gate 1: the main folder has no capture files yet

	@Test
	public void aMainFolderWithCaptureFilesIsNotSeeded() throws IOException
	{
		legacyFolder();
		put(main(), "character.json", "{\"from\": \"a capture\"}");
		Map<String, String> before = digest(legacy());
		Map<String, String> mainBefore = digest(main());
		assertEquals(LegacySeed.Outcome.TARGET_IN_USE, runDirect(MAIN));
		assertEquals(mainBefore, digest(main()));
		assertNull(marker());
		assertEquals(before, digest(legacy()));
	}

	@Test
	public void aMainFolderWithOnlyItsModeFileIsSeeded() throws IOException
	{
		legacyFolder();
		put(main(), JsonWriter.MODE_FILE, "{\"mode\": \"main\"}");
		assertEquals(LegacySeed.Outcome.SEEDED, runDirect(MAIN));
		assertEquals(digest(legacy()), copiedInto());
	}

	// ---- gate 2: the legacy folder names this account hash

	@Test
	public void eachHashFileAloneIsEnoughToSeed() throws IOException
	{
		for (String only : LegacySeed.HASH_FILES)
		{
			clear();
			legacyFolder();
			for (String other : LegacySeed.HASH_FILES)
			{
				if (!other.equals(only))
				{
					put(legacy(), other, "{\"world_types\": [\"MEMBERS\"]}");
				}
			}
			assertEquals(only, LegacySeed.Outcome.SEEDED, runDirect(MAIN));
		}
	}

	@Test
	public void noHashFileNamingTheAccountIsNotSeeded() throws IOException
	{
		legacyFolder();
		for (String f : LegacySeed.HASH_FILES)
		{
			put(legacy(), f, "{\"world_types\": [\"MEMBERS\"]}");
		}
		assertEquals(LegacySeed.Outcome.OTHER_ACCOUNT, runDirect(MAIN));
		assertFalse(Files.exists(main()));
	}

	@Test
	public void anyHashFileNamingAnotherAccountIsNotSeeded() throws IOException
	{
		for (String f : LegacySeed.HASH_FILES)
		{
			clear();
			legacyFolder();
			put(legacy(), f, "{\"account_hash\": \"77\"}");
			Map<String, String> before = digest(legacy());
			assertEquals(f, LegacySeed.Outcome.OTHER_ACCOUNT, runDirect(MAIN));
			assertFalse(Files.exists(main()));
			assertEquals(before, digest(legacy()));
		}
	}

	// ---- gate 3: nothing but the main game recorded

	@Test
	public void anEventWorldInAnyFileOrLineIsNotSeeded() throws IOException
	{
		for (String type : LegacySeed.EVENT_WORLDS)
		{
			for (String where : Arrays.asList("sessions.json", "loot.jsonl", "item_gains_archive_x.json"))
			{
				clear();
				legacyFolder();
				String nested = "{\"sessions\": [{\"first\": {\"world_types\": [\"MEMBERS\", \"" + type + "\"]}}]}";
				if (where.equals("sessions.json"))
				{
					put(legacy(), where, "{\"account_hash\": " + HASH + ", \"sessions\": [{\"first\": {\"world_types\":"
						+ " [\"MEMBERS\", \"" + type + "\"]}}]}");
				}
				else if (where.equals("loot.jsonl"))
				{
					put(legacy(), where, "{\"npc_name\": \"Zulrah\"}\n" + nested.replace('\n', ' ') + "\n");
				}
				else
				{
					put(legacy(), where, nested);
				}
				Map<String, String> before = digest(legacy());
				assertEquals(type + " in " + where, LegacySeed.Outcome.NOT_MAIN_GAME, runDirect(MAIN));
				assertFalse(Files.exists(main()));
				assertEquals(before, digest(legacy()));
			}
		}
	}

	@Test
	public void aLeagueProfileTypeIsNotSeeded() throws IOException
	{
		legacyFolder();
		put(legacy(), "account.json", "{\"account_hash\": \"" + HASH + "\", \"profile_type\": \"DEMONIC_PACTS_LEAGUE\"}");
		assertEquals(LegacySeed.Outcome.NOT_MAIN_GAME, runDirect(MAIN));
		assertFalse(Files.exists(main()));
	}

	@Test
	public void mainWorldTypesAloneAreSeeded() throws IOException
	{
		legacyFolder(); // MEMBERS and PVP: both main-game worlds
		assertEquals(LegacySeed.Outcome.SEEDED, runDirect(MAIN));
	}

	// ---- which keys

	@Test
	public void onlyAMainKeyIsSeeded() throws IOException
	{
		legacyFolder();
		ProfileKey league = new ProfileKey(HASH, "seasonal-demonic_pacts_league", NAME);
		json.identify(league, observed(), T0, true);
		json.write(league, "vars.json", Collections.singletonMap("a", 1));
		executor.runAll();
		assertFalse(Files.exists(main()));
		Path leagueDir = main().resolveSibling(league.mode);
		assertFalse(Files.exists(leagueDir.resolve("killcounts.json")));
		assertFalse(Files.exists(leagueDir.resolve(LegacySeed.MARKER)));
		assertEquals(LegacySeed.Outcome.NOT_MAIN, runDirect(new ProfileKey(HASH, GameMode.MAIN, "accounts")));
	}

	@Test
	public void noLegacyFolderDoesNothing() throws IOException
	{
		json.identify(MAIN, observed(), T0, true);
		executor.runAll();
		assertEquals(Collections.singleton(JsonWriter.MODE_FILE), digest(main()).keySet());
	}

	@Test
	public void nothingIsCopiedWhileWriteFilesIsOffAndTheFirstWriteAfterwardSeedsFirst() throws IOException
	{
		legacyFolder();
		put(legacy(), "vars.json", "{\"observed_at\": \"" + IN_S2 + "\"}");
		on.set(false);
		json.identify(MAIN, observed(), T0, true);
		json.readAsync(MAIN, "vars.json", JsonObject.class, v -> { }); // a capture now holds vars.json as missing
		executor.runAll();
		assertFalse(Files.exists(main()));
		on.set(true);
		json.write(MAIN, "position.json", Collections.singletonMap("x", 1));
		executor.runAll();
		JsonObject marker = marker();
		assertNotNull(marker);
		Map<String, String> others = digest(legacy());
		others.remove("vars.json");
		assertEquals(others, copiedInto().entrySet().stream().filter(e -> !e.getKey().equals("position.json"))
			.collect(TreeMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll));
		assertTrue(Files.exists(main().resolve("position.json")));
		// the capture's missing vars.json would overwrite a copy, so it waits for a session that has not read it
		assertTrue(skipped(marker, "vars.json").get("retry").getAsBoolean());
		json = writer();
		json.identify(MAIN, observed(), T0, true);
		executor.runAll();
		assertEquals(digest(legacy()).get("vars.json"), digest(main()).get("vars.json"));
		assertTrue(strings(marker().getAsJsonArray("copied")).contains("vars.json"));
	}

	// ---- never overwrite, damaged and unreadable files

	@Test
	public void aTargetThatExistsIsNeverOverwrittenButSkippedAndListed() throws IOException
	{
		legacyFolder();
		// a copy that was cut short, then a capture wrote kill counts of its own
		put(main(), LegacySeed.JOURNAL, "{\"legacy_folder\": \"" + NAME + "\"}");
		put(main(), "killcounts.json", "{\"killcounts\": {\"Zulrah\": {\"count\": 12}}}");
		byte[] theirs = Files.readAllBytes(main().resolve("killcounts.json"));
		Map<String, String> before = digest(legacy());
		assertEquals(LegacySeed.Outcome.SEEDED, runDirect(MAIN));

		assertTrue(Arrays.equals(theirs, Files.readAllBytes(main().resolve("killcounts.json"))));
		JsonObject marker = marker();
		assertEquals(Collections.singletonList("killcounts.json"), strings(marker.getAsJsonArray("skipped")));
		assertTrue(marker.getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString()
			.contains("not overwritten"));
		assertFalse(strings(marker.getAsJsonArray("copied")).contains("killcounts.json"));
		assertTrue(marker.get("resumed").getAsBoolean());
		Map<String, String> others = new TreeMap<>(before);
		others.remove("killcounts.json");
		Map<String, String> got = copiedInto();
		got.remove("killcounts.json");
		assertEquals(others, got);
		assertEquals(before, digest(legacy()));
	}

	@Test
	public void aDamagedFileIsLeftInTheOldFolderAndTheRestCopied() throws IOException
	{
		legacyFolder();
		put(legacy(), "vars.json", "{\"broken");
		assertEquals(LegacySeed.Outcome.SEEDED, runDirect(MAIN));
		assertFalse(Files.exists(main().resolve("vars.json")));
		JsonObject skip = skipped(marker(), "vars.json");
		assertTrue(skip.get("reason").getAsString().contains("damaged"));
		assertFalse(skip.get("retry").getAsBoolean());
		Map<String, String> rest = digest(legacy());
		rest.remove("vars.json");
		assertEquals(rest, copiedInto());
	}

	@Test
	public void oneUnreadableFileDoesNotStopTheRestAndIsCopiedAtTheNextLogin() throws IOException
	{
		legacyFolder();
		put(legacy(), "bank.json", "{\"exported_at\": \"" + IN_S2 + "\", \"items\": []}");
		Path bank = legacy().resolve("bank.json");
		Map<String, String> before = digest(legacy());
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		LegacySeed.Outcome outcome;
		// Windows byte-range locks keep other readers out; elsewhere the file is made unreadable
		try (FileChannel ch = windows ? FileChannel.open(bank, StandardOpenOption.WRITE) : null;
			FileLock lock = windows ? ch.lock() : null)
		{
			if (!windows)
			{
				assumeTrue(bank.toFile().setReadable(false) && !Files.isReadable(bank));
			}
			outcome = runDirect(MAIN);
		}
		finally
		{
			bank.toFile().setReadable(true);
		}
		assertEquals(LegacySeed.Outcome.SEEDED, outcome);
		JsonObject marker = marker();
		assertEquals(Collections.singletonList("bank.json"), strings(marker.getAsJsonArray("skipped")));
		assertTrue(skipped(marker, "bank.json").get("retry").getAsBoolean());
		assertFalse(strings(marker.getAsJsonArray("copied")).contains("bank.json")); // so the importer still reads it
		assertFalse(Files.exists(main().resolve("bank.json")));
		assertFalse(Files.exists(main().resolve("bank.json" + LegacySeed.SEED_TMP)));
		Map<String, String> rest = new TreeMap<>(before);
		rest.remove("bank.json");
		assertEquals(rest, copiedInto());
		assertEquals(before, digest(legacy()));

		// the next login, with the file free again: copied, confirmed and added to the marker
		json.identify(MAIN, observed(), T0.plusSeconds(60), true);
		executor.runAll();
		assertEquals(before, copiedInto());
		marker = marker();
		assertEquals(new ArrayList<>(before.keySet()), new ArrayList<>(new TreeSet<>(strings(marker.getAsJsonArray(
			"copied")))));
		assertEquals(0, marker.getAsJsonArray("skipped").size());
		assertEquals(Collections.singletonList("bank.json"), strings(marker.getAsJsonArray("retried").get(0)
			.getAsJsonObject().getAsJsonArray("copied")));
		assertEquals(LegacySeed.Outcome.ALREADY_SEEDED, runDirect(MAIN));
		assertEquals(before, digest(legacy()));
	}

	@Test
	public void aLockedLootFileWhoseTargetACaptureThenStartedIsLeftForTheImporter() throws IOException
	{
		legacyFolder();
		Path loot = legacy().resolve("loot.jsonl");
		byte[] theirs = Files.readAllBytes(loot);
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		try (FileChannel ch = windows ? FileChannel.open(loot, StandardOpenOption.WRITE) : null;
			FileLock lock = windows ? ch.lock() : null)
		{
			if (!windows)
			{
				assumeTrue(loot.toFile().setReadable(false) && !Files.isReadable(loot));
			}
			json.identify(MAIN, observed(), T0, true);
			executor.runAll();
		}
		finally
		{
			loot.toFile().setReadable(true);
		}
		assertTrue(skipped(marker(), "loot.jsonl").get("retry").getAsBoolean());
		json.appendLine(MAIN, "loot.jsonl", Collections.singletonMap("npc_name", "Kraken")); // this session's drop
		executor.runAll();
		byte[] ours = Files.readAllBytes(main().resolve("loot.jsonl"));

		json = writer(); // the next session: the target is taken, so the old file stays where the importer reads it
		json.identify(MAIN, observed(), T0, true);
		executor.runAll();
		assertTrue(Arrays.equals(ours, Files.readAllBytes(main().resolve("loot.jsonl"))));
		assertTrue(Arrays.equals(theirs, Files.readAllBytes(loot)));
		JsonObject skip = skipped(marker(), "loot.jsonl");
		assertTrue(skip.get("reason").getAsString().contains("not overwritten"));
		assertFalse(skip.get("retry").getAsBoolean());
		assertFalse(strings(marker().getAsJsonArray("copied")).contains("loot.jsonl"));
	}

	// ---- a seed that could not go on at all

	@Test
	public void aJournalWriteThatFailsIsRetriedWithoutLockingTheCopyOut() throws IOException
	{
		legacyFolder();
		put(legacy(), "farming.json", "{\"observed_at\": \"" + IN_S2 + "\"}");
		Map<String, String> before = digest(legacy());
		LegacySeed.Out real = json.legacy.out;
		AtomicBoolean fail = new AtomicBoolean(true);
		json.legacy.out = (d, f, text) ->
		{
			if (f.equals(LegacySeed.JOURNAL) && fail.getAndSet(false))
			{
				throw new IOException("the disk was busy");
			}
			real.write(d, f, text);
		};
		AtomicLong now = new AtomicLong();
		json.clock = now::get;
		// the seed fails; the login's captures carry on: kill counts are read (none) and written afresh, and
		// farming.json is read (none)
		json.identify(MAIN, observed(), T0, true);
		json.readAsync(MAIN, "killcounts.json", JsonObject.class, v -> { });
		json.write(MAIN, "killcounts.json", Collections.singletonMap("killcounts", Collections.emptyMap()));
		json.readAsync(MAIN, "farming.json", JsonObject.class, v -> { });
		executor.runAll();
		assertNull(marker());
		assertFalse(Files.exists(main().resolve(LegacySeed.JOURNAL)));
		byte[] ours = Files.readAllBytes(main().resolve("killcounts.json"));

		now.addAndGet(json.seedRetryNanos);
		json.write(MAIN, "position.json", Collections.singletonMap("x", 1));
		executor.runOne(); // tried again first: the files in main are this session's own, not an earlier session's
		JsonObject marker = marker();
		assertNotNull("not refused as TARGET_IN_USE", marker);
		assertTrue(Files.exists(main().resolve(LegacySeed.JOURNAL)));

		// the capture's kill counts are never replaced, and farming.json waits for a session that has not read it
		assertTrue(Arrays.equals(ours, Files.readAllBytes(main().resolve("killcounts.json"))));
		assertFalse(skipped(marker, "killcounts.json").get("retry").getAsBoolean());
		assertTrue(skipped(marker, "farming.json").get("retry").getAsBoolean());
		assertFalse(Files.exists(main().resolve("farming.json")));
		Map<String, String> rest = new TreeMap<>(before);
		rest.remove("killcounts.json");
		rest.remove("farming.json");
		Map<String, String> got = copiedInto();
		got.remove("killcounts.json");
		got.remove("position.json");
		assertEquals(rest, got);

		json = writer(); // the next session finishes it
		json.identify(MAIN, observed(), T0, true);
		executor.runAll();
		assertEquals(before.get("farming.json"), digest(main()).get("farming.json"));
		assertTrue(strings(marker().getAsJsonArray("copied")).contains("farming.json"));
		assertEquals(before, digest(legacy()));
	}

	@Test
	public void aRetryAfterAFailureStillRefusesAMainFolderWithAnEarlierSessionsCaptures() throws IOException
	{
		legacyFolder();
		put(main(), "sessions.json", "{\"from\": \"an earlier session\"}");
		json.seedRetryNanos = 0;
		rootFails.set(true); // the seed's first try cannot even find the plugin folder
		json.write(MAIN, "sessions.json", Collections.singletonMap("sessions", Collections.emptyList()));
		json.write(MAIN, "position.json", Collections.singletonMap("x", 1));
		executor.runAll();
		// sessions.json was there before this session rewrote it, so the retry still finds the folder in use
		assertNull(marker());
		assertFalse(Files.exists(main().resolve(LegacySeed.JOURNAL)));
		assertFalse(Files.exists(main().resolve("killcounts.json")));
	}

	@Test
	public void aFailedSeedWaitsBeforeItRunsAgain() throws IOException
	{
		legacyFolder();
		AtomicLong now = new AtomicLong();
		json.clock = now::get;
		rootFails.set(true);
		json.write(MAIN, "position.json", Collections.singletonMap("x", 1));
		json.write(MAIN, "position.json", Collections.singletonMap("x", 2));
		executor.runAll();
		assertNull(marker()); // not run again straight away
		now.addAndGet(json.seedRetryNanos);
		json.write(MAIN, "position.json", Collections.singletonMap("x", 3));
		executor.runAll();
		assertNotNull(marker());
	}

	// ---- only files provably this account's

	@Test
	public void aNameTakenOverByAnotherAccountLeavesTheOlderAccountsFilesInTheOldFolder() throws IOException
	{
		// account A played as NAME and changed name; this account (HASH) took NAME, and its client rewrote the identity
		// files and sessions.json's hash but kept A's session (with A's XP), kill counts and loot
		String a = session("2026-10-20T10:00:00Z", "2026-10-20T11:00:00Z", 900000, 950000);
		String ours = session(S2, "2026-10-27T11:00:00Z", 100, 300);
		legacyFolder();
		put(legacy(), "sessions.json", sessions(HASH, a, ours));
		put(legacy(), "killcounts.json", "{\"observed_at\": \"2026-10-20T10:30:00Z\", \"killcounts\": {}}");
		put(legacy(), "loot.jsonl", "{\"observed_at\": \"2026-10-20T10:30:00Z\"}\n{\"observed_at\": \"" + IN_S2 + "\"}\n");
		put(legacy(), "appearance.json", "{\"kit_last_seen\": {\"1\": 3}}"); // no times at all
		put(legacy(), "farming.json", "{\"observed_at\": \"" + IN_S2 + "\"}"); // only this account's session
		put(legacy(), "item_gains_archive_2026-10-27T10-00-00Z_1.json", "{\"sessions\": [{\"session_start\": \""
			+ S2 + "\", \"gains\": [{\"time\": \"" + IN_S2 + "\"}]}]}");
		Map<String, String> before = digest(legacy());
		assertEquals(LegacySeed.Outcome.SEEDED, runDirect(MAIN));

		List<String> copied = strings(marker().getAsJsonArray("copied"));
		assertEquals(Arrays.asList("account.json", "character.json", "farming.json",
			"item_gains_archive_2026-10-27T10-00-00Z_1.json"), copied);
		for (String left : Arrays.asList("appearance.json", "killcounts.json", "loot.jsonl", "sessions.json"))
		{
			JsonObject skip = skipped(marker(), left);
			assertNotNull(left, skip);
			assertTrue(left, skip.get("reason").getAsString().startsWith("not shown to be this account's"));
			assertFalse(left, skip.get("retry").getAsBoolean());
			assertFalse(left, Files.exists(main().resolve(left)));
		}
		assertEquals(before, digest(legacy()));
		assertEquals(LegacySeed.Outcome.ALREADY_SEEDED, runDirect(MAIN)); // and never retried
	}

	@Test
	public void xpGainedElsewhereBetweenSessionsEndsTheProofThere() throws IOException
	{
		legacyFolder();
		put(legacy(), "sessions.json", sessions(HASH, session(S1, "2026-10-26T11:00:00Z", 100, 200),
			session(S2, "2026-10-27T11:00:00Z", 250, 300))); // 50 XP gained on another device in between
		assertEquals(LegacySeed.Outcome.SEEDED, runDirect(MAIN));
		// kill counts, loot, the archive and sessions.json record times in S1; the identity files name the account
		assertEquals(Arrays.asList("account.json", "character.json"), strings(marker().getAsJsonArray("copied")));
	}

	@Test
	public void timesJustOutsideASessionAreWithinItsGraceButNoFurther() throws IOException
	{
		legacyFolder();
		put(legacy(), "vars.json", "{\"observed_at\": \"2026-10-27T11:01:00Z\"}"); // the logout flush, a minute late
		put(legacy(), "farming.json", "{\"observed_at\": \"2026-10-27T11:01:01Z\"}");
		assertEquals(LegacySeed.Outcome.SEEDED, runDirect(MAIN));
		assertTrue(Files.exists(main().resolve("vars.json")));
		assertFalse(Files.exists(main().resolve("farming.json")));
	}

	// ---- cut short, and once only

	@Test
	public void aCopyCutShortLeavesNoMarkerAndTheNextLoginFinishesItWithoutDuplicates() throws IOException
	{
		legacyFolder();
		Map<String, String> before = digest(legacy());
		List<String> done = new ArrayList<>();
		json.legacy.afterCopy = f ->
		{
			done.add(f);
			if (done.size() == 2)
			{
				throw new IllegalStateException("the client closed mid-copy");
			}
		};
		json.identify(MAIN, observed(), T0, true);
		executor.runAll();
		assertNull(marker());
		assertEquals(2, copiedInto().size());

		json = writer(); // the next session
		json.identify(MAIN, observed(), T0, true);
		executor.runAll();
		JsonObject marker = marker();
		assertNotNull(marker);
		assertTrue(marker.get("resumed").getAsBoolean());
		assertEquals(new ArrayList<>(before.keySet()), strings(marker.getAsJsonArray("copied")));
		assertEquals(0, marker.getAsJsonArray("skipped").size());
		assertEquals(before, copiedInto()); // loot.jsonl and every other file exactly once
		assertEquals(before, digest(legacy()));
	}

	@Test
	public void aSecondLoginOrSessionDoesNotSeedAgain() throws IOException
	{
		legacyFolder();
		json.identify(MAIN, observed(), T0, true);
		executor.runAll();
		byte[] firstMarker = Files.readAllBytes(main().resolve(LegacySeed.MARKER));
		// the session's captures move on, and something new appears in the old folder
		put(main(), "killcounts.json", "{\"killcounts\": {\"Zulrah\": {\"count\": 40}}}");
		put(legacy(), "farming.json", "{}");
		Map<String, String> mainNow = digest(main());

		json.identify(MAIN, observed(), T0.plusSeconds(60), true); // a relogin in the same session
		json.readAsync(MAIN, "vars.json", JsonObject.class, v -> { });
		executor.runAll();
		json = writer(); // and the next session
		json.identify(MAIN, observed(), T0.plusSeconds(120), true);
		executor.runAll();
		assertEquals(LegacySeed.Outcome.ALREADY_SEEDED, runDirect(MAIN));

		Map<String, String> mainAfter = digest(main());
		mainAfter.remove(JsonWriter.MODE_FILE); // last_seen moves on
		mainNow.remove(JsonWriter.MODE_FILE);
		assertEquals(mainNow, mainAfter);
		assertTrue(Arrays.equals(firstMarker, Files.readAllBytes(main().resolve(LegacySeed.MARKER))));
		assertFalse(Files.exists(main().resolve("farming.json")));
	}

	private void clear() throws IOException
	{
		if (!Files.exists(dir))
		{
			return;
		}
		try (Stream<Path> s = Files.walk(dir))
		{
			Path[] all = s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toArray(Path[]::new);
			for (Path p : all)
			{
				if (!p.equals(dir))
				{
					Files.delete(p);
				}
			}
		}
	}

	/**
	 * Writes the synthetic importer fixture {@code tests/fixtures/synthetic/legacy_seed/rsbuddy/}: the legacy folder
	 * of {@code tests/fixtures/synthetic/mode_layout/} seeded into its main folder by the real writer. Skipped unless
	 * RSBUDDY_SEED_FIXTURE_OUT names the folder to write into, for example
	 * {@code RSBUDDY_SEED_FIXTURE_OUT=../tests/fixtures/synthetic/legacy_seed/rsbuddy gradlew test --no-daemon}.
	 */
	@Test
	public void writeTheSeedFixture() throws IOException
	{
		String out = System.getenv("RSBUDDY_SEED_FIXTURE_OUT");
		assumeTrue(out != null && !out.isEmpty());
		String hero = "Synthetic Hero";
		Path source = Paths.get("..", "tests", "fixtures", "synthetic", "mode_layout", "rsbuddy", hero);
		try (Stream<Path> s = Files.list(source))
		{
			for (Path p : (Iterable<Path>) s::iterator)
			{
				Files.createDirectories(dir.resolve(hero));
				Files.copy(p, dir.resolve(hero).resolve(p.getFileName().toString()));
			}
		}
		// the session the legacy captures were made in, so every file is shown to be this account's
		put(dir.resolve(hero), "sessions.json", sessions(-4242424242424242424L, session("2026-10-21T09:55:00Z",
			"2026-10-21T10:06:00Z", 100, 200)));
		ProfileKey key = new ProfileKey(-4242424242424242424L, GameMode.MAIN, hero);
		Map<String, Object> seen = new LinkedHashMap<>();
		seen.put("world", 302);
		seen.put("world_types", Collections.singletonList("MEMBERS"));
		seen.put("profile_type", "STANDARD");
		json.identify(key, seen, Instant.parse("2026-10-28T12:00:00Z"), true);
		executor.runAll();
		if (marker(key) == null)
		{
			fail("the fixture was not seeded");
		}
		Path target = Paths.get(out);
		try (Stream<Path> files = Files.walk(dir))
		{
			for (Path p : (Iterable<Path>) files::iterator)
			{
				Path to = target.resolve(dir.relativize(p).toString());
				if (Files.isDirectory(p))
				{
					Files.createDirectories(to);
				}
				else
				{
					Files.copy(p, to);
				}
			}
		}
	}

	private JsonObject marker(ProfileKey key)
	{
		Path p = dir.resolve(JsonWriter.ACCOUNTS).resolve(key.hashText()).resolve(key.mode).resolve(LegacySeed.MARKER);
		return Files.exists(p) ? new JsonObject() : null;
	}
}
