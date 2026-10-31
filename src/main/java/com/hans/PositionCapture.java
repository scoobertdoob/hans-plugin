// Adapted from OSRS Toolkit Exporter (c) Gorsokk, BSD-2-Clause, https://github.com/Gorsokk/osrs-toolkit
package com.hans;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Future;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * position.json: where the player stands. A move to another world or under another key is written on its tick; steps
 * on the same world at most every {@link #EVERY_TICKS} ticks (the file is "where the player is now", so a walk need not
 * rewrite it on every tile), and the last tile not yet written is written at logout.
 */
final class PositionCapture implements Capture
{
	static final int EVERY_TICKS = 5; // 3 seconds

	private final CaptureContext ctx;
	private WorldPoint lastWritten;
	private ProfileKey lastKey;
	private int lastWorld;
	private int tick;
	private int writtenTick;
	private PositionSnapshot unwritten; // a step read but held back by EVERY_TICKS, for the logout
	private ProfileKey unwrittenKey;

	PositionCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		tick++;
		if (!ctx.config.exportPosition())
		{
			lastWritten = null;
			unwritten = null;
			return;
		}
		ProfileKey key = ctx.login.reading();
		if (key == null || !ctx.json.enabled())
		{
			return;
		}

		WorldPoint pos = local.getWorldLocation();
		int world = ctx.client.getWorld();
		if (pos == null || unchanged(pos, key, world, lastWritten, lastKey, lastWorld))
		{
			unwritten = null; // back on the tile last written
			return;
		}
		PositionSnapshot snapshot = snapshot(pos, key, world, now);
		if (key.equals(lastKey) && world == lastWorld && tick - writtenTick < EVERY_TICKS)
		{
			unwritten = snapshot; // a step: written once the gap is over, or at logout
			unwrittenKey = key;
			return;
		}

		lastWritten = pos;
		lastKey = key;
		lastWorld = world;
		writtenTick = tick;
		unwritten = null;
		ctx.json.write(key, "position.json", snapshot);
	}

	@Override
	public Future<?> onLogout(Instant now)
	{
		PositionSnapshot last = unwritten;
		unwritten = null;
		lastWritten = null; // the next login writes its first tile, whatever the file was left holding
		lastKey = null;
		if (last != null && ctx.config.exportPosition() && ctx.json.enabled())
		{
			return ctx.json.write(unwrittenKey, "position.json", last); // read and tagged before the logout
		}
		return null;
	}

	private static PositionSnapshot snapshot(WorldPoint pos, ProfileKey key, int world, Instant now)
	{
		PositionSnapshot snapshot = new PositionSnapshot();
		snapshot.exported_at = now.toString();
		snapshot.player_name = key.name;
		snapshot.world = world;
		snapshot.plane = pos.getPlane();
		snapshot.x = pos.getX();
		snapshot.y = pos.getY();
		snapshot.region_id = pos.getRegionID();
		snapshot.region_x = pos.getRegionX();
		snapshot.region_y = pos.getRegionY();
		return snapshot;
	}

	/** True when neither the tile, the profile key nor the world differs from what was last written. */
	static boolean unchanged(WorldPoint pos, ProfileKey key, int world, WorldPoint lastPos, ProfileKey lastKey,
		int lastWorld)
	{
		return pos.equals(lastPos) && Objects.equals(key, lastKey) && world == lastWorld;
	}

	@SuppressWarnings("unused")
	private static class PositionSnapshot
	{
		String exported_at;
		String player_name;
		int world;
		int plane;
		int x;
		int y;
		int region_id;
		int region_x;
		int region_y;
	}
}
