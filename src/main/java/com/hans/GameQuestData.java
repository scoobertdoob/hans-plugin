// Adapted from OSRS Toolkit Exporter (c) Gorsokk, BSD-2-Clause, https://github.com/Gorsokk/osrs-toolkit
package com.hans;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.DBTableID;

/**
 * game_quests.json: the game's own Quest table (levels and quests required, start tile and NPC, experience rewarded,
 * the game's raw codes), in the shape the OSRS Toolkit Exporter writes it.
 *
 * The table is read through {@link QuestSource}, so that decoding can be tested without a client.
 * Values the table does not hold for a row take these defaults: 0 for numbers, -1 for the series, no list entries.
 */
final class GameQuestData
{
	static final int FORMAT_VERSION = 1;
	static final String SOURCE = RsbuddyData.SOURCE_PREFIX + ", game client database table Quest (DBTableID.Quest)";
	static final String NOTE = "Read from the game client, not from any website. difficulty_code (0-5) and length_code (0-4) "
		+ "are the game's raw codes. requirement_combat_raw, prerequisite_direct and prerequisite_indirect are raw values "
		+ "whose meaning is not known. release_date_parts are the three raw values of the release date. start.x/y/plane "
		+ "come from the packed start coordinate. required_quests are resolved from row ids. npc_names has null where "
		+ "the game itself names the NPC \"null\". Rows of the table whose name has no letter (placeholders for quests "
		+ "not defined yet) are left out and counted in skipped_placeholder_rows. A skill the client has no name for is "
		+ "written as skill#<id>.";

	private GameQuestData()
	{
	}

	/** Reads of the game's Quest table. Every method answers null (or an empty list) rather than throwing. */
	interface QuestSource
	{
		/** The row ids of the Quest table; null when it is not loaded. */
		List<Integer> rows();

		/** The values of one column of one row at a tuple index; null when the row has no value there. */
		Object[] cell(int row, int column, int tupleIndex);

		/** The game's name of an NPC; null when unknown. */
		String npcName(int npcId);

		/** The name of the skill with this id; null when the client has no name for it. */
		String skillName(int skillId);
	}

	static Export decode(QuestSource src, String exportedAt, int clientRevision)
	{
		Export out = new Export();
		out.exported_at = exportedAt;
		out.format_version = FORMAT_VERSION;
		out.client_revision = clientRevision;
		out.source = SOURCE;
		out.note = NOTE;
		out.quests = new ArrayList<>();
		List<Integer> rows = src.rows();
		if (rows == null)
		{
			rows = Collections.emptyList();
		}
		for (int row : rows)
		{
			String name = text(src.cell(row, DBTableID.Quest.COL_DISPLAYNAME, 0));
			if (name == null || !hasLetter(name))
			{
				out.skipped_placeholder_rows++;
				continue;
			}
			out.quests.add(quest(src, row, name));
		}
		out.quest_count = out.quests.size();
		return out;
	}

