package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.DBTableID;
import org.junit.Test;

/**
 * The files must have the exact shape of the OSRS Toolkit Exporter's (key names, order, nesting, value types).
 * The expected texts below are taken from files that exporter wrote.
 */
public class ExporterFormatTest
{
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Compares two JSON texts including the order of keys. */
	private static void assertSameJson(String expected, Object actual)
	{
		assertEquals(new JsonParser().parse(expected).toString(), new JsonParser().parse(GSON.toJson(actual)).toString());
	}

	// -------------------------------------------------------------------- game_quests.json

	/** A fake Quest table: values by row, column and tuple index. */
	private static final class FakeTable implements GameQuestData.QuestSource
	{
		final List<Integer> rows = new ArrayList<>();
		final Map<String, Object[]> cells = new HashMap<>();
		final Map<Integer, String> npcs = new HashMap<>();
		final boolean parallelPairs;

		FakeTable(boolean parallelPairs)
		{
			this.parallelPairs = parallelPairs;
		}

		void put(int row, int column, int tuple, Object... values)
		{
			cells.put(row + ":" + column + ":" + tuple, values);
		}

		void putPairs(int row, int column, int... flat)
		{
			if (parallelPairs)
			{
				Object[] first = new Object[flat.length / 2];
				Object[] second = new Object[flat.length / 2];
				for (int i = 0; i < flat.length; i += 2)
				{
					first[i / 2] = flat[i];
					second[i / 2] = flat[i + 1];
				}
				cells.put(row + ":" + column + ":0", first);
				cells.put(row + ":" + column + ":1", second);
			}
			else
			{
				Object[] all = new Object[flat.length];
				for (int i = 0; i < flat.length; i++)
				{
					all[i] = flat[i];
				}
				cells.put(row + ":" + column + ":0", all);
			}
		}

		@Override
		public List<Integer> rows()
		{
			return rows;
		}

		@Override
		public Object[] cell(int row, int column, int tupleIndex)
		{
			return cells.get(row + ":" + column + ":" + tupleIndex);
		}

		@Override
		public String npcName(int npcId)
		{
			return npcs.get(npcId);
		}

		@Override
		public String skillName(int skillId)
		{
			String[] names = {"Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking",
				"Woodcutting", "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore",
				"Agility", "Thieving", "Slayer"};
			return skillId < names.length ? names[skillId] : null;
		}
	}

	private static FakeTable animalMagnetism(boolean parallelPairs)
	{
		FakeTable t = new FakeTable(parallelPairs);
		final int q = 0;
		t.rows.add(q);
		t.rows.add(99); // a placeholder row: a quest called "."
		t.put(99, DBTableID.Quest.COL_DISPLAYNAME, 0, ".");
		for (int[] required : new int[][]{{44, 0}, {120, 0}, {111, 0}})
		{
			t.put(required[0], DBTableID.Quest.COL_DISPLAYNAME, 0,
				required[0] == 44 ? "Ernest the Chicken" : required[0] == 120 ? "The Restless Ghost" : "Priest in Peril");
		}
		t.put(q, DBTableID.Quest.COL_ID, 0, 123);
		t.put(q, DBTableID.Quest.COL_DISPLAYNAME, 0, "Animal Magnetism");
		t.put(q, DBTableID.Quest.COL_MEMBERS, 0, 1);
		t.put(q, DBTableID.Quest.COL_DIFFICULTY, 0, 1);
		t.put(q, DBTableID.Quest.COL_LENGTH, 0, 2);
		t.put(q, DBTableID.Quest.COL_QUESTPOINTS, 0, 1);
		t.put(q, DBTableID.Quest.COL_RELEASEDATE, 0, 12);
		t.put(q, DBTableID.Quest.COL_RELEASEDATE, 1, 12);
		t.put(q, DBTableID.Quest.COL_RELEASEDATE, 2, 2006);
		t.putPairs(q, DBTableID.Quest.COL_REQUIREMENT_STATS, 8, 35, 4, 30, 12, 19, 18, 18);
		t.put(q, DBTableID.Quest.COL_REQUIREMENT_QUESTS, 0, 44, 120, 111);
		t.put(q, DBTableID.Quest.COL_REQUIREMENT_COMBAT, 0, 3);
		t.put(q, DBTableID.Quest.COL_PREREQUISITE_DIRECT, 0, 1);
		t.put(q, DBTableID.Quest.COL_STARTCOORD, 0, (3094 << 14) | 3359);
		t.put(q, DBTableID.Quest.COL_STARTNPC, 0, 4408);
		t.npcs.put(4408, "null"); // the game names this NPC "null"
		t.putPairs(q, DBTableID.Quest.COL_STAT_XP_AWARDED, 12, 10000, 9, 10000, 18, 10000, 8, 25000);
		t.put(q, DBTableID.Quest.COL_ENDSTATE, 0, 240);
		t.put(q, DBTableID.Quest.COL_CR_RECOMMENDATION_REASON, 0, "Learn how to equip Ava's ranged devices.");
		return t;
	}

