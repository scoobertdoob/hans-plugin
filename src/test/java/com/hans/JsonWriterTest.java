package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.client.util.Filepath;
import org.junit.Test;

/** The mode folder layout: accounts/&lt;hash&gt;/&lt;mode&gt;/, mode.json and names.json. Synthetic data only. */
public class JsonWriterTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private final Path dir;
	private final GameFake.ManualExecutor executor = new GameFake.ManualExecutor();
	private final AtomicBoolean on = new AtomicBoolean(true);
	private final JsonWriter json;

	public JsonWriterTest() throws IOException
	{
		dir = Files.createTempDirectory("rsbuddy-writer");
		json = new JsonWriter(executor, new Gson(), () -> Filepath.Unchecked.getRooted(dir), on::get, t -> { });
	}

	private static Map<String, Object> observed(String... types)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("world_types", Arrays.asList(types));
		m.put("profile_type", "STANDARD");
		return m;
	}

	private JsonObject file(String... path) throws IOException
	{
		Path p = dir;
		for (String s : path)
		{
			p = p.resolve(s);
		}
		return Files.exists(p) ? new JsonParser().parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
			.getAsJsonObject() : null;
	}

	@Test
	public void aReservedWindowsNameStillWrites() throws IOException
	{
		ProfileKey con = new ProfileKey(-42L, GameMode.MAIN, "Con");
		json.write(con, "character.json", Collections.singletonMap("account_name", "Con"));
		executor.runAll();
		assertEquals("Con", file("accounts", "-42", "main", "character.json").get("account_name").getAsString());
	}

	@Test
	public void theModeFileIsQueuedBeforeTheFirstWriteAndKeepsFirstSeen() throws IOException
	{
		ProfileKey key = new ProfileKey(-7L, "seasonal-demonic_pacts_league", "Synthetic");
		json.identify(key, observed("MEMBERS", "SEASONAL"), T0, true);
		json.write(key, "vars.json", Collections.singletonMap("a", 1));
		executor.runOne();
		assertNull(file("accounts", "-7", "seasonal-demonic_pacts_league", "vars.json"));
		JsonObject mode = file("accounts", "-7", "seasonal-demonic_pacts_league", "mode.json");
		assertEquals(2, mode.get("layout").getAsInt());
		assertEquals("-7", mode.get("account_hash").getAsString()); // a string, as account.json writes it
		assertEquals("seasonal-demonic_pacts_league", mode.get("mode").getAsString());
		assertEquals("Synthetic", mode.get("name").getAsString());
		assertEquals("SEASONAL", mode.getAsJsonArray("world_types").get(1).getAsString());
		assertEquals(T0.toString(), mode.get("first_seen").getAsString());
		executor.runAll();

		// unchanged: nothing more is queued; at the next settle last_seen moves on and first_seen is kept
		json.identify(key, observed("MEMBERS", "SEASONAL"), T0.plusSeconds(5), false);
		assertEquals(0, executor.pendingCount());
		json.identify(key, observed("MEMBERS", "SEASONAL"), T0.plusSeconds(60), true);
		executor.runAll();
		mode = file("accounts", "-7", "seasonal-demonic_pacts_league", "mode.json");
		assertEquals(T0.toString(), mode.get("first_seen").getAsString());
		assertEquals(T0.plusSeconds(60).toString(), mode.get("last_seen").getAsString());
	}

	@Test
	public void namesAreMergedPerAccountHash() throws IOException
	{
		ProfileKey before = new ProfileKey(5L, GameMode.MAIN, "Old Name");
		ProfileKey after = new ProfileKey(5L, GameMode.MAIN, "New Name");
		json.identify(before, observed("MEMBERS"), T0, true);
		executor.runAll();
		json.identify(after, observed("MEMBERS"), T0.plusSeconds(10), true);
		executor.runAll();
		JsonObject names = file("accounts", "5", "names.json");
		assertEquals("5", names.get("account_hash").getAsString());
		JsonArray rows = names.getAsJsonArray("names");
		assertEquals(2, rows.size());
		assertEquals("Old Name", rows.get(0).getAsJsonObject().get("name").getAsString());
		assertEquals(T0.toString(), rows.get(0).getAsJsonObject().get("first_seen").getAsString());
		assertEquals("New Name", rows.get(1).getAsJsonObject().get("name").getAsString());
		assertEquals("New Name", file("accounts", "5", "main", "mode.json").get("name").getAsString());
	}

	@Test
	public void writingSwitchedOnLaterStillWritesTheModeFileFirst() throws IOException
	{
		ProfileKey key = new ProfileKey(9L, GameMode.MAIN, "Synthetic");
		on.set(false);
		json.identify(key, observed("MEMBERS"), T0, true);
		json.write(key, "vars.json", Collections.singletonMap("a", 1));
		assertEquals(0, executor.pendingCount());
		on.set(true);
		json.appendLine(key, "loot.jsonl", Collections.singletonMap("a", 1));
		executor.runOne();
		assertTrue(Files.exists(dir.resolve("accounts").resolve("9").resolve("main").resolve("mode.json")));
		assertFalse(Files.exists(dir.resolve("accounts").resolve("9").resolve("main").resolve("loot.jsonl")));
		executor.runAll();
		assertTrue(Files.exists(dir.resolve("accounts").resolve("9").resolve("main").resolve("loot.jsonl")));
	}

	@Test
	public void theOldNameFolderIsNeverWritten() throws IOException
	{
		ProfileKey key = new ProfileKey(3L, GameMode.MAIN, "Synthetic");
		json.write(key, "character.json", Collections.singletonMap("a", 1));
		json.writeShared("game_quests.json", Collections.singletonMap("a", 1));
		executor.runAll();
		assertFalse(Files.exists(dir.resolve("Synthetic")));
		assertTrue(Files.exists(dir.resolve("game_quests.json")));
	}

	@Test
	public void theQuarantineIsAppendOnlyAtThePluginRoot() throws IOException
	{
		Path q = dir.resolve(JsonWriter.QUARANTINE_FILE);
		Files.write(q, "{\"earlier\":true}\n".getBytes(StandardCharsets.UTF_8)); // a line from an earlier session
		json.appendQuarantine(Collections.singletonMap("why", "a"));
		json.appendQuarantine(Collections.singletonMap("why", "b\nwith a line break"));
		on.set(false);
		json.appendQuarantine(Collections.singletonMap("why", "c")); // "Write files" is off: not written
		executor.runAll();
		assertEquals(Arrays.asList("{\"earlier\":true}", "{\"why\":\"a\"}", "{\"why\":\"b\\nwith a line break\"}"),
			Files.readAllLines(q, StandardCharsets.UTF_8));
		assertFalse(Files.exists(dir.resolve(JsonWriter.ACCOUNTS)));
	}
}