	private static Quest quest(QuestSource src, int row, String name)
	{
		Quest q = new Quest();
		q.row_id = row;
		q.quest_id = number(src.cell(row, DBTableID.Quest.COL_ID, 0), 0, 0);
		q.name = name;
		q.members = number(src.cell(row, DBTableID.Quest.COL_MEMBERS, 0), 0, 0) != 0;
		q.difficulty_code = number(src.cell(row, DBTableID.Quest.COL_DIFFICULTY, 0), 0, 0);
		q.length_code = number(src.cell(row, DBTableID.Quest.COL_LENGTH, 0), 0, 0);
		q.quest_points = number(src.cell(row, DBTableID.Quest.COL_QUESTPOINTS, 0), 0, 0);
		q.series = number(src.cell(row, DBTableID.Quest.COL_SERIES, 0), 0, -1);
		q.series_number = number(src.cell(row, DBTableID.Quest.COL_SERIESNO, 0), 0, 0);
		q.release_date_parts = releaseDate(src, row);
		q.levels = skillPairs(src, row, DBTableID.Quest.COL_REQUIREMENT_STATS);
		q.recommended_levels = skillPairs(src, row, DBTableID.Quest.COL_RECOMMENDED_STATS);
		q.required_quest_rows = new ArrayList<>();
		q.required_quests = new ArrayList<>();
		Object[] required = src.cell(row, DBTableID.Quest.COL_REQUIREMENT_QUESTS, 0);
		if (required != null)
		{
			for (int i = 0; i < required.length; i++)
			{
				int requiredRow = number(required, i, -1);
				if (requiredRow < 0)
				{
					continue;
				}
				String requiredName = text(src.cell(requiredRow, DBTableID.Quest.COL_DISPLAYNAME, 0));
				q.required_quest_rows.add(requiredRow);
				if (requiredName != null)
				{
					q.required_quests.add(requiredName);
				}
			}
		}
		q.required_quest_points = number(src.cell(row, DBTableID.Quest.COL_REQUIREMENT_QUESTPOINTS, 0), 0, 0);
		q.requirement_combat_raw = number(src.cell(row, DBTableID.Quest.COL_REQUIREMENT_COMBAT, 0), 0, 0);
		q.prerequisite_direct = number(src.cell(row, DBTableID.Quest.COL_PREREQUISITE_DIRECT, 0), 0, 0);
		q.prerequisite_indirect = number(src.cell(row, DBTableID.Quest.COL_PREREQUISITE_INDIRECT, 0), 0, 0);
		q.start = start(src, row);
		q.xp_rewards = new ArrayList<>();
		for (int[] pair : pairs(src, row, DBTableID.Quest.COL_STAT_XP_AWARDED))
		{
			XpReward reward = new XpReward();
			reward.skill_id = pair[0];
			reward.skill = skillName(src, pair[0]);
			reward.xp = pair[1];
			q.xp_rewards.add(reward);
		}
		q.unstarted_state = number(src.cell(row, DBTableID.Quest.COL_UNSTARTEDSTATE, 0), 0, 0);
		q.end_state = number(src.cell(row, DBTableID.Quest.COL_ENDSTATE, 0), 0, 0);
		q.recommendation_reason = text(src.cell(row, DBTableID.Quest.COL_CR_RECOMMENDATION_REASON, 0));
		return q;
	}

	/** The three raw values of the release date: all in tuple 0, or one in each of the tuples 0, 1 and 2. */
	private static List<Integer> releaseDate(QuestSource src, int row)
	{
		List<Integer> parts = new ArrayList<>();
		Object[] first = src.cell(row, DBTableID.Quest.COL_RELEASEDATE, 0);
		if (first != null && first.length >= 3)
		{
			for (int i = 0; i < 3; i++)
			{
				parts.add(number(first, i, 0));
			}
			return parts;
		}
		parts.add(number(first, 0, 0));
		parts.add(number(src.cell(row, DBTableID.Quest.COL_RELEASEDATE, 1), 0, 0));
		parts.add(number(src.cell(row, DBTableID.Quest.COL_RELEASEDATE, 2), 0, 0));
		return parts;
	}

	private static List<SkillLevel> skillPairs(QuestSource src, int row, int column)
	{
		List<SkillLevel> out = new ArrayList<>();
		for (int[] pair : pairs(src, row, column))
		{
			SkillLevel l = new SkillLevel();
			l.skill_id = pair[0];
			l.skill = skillName(src, pair[0]);
			l.level = pair[1];
			out.add(l);
		}
		return out;
	}