	private static final String ANIMAL_MAGNETISM = "{\"row_id\":0,\"quest_id\":123,\"name\":\"Animal Magnetism\","
		+ "\"members\":true,\"difficulty_code\":1,\"length_code\":2,\"quest_points\":1,\"series\":-1,\"series_number\":0,"
		+ "\"release_date_parts\":[12,12,2006],\"levels\":[{\"skill_id\":8,\"skill\":\"Woodcutting\",\"level\":35},"
		+ "{\"skill_id\":4,\"skill\":\"Ranged\",\"level\":30},{\"skill_id\":12,\"skill\":\"Crafting\",\"level\":19},"
		+ "{\"skill_id\":18,\"skill\":\"Slayer\",\"level\":18}],\"recommended_levels\":[],\"required_quest_rows\":[44,120,111],"
		+ "\"required_quests\":[\"Ernest the Chicken\",\"The Restless Ghost\",\"Priest in Peril\"],"
		+ "\"required_quest_points\":0,\"requirement_combat_raw\":3,\"prerequisite_direct\":1,\"prerequisite_indirect\":0,"
		+ "\"start\":{\"x\":3094,\"y\":3359,\"plane\":0,\"npc_ids\":[4408],\"npc_names\":[null]},"
		+ "\"xp_rewards\":[{\"skill_id\":12,\"skill\":\"Crafting\",\"xp\":10000},{\"skill_id\":9,\"skill\":\"Fletching\",\"xp\":10000},"
		+ "{\"skill_id\":18,\"skill\":\"Slayer\",\"xp\":10000},{\"skill_id\":8,\"skill\":\"Woodcutting\",\"xp\":25000}],"
		+ "\"unstarted_state\":0,\"end_state\":240,\"recommendation_reason\":\"Learn how to equip Ava's ranged devices.\"}";

	@Test
	public void gameQuestsMatchTheExportersFile()
	{
		for (boolean parallel : new boolean[]{true, false})
		{
			GameQuestData.Export export = GameQuestData.decode(animalMagnetism(parallel), "2026-10-26T10:50:55.620573300Z", 241);
			assertEquals(1, export.quest_count);
			assertEquals(1, export.skipped_placeholder_rows);
			assertTrue(GameQuestData.SOURCE.startsWith("RSBuddy companion plugin"));
			assertSameJson(ANIMAL_MAGNETISM, export.quests.get(0));
			// top level: the keys and their order
			assertEquals(Arrays.asList("exported_at", "format_version", "client_revision", "source", "note", "quest_count",
				"skipped_placeholder_rows", "quests"),
				new ArrayList<>(new JsonParser().parse(GSON.toJson(export)).getAsJsonObject().keySet()));
		}
	}

