// Adapted from OSRS Toolkit Exporter (c) Gorsokk, BSD-2-Clause, https://github.com/Gorsokk/osrs-toolkit
package com.hans;

import java.time.Instant;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import net.runelite.api.Player;
import net.runelite.api.events.VarbitChanged;

/**
 * The two progress files that are read from game variables: diaries.json and combat_achievements.json.
 * Same timing as the OSRS Toolkit Exporter: any game variable change marks them dirty, and they are written at most
 * once every {@link #EVERY_TICKS} ticks (~30 s), and at login. Both are switched by "Export character progress".
 */
final class ProgressCaptures
{
	static final int EVERY_TICKS = 50;

	private ProgressCaptures()
	{
	}

	static Capture diaries(CaptureContext ctx)
	{
		return new VarCapture(ctx, "diaries.json",
			(now, c) -> ToolkitData.diaries(now, c.client::getVarbitValue));
	}

	static Capture combatAchievements(CaptureContext ctx)
	{
		return new VarCapture(ctx, "combat_achievements.json",
			(now, c) -> ToolkitData.combatAchievements(now, c.client::getVarpValue, c.client::getVarbitValue));
	}

	/**
	 * The batching rule of one file: dirty marks, a write at most every {@link #EVERY_TICKS} ticks, nothing before the
	 * login has settled, and a flush of whatever is still waiting at logout. Client thread only.
	 */
	static final class Batch
	{
		private boolean dirty = true;
		private int ticksSince = EVERY_TICKS;

		void markDirty()
		{
			dirty = true;
		}

		/** A new reading is wanted at once (login, setting switched on). */
		void markDirtyNow()
		{
			dirty = true;
			ticksSince = EVERY_TICKS;
		}

		/** @return true when the file is to be (re)built and written on this tick */
		boolean tick(boolean settled)
		{
			if (!settled)
			{
				return false;
			}
			ticksSince++;
			if (dirty && ticksSince >= EVERY_TICKS)
			{
				dirty = false;
				ticksSince = 0;
				return true;
			}
			return false;
		}

		/** True when a change is still waiting for the next window. */
		boolean waiting()
		{
			return dirty;
		}

		void written()
		{
			dirty = false;
		}
	}

	/** One file computed from game variables. */
	private static final class VarCapture implements Capture
	{
		private final CaptureContext ctx;
		private final String fileName;
		private final BiFunction<String, CaptureContext, Object> build;
		private final Batch batch = new Batch();
		private Object pending; // a reading taken while waiting; written at logout if still unwritten
		private ProfileKey pendingKey; // whose reading pending is
		private boolean stale; // a variable changed since pending (or the last write) was read

		VarCapture(CaptureContext ctx, String fileName, BiFunction<String, CaptureContext, Object> build)
		{
			this.ctx = ctx;
			this.fileName = fileName;
			this.build = build;
		}

		@Override
		public void onLogin()
		{
			batch.markDirtyNow();
		}

		@Override
		public void onVarbitChanged(VarbitChanged event)
		{
			batch.markDirty();
			stale = true;
		}

		@Override
		public void onConfigChanged(String key)
		{
			if ("exportCharacter".equals(key))
			{
				batch.markDirtyNow();
			}
		}

		@Override
		public void onGameTick(Player local, Instant now)
		{
			ProfileKey key = ctx.login.reading(); // pending keeps the key it was read under
			if (key == null || !ctx.config.exportCharacter() || !ctx.json.enabled())
			{
				return;
			}
			if (batch.tick(true))
			{
				pending = null;
				stale = false;
				ctx.json.write(key, fileName, build.apply(now.toString(), ctx));
			}
			else if (batch.waiting() && stale)
			{
				stale = false;
				pending = build.apply(now.toString(), ctx);
				pendingKey = key;
			}
		}

		@Override
		public Future<?> onLogout(Instant now)
		{
			Object last = pending;
			ProfileKey key = pendingKey;
			pending = null;
			pendingKey = null;
			if (last != null && batch.waiting() && ctx.config.exportCharacter() && ctx.json.enabled())
			{
				batch.written();
				ctx.json.write(key, fileName, last);
			}
			return null;
		}
	}
}
