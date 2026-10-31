// Adapted from OSRS Toolkit Exporter (c) Gorsokk, BSD-2-Clause, https://github.com/Gorsokk/osrs-toolkit
package com.hans;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * The plain structures RSBuddy writes, and the functions that build them from plain values.
 *
 * character.json and quests.json keep exactly the structure and key names of the OSRS Toolkit Exporter's files,
 * so the existing importer reads them unchanged (character.json has one extra key, account_hash).
 * Reading the game client is kept out of this class so that it can be tested on its own.
 */
final class RsbuddyData
{
	static final int FORMAT_VERSION = 1;
	/** Every "source" string starts with this; the importer uses it to label the origin of the data. */
	static final String SOURCE_PREFIX = "RSBuddy companion plugin";
	static final String SOURCE = SOURCE_PREFIX + ", read from the game client";

	private RsbuddyData()
	{
	}

	// ------------------------------------------------------------------ snapshot

	/** One reading of the character, taken on the client thread and never changed afterwards. */
	static final class Snapshot
	{
		final String name;
		final long accountHash;
		final int world;
		final List<String> worldTypes;
		final int combatLevel;
		final List<SkillValue> skills;
		final long totalXp;
		/** The player's tile when read ({x, y, plane}); null when unknown. */
		Map<String, Integer> position;

		Snapshot(String name, long accountHash, int world, List<String> worldTypes, int combatLevel,
			List<SkillValue> skills)
		{
			this.name = name;
			this.accountHash = accountHash;
			this.world = world;
			this.worldTypes = worldTypes;
			this.combatLevel = combatLevel;
			this.skills = skills;
			long xp = 0;
			for (SkillValue s : skills)
			{
				xp += s.xp;
			}
			this.totalXp = xp;
		}
	}

	static final class SkillValue
	{
		final String name;
		final int real;
		final int boosted;
		final int xp;

		SkillValue(String name, int real, int boosted, int xp)
		{
			this.name = name;
			this.real = real;
			this.boosted = boosted;
			this.xp = xp;
		}
	}

	// ------------------------------------------------------------------ character

	static CharacterFile character(String exportedAt, Snapshot s)
	{
		CharacterFile out = new CharacterFile();
		out.exported_at = exportedAt;
		out.format_version = FORMAT_VERSION;
		out.source = SOURCE;
		out.account_name = s.name;
		out.account_hash = s.accountHash;
		out.world = s.world;
		out.world_types = new ArrayList<>(s.worldTypes);
		out.combat_level = s.combatLevel;
		out.stats = new LinkedHashMap<>();
		for (SkillValue v : s.skills)
		{
			Stat st = new Stat();
			st.real_level = v.real;
			st.boosted_level = v.boosted;
			st.experience = v.xp;
			out.stats.put(v.name, st);
			out.total_level += v.real;
			out.total_experience += v.xp;
		}
		return out;
	}

	// --------------------------------------------------------------------- quests

	static final String FINISHED = "FINISHED";
	static final String IN_PROGRESS = "IN_PROGRESS";
	static final String NOT_STARTED = "NOT_STARTED";

	/** The game's quest status script answers 2 (finished), 1 (not started) or anything else (in progress). */
	static String questState(int status)
	{
		switch (status)
		{
			case 2:
				return FINISHED;
			case 1:
				return NOT_STARTED;
			default:
				return IN_PROGRESS;
		}
	}

	/**
	 * @param rows        the row ids of the game's Quest table (null: not loaded yet)
	 * @param nameOfRow   the quest's name for a row (null when unreadable)
	 * @param statusOfRow the game's quest status for a row (the row id is the quest id the status script takes)
	 * @param questPoints the player's quest points
	 * @return null when the table is not loaded yet
	 */
	static Quests quests(String exportedAt, List<Integer> rows, IntFunction<String> nameOfRow,
		IntUnaryOperator statusOfRow, int questPoints)
	{
		if (rows == null || rows.isEmpty())
		{
			return null;
		}
		Quests out = new Quests();
		out.exported_at = exportedAt;
		out.format_version = FORMAT_VERSION;
		out.source = SOURCE + " (the game's Quest table and its quest status script)";
		out.quest_points = questPoints;
		out.quests = new ArrayList<>();
		out.summary = new QuestSummary();
		for (int row : rows)
		{
			String name = nameOfRow.apply(row);
			if (name == null || !hasLetter(name))
			{
				continue; // unreadable, or a placeholder row (a quest called "."): not a quest yet
			}
			QuestState q = new QuestState();
			q.id = row;
			q.name = name;
			q.state = questState(statusOfRow.applyAsInt(row));
			out.quests.add(q);
			if (FINISHED.equals(q.state))
			{
				out.summary.finished++;
			}
			else if (NOT_STARTED.equals(q.state))
			{
				out.summary.not_started++;
			}
			else
			{
				out.summary.in_progress++;
			}
		}
		out.quests.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		return out;
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

	// ------------------------------------------------------------------- json models

	@SuppressWarnings("unused")
	static class CharacterFile
	{
		String exported_at;
		int format_version;
		String source;
		String account_name;
		long account_hash;
		int world;
		List<String> world_types;
		int combat_level;
		int total_level;
		long total_experience;
		Map<String, Stat> stats;
	}

	@SuppressWarnings("unused")
	static class Stat
	{
		int real_level;
		int boosted_level;
		int experience;
	}

	@SuppressWarnings("unused")
	static class Quests
	{
		String exported_at;
		int format_version;
		String source;
		int quest_points;
		QuestSummary summary;
		List<QuestState> quests;
	}

	@SuppressWarnings("unused")
	static class QuestSummary
	{
		int finished;
		int in_progress;
		int not_started;
	}

	@SuppressWarnings("unused")
	static class QuestState
	{
		int id;
		String name;
		String state;
	}

	/** sessions.json. */
	@SuppressWarnings("unused")
	static class SessionsFile
	{
		int format_version;
		String account_name;
		long account_hash;
		List<Session> sessions;
	}

	/**
	 * One play session, login to logout. "end" is null while it is live. "last_update" (not in the original
	 * field list) is when the live session was last written, so a session cut short by a crash can be closed
	 * at the right time the next time the plugin runs.
	 */
	@SuppressWarnings("unused")
	static class Session
	{
		String start;
		String end;
		String last_update;
		List<Integer> worlds = new ArrayList<>();
		Map<String, Integer> xp_start = new LinkedHashMap<>();
		Map<String, Integer> xp_end = new LinkedHashMap<>();
		Map<String, Integer> levels_start = new LinkedHashMap<>();
		Map<String, Integer> levels_end = new LinkedHashMap<>();
		/** The player's tile at the last update, so at logout once the session ends ({x, y, plane}). */
		Map<String, Integer> position_end;

		Session copy()
		{
			Session c = new Session();
			c.start = start;
			c.end = end;
			c.last_update = last_update;
			c.worlds = new ArrayList<>(worlds);
			c.xp_start = new LinkedHashMap<>(xp_start);
			c.xp_end = new LinkedHashMap<>(xp_end);
			c.levels_start = new LinkedHashMap<>(levels_start);
			c.levels_end = new LinkedHashMap<>(levels_end);
			c.position_end = position_end == null ? null : new LinkedHashMap<>(position_end);
			return c;
		}
	}
}
