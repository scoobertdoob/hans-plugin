package com.hans;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * The one-time seed copy (docs/plugin-mode-separation.md §5): the old {@code rsbuddy/<RSN>/} folder is copied into
 * {@code accounts/<hash>/main/}, so the accumulated files (sessions, kill counts, farming, vars, item gains and their
 * archives) carry on from their history. {@link JsonWriter} runs it on the executor ahead of a main key's first task
 * in each session (and at a later login while files it listed are left to retry), and never lets it copy a file a
 * capture has already read or written in this session.
 *
 * It copies only when all of these hold: the main folder has no capture files from an earlier session; the legacy
 * account.json, character.json or sessions.json names this account hash (and none names another); and no legacy file
 * records an event world or a profile type other than STANDARD (the same evidence the importer's
 * {@code legacy_mode_unknown} reads). Otherwise nothing is copied and the importer keeps the legacy folder as it is.
 *
 * Even then, a file is copied only when it is provably this account's: account.json and character.json by naming this
 * hash, and every other file by every time it records falling within a session proven to be this account's (see
 * {@link #provenSessions}). An account that later took this name on the same PC rewrote the identity files and
 * sessions.json's hash, but not the older entries of the other files; those are left in the old folder as unknown
 * provenance, which the importer still reads.
 *
 * The legacy folder is only ever read. A target that exists is never overwritten. Each file is copied to a temp file,
 * moved into place without replacing anything, and read back. {@code migrating_from.json} is written before the first
 * copy, and {@code migrated_from.json} (the importer's marker) after the copies: it lists the files copied and
 * confirmed (the only legacy files the importer then leaves aside) and every other file with the reason. A file that
 * could not be read or copied, or that a capture read first, is listed as one to retry, and is copied at a later
 * login if its target is still free; the marker is then rewritten with it added.
 */
@Slf4j
final class LegacySeed
{
	static final String MARKER = "migrated_from.json";
	static final String JOURNAL = "migrating_from.json";
	static final String SEED_TMP = ".seed.tmp";
	static final String SESSIONS = "sessions.json";
	/**
	 * Leeway around a proven session, for times stamped just before its start or after its end (the logout flush).
	 * Far shorter than the time a released name takes to pass to another account.
	 */
	static final Duration SESSION_GRACE = Duration.ofSeconds(60);

	/** World types that mean the folder also recorded something other than the main game (the importer's list). */
	static final Set<String> EVENT_WORLDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("SEASONAL",
		"DEADMAN", "BETA_WORLD", "NOSAVE_MODE", "TOURNAMENT_WORLD", "FRESH_START_WORLD", "PVP_ARENA",
		"QUEST_SPEEDRUNNING")));
	/** The legacy files that name the account hash. */
	static final List<String> HASH_FILES = Collections.unmodifiableList(Arrays.asList("account.json",
		"character.json", SESSIONS));
	/** Snapshots of the account that wrote them last: this account's when they name its hash. */
	static final Set<String> IDENTITY_FILES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("account.json",
		"character.json")));

	/** What a run did. */
	enum Outcome
	{
		/** Not a main key, or a name that is the new layout's own folder. */
		NOT_MAIN,
		/** No legacy folder for this name. */
		NO_LEGACY,
		/** migrated_from.json is already there, with nothing left that this session may retry. */
		ALREADY_SEEDED,
		/** The main folder has capture files from an earlier session, and no copy of this folder was cut short. */
		TARGET_IN_USE,
		/** No legacy file names this hash, or one names another. */
		OTHER_ACCOUNT,
		/** A legacy file records an event world or a profile type other than STANDARD. */
		NOT_MAIN_GAME,
		/** Copied and marked (some files may be listed to retry). */
		SEEDED,
		/** The files a marker listed to retry were tried again, and the marker rewritten. */
		RETRIED
	}

	/** Writes one small JSON file atomically ({@link JsonWriter}'s temp-and-move write). */
	interface Out
	{
		void write(Filepath dir, String fileName, String json) throws IOException;
	}

	/** One legacy file, read once: what it records other than the main game, and whether it is this account's. */
	private static final class Look
	{
		final Set<String> evidence = new TreeSet<>();
		/** Why it cannot be shown to be this account's; null when it can. */
		String unproven;
		/** It (or sessions.json) could not be read now: tried again at a later login. */
		boolean unreadable;
	}

	private final Gson gson;
	// tests wrap it to make a write fail (JsonWriter's write otherwise)
	Out out;
	// runs on the executor after each file is copied and confirmed; tests stop the copy there (does nothing otherwise)
	Consumer<String> afterCopy = f -> { };

	LegacySeed(Gson gson, Out out)
	{
		this.gson = gson;
		this.out = out;
	}

	/** {@link #run(Filepath, ProfileKey, String, Set, Set)} for a session that has touched nothing yet. */
	Outcome run(Filepath root, ProfileKey key, String now) throws IOException
	{
		return run(root, key, now, Collections.emptySet(), Collections.emptySet());
	}

	/**
	 * Runs on the executor. {@code root} is the plugin folder; {@code now} stamps the journal and the marker.
	 * {@code wrote} is what this session has written into the key's folder (its own files, not an earlier session's
	 * captures); {@code inUse} is what it has read or written there, which is never copied in this session.
	 * Thrown when the copy cannot go on at all: nothing is then marked, and it is tried again.
	 */
	Outcome run(Filepath root, ProfileKey key, String now, Set<String> wrote, Set<String> inUse) throws IOException
	{
		if (!key.isMain() || JsonWriter.ACCOUNTS.equalsIgnoreCase(key.name))
		{
			return Outcome.NOT_MAIN;
		}
		Filepath legacy = root.joinSegment(key.name);
		if (!legacy.isDirectory())
		{
			return Outcome.NO_LEGACY;
		}
		Filepath main = root.joinSegment(JsonWriter.ACCOUNTS).joinSegment(key.hashText()).joinSegment(key.mode);
		if (main.joinSegment(MARKER).exists())
		{
			return retry(legacy, main, key, now, inUse);
		}
		String folderName = legacy.getFileName();
		boolean resumed = journalNames(main.joinSegment(JOURNAL), folderName);
		if (!resumed && hasCaptureFiles(main, wrote))
		{
			return Outcome.TARGET_IN_USE;
		}
		List<Filepath> entries = list(legacy);
		if (!namesHash(legacy, key.hashText()))
		{
			return Outcome.OTHER_ACCOUNT;
		}
		List<Instant[]> sessions = provenSessions(legacy, key.hashText());
		Map<String, Look> looks = new HashMap<>();
		Set<String> evidence = new TreeSet<>();
		for (Filepath f : entries)
		{
			Look l = look(f, key.hashText(), sessions);
			looks.put(f.getFileName(), l);
			evidence.addAll(l.evidence);
		}
		if (!evidence.isEmpty())
		{
			log.info("Hans: not copying the old folder {} into main: it also recorded {}", folderName, evidence);
			return Outcome.NOT_MAIN_GAME;
		}

		main.createDirectories();
		if (!resumed)
		{
			Map<String, Object> journal = new LinkedHashMap<>();
			journal.put("legacy_folder", folderName);
			journal.put("started_at", now);
			out.write(main, JOURNAL, gson.toJson(journal));
		}
		List<String> copied = new ArrayList<>();
		List<Map<String, Object>> skipped = new ArrayList<>();
		for (Filepath f : entries)
		{
			String name = f.getFileName();
			Map<String, Object> skip = settle(f, looks.get(name), main, inUse);
			if (skip == null)
			{
				copied.add(name);
			}
			else
			{
				skipped.add(skip);
			}
			afterCopy.accept(name); // outside copy's catch: a test's "crash" here stops the whole run
		}

		Map<String, Object> marker = new LinkedHashMap<>();
		marker.put("legacy_folder", folderName);
		marker.put("migrated_at", now);
		marker.put("account_hash", key.hashText());
		marker.put("mode", key.mode);
		marker.put("resumed", resumed);
		marker.put("copied", copied);
		marker.put("skipped", skipped);
		out.write(main, MARKER, gson.toJson(marker));
		log.info("Hans: copied the old folder {} into {} ({} files, {} left in the old folder)", folderName, key,
			copied.size(), skipped.size());
		return Outcome.SEEDED;
	}

	/**
	 * A marker is there: the files it lists to retry are tried again (the legacy folder's other gates passed when it
	 * was written), and the marker is rewritten with what changed. The copied list only ever grows.
	 */
	private Outcome retry(Filepath legacy, Filepath main, ProfileKey key, String now, Set<String> inUse)
		throws IOException
	{
		String text = new String(bytes(main.joinSegment(MARKER)), StandardCharsets.UTF_8); // unreadable: tried again
		JsonElement e;
		try
		{
			e = gson.fromJson(text, JsonElement.class);
		}
		catch (RuntimeException damaged)
		{
			return Outcome.ALREADY_SEEDED; // not ours to change
		}
		JsonObject marker = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		JsonElement folder = marker == null ? null : marker.get("legacy_folder");
		JsonElement list = marker == null ? null : marker.get("skipped");
		if (folder == null || !folder.isJsonPrimitive() || !folder.getAsString().equalsIgnoreCase(legacy.getFileName())
			|| list == null || !list.isJsonArray())
		{
			return Outcome.ALREADY_SEEDED; // another folder's marker, or not ours to change
		}
		List<String> again = new ArrayList<>();
		for (JsonElement s : list.getAsJsonArray())
		{
			JsonObject o = s.isJsonObject() ? s.getAsJsonObject() : null;
			if (o != null && o.has("file") && o.get("retry") != null && o.get("retry").isJsonPrimitive()
				&& o.get("retry").getAsBoolean() && !inUse.contains(o.get("file").getAsString()))
			{
				again.add(o.get("file").getAsString());
			}
		}
		if (again.isEmpty())
		{
			return Outcome.ALREADY_SEEDED; // nothing to retry, or nothing this session may copy
		}

		List<Instant[]> sessions = provenSessions(legacy, key.hashText());
		JsonArray copied = marker.has("copied") && marker.get("copied").isJsonArray()
			? marker.getAsJsonArray("copied") : new JsonArray();
		JsonArray skipped = new JsonArray();
		List<String> nowCopied = new ArrayList<>();
		for (JsonElement s : list.getAsJsonArray())
		{
			JsonObject o = s.isJsonObject() ? s.getAsJsonObject() : null;
			String name = o != null && o.has("file") ? o.get("file").getAsString() : null;
			if (name == null || !again.contains(name))
			{
				skipped.add(s);
				continue;
			}
			Filepath f = legacy.joinSegment(name);
			Map<String, Object> skip;
			if (!f.isFile())
			{
				skip = skip(name, "no longer in the old folder", false);
			}
			else
			{
				Look l = look(f, key.hashText(), sessions);
				skip = !l.evidence.isEmpty()
					? skip(name, "records " + l.evidence + "; left in the old folder", false)
					: settle(f, l, main, inUse);
			}
			if (skip == null)
			{
				copied.add(name);
				nowCopied.add(name);
			}
			else
			{
				skipped.add(gson.toJsonTree(skip));
			}
			afterCopy.accept(name);
		}
		JsonArray retried = marker.has("retried") && marker.get("retried").isJsonArray()
			? marker.getAsJsonArray("retried") : new JsonArray();
		JsonObject round = new JsonObject();
		round.addProperty("at", now);
		round.add("copied", gson.toJsonTree(nowCopied));
		retried.add(round);
		marker.add("copied", copied);
		marker.add("skipped", skipped);
		marker.add("retried", retried);
		out.write(main, MARKER, gson.toJson(marker));
		log.info("Hans: tried the old folder {} again for {}: {} more copied", legacy.getFileName(), key,
			nowCopied.size());
		return Outcome.RETRIED;
	}

	/** What becomes of one legacy entry: null when it is now copied and confirmed, otherwise its skipped entry. */
	private static Map<String, Object> settle(Filepath f, Look look, Filepath main, Set<String> inUse)
	{
		String name = f.getFileName();
		if (!f.isFile())
		{
			return skip(name, "a folder, not a file; left in the old folder", false);
		}
		if (name.endsWith(".tmp"))
		{
			return skip(name, "a temp file left by an interrupted write; left in the old folder", false);
		}
		if (look.unreadable)
		{
			return skip(name, look.unproven + "; the original is unchanged in the old folder", true);
		}
		if (look.unproven != null)
		{
			return skip(name, "not shown to be this account's (" + look.unproven + "); left in the old folder", false);
		}
		return copy(f, main, name, inUse);
	}

	private static Map<String, Object> skip(String name, String reason, boolean retry)
	{
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("file", name);
		s.put("reason", reason);
		s.put("retry", retry);
		return s;
	}

	/**
	 * Copies one legacy file into {@code main}; null when the main folder now holds exactly its bytes, otherwise its
	 * skipped entry. Never replaces a target, never copies a file this session has read or written, and never changes
	 * the legacy file.
	 */
	private static Map<String, Object> copy(Filepath from, Filepath main, String name, Set<String> inUse)
	{
		Filepath to = main.joinSegment(name);
		Filepath tmp = main.joinSegment(name + SEED_TMP);
		try
		{
			byte[] original = bytes(from);
			if (to.exists())
			{
				// the same bytes: copied by an earlier run that was cut short
				return Arrays.equals(original, bytes(to)) ? null
					: skip(name, "already in the main folder with other contents; not overwritten", false);
			}
			if (inUse.contains(name))
			{
				// a capture read it as missing: its copy of the file would overwrite this one
				return skip(name, "read by a capture before it could be copied; copied at a later login", true);
			}
			// REPLACE_EXISTING only for our own temp file (left by a run that was cut short), never for a target
			from.copyTo(tmp, StandardCopyOption.REPLACE_EXISTING);
			tmp.moveTo(to); // no REPLACE_EXISTING: fails rather than overwrite a file that appeared meanwhile
			if (!Arrays.equals(original, bytes(to)))
			{
				return skip(name, "the copy did not match the original; the original is unchanged in the old folder",
					true);
			}
			return null;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Hans: could not copy the old {}; carrying on with the rest", name, e);
			try
			{
				tmp.deleteIfExists(); // only our own temp copy: the original stays in the old folder
			}
			catch (IOException | RuntimeException ignored)
			{
				// the temp file is ignored by the next run's checks
			}
			return skip(name, "could not be read or copied (" + e.getClass().getSimpleName() + "); the original is"
				+ " unchanged in the old folder", true);
		}
	}

	/** The legacy folder's entries, sorted by name. */
	private static List<Filepath> list(Filepath dir) throws IOException
	{
		try (Stream<Filepath> s = dir.walk(1))
		{
			return s.filter(p -> !p.equals(dir)).sorted().collect(Collectors.toList());
		}
	}

	/** Whether the main folder has any file but its identity, our temp files and what this session wrote there. */
	private static boolean hasCaptureFiles(Filepath main, Set<String> wrote) throws IOException
	{
		if (!main.isDirectory())
		{
			return false;
		}
		for (Filepath f : list(main))
		{
			String name = f.getFileName();
			if (!name.equals(JsonWriter.MODE_FILE) && !name.equals(JOURNAL) && !name.endsWith(".tmp")
				&& !wrote.contains(name))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether a copy of {@code folderName} was started here and cut short. */
	private boolean journalNames(Filepath journal, String folderName)
	{
		JsonElement e = parse(journal);
		JsonElement name = e != null && e.isJsonObject() ? e.getAsJsonObject().get("legacy_folder") : null;
		return name != null && name.isJsonPrimitive() && name.getAsString().equalsIgnoreCase(folderName);
	}

	/** At least one of account.json, character.json and sessions.json names {@code hash}, and none names another. */
	private boolean namesHash(Filepath legacy, String hash)
	{
		boolean same = false;
		for (String name : HASH_FILES)
		{
			Boolean names = namesHash(parse(legacy.joinSegment(name)), hash);
			if (names == null)
			{
				continue;
			}
			if (!names)
			{
				return false;
			}
			same = true;
		}
		return same;
	}

	/** Whether a file's top-level account_hash is {@code hash}; null when it records none. */
	private static Boolean namesHash(JsonElement e, String hash)
	{
		JsonElement h = e != null && e.isJsonObject() ? e.getAsJsonObject().get("account_hash") : null;
		if (h == null || h.isJsonNull())
		{
			return null;
		}
		// a string in account.json, a number elsewhere: compared as the decimal text
		return h.isJsonPrimitive() && hash.equals(h.getAsString());
	}

	/**
	 * When this account was provably playing, as [start, end] pairs. sessions.json names the account that wrote it
	 * last, so its newest session is that account's own. Each session before it is this account's too when its XP at
	 * the end equals, skill for skill, the XP at the start of the session after it: another account's session cannot
	 * match like that. The walk stops at the first that does not (a different account, or XP gained elsewhere in
	 * between, which is just as safely left out). Empty when sessions.json does not name this hash; null when it cannot
	 * be read now.
	 */
	private List<Instant[]> provenSessions(Filepath legacy, String hash)
	{
		Filepath f = legacy.joinSegment(SESSIONS);
		if (!f.isFile())
		{
			return Collections.emptyList();
		}
		String text;
		try
		{
			text = new String(bytes(f), StandardCharsets.UTF_8);
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
		JsonElement e;
		try
		{
			e = gson.fromJson(text, JsonElement.class);
		}
		catch (RuntimeException damaged)
		{
			return Collections.emptyList();
		}
		if (!Boolean.TRUE.equals(namesHash(e, hash)) || !e.getAsJsonObject().has("sessions")
			|| !e.getAsJsonObject().get("sessions").isJsonArray())
		{
			return Collections.emptyList();
		}
		List<JsonObject> sessions = new ArrayList<>();
		for (JsonElement s : e.getAsJsonObject().getAsJsonArray("sessions"))
		{
			if (s.isJsonObject() && time(s.getAsJsonObject(), "start") != null)
			{
				sessions.add(s.getAsJsonObject());
			}
		}
		sessions.sort(Comparator.comparing(s -> time(s, "start")));
		List<Instant[]> out = new ArrayList<>();
		for (int i = sessions.size() - 1; i >= 0; i--)
		{
			JsonObject s = sessions.get(i);
			if (i < sessions.size() - 1)
			{
				JsonElement end = s.get("xp_end");
				JsonElement next = sessions.get(i + 1).get("xp_start");
				if (end == null || !end.isJsonObject() || end.getAsJsonObject().size() == 0 || !end.equals(next))
				{
					break;
				}
			}
			Instant start = time(s, "start");
			Instant end = time(s, "end") != null ? time(s, "end") : time(s, "last_update");
			out.add(new Instant[]{start, end == null || end.isBefore(start) ? start : end});
		}
		return out;
	}

	/**
	 * Reads one legacy file: what it records other than the main game, and whether it is this account's (an identity
	 * file naming this hash, or a file whose every recorded time lies within a proven session).
	 */
	private Look look(Filepath f, String hash, List<Instant[]> sessions)
	{
		Look l = new Look();
		String name = f.getFileName();
		if (!f.isFile() || name.endsWith(".tmp"))
		{
			return l; // left in the old folder by settle
		}
		boolean jsonl = name.endsWith(".jsonl");
		if (!jsonl && !name.endsWith(".json"))
		{
			l.unproven = "not a capture file";
			return l;
		}
		String text;
		try
		{
			text = new String(bytes(f), StandardCharsets.UTF_8);
		}
		catch (IOException | RuntimeException e)
		{
			// an unreadable file is no evidence either way (as in the importer), and is tried again
			l.unreadable = true;
			l.unproven = "could not be read (" + e.getClass().getSimpleName() + ")";
			return l;
		}
		List<String> parts = jsonl ? Arrays.asList(text.split("\n")) : Collections.singletonList(text);
		List<JsonElement> parsed = new ArrayList<>();
		boolean damaged = false;
		for (String part : parts)
		{
			if (part.trim().isEmpty())
			{
				continue;
			}
			try
			{
				JsonElement e = gson.fromJson(part, JsonElement.class);
				walk(e, l.evidence);
				parsed.add(e);
			}
			catch (RuntimeException e)
			{
				damaged = true; // a damaged file or line is no evidence of another mode either way
			}
		}
		if (IDENTITY_FILES.contains(name) && !damaged && parsed.size() == 1
			&& Boolean.TRUE.equals(namesHash(parsed.get(0), hash)))
		{
			return l;
		}
		if (damaged)
		{
			l.unproven = "damaged, so its times cannot be checked";
			return l;
		}
		if (sessions == null)
		{
			l.unreadable = true;
			l.unproven = SESSIONS + " could not be read to check its times";
			return l;
		}
		List<Instant> times = new ArrayList<>();
		for (JsonElement e : parsed)
		{
			times(e, times);
		}
		if (times.isEmpty())
		{
			l.unproven = "it records no times";
		}
		else if (!times.stream().allMatch(t -> covered(t, sessions)))
		{
			l.unproven = "it records times outside this account's proven sessions";
		}
		return l;
	}

	private static boolean covered(Instant t, List<Instant[]> sessions)
	{
		for (Instant[] s : sessions)
		{
			if (!t.isBefore(s[0].minus(SESSION_GRACE)) && !t.isAfter(s[1].plus(SESSION_GRACE)))
			{
				return true;
			}
		}
		return false;
	}

	/** Every ISO-8601 time in {@code e}, in values and keys alike. */
	private static void times(JsonElement e, List<Instant> out)
	{
		if (e == null || e.isJsonNull())
		{
			return;
		}
		if (e.isJsonPrimitive())
		{
			Instant t = e.getAsJsonPrimitive().isString() ? instant(e.getAsString()) : null;
			if (t != null)
			{
				out.add(t);
			}
			return;
		}
		if (e.isJsonArray())
		{
			for (JsonElement x : e.getAsJsonArray())
			{
				times(x, out);
			}
			return;
		}
		for (Map.Entry<String, JsonElement> field : e.getAsJsonObject().entrySet())
		{
			Instant t = instant(field.getKey());
			if (t != null)
			{
				out.add(t);
			}
			times(field.getValue(), out);
		}
	}

	private static Instant time(JsonObject o, String field)
	{
		JsonElement e = o.get(field);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? instant(e.getAsString()) : null;
	}

	/** {@code s} as an instant when it is a whole ISO-8601 date and time with an offset; otherwise null. */
	static Instant instant(String s)
	{
		if (s.length() < 20 || s.charAt(4) != '-' || s.charAt(10) != 'T')
		{
			return null;
		}
		try
		{
			return OffsetDateTime.parse(s).toInstant();
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	private static void walk(JsonElement e, Set<String> evidence)
	{
		if (e == null)
		{
			return;
		}
		if (e.isJsonArray())
		{
			for (JsonElement x : e.getAsJsonArray())
			{
				walk(x, evidence);
			}
			return;
		}
		if (!e.isJsonObject())
		{
			return;
		}
		for (Map.Entry<String, JsonElement> field : e.getAsJsonObject().entrySet())
		{
			JsonElement v = field.getValue();
			if (field.getKey().equals("world_types") && v.isJsonArray())
			{
				for (JsonElement t : v.getAsJsonArray())
				{
					if (t.isJsonPrimitive() && EVENT_WORLDS.contains(t.getAsString()))
					{
						evidence.add("world type " + t.getAsString());
					}
				}
			}
			else if (field.getKey().equals("profile_type") && v.isJsonPrimitive()
				&& v.getAsJsonPrimitive().isString() && !"STANDARD".equals(v.getAsString()))
			{
				evidence.add("profile type " + v.getAsString());
			}
			else
			{
				walk(v, evidence);
			}
		}
	}

	/** The parsed file; null when it is absent or cannot be read or parsed. */
	private JsonElement parse(Filepath f)
	{
		try
		{
			return f.isFile() ? gson.fromJson(new String(bytes(f), StandardCharsets.UTF_8), JsonElement.class) : null;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	private static byte[] bytes(Filepath f) throws IOException
	{
		try (InputStream in = f.openInputStream())
		{
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			for (int n; (n = in.read(chunk)) != -1; )
			{
				buf.write(chunk, 0, n);
			}
			return buf.toByteArray();
		}
	}
}
