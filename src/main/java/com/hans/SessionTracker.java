package com.hans;

import com.hans.RsbuddyData.Session;
import com.hans.RsbuddyData.SkillValue;
import com.hans.RsbuddyData.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The play session that is running now (login to logout). Thread safe: the client thread updates it, and the
 * logout and shutdown handlers may end it from another thread. Hands out copies, never the live object.
 */
final class SessionTracker
{
	private Session live;
	private Instant startInstant;
	private long startXpTotal;
	private long lastXpTotal;

	synchronized boolean isActive()
	{
		return live != null;
	}

	synchronized void begin(Instant start, Snapshot s)
	{
		live = new Session();
		startInstant = start;
		live.start = start.toString();
		live.last_update = live.start;
		live.worlds.add(s.world);
		fill(live.xp_start, live.levels_start, s.skills);
		fill(live.xp_end, live.levels_end, s.skills);
		live.position_end = s.position;
		startXpTotal = s.totalXp;
		lastXpTotal = s.totalXp;
	}

	/** @return true when the experience changed since the last update */
	synchronized boolean update(Instant now, Snapshot s)
	{
		if (live == null)
		{
			return false;
		}
		fill(live.xp_end, live.levels_end, s.skills);
		if (s.position != null)
		{
			live.position_end = s.position;
		}
		live.last_update = now.toString();
		List<Integer> worlds = live.worlds;
		if (worlds.isEmpty() || worlds.get(worlds.size() - 1) != s.world)
		{
			worlds.add(s.world);
		}
		boolean xpChanged = s.totalXp != lastXpTotal;
		lastXpTotal = s.totalXp;
		return xpChanged;
	}

	/** Ends the session; null when none is running. s may be null (the end values then stay as last updated). */
	synchronized Session end(Instant now, Snapshot s)
	{
		if (live == null)
		{
			return null;
		}
		if (s != null)
		{
			update(now, s);
		}
		live.end = now.toString();
		live.last_update = live.end;
		Session done = live.copy();
		live = null;
		startInstant = null;
		return done;
	}

	synchronized Session copy()
	{
		return live == null ? null : live.copy();
	}

	synchronized Instant startInstant()
	{
		return startInstant;
	}

	synchronized long xpGained()
	{
		return live == null ? 0 : lastXpTotal - startXpTotal;
	}

	private static void fill(Map<String, Integer> xp, Map<String, Integer> levels, List<SkillValue> skills)
	{
		xp.clear();
		levels.clear();
		for (SkillValue v : skills)
		{
			xp.put(v.name, v.xp);
			levels.put(v.name, v.real);
		}
	}
}
