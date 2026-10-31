package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hans.RsbuddyData.Session;
import com.hans.RsbuddyData.SessionsFile;
import com.hans.RsbuddyData.SkillValue;
import com.hans.RsbuddyData.Snapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/** Regression tests for the session, vars, progress, quest, farming and position fixes. Synthetic data only. */
public class ProgressCapturesTest
{
	private static Snapshot snap(int world)
	{
		return new Snapshot("Tester", 1L, world, Collections.emptyList(), 3,
			new ArrayList<>(Arrays.asList(new SkillValue("Attack", 1, 1, 0))));
	}

	private static Session session(String start, String end)
	{
		Session s = new Session();
		s.start = start;
		s.end = end;
		s.last_update = start;
		return s;
	}

	@Test
	public void sessionHistoryIsLoadedBeforeTheFirstRealWrite()
	{
		SessionCapture c = new SessionCapture(null, null, (a, s, x) -> { });
		SessionsFile disk = new SessionsFile();
		disk.sessions = new ArrayList<>(Arrays.asList(session("2026-01-01T00:00:00Z", "2026-01-01T01:00:00Z")));
		Session live = session("2026-02-01T00:00:00Z", null);
		ProfileKey key = new ProfileKey(1L, GameMode.MAIN, "Tester");
		SessionsFile out = c.compose(key, live, () -> disk);
		assertEquals(2, out.sessions.size());
		// a later write must not read again, and keeps both
		SessionsFile again = c.compose(key, live, () -> null);
		assertEquals(2, again.sessions.size());
	}

	@Test
	public void refreshedSnapshotCarriesNewWorldAndPosition()
	{
		Map<String, Integer> pos = new LinkedHashMap<>();
		pos.put("x", 5);
		Snapshot s = SessionCapture.refreshed(snap(301), 302, pos);
		assertEquals(302, s.world);
		assertSame(pos, s.position);
		Snapshot kept = SessionCapture.refreshed(s, 303, null);
		assertSame(pos, kept.position);
		assertEquals(303, kept.world);
	}

	@Test
	public void miscellaniaEntriesSurviveWhenUnreadable()
	{
		Map<Integer, VarsCapture.Entry> old = new TreeMap<>();
		old.put(7, new VarsCapture.Entry(100, "t"));
		Map<Integer, VarsCapture.Entry> into = new TreeMap<>();
		VarsCapture.keepPrevious(into, old, 7);
		VarsCapture.keepPrevious(into, old, 8); // nothing before: nothing invented
		assertEquals(100, into.get(7).v);
		assertFalse(into.containsKey(8));
		into.put(9, new VarsCapture.Entry(5, "n"));
		VarsCapture.keepPrevious(into, old, 9);
		assertEquals(5, into.get(9).v);
	}

	@Test
	public void batchWaitsForSettleAndKeepsTheWindow()
	{
		ProgressCaptures.Batch b = new ProgressCaptures.Batch();
		assertFalse(b.tick(false)); // not settled: no read
		assertTrue(b.tick(true)); // the initial read happens once settled
		b.markDirty();
		assertFalse(b.tick(true)); // inside the 30 s window
		assertTrue(b.waiting()); // so logout has something to flush
		for (int i = 0; i < ProgressCaptures.EVERY_TICKS - 2; i++)
		{
			b.tick(true);
		}
		assertTrue(b.tick(true));
		assertFalse(b.waiting());
	}

	@Test
	public void questVariablesMarkQuestsStale()
	{
		assertTrue(QuestsCapture.affectsQuests(QuestProgressVars.VARPS[3], -1));
		assertTrue(QuestsCapture.affectsQuests(-1, QuestProgressVars.VARBITS[3]));
		assertFalse(QuestsCapture.affectsQuests(-1, -1));
	}

	@Test
	public void sharedRegionsAreSplitIntoTheirAreas()
	{
		assertEquals("12083:falador", FarmingCapture.areaKey(new WorldPoint(3050, 3272, 0)));
		assertEquals("12083:port_sarim", FarmingCapture.areaKey(new WorldPoint(3050, 3271, 0)));
		assertEquals("12082", FarmingCapture.areaKey(new WorldPoint(3050, 3240, 0))); // Port Sarim only
		assertEquals("11317:catherby", FarmingCapture.areaKey(new WorldPoint(2830, 3445, 0)));
		assertEquals("11317:catherby_fruit_tree", FarmingCapture.areaKey(new WorldPoint(2830, 3445, 1)));
		assertNull(FarmingCapture.areaKey(new WorldPoint(2830, 3445, 2))); // neither is sent up there
		assertEquals("11317:catherby_fruit_tree", FarmingCapture.areaKey(new WorldPoint(2860, 3430, 0)));
		assertEquals("11062", FarmingCapture.areaKey(new WorldPoint(2805, 3465, 1)));
		assertNull(FarmingCapture.areaName(new WorldPoint(2805, 3465, 0)));
		assertEquals("10548", FarmingCapture.areaKey(new WorldPoint(2670, 3336, 0))); // Ardougne is one area
		assertEquals("10548", FarmingCapture.areaKey(new WorldPoint(2670, 3335, 0)));
	}

	@Test
	public void fossilIslandIsOnlyTrustedOnTheGroundAwayFromTheStairs()
	{
		assertEquals("14651", FarmingCapture.areaKey(new WorldPoint(3700, 3800, 0)));
		assertNull(FarmingCapture.areaKey(new WorldPoint(3700, 3800, 1)));
		assertNull(FarmingCapture.areaKey(new WorldPoint(3753, 3869, 0)));
		assertNull(FarmingCapture.areaKey(new WorldPoint(3728, 3831, 0)));
		assertEquals("14907", FarmingCapture.areaKey(new WorldPoint(3728, 3820, 0)));
	}

	@Test
	public void questReadingsCompareByState()
	{
		RsbuddyData.Quests a = quests(5, "FINISHED");
		assertTrue(QuestsCapture.sameStates(a, quests(5, "FINISHED")));
		assertFalse(QuestsCapture.sameStates(a, quests(5, "IN_PROGRESS")));
		assertFalse(QuestsCapture.sameStates(a, quests(6, "FINISHED")));
	}

	private static RsbuddyData.Quests quests(int qp, String state)
	{
		RsbuddyData.Quests q = RsbuddyData.quests("t", Arrays.asList(1, 2), r -> "Quest " + r, r -> 0, qp);
		q.quests.get(0).state = state;
		return q;
	}

	@Test
	public void positionChangesWithWorldOrAccount()
	{
		WorldPoint p = new WorldPoint(3200, 3200, 0);
		ProfileKey a = new ProfileKey(1L, GameMode.MAIN, "A");
		ProfileKey b = new ProfileKey(2L, GameMode.MAIN, "B");
		ProfileKey league = new ProfileKey(1L, "seasonal-demonic_pacts_league", "A");
		assertTrue(PositionCapture.unchanged(p, a, 301, new WorldPoint(3200, 3200, 0), a, 301));
		assertFalse(PositionCapture.unchanged(p, a, 302, p, a, 301)); // same-tile world hop
		assertFalse(PositionCapture.unchanged(p, b, 301, p, a, 301)); // account switch
		assertFalse(PositionCapture.unchanged(p, league, 301, p, a, 301)); // same account, another mode
	}
}
