package com.hans;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.runelite.client.game.WorldService;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.http.api.worlds.WorldType;

/**
 * Which Deadman season a world is running: the world's activity in the world list (the season's name as Jagex
 * publishes it, such as "Deadman: Annihilation"), read from the list RuneLite already keeps for its world hopper.
 * RuneLite's profile type has one {@code DEADMAN} for every season and the world types say only "Deadman", so this is
 * the game's only evidence of the season (docs/plugin-mode-separation.md, "Deadman seasons").
 *
 * A name is never taken from a list that may be stale: RuneLite refreshes its list every 10 minutes while logged in,
 * but not on the login screen, so a list left from before a season change could name the previous season. RuneLite
 * posts a {@code WorldsFetch} event for every list it fetches ({@link #fetched}, any thread), and the plugin stamps it
 * with the time it came, so its age is known exactly and nothing polls the list. The list in hand is read directly
 * only once, on a Deadman world, when no event has been seen (the list from before this plugin started: age unknown,
 * never trusted). A list is trusted for {@link #TRUST_NANOS} from its fetch. Only when no list is trusted is a refresh
 * asked for, at most every {@link #ASK_NANOS}; a trusted list that doesn't name the world (an unlisted world, a blank
 * activity) asks for nothing, and the next fetch is awaited. No name is taken then (the login records nothing). The
 * list must also still call the world a Deadman world. Once read, a world's name holds until the login ends
 * ({@link #forget}), so a later refresh never moves a login to another folder mid-way: a change shows at the next
 * login. Client thread, except {@link #fetched}.
 */
final class WorldSeasons
{
	static final long TRUST_NANOS = TimeUnit.MINUTES.toNanos(20);
	static final long ASK_NANOS = TimeUnit.MINUTES.toNanos(5);

	private final Supplier<WorldResult> worlds;
	private final Runnable refresh;
	private final LongSupplier clock;
	private final Map<Integer, String> held = new HashMap<>(); // world id to the name read in this login
	private final AtomicReference<Fetch> pending = new AtomicReference<>(); // fetched, not yet taken up
	private boolean seen; // whether the list in hand has been read directly (when no fetch had been seen)
	private WorldResult last;
	private long freshAt; // when `last` was fetched; meaningful only when `fresh`
	private boolean fresh; // whether it is known when `last` was fetched
	private boolean asked;
	private long askedAt;

	/** A list and the time it came. */
	private static final class Fetch
	{
		final WorldResult result;
		final long at;

		Fetch(WorldResult result, long at)
		{
			this.result = result;
			this.at = at;
		}
	}

	WorldSeasons(Supplier<WorldResult> worlds, Runnable refresh, LongSupplier clock)
	{
		this.worlds = worlds;
		this.refresh = refresh;
		this.clock = clock;
	}

	/** RuneLite's own world list: no request of ours except a refresh when the list in hand is not recent. */
	static WorldSeasons of(WorldService service)
	{
		return new WorldSeasons(service::getWorlds, service::refresh, System::nanoTime);
	}

	/** RuneLite fetched the world list (WorldsFetch event, any thread): stamped with the time it came. */
	void fetched(WorldResult result)
	{
		if (result != null)
		{
			pending.set(new Fetch(result, clock.getAsLong()));
		}
	}

	/** The world's activity text when a recent list calls it a Deadman world; null when not known (yet). */
	String activity(int world)
	{
		String name = held.get(world);
		if (name != null)
		{
			return name;
		}
		long now = clock.getAsLong();
		Fetch f = pending.getAndSet(null);
		if (f != null)
		{
			last = f.result;
			freshAt = f.at;
			fresh = true;
			seen = true;
		}
		if (last == null && !seen)
		{
			// no fetch seen since this plugin started: the list RuneLite holds may be of any age
			seen = true;
			last = worlds.get();
			fresh = false;
		}
		boolean trusted = last != null && fresh && now - freshAt <= TRUST_NANOS;
		World w = trusted ? last.findWorld(world) : null;
		if (w == null || w.getTypes() == null || !w.getTypes().contains(WorldType.DEADMAN)
			|| GameMode.season(w.getActivity()) == null) // blank, "-" or punctuation only: no name, not cached
		{
			if (!trusted && (!asked || now - askedAt >= ASK_NANOS))
			{
				asked = true;
				askedAt = now;
				refresh.run();
			}
			return null;
		}
		held.put(world, w.getActivity());
		return w.getActivity();
	}

	/** The login ended: the next login reads every world's name afresh. */
	void forget()
	{
		held.clear();
	}
}