	@Test
	public void aMissingRecommendationReasonIsLeftOut()
	{
		FakeTable t = animalMagnetism(true);
		t.cells.remove("0:" + DBTableID.Quest.COL_CR_RECOMMENDATION_REASON + ":0");
		String json = GSON.toJson(GameQuestData.decode(t, "x", 241).quests.get(0));
		assertFalse(json.contains("recommendation_reason"));
	}

	@Test
	public void theReleaseDateMayComeInOneTuple()
	{
		FakeTable t = animalMagnetism(true);
		t.put(0, DBTableID.Quest.COL_RELEASEDATE, 0, 12, 12, 2006);
		t.cells.remove("0:" + DBTableID.Quest.COL_RELEASEDATE + ":1");
		t.cells.remove("0:" + DBTableID.Quest.COL_RELEASEDATE + ":2");
		assertEquals(Arrays.asList(12, 12, 2006), GameQuestData.decode(t, "x", 241).quests.get(0).release_date_parts);
	}

	// ------------------------------------------------------------------------ items

	@Test
	public void itemFilesMatchTheExportersFile()
	{
		ToolkitData.Items items = ToolkitData.items("2026-10-26T10:52:11.555559800Z", "equipment",
			new int[]{11851, 11853, -1, 5}, new int[]{1, 1, 1, 0},
			id -> id == 11851 ? "Graceful hood" : "Graceful cape");
		items.source = items.source.replace("RSBuddy companion plugin", "OSRS Toolkit Exporter"); // only the label differs
		assertSameJson("{\"exported_at\":\"2026-10-26T10:52:11.555559800Z\",\"format_version\":1,"
			+ "\"source\":\"OSRS Toolkit Exporter, read from the game client\",\"kind\":\"equipment\",\"item_count\":2,"
			+ "\"items\":[{\"slot\":0,\"id\":11851,\"quantity\":1,\"name\":\"Graceful hood\"},"
			+ "{\"slot\":1,\"id\":11853,\"quantity\":1,\"name\":\"Graceful cape\"}]}", items);
	}

	@Test
	public void sourcesStartWithTheCompanionLabel()
	{
		assertTrue(RsbuddyData.SOURCE.startsWith("RSBuddy companion plugin"));
		assertTrue(ToolkitData.diaries("x", id -> 0).source.startsWith("RSBuddy companion plugin"));
		assertTrue(ToolkitData.combatAchievements("x", id -> 0, id -> 0).source.startsWith("RSBuddy companion plugin"));
		assertTrue(ToolkitData.items("x", "bank", new int[0], new int[0], id -> "").source.startsWith("RSBuddy companion plugin"));
	}

	// ----------------------------------------------------------------------- diaries

	@Test
	public void diariesHaveTwelveRegionsOfFourTiers()
	{
		ToolkitData.Diaries d = ToolkitData.diaries("x", id -> 0);
		assertEquals(12, d.diaries.size());
		assertEquals(48, d.summary.tiers_possible);
		assertEquals(Arrays.asList("easy", "medium", "hard", "elite"),
			new ArrayList<>(d.diaries.get("Ardougne").keySet()));
		assertEquals(Arrays.asList("exported_at", "format_version", "source", "note", "summary", "diaries"),
			new ArrayList<>(new JsonParser().parse(GSON.toJson(d)).getAsJsonObject().keySet()));
	}

	@Test
	public void combatAchievementsCountTaskBits()
	{
		ToolkitData.CombatAchievements ca = ToolkitData.combatAchievements("x", id -> 0b1011, id -> 6);
		assertEquals(3 * ToolkitData.CA_TASK_VARPS.length, ca.summary.total_tasks_completed);
		assertEquals(6, ca.summary.points);
		assertEquals(Arrays.asList("easy", "medium", "hard", "elite", "master", "grandmaster"),
			new ArrayList<>(ca.tier_status_raw.keySet()));
	}
}
