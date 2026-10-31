// Adapted from OSRS Toolkit Exporter (c) Gorsokk, BSD-2-Clause, https://github.com/Gorsokk/osrs-toolkit
package com.hans;

import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.DBTableID;

/**
 * game_quests.json: the game's own Quest table, read from the client so that it always matches the game version.
 * Written once per login, into the plugin's folder itself (it belongs to no account), and only from a main-game world:
 * a beta or seasonal world may show quests the main game does not have. The table may not be loaded in the very
 * first ticks, so a few tries are made.
 */
@Slf4j
final class GameQuestsCapture implements Capture
{
	private static final int MAX_TRIES = 5;

	private final CaptureContext ctx;
	private final GameQuestData.QuestSource source;
	private boolean dirty = true;
	private int tries;

	GameQuestsCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
		this.source = new ClientQuestSource(ctx);
	}

	@Override
	public void onLogin()
	{
		markDirty();
	}

	@Override
	public void onConfigChanged(String key)
	{
		if ("exportGameQuests".equals(key))
		{
			markDirty();
		}
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (!dirty || key == null || !key.isMain() || !ctx.config.exportGameQuests() || !ctx.json.enabled())
		{
			return;
		}
		GameQuestData.Export data = GameQuestData.decode(source, now.toString(), ctx.client.getRevision());
		if (data.quest_count == 0)
		{
			// the table may not be loaded yet: try again on a later tick, a few times only
			if (++tries >= MAX_TRIES)
			{
				dirty = false;
				log.debug("Hans: the quest table was not available");
			}
			return;
		}
		dirty = false;
		ctx.json.writeShared("game_quests.json", data);
	}

	private void markDirty()
	{
		dirty = true;
		tries = 0;
	}

	/** The Quest table, read from the client. Client thread only. */
	private static final class ClientQuestSource implements GameQuestData.QuestSource
	{
		private final CaptureContext ctx;

		ClientQuestSource(CaptureContext ctx)
		{
			this.ctx = ctx;
		}

		@Override
		public List<Integer> rows()
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

		@Override
		public Object[] cell(int row, int column, int tupleIndex)
		{
			try
			{
				return ctx.client.getDBTableField(row, column, tupleIndex);
			}
			catch (RuntimeException e)
			{
				return null;
			}
		}

		@Override
		public String npcName(int npcId)
		{
			try
			{
				NPCComposition npc = ctx.client.getNpcDefinition(npcId);
				return npc == null ? null : npc.getName();
			}
			catch (RuntimeException e)
			{
				return null;
			}
		}

		@Override
		public String skillName(int skillId)
		{
			Skill[] skills = Skill.values();
			return skillId >= 0 && skillId < skills.length && skills[skillId] != Skill.OVERALL
				? skills[skillId].getName()
				: null;
		}
	}
}
