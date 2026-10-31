package com.hans;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Writes the capture files into the plugin's own data folder ({@code getPluginDirectory()}), one folder per account
 * and game mode ({@code accounts/<account hash>/<mode>/}, see {@link ProfileKey}), atomically: a temp file is
 * written and then moved over the real one, so a reader never sees half a file.
 *
 * Each mode folder gets its mode.json (and the account folder its names.json) before any other file of that key:
 * see {@link #identify}. The old {@code <RSN>/} folders are never written. A main key's first task in each session
 * starts with the one-time copy of its old folder ({@link LegacySeed}); a file a capture has read or written in this
 * session is never copied in it, so no capture's copy of a file is replaced underneath it.
 *
 * All disk IO runs on RuneLite's executor, and every task carries the key it was queued for. Data is serialised
 * before the hand-over (on the calling thread, for a consistent reading) except in {@link #writeLater}, where the
 * caller's supplier runs on the executor. A failure is logged and never thrown.
 */
@Slf4j
final class JsonWriter
{
	static final String ACCOUNTS = "accounts";
	static final String MODE_FILE = "mode.json";
	static final String NAMES_FILE = "names.json";
	static final String QUARANTINE_FILE = "unassigned.jsonl";
	static final int LAYOUT = 2;

	/** Where the files go: {@code Plugin.getPluginDirectory()}, which can fail with an IOException. */
	interface Root
	{
		Filepath get() throws IOException;
	}

	private final ScheduledExecutorService executor;
	private final Root root;
	private final BooleanSupplier enabled;
	private final Consumer<Instant> onWritten;
	private final Gson gson;
	private final Gson gsonWithNulls;
	private final Gson compact;
	// what the client reported for each key (identify), and what was last queued as its mode.json
	private final Map<ProfileKey, Map<String, Object>> seen = new HashMap<>();
	private final Map<ProfileKey, Map<String, Object>> queued = new HashMap<>();
	// the executor's (only its tasks read or change these): what the legacy seed copy of each main key did when it last
	// ran in this session; when a seed that failed may run again; and, in each main key's folder, the files this
	// session created and the files it read or wrote
	private final Map<ProfileKey, LegacySeed.Outcome> seedRan = new ConcurrentHashMap<>();
	private final Map<ProfileKey, Long> seedRetryAt = new ConcurrentHashMap<>();
	private final Map<ProfileKey, Set<String>> created = new ConcurrentHashMap<>();
	private final Map<ProfileKey, Set<String>> inUse = new ConcurrentHashMap<>();
	final LegacySeed legacy;
	// how long a failed seed waits before it runs again, and the clock it is timed by (tests shorten it)
	long seedRetryNanos = 30_000_000_000L;
	LongSupplier clock = System::nanoTime;

	/**
	 * @param clientGson RuneLite's Gson (it must be derived from, not replaced)
	 * @param root       the plugin's data folder, {@code Plugin.getPluginDirectory()}
	 * @param enabled    the master switch; nothing is written while it is false
	 * @param onWritten  told (on the executor thread) when a file has been written
	 */
	JsonWriter(ScheduledExecutorService executor, Gson clientGson, Root root, BooleanSupplier enabled,
		Consumer<Instant> onWritten)
	{
		this.executor = executor;
		this.root = root;
		this.enabled = enabled;
		this.onWritten = onWritten;
		// the same Gson settings as the OSRS Toolkit Exporter: pretty, no HTML escaping, null fields left out
		this.gson = clientGson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		this.compact = clientGson.newBuilder().disableHtmlEscaping().create();
		this.gsonWithNulls = clientGson.newBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
		this.legacy = new LegacySeed(gson, this::writeFile);
	}

	/** The master "Write files" switch. */
	boolean enabled()
	{
		return enabled.getAsBoolean();
	}

	/**
	 * Records what the client reports for a settled key (world, world types, profile type) and queues its mode.json
	 * and names.json when that differs from what was last queued, or always when {@code fresh} (at settle, so
	 * last_seen moves on). Written whatever the export settings, but only while "Write files" is on; when it is off,
	 * the first write of the key after it is switched on queues them first.
	 */
	synchronized void identify(ProfileKey key, Map<String, Object> observed, Instant now, boolean fresh)
	{
		seen.put(key, observed);
		if (fresh)
		{
			queued.remove(key);
		}
		if (enabled() && !observed.equals(queued.get(key)))
		{
			queueIdentity(key, observed, now, fresh);
		}
	}

	/** Queues the key's mode.json and names.json first, if they have not been for what the client reported. */
	private synchronized void prepare(ProfileKey key)
	{
		Map<String, Object> observed = seen.get(key);
		if (observed != null && !observed.equals(queued.get(key)))
		{
			queueIdentity(key, observed, Instant.now(), false);
		}
	}

	/**
	 * {@code task}, preceded the first time in each session (while "Write files" is on) by the one-time copy of a main
	 * key's old {@code <RSN>/} folder ({@link LegacySeed}). Every executor task of a key goes through here and the
	 * executor runs them in order, so the copy finishes before the key's first {@link PriorFile} read or write.
	 */
	private Runnable seeded(ProfileKey key, Runnable task)
	{
		return () ->
		{
			if (key.isMain() && enabled() && !seedRan.containsKey(key))
			{
				runSeed(key);
			}
			task.run();
		};
	}

	/**
	 * Runs on the executor. When the copy could not go on at all (the journal could not be written, say), it is not
	 * counted as run: it runs again before a later task of the key, at most every {@link #seedRetryNanos}. Its gate then
	 * ignores the files this session created meanwhile, and it never copies a file this session read or wrote, so the
	 * captures that carried on are neither replaced nor mistaken for an earlier session's.
	 */
	private void runSeed(ProfileKey key)
	{
		Long at = seedRetryAt.get(key);
		if (at != null && clock.getAsLong() - at < 0)
		{
			return;
		}
		try
		{
			seedRan.put(key, legacy.run(root.get(), key, Instant.now().toString(), files(created, key),
				files(inUse, key)));
			seedRetryAt.remove(key);
		}
		catch (IOException | RuntimeException e)
		{
			seedRetryAt.put(key, clock.getAsLong() + seedRetryNanos);
			log.warn("Hans: could not copy the old folder for {}; trying again shortly", key, e);
		}
	}

	private static Set<String> files(Map<ProfileKey, Set<String>> m, ProfileKey key)
	{
		Set<String> s = m.get(key);
		return s == null ? Collections.emptySet() : s;
	}

	/**
	 * Runs on the executor, before the read or write: notes {@code fileName} in a main key's folder as in use, and,
	 * when it is not there at this session's first read or write of it, as this session's own: if it exists later,
	 * this session created it (the seed never copies a file in use). When that cannot be told, it counts as an earlier
	 * session's.
	 */
	private void touched(ProfileKey key, String fileName)
	{
		if (key == null || !key.isMain())
		{
			return;
		}
		boolean first = inUse.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(fileName);
		if (first)
		{
			try
			{
				if (!folder(key).joinSegment(fileName).exists())
				{
					created.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(fileName);
				}
			}
			catch (IOException | RuntimeException e)
			{
				// not known to be ours: the seed's gate still counts it
			}
		}
	}

	/**
	 * {@code fresh}: a new login. A seed that copied in this session runs again before the key's next task, to retry
	 * what it listed (it then finds nothing left to do, or tries those files); a refusal is not run again.
	 */
	private void queueIdentity(ProfileKey key, Map<String, Object> observed, Instant now, boolean fresh)
	{
		queued.put(key, observed);
		final String stamp = now.toString();
		Runnable task = seeded(key, () -> writeIdentity(key, observed, stamp));
		executor.execute(() ->
		{
			if (fresh)
			{
				seedRan.computeIfPresent(key, (k, o) ->
					o == LegacySeed.Outcome.SEEDED || o == LegacySeed.Outcome.RETRIED ? null : o);
			}
			task.run();
		});
	}

	/**
	 * Writes {@code <key's folder>/<fileName>}. Serialised now, written on the executor.
	 *
	 * @return the pending write, to wait for on shutdown; null when nothing is written
	 */
	Future<?> write(ProfileKey key, String fileName, Object data)
	{
		if (key == null || !enabled())
		{
			return null;
		}
		prepare(key);
		final String json = gson.toJson(data);
		return executor.submit(seeded(key, () -> writeFile(key, fileName, json)));
	}

	/**
	 * Done once every task queued before it has run: RuneLite's executor is a single thread, in order. The client's
	 * closing waits for it, so a logout write a capture did not return is still waited for.
	 */
	Future<?> barrier()
	{
		return executor.submit(() -> { });
	}

	/** Writes {@code <plugin folder>/<fileName>}: game-wide data that belongs to no account. */
	void writeShared(String fileName, Object data)
	{
		if (!enabled())
		{
			return;
		}
		final String json = gson.toJson(data);
		executor.execute(() -> writeFile((ProfileKey) null, fileName, json));
	}

	/**
	 * Composes and writes a file entirely on the executor (the supplier runs there, so it may read the disk).
	 * A null supplier result skips the write, leaving any existing file untouched.
	 *
	 * @return the pending write, to wait for on shutdown; null when nothing is written
	 */
	Future<?> writeLater(ProfileKey key, String fileName, Supplier<?> data)
	{
		return writeLater(key, fileName, data, true);
	}

	/**
	 * As {@link #writeLater(ProfileKey, String, Supplier)}; {@code nulls} false leaves null fields out, as
	 * {@link #write} does, for a file whose optional fields are absent rather than null.
	 */
	Future<?> writeLater(ProfileKey key, String fileName, Supplier<?> data, boolean nulls)
	{
		final Gson serializer = nulls ? gsonWithNulls : gson;
		if (key == null || !enabled())
		{
			return null;
		}
		prepare(key);
		return executor.submit(seeded(key, () ->
		{
			try
			{
				Object value = data.get();
				if (value != null)
				{
					writeFile(key, fileName, serializer.toJson(value));
				}
			}
			catch (RuntimeException e)
			{
				log.warn("Hans: could not compose {}", fileName, e);
			}
		}));
	}

	/** Reads a file back; null when it is not there or cannot be read. Call on the executor, not the client thread. */
	<T> T read(ProfileKey key, String fileName, Class<T> type)
	{
		try
		{
			touched(key, fileName);
			return read(folder(key).joinSegment(fileName), type);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Hans: could not read {}; starting a new file", fileName, e);
			return null;
		}
	}

	/**
	 * Runs on the executor (inside a {@link #writeLater} supplier): the file as it is on disk now, for a capture that
	 * merges into it; null when there is none. A file that is there but cannot be read is not overwritten: it is moved
	 * aside to {@code <name>_unreadable_<millis>.json} (kept, never deleted) and null is returned, so the merge starts a
	 * new file. Throws when it could not be moved: the caller then skips its write and the file stays as it is.
	 */
	<T> T readOrSetAside(ProfileKey key, String fileName, Class<T> type) throws IOException
	{
		touched(key, fileName);
		Filepath dir = folder(key);
		Filepath f = dir.joinSegment(fileName);
		try
		{
			return read(f, type);
		}
		catch (com.google.gson.JsonIOException e)
		{
			// a read failure, not a bad file: leave it where it is
			throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e);
		}
		catch (com.google.gson.JsonParseException e)
		{
			int dot = fileName.lastIndexOf('.');
			String base = dot < 0 ? fileName : fileName.substring(0, dot);
			String ext = dot < 0 ? "" : fileName.substring(dot);
			String aside = base + "_unreadable_" + System.currentTimeMillis() + ext;
			f.moveTo(dir.joinSegment(aside));
			log.warn("Hans: {} could not be read; kept it as {} and started a new file", fileName, aside, e);
			return null;
		}
	}

	private <T> T read(Filepath f, Class<T> type) throws IOException
	{
		if (!f.exists())
		{
			return null;
		}
		try (InputStream in = f.openInputStream(); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			return gson.fromJson(r, type);
		}
	}

	/**
	 * Appends one line to {@code <key's folder>/<fileName>} (a .jsonl file), on the executor.
	 * The line is one compact JSON object.
	 */
	void appendLine(ProfileKey key, String fileName, Object data)
	{
		if (key == null || !enabled())
		{
			return;
		}
		prepare(key);
		final String line = compact.toJson(data) + '\n';
		executor.execute(seeded(key, () ->
		{
			touched(key, fileName);
			append(() -> folder(key), fileName, line);
		}));
	}

	/**
	 * Appends one line to {@code <plugin folder>/unassigned.jsonl}, the quarantine: evidence that cannot be credited
	 * to any account and game mode (for example a drop that arrived during a world hop, before a tick confirmed the
	 * world, or while the account hash was unknown). One file for every account, whatever the hash (-1 too), so
	 * nothing is discarded. Append-only: opened with CREATE and APPEND (which Windows honours too), never truncated,
	 * rewritten or moved. Kept raw and apart; the app reads only the {@code accounts/<hash>/<mode>/} folders.
	 */
	void appendQuarantine(Object data)
	{
		if (!enabled())
		{
			return;
		}
		final String line = compact.toJson(data) + '\n';
		executor.execute(() -> append(root, QUARANTINE_FILE, line));
	}

	/** Runs on the executor. */
	private void append(Root folder, String fileName, String line)
	{
		try
		{
			Filepath dir = folder.get();
			dir.createDirectories();
			try (OutputStream os = dir.joinSegment(fileName).openOutputStream(StandardOpenOption.CREATE,
				StandardOpenOption.APPEND))
			{
				os.write(line.getBytes(StandardCharsets.UTF_8));
			}
			onWritten.accept(Instant.now());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Hans: could not append to {}", fileName, e);
		}
	}

	/**
	 * Reads a file back on the executor and hands the result (null when absent or unreadable) to {@code done},
	 * which also runs on the executor.
	 */
	<T> void readAsync(ProfileKey key, String fileName, Class<T> type, Consumer<T> done)
	{
		executor.execute(seeded(key, () -> done.accept(read(key, fileName, type))));
	}

	/** {@code <plugin folder>/accounts/<hash>/<mode>/}: one joinSegment per level, so it stays in the sandbox. */
	private Filepath folder(ProfileKey key) throws IOException
	{
		return accountFolder(key).joinSegment(key.mode);
	}

	private Filepath accountFolder(ProfileKey key) throws IOException
	{
		return root.get().joinSegment(ACCOUNTS).joinSegment(key.hashText());
	}

	/**
	 * Runs on the executor: mode.json (the folder's identity and what the client reported, keeping first_seen) and
	 * names.json (every name seen on the hash, merged).
	 */
	private void writeIdentity(ProfileKey key, Map<String, Object> observed, String now)
	{
		try
		{
			JsonObject before = read(folder(key).joinSegment(MODE_FILE), JsonObject.class);
			Map<String, Object> mode = new LinkedHashMap<>();
			mode.put("layout", LAYOUT);
			mode.put("account_hash", key.hashText());
			mode.put("mode", key.mode);
			mode.put("name", key.name);
			mode.putAll(observed);
			mode.put("first_seen", text(before, "first_seen", now));
			mode.put("last_seen", now);
			writeFile(key, MODE_FILE, gson.toJson(mode));

			Filepath account = accountFolder(key);
			JsonObject names = read(account.joinSegment(NAMES_FILE), JsonObject.class);
			JsonObject merged = mergeName(names, key, now);
			if (merged != null)
			{
				writeFile(account, NAMES_FILE, gson.toJson(merged));
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Hans: could not write {} for {}", MODE_FILE, key, e);
		}
	}

	/** names.json with {@code key}'s name added or its last_seen moved on; null when unchanged. Raw, append-merge. */
	static JsonObject mergeName(JsonObject existing, ProfileKey key, String now)
	{
		JsonObject out = existing != null ? existing : new JsonObject();
		out.addProperty("account_hash", key.hashText());
		JsonElement list = out.get("names");
		JsonArray names = list != null && list.isJsonArray() ? list.getAsJsonArray() : new JsonArray();
		out.add("names", names);
		for (JsonElement e : names)
		{
			if (e.isJsonObject() && new JsonPrimitive(key.name).equals(e.getAsJsonObject().get("name")))
			{
				JsonObject row = e.getAsJsonObject();
				if (Objects.equals(text(row, "last_seen", null), now))
				{
					return null;
				}
				row.addProperty("last_seen", now);
				return out;
			}
		}
		JsonObject row = new JsonObject();
		row.addProperty("name", key.name);
		row.addProperty("first_seen", now);
		row.addProperty("last_seen", now);
		names.add(row);
		return out;
	}

	private static String text(JsonObject o, String field, String fallback)
	{
		JsonElement e = o == null ? null : o.get(field);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : fallback;
	}

	/** Runs on the executor. key null: the plugin folder itself. */
	private void writeFile(ProfileKey key, String fileName, String json)
	{
		try
		{
			touched(key, fileName);
			writeFile(key == null ? root.get() : folder(key), fileName, json);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Hans: could not write {}", fileName, e);
		}
	}

	private void writeFile(Filepath dir, String fileName, String json) throws IOException
	{
		dir.createDirectories();
		Filepath out = dir.joinSegment(fileName);
		Filepath tmp = dir.joinSegment(fileName + ".tmp");
		try (OutputStream os = tmp.openOutputStream())
		{
			os.write(json.getBytes(StandardCharsets.UTF_8));
		}
		try
		{
			tmp.moveTo(out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException atomicFailed)
		{
			tmp.moveTo(out, StandardCopyOption.REPLACE_EXISTING);
		}
		onWritten.accept(Instant.now());
	}
}
