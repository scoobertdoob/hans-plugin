package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.http.api.worlds.WorldType;
import org.junit.Test;

/** Naming a Deadman season from RuneLite's world list, and never from one that may be stale. */
public class WorldSeasonsTest
{
	private WorldResult list; // the list RuneLite holds
	private int refreshes;
	private long nanos;
	private final WorldSeasons seasons = new WorldSeasons(() -> list, () -> refreshes++, () -> nanos);

	private static WorldResult list(String activity, WorldType... types)
	{
		List<World> worlds = new ArrayList<>();
		EnumSet<WorldType> set = EnumSet.noneOf(WorldType.class);
		for (WorldType t : types)
		{
			set.add(t);
		}
		worlds.add(World.builder().id(345).types(set).activity(activity).build());
		WorldResult r = new WorldResult();
		r.setWorlds(worlds);
		return r;
	}

	/** RuneLite fetches a list (the WorldsFetch event). */
	private void fetch(WorldResult r)
	{
		list = r;
		seasons.fetched(r);
	}

	private void minutes(long n)
	{
		nanos += TimeUnit.MINUTES.toNanos(n);
	}

	@Test
	public void theListHeldBeforeThePluginStartedIsNotTrustedUntilItIsReplaced()
	{
		list = list("Deadman: Annihilation", WorldType.DEADMAN); // could be left from any time before
		assertNull(seasons.activity(345));
		assertEquals(1, refreshes);
		fetch(list("Deadman: Armageddon", WorldType.DEADMAN)); // RuneLite fetched it again
		assertEquals("Deadman: Armageddon", seasons.activity(345));
	}

	@Test
	public void aListAppearingWhereThereWasNoneIsTrusted()
	{
		assertNull(seasons.activity(345));
		fetch(list("Deadman: Annihilation", WorldType.DEADMAN)); // the answer to the refresh it asked for
		assertEquals("Deadman: Annihilation", seasons.activity(345));
	}

	@Test
	public void aListThatIsNoLongerRecentIsNotTrustedAtTheNextLogin()
	{
		seasons.activity(345);
		fetch(list("Deadman: Annihilation", WorldType.DEADMAN));
		assertEquals("Deadman: Annihilation", seasons.activity(345));
		seasons.forget(); // logged out, and the client sat on the login screen (no refresh) past a season change
		minutes(21);
		int before = refreshes;
		assertNull(seasons.activity(345));
		assertEquals(before + 1, refreshes);
		minutes(1);
		assertNull(seasons.activity(345));
		assertEquals(before + 1, refreshes); // asked at most every 5 minutes
		minutes(5);
		assertNull(seasons.activity(345));
		assertEquals(before + 2, refreshes);
		fetch(list("Deadman: Armageddon", WorldType.DEADMAN));
		assertEquals("Deadman: Armageddon", seasons.activity(345));
	}

	@Test
	public void aListFetchedOnTheLoginScreenIsAsOldAsItsFetch()
	{
		seasons.activity(345);
		fetch(list("Deadman: Annihilation", WorldType.DEADMAN));
		assertEquals("Deadman: Annihilation", seasons.activity(345));
		seasons.forget();
		minutes(60 * 24 * 30);
		fetch(list("Deadman: Annihilation", WorldType.DEADMAN)); // fetched on the login screen, now
		minutes(25); // and the player logs in much later
		assertNull(seasons.activity(345));
		fetch(list("Deadman: Armageddon", WorldType.DEADMAN)); // the refresh it asked for
		assertEquals("Deadman: Armageddon", seasons.activity(345));
		seasons.forget();
		minutes(5);
		assertEquals("Deadman: Armageddon", seasons.activity(345)); // recent enough
	}

	@Test
	public void aListIsRecentForTwentyMinutesFromItsFetch()
	{
		fetch(list("Deadman: Annihilation", WorldType.DEADMAN)); // RuneLite's own refresh
		minutes(19);
		assertEquals("Deadman: Annihilation", seasons.activity(345));
		seasons.forget();
		minutes(2);
		assertNull(seasons.activity(345));
	}

	@Test
	public void aNameHoldsForTheWholeLoginAndIsReadAfreshAtTheNext()
	{
		seasons.activity(345);
		fetch(list("Deadman: Annihilation", WorldType.DEADMAN));
		assertEquals("Deadman: Annihilation", seasons.activity(345));
		minutes(60);
		fetch(list("Deadman: Annihilation Finale", WorldType.DEADMAN));
		assertEquals("Deadman: Annihilation", seasons.activity(345)); // never moved to another folder mid-login
		seasons.forget();
		assertEquals("Deadman: Annihilation Finale", seasons.activity(345));
	}

	@Test
	public void theListMustCallTheWorldADeadmanWorldWithAName()
	{
		seasons.activity(345);
		fetch(list("Trade - Members", WorldType.MEMBERS)); // the world number now belongs to another kind of world
		assertNull(seasons.activity(345));
		fetch(list("   ", WorldType.DEADMAN));
		assertNull(seasons.activity(345));
		fetch(list(null, WorldType.DEADMAN));
		assertNull(seasons.activity(345));
		assertNull(seasons.activity(999)); // not in the list (an unlisted world)
	}

	@Test
	public void aTrustedListThatDoesNotNameTheWorldAsksForNoRefresh()
	{
		fetch(list("Deadman: Annihilation", WorldType.DEADMAN));
		for (int i = 0; i < 19; i++)
		{
			minutes(1); // an unlisted world doesn't make the plugin ask while the list is trusted
			assertNull(seasons.activity(999));
			assertEquals(0, refreshes);
		}
		minutes(2); // the list is no longer recent: now it asks, once per 5 minutes
		assertNull(seasons.activity(999));
		assertEquals(1, refreshes);
		assertNull(seasons.activity(999));
		assertEquals(1, refreshes);
	}

	@Test
	public void aDelayedRefreshIsTakenUpWhenItComesWithoutAskingAgain()
	{
		assertNull(seasons.activity(345)); // no list at all: asks
		assertEquals(1, refreshes);
		minutes(2);
		assertNull(seasons.activity(345)); // the refresh is still on its way: the 5-minute spacing holds
		assertEquals(1, refreshes);
		minutes(1);
		fetch(list("Deadman: Annihilation", WorldType.DEADMAN)); // it arrives late
		assertEquals("Deadman: Annihilation", seasons.activity(345));
		assertEquals(1, refreshes);
	}
}
