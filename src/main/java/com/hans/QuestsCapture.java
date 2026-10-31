package com.hans;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;

/**
 * quests.json: the state of every quest of the game's quest table. Re-read at once when the quest points or a known
 * quest progress variable change. Quests whose variable is not known (not in {@link QuestProgressVars}, or added to
 * the game later) are caught by a check after any other variable change, at most every {@link #RECHECK_TICKS}
 * ticks, which writes only when a quest's state differs.
 */
final class QuestsCapture implements Capture
{
	private static final int MAX_TRIES = 20; // the table may not be loaded in the first ticks
	static final int RECHECK_TICKS = 50; // ~30 s

	private static final Set<Integer> PROGRESS_VARPS = toSet(QuestProgressVars.VARPS);
	private static final Set<Integer> PROGRESS_VARBITS = toSet(QuestProgressVars.VARBITS);

	private final CaptureContext ctx;
	private boolean dirty;
	private int tries;
	private boolean maybe; // some other variable changed: a quest of unknown variable may have started
	private int sinceCheck;

	private volatile RsbuddyData.Quests latest; // the last reading, for the panel
	private ProfileKey latestKey; // whose reading latest is (client thread)

	/** The last quest reading taken for {@code key}, or null. Client thread. */
	RsbuddyData.Quests latest(ProfileKey key)
	{
		return key.equals(latestKey) ? latest : null;
	}

	/** The last quest reading of this login, or null. */
	RsbuddyData.Quests latest()
	{
		return latest;
	}

	QuestsCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
	}

	@Override
	public void onSettled(Player local, Instant now)
	{
		markDirty();
	}

	@Override
	public void onVarbitChanged(VarbitChanged event)
	{
		if (affectsQuests(event.getVarpId(), event.getVarbitId()))
		{
			markDirty(); // a quest started, advanced or finished (a miniquest worth 0 QP changes no quest points)
		}
		else
		{
			maybe = true;
		}
	}

	/** True for the quest points and for any variable that holds a quest's progress. */
	static boolean affectsQuests(int varpId, int varbitId)
	{
		return varpId == VarPlayerID.QP || PROGRESS_VARPS.contains(varpId) || PROGRESS_VARBITS.contains(varbitId);
	}

	@Override
	public void onConfigChanged(String key)
	{
		if ("exportCharacter".equals(key))
		{
			markDirty();
		}
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null || !ctx.config.exportCharacter() && !ctx.uploading())
		{
			return;
		}
		sinceCheck++;
		boolean recheck = !dirty && maybe && latest != null && sinceCheck >= RECHECK_TICKS;
		if (!dirty && !recheck)
		{
			return;
		}
		sinceCheck = 0;
		maybe = false;
		RsbuddyData.Quests quests = RsbuddyData.quests(now.toString(), questRows(), this::questName,
			this::questStatus, ctx.client.getVarpValue(VarPlayerID.QP));
		if (recheck)
		{
			if (quests != null && !sameStates(quests, latest))
			{
				latest = quests;
				latestKey = key;
				write(key, quests);
			}
			return;
		}
		if (quests != null)
		{
			latest = quests;
			latestKey = key;
			dirty = false;
			write(key, quests);
		}
		else if (++tries >= MAX_TRIES)
		{
			dirty = false; // the table never loaded; try again at the next login or quest completion
		}
	}

	private void write(ProfileKey key, RsbuddyData.Quests quests)
	{
		if (ctx.config.exportCharacter())
		{
			ctx.json.write(key, "quests.json", quests);
		}
	}

	/** True when both readings give every quest the same state and the same quest points (times aside). */
	static boolean sameStates(RsbuddyData.Quests a, RsbuddyData.Quests b)
	{
		if (a.quest_points != b.quest_points || a.quests == null || b.quests == null
			|| a.quests.size() != b.quests.size())
		{
			return false;
		}
		for (int i = 0; i < a.quests.size(); i++)
		{
			RsbuddyData.QuestState x = a.quests.get(i);
			RsbuddyData.QuestState y = b.quests.get(i);
			if (x.id != y.id || !Objects.equals(x.state, y.state))
			{
				return false;
			}
		}
		return true;
	}

	private static Set<Integer> toSet(int[] ids)
	{
		Set<Integer> out = new HashSet<>();
		for (int id : ids)
		{
			out.add(id);
		}
		return out;
	}

	private void markDirty()
	{
		dirty = true;
		tries = 0;
	}

	private List<Integer> questRows()
	{
		try
		{
			return ctx.client.getDBTableRows(DBTableID.Quest.ID);
		}
		catch (RuntimeException e)
		{
			return null; // the table is not loaded (yet)
		}
	}

	private String questName(int row)
	{
		try
		{
			Object[] cell = ctx.client.getDBTableField(row, DBTableID.Quest.COL_DISPLAYNAME, 0);
			return cell != null && cell.length > 0 && cell[0] instanceof String ? (String) cell[0] : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	// The game's own quest status script takes the quest's row of the Quest table.
	private int questStatus(int row)
	{
		ctx.client.runScript(ScriptID.QUEST_STATUS_GET, row);
		return ctx.client.getIntStack()[0];
	}
}
