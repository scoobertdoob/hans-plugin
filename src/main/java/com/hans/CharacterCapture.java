package com.hans;

import com.hans.RsbuddyData.SkillValue;
import com.hans.RsbuddyData.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.StatChanged;

/**
 * character.json: levels and experience of every skill.
 *
 * The reading is kept current every tick that XP changed, and stamped with the time it was read, so the reading
 * kept for logout is never stale; the file itself is written at most every {@link #EVERY_TICKS} ticks.
 * The sessions use these readings too (see {@link SessionCapture}).
 */
final class CharacterCapture implements Capture
{
	private static final int EVERY_TICKS = 50; // ~30 s between character.json writes while XP changes

	private final CaptureContext ctx;
	private Snapshot last;           // kept current every tick while XP changes
	private Instant lastAt;          // when last was read: files are stamped with this, never later
	private ProfileKey lastKey;      // whose reading last is: it is written to that key's folder only
	private boolean unwritten;       // last is newer than the last character.json
	private boolean dirty;
	private boolean justWritten;     // a write cycle ran on this tick (read by SessionCapture)
	private int ticksSince;

	CharacterCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
	}

	@Override
	public void onSettled(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null)
		{
			return;
		}
		last = snapshot(local);
		lastAt = now;
		lastKey = key;
		dirty = true;
		ticksSince = EVERY_TICKS;
	}

	@Override
	public void onStatChanged(StatChanged event)
	{
		dirty = true;
	}

	@Override
	public void onConfigChanged(String key)
	{
		if ("exportCharacter".equals(key))
		{
			dirty = true;
			ticksSince = EVERY_TICKS;
		}
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		justWritten = false;
		ProfileKey key = ctx.login.reading();
		if (key == null)
		{
			return; // no reading now (the client may show another account): a stat change stays dirty
		}
		ticksSince++;
		if (dirty)
		{
			// read every tick that XP changed, so the reading kept for logout is never stale
			dirty = false;
			unwritten = true;
			last = snapshot(local);
			lastAt = now;
			lastKey = key;
		}
		if (unwritten && ticksSince >= EVERY_TICKS)
		{
			unwritten = false;
			ticksSince = 0;
			justWritten = true;
			write();
		}
	}

	/** The newest reading (null before the first). */
	Snapshot latest()
	{
		return last;
	}

	/** When {@link #latest} was read. */
	Instant latestAt()
	{
		return lastAt;
	}

	/** True once, after a tick on which character.json was due (and so the sessions should take the reading too). */
	boolean takeWritten()
	{
		boolean was = justWritten;
		justWritten = false;
		return was;
	}

	/** Writes the newest reading now if it has not been written yet (at logout), under the key it was read for. */
	void flush()
	{
		if (last != null && unwritten)
		{
			unwritten = false;
			write();
		}
	}

	private void write()
	{
		if (ctx.config.exportCharacter())
		{
			ctx.json.write(lastKey, "character.json", RsbuddyData.character(lastAt.toString(), last));
		}
	}

	private Snapshot snapshot(Player local)
	{
		List<SkillValue> skills = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue; // not a skill: the total is computed from the others
			}
			skills.add(new SkillValue(skill.getName(), ctx.client.getRealSkillLevel(skill),
				ctx.client.getBoostedSkillLevel(skill), ctx.client.getSkillExperience(skill)));
		}
		List<String> types = new ArrayList<>();
		for (WorldType type : ctx.client.getWorldType())
		{
			types.add(type.name());
		}
		Snapshot s = new Snapshot(local.getName(), ctx.client.getAccountHash(), ctx.client.getWorld(), types,
			local.getCombatLevel(), skills);
		WorldPoint pos = local.getWorldLocation();
		if (pos != null)
		{
			s.position = new LinkedHashMap<>();
			s.position.put("x", pos.getX());
			s.position.put("y", pos.getY());
			s.position.put("plane", pos.getPlane());
		}
		return s;
	}
}