	/**
	 * A column that is a list of (number, number) pairs, such as (skill, level). The game's table gives the first
	 * numbers at tuple index 0 and the second numbers at tuple index 1; if there is no tuple index 1, the pairs
	 * are read from tuple 0 two at a time.
	 */
	static List<int[]> pairs(QuestSource src, int row, int column)
	{
		List<int[]> out = new ArrayList<>();
		Object[] a = src.cell(row, column, 0);
		if (a == null || a.length == 0)
		{
			return out;
		}
		Object[] b = src.cell(row, column, 1);
		if (b != null && b.length == a.length)
		{
			for (int i = 0; i < a.length; i++)
			{
				out.add(new int[]{number(a, i, 0), number(b, i, 0)});
			}
		}
		else if (b == null && a.length % 2 == 0)
		{
			for (int i = 0; i + 1 < a.length; i += 2)
			{
				out.add(new int[]{number(a, i, 0), number(a, i + 1, 0)});
			}
		}
		return out;
	}

	private static Start start(QuestSource src, int row)
	{
		Start s = new Start();
		int packed = number(src.cell(row, DBTableID.Quest.COL_STARTCOORD, 0), 0, 0);
		if (packed > 0)
		{
			// the packed coordinate: plane in bits 28-29, x in bits 14-27, y in bits 0-13
			s.x = (packed >> 14) & 0x3FFF;
			s.y = packed & 0x3FFF;
			s.plane = (packed >> 28) & 3;
		}
		s.npc_ids = new ArrayList<>();
		s.npc_names = new ArrayList<>();
		Object[] npcs = src.cell(row, DBTableID.Quest.COL_STARTNPC, 0);
		if (npcs != null)
		{
			for (int i = 0; i < npcs.length; i++)
			{
				int id = number(npcs, i, -1);
				if (id < 0)
				{
					continue;
				}
				String npcName = src.npcName(id);
				s.npc_ids.add(id);
				s.npc_names.add(npcName == null || "null".equals(npcName) ? null : npcName); // the game names some NPCs "null"
			}
		}
		return s;
	}

	private static String skillName(QuestSource src, int skillId)
	{
		String name = src.skillName(skillId);
		return name != null ? name : "skill#" + skillId;
	}

	// ------------------------------------------------------------------ cell values

	/** The i-th value of a cell as an int (a number, or a boolean as 1/0); dflt when it is missing. */
	static int number(Object[] cell, int i, int dflt)
	{
		if (cell == null || i < 0 || i >= cell.length || cell[i] == null)
		{
			return dflt;
		}
		Object v = cell[i];
		if (v instanceof Number)
		{
			return ((Number) v).intValue();
		}
		if (v instanceof Boolean)
		{
			return ((Boolean) v) ? 1 : 0;
		}
		return dflt;
	}

	private static String text(Object[] cell)
	{
		return cell != null && cell.length > 0 && cell[0] instanceof String ? (String) cell[0] : null;
	}

	private static boolean hasLetter(String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			if (Character.isLetter(s.charAt(i)))
			{
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- json models

	@SuppressWarnings("unused")
	static class Export
	{
		String exported_at;
		int format_version;
		int client_revision;
		String source;
		String note;
		int quest_count;
		int skipped_placeholder_rows;
		List<Quest> quests;
	}

	@SuppressWarnings("unused")
	static class Quest
	{
		int row_id;
		int quest_id;
		String name;
		boolean members;
		int difficulty_code;
		int length_code;
		int quest_points;
		int series;
		int series_number;
		List<Integer> release_date_parts;
		List<SkillLevel> levels;
		List<SkillLevel> recommended_levels;
		List<Integer> required_quest_rows;
		List<String> required_quests;
		int required_quest_points;
		int requirement_combat_raw;
		int prerequisite_direct;
		int prerequisite_indirect;
		Start start;
		List<XpReward> xp_rewards;
		int unstarted_state;
		int end_state;
		String recommendation_reason; // left out of the file when the game has none
	}

	@SuppressWarnings("unused")
	static class SkillLevel
	{
		int skill_id;
		String skill;
		int level;
	}

	@SuppressWarnings("unused")
	static class XpReward
	{
		int skill_id;
		String skill;
		int xp;
	}

	@SuppressWarnings("unused")
	static class Start
	{
		int x;
		int y;
		int plane;
		List<Integer> npc_ids;
		List<String> npc_names;
	}
}
