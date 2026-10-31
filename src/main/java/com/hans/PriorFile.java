package com.hans;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The previous contents of one of our own files, read once per profile key (on the executor) so that a capture which
 * accumulates across sessions (last seen times, latest value per region) carries on from the file instead of
 * overwriting it. Created and polled on the client thread; the load itself completes on the executor.
 *
 * The executor publishes one immutable {@link Loaded} (whose file, for which request, what it held) and never decides
 * whether it is current: the client thread checks the key and generation before every use. So a load for one key that
 * finishes after {@link #ensure} moved on to another key can never be read as the other key's file.
 */
final class PriorFile<T>
{
	/** One finished load. Published whole, so its key, generation and value always belong together. */
	private static final class Loaded<T>
	{
		final ProfileKey key;
		final int generation;
		final T value;

		Loaded(ProfileKey key, int generation, T value)
		{
			this.key = key;
			this.generation = generation;
			this.value = value;
		}
	}

	private final CaptureContext ctx;
	private final String fileName;
	private final Class<T> type;
	private ProfileKey key; // client thread
	private int generation; // client thread
	// the newest load finished (the executor sets it; a slower, older load never replaces a newer one)
	private final AtomicReference<Loaded<T>> result = new AtomicReference<>();
	// runs on the executor between a load's read and its publication; tests pause there (does nothing otherwise)
	Runnable beforePublish = () -> { };

	PriorFile(CaptureContext ctx, String fileName, Class<T> type)
	{
		this.ctx = ctx;
		this.fileName = fileName;
		this.type = type;
	}

	/** Starts the load the first time, and again when the key (account or game mode) changed. */
	void ensure(ProfileKey k)
	{
		if (k.equals(key))
		{
			return;
		}
		key = k;
		int requested = ++generation;
		ctx.json.readAsync(k, fileName, type, v ->
		{
			beforePublish.run();
			Loaded<T> done = new Loaded<>(k, requested, v);
			result.accumulateAndGet(done, (old, now) -> old == null || now.generation > old.generation ? now : old);
		});
	}

	/** Discards this session's cached file so the next login reads the latest contents from disk. */
	void reset()
	{
		key = null;
		generation++;
	}

	/** The load of the key and request current now; null while it has not finished (or was for another). */
	private Loaded<T> current()
	{
		Loaded<T> r = result.get();
		return r != null && r.generation == generation && r.key.equals(key) ? r : null;
	}

	boolean loaded()
	{
		return current() != null;
	}

	/** The file as it was before this session touched it; null when there was none (or it is not loaded). */
	T get()
	{
		Loaded<T> r = current();
		return r == null ? null : r.value;
	}

	/** Changes whenever another key's file is being loaded: the owner's in-memory state is then stale. */
	int generation()
	{
		return generation;
	}
}
