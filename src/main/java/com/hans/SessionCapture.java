package com.hans;

import com.hans.RsbuddyData.Session;
import com.hans.RsbuddyData.SessionsFile;
import com.hans.RsbuddyData.Snapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * sessions.json: every play session of the account, login to logout, with the XP and levels at both ends.
 * Uses the readings that {@link CharacterCapture} keeps current (so this capture is registered after it).
 */
final class SessionCapture implements Capture
{
	private static final int REFRESH_TICKS = 100; // ~1 min between world/position refreshes of the open session
	private static final int HEARTBEAT_TICKS = 200; // ~2 min between sessions.json writes, XP change or not

	/** Told on the client thread when the live session changes (account null: no session is running). */
	interface Listener
	{
		void changed(String account, Instant start, long xpGained);
	}

	private final CaptureContext ctx;
	private final CharacterCapture character;
	private final Listener listener;
	private final SessionTracker tracker = new SessionTracker();
	private final SessionLog sessionLog = new SessionLog(); // touched only on the executor
	// executor only: the key and session whose file sessionLog holds (reloaded from disk when either differs)
	private ProfileKey loadedKey;
	private String loadedStart;
	private int ticksSince;
	private int ticksSinceRefresh;
	/** When the tracker was last given a reading (with the world and tile of that moment). */
	private volatile Instant fedAt;
	/** The last tick on which a reading could be taken, and whether the latest tick was one. */
	private Instant readAt;
	private boolean readable;
	private volatile Session previous; // the last finished session, for the panel (read from the file at login)

	SessionCapture(CaptureContext ctx, CharacterCapture character, Listener listener)
	{
		this.ctx = ctx;
		this.character = character;
		this.listener = listener;
	}

	@Override
	public void onSettled(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null || character.latest() == null)
		{
			return;
		}
		ticksSince = 0;
		ticksSinceRefresh = 0;
		tracker.begin(now, character.latest());
		fedAt = now;
		readAt = now;
		readable = true;
		write(key, tracker.copy());
		// after that write (same executor), the file holds every earlier session: keep the newest finished one
		ctx.json.readAsync(key, "sessions.json", SessionsFile.class, f -> previous = lastFinished(f));
		notifyListener();
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		readable = key != null;
		if (key == null)
		{
			return; // no sample now: the client may show another account
		}
		readAt = now;
		ticksSince++;
		ticksSinceRefresh++;
		if (character.takeWritten())
		{
			tracker.update(character.latestAt(), withNow(character.latest(), local));
			fedAt = now;
			ticksSinceRefresh = 0;
		}
		else if (ticksSinceRefresh >= REFRESH_TICKS && character.latest() != null)
		{
			// idle play: no stat changed, but the world, position and last-seen time still move on
			ticksSinceRefresh = 0;
			tracker.update(now, withNow(character.latest(), local));
			fedAt = now;
		}
		if (ticksSince >= HEARTBEAT_TICKS)
		{
			ticksSince = 0;
			write(key, tracker.copy());
		}
		notifyListener();
	}

	/** The reading with the world and tile of right now (the skills are as current as {@link CharacterCapture}). */
	private Snapshot withNow(Snapshot s, Player local)
	{
		WorldPoint pos = local.getWorldLocation();
		Map<String, Integer> position = null;
		if (pos != null)
		{
			position = new LinkedHashMap<>();
			position.put("x", pos.getX());
			position.put("y", pos.getY());
			position.put("plane", pos.getPlane());
		}
		return refreshed(s, ctx.client.getWorld(), position);
	}

	/** A copy of the reading with a newer world and tile (the old tile is kept when none is given). */
	static Snapshot refreshed(Snapshot s, int world, Map<String, Integer> position)
	{
		Snapshot out = new Snapshot(s.name, s.accountHash, world, s.worldTypes, s.combatLevel, s.skills);
		out.position = position != null ? position : s.position;
		return out;
	}

	/**
	 * Ends the live session (if any) with the newest reading and writes it under this login's key; returns the
	 * pending write. If no reading could be taken on the latest tick, it ends at the last tick on which one could.
	 */
	@Override
	public Future<?> onLogout(Instant now)
	{
		Instant end = readable || readAt == null ? now : readAt;
		readable = false;
		Session ended = tracker.end(end, endReading(character.latest(), character.latestAt(), fedAt));
		if (ended == null)
		{
			return null;
		}
		character.flush();
		previous = ended;
		notifyListener();
		return write(ctx.login.logoutKey(), ended);
	}

	/**
	 * The reading to end the session with: the character's last one if it was taken after the tracker was last fed,
	 * else null, so the world and tile of a later idle refresh are not put back to older ones.
	 */
	static Snapshot endReading(Snapshot latest, Instant latestAt, Instant fedAt)
	{
		return latest != null && latestAt != null && (fedAt == null || latestAt.isAfter(fedAt)) ? latest : null;
	}

	/**
	 * Composes and writes sessions.json on the executor. Before the first write that actually happens in a login the
	 * file already on disk is read, so past sessions are kept (and one cut short by a crash is closed), even if
	 * writing was switched on after the login.
	 */
	private Future<?> write(ProfileKey key, Session live)
	{
		if (live == null || key == null)
		{
			return null;
		}
		return ctx.json.writeLater(key, "sessions.json",
			() -> compose(key, live, () -> ctx.json.read(key, "sessions.json", SessionsFile.class)));
	}

	/**
	 * Runs on the executor, only when a write really happens: loads the disk file first whenever the key (account
	 * or game mode) or the session differs from the one held, so a late write of one key never lands in another's
	 * history.
	 */
	SessionsFile compose(ProfileKey key, Session live, Supplier<SessionsFile> disk)
	{
		if (!key.equals(loadedKey) || !live.start.equals(loadedStart))
		{
			sessionLog.load(disk.get(), live.start);
			loadedKey = key;
			loadedStart = live.start;
		}
		return sessionLog.compose(key.name, key.hash, live);
	}

	/** A copy of the live session, or null. */
	Session live()
	{
		return tracker.copy();
	}

	/** The newest finished session (this login's, once it ended), or null. */
	Session previous()
	{
		return previous;
	}

	private static Session lastFinished(SessionsFile f)
	{
		Session best = null;
		if (f != null && f.sessions != null)
		{
			for (Session s : f.sessions)
			{
				if (s != null && s.end != null && (best == null || s.end.compareTo(best.end) > 0))
				{
					best = s;
				}
			}
		}
		return best;
	}

	private void notifyListener()
	{
		boolean active = tracker.isActive();
		ProfileKey key = ctx.login.logoutKey();
		listener.changed(active && key != null ? key.name : null, tracker.startInstant(), tracker.xpGained());
	}
}
