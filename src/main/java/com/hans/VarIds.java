package com.hans;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The raw game variables of vars.json, by group, as {varps, varbits}. Ids from docs/plugin-stage-b-research.md
 * (sections 4, 5, 6, 7, 8, 12); named gameval constants where they exist. Quest progress ids: QuestProgressVars.
 */
final class VarIds
{
	private VarIds()
	{
	}

	static Map<String, int[][]> groups()
	{
		Map<String, int[][]> g = new LinkedHashMap<>();
		g.put("diaries", diaries());
		g.put("combat_achievements", combatAchievements());
		g.put("music", new int[][]{MusicTracks.VARPS.clone(), {}}); // MUSICMULTI_1 to 27
		g.put("slayer", new int[][]{
			{395 /* task */, 394 /* amount left */, 4258 /* original amount */, 2096 /* Konar area */,
				5720 /* Mortimer tasks completed */},
			{VarbitID.SLAYER_TARGET_BOSSID, VarbitID.SLAYER_MASTER, VarbitID.SLAYER_POINTS,
				4069 /* tasks completed */, 5617 /* Wilderness tasks completed */,
				VarbitID.SLAYER_MODIFIER_ID, 15777 /* modifier value */, 15778 /* modifier negative */}});
		g.put("dailies", new int[][]{
			{1060 /* NZONE reward points */, 1527 /* Hand in the sand */, 359 /* Miscellania quest */},
			{VarbitID.NZONE_HERBBOXES_PURCHASED, 4539 /* Zaff */, 4547 /* Cromperty */, 4540 /* Lundail */,
				4549 /* Bert */, 4559 /* flax */, 4563 /* Rantz */, 4543 /* Robin */, 7939 /* Thirus */,
				VarbitID.IRONMAN, VarbitID.MISC_COFFERS, VarbitID.MISC_APPROVAL,
				// the diary tiers that unlock the dailies
				4479, 4459, 4466, 4475, 4471, 4488, 4489, 4490, 7926}});
		List<Integer> counts = new ArrayList<>();
		counts.add(2943);
		counts.add(2944);
		range(counts, 4613, 4622);
		g.put("collection_log_counts", new int[][]{toArray(counts), {}});
		// Clue completion varps, raw: client script 2352 reads all seven, so the client is sent them. 1111, 1112 and
		// 1354 held the old 16-bit tier counts (the _DEPRECATED varbits) and no longer change; which tier 1533, 1534 and
		// 2201 hold is not known yet (#118: confirm in game). The per-tier counts come from chat, in killcounts.json.
		g.put("clue_counts", new int[][]{{VarPlayerID.COMPLETED_CLUES, VarPlayerID.COMPLETED_CLUES1,
			VarPlayerID.COMPLETED_CLUES2, VarPlayerID.COMPLETED_CLUES3, VarPlayerID.COMPLETED_CLUES4,
			VarPlayerID.COMPLETED_CLUES5, VarPlayerID.COMPLETED_CLUES_TOTAL}, {}});
		// each quest's progress, for its next step in the web app (reference/quest_steps.json)
		g.put("quest_progress", new int[][]{QuestProgressVars.VARPS, QuestProgressVars.VARBITS});
		return g;
	}

	private static int[][] diaries()
	{
		List<Integer> varps = new ArrayList<>();
		range(varps, 1176, 1187);
		range(varps, 1192, 1200);
		range(varps, 1000, 1002);
		varps.add(2085);
		varps.add(2086);

		List<Integer> varbits = new ArrayList<>();
		for (Object[] area : ToolkitData.DIARIES)
		{
			for (int id : (int[]) area[1])
			{
				varbits.add(id); // tier complete
			}
		}
		range(varbits, 4499, 4538); // reward claimed: Ardougne to Lumbridge, 4 tiers each
		for (int id : new int[]{3577, 3598, 3610, 4567})
		{
			varbits.add(id); // Karamja rewards
		}
		range(varbits, 7929, 7932); // Kourend reward
		varbits.add(2423); // tasks done per tier: Karamja easy
		range(varbits, 6288, 6330); // Karamja medium to Wilderness/Western, all tiers
		range(varbits, 7933, 7936); // Kourend counts
		range(varbits, 4448, 4457); // started
		varbits.add(7924);
		range(varbits, 3566, 3575); // Karamja per-task varbits
		range(varbits, 3579, 3597);
		range(varbits, 3600, 3609);
		return new int[][]{toArray(varps), toArray(new ArrayList<>(new LinkedHashSet<>(varbits)))};
	}

	private static int[][] combatAchievements()
	{
		List<Integer> varbits = new ArrayList<>();
		for (int id : ToolkitData.CA_TIER_VARBITS)
		{
			varbits.add(id);
		}
		range(varbits, 12885, 12890); // tasks completed per tier
		for (int id : new int[]{4132, 10660, 10661, 14812, 14813, 14814})
		{
			varbits.add(id); // point thresholds
		}
		varbits.add(VarbitID.CA_POINTS);
		return new int[][]{ToolkitData.CA_TASK_VARPS.clone(), toArray(new ArrayList<>(new LinkedHashSet<>(varbits)))};
	}

	private static void range(List<Integer> into, int from, int to)
	{
		for (int id = from; id <= to; id++)
		{
			into.add(id);
		}
	}

	private static int[] toArray(List<Integer> in)
	{
		int[] out = new int[in.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = in.get(i);
		}
		return out;
	}
}
