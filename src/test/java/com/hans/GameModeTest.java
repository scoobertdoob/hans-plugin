package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;
import org.junit.Test;

/** The mode resolver table (docs/plugin-mode-separation.md section 1). */
public class GameModeTest
{
	private static final RuneScapeProfileType LEAGUE = RuneScapeProfileType.DEMONIC_PACTS_LEAGUE;

	private static String mode(RuneScapeProfileType profile, WorldType... types)
	{
		Set<WorldType> set = EnumSet.noneOf(WorldType.class);
		for (WorldType t : types)
		{
			set.add(t);
		}
		return GameMode.of(set, profile, null);
	}

	private static String deadman(String activity, WorldType... types)
	{
		Set<WorldType> set = EnumSet.of(WorldType.DEADMAN);
		for (WorldType t : types)
		{
			set.add(t);
		}
		return GameMode.of(set, RuneScapeProfileType.DEADMAN, activity);
	}

	@Test
	public void theMainGameIsEveryOrdinaryWorld()
	{
		assertEquals("main", mode(null));
		assertEquals("main", mode(RuneScapeProfileType.STANDARD, WorldType.MEMBERS));
		for (WorldType t : new WorldType[]{WorldType.PVP, WorldType.HIGH_RISK, WorldType.SKILL_TOTAL,
			WorldType.LAST_MAN_STANDING, WorldType.LEGACY_ONLY, WorldType.EOC_ONLY, WorldType.BOUNTY,
			WorldType.HARDCORE_MAIN})
		{
			assertEquals(t.name(), "main", mode(null, WorldType.MEMBERS, t));
		}
	}

	@Test
	public void theTableIsCheckedInOrder()
	{
		assertEquals("beta", mode(RuneScapeProfileType.BETA, WorldType.BETA_WORLD));
		assertEquals("beta", mode(RuneScapeProfileType.BETA, WorldType.NOSAVE_MODE));
		assertEquals("tournament", mode(null, WorldType.TOURNAMENT_WORLD));
		assertEquals("seasonal-demonic_pacts_league", mode(LEAGUE, WorldType.SEASONAL, WorldType.MEMBERS));
		assertEquals("deadman-deadman_annihilation", deadman("Deadman: Annihilation"));
		assertEquals(GameMode.UNIDENTIFIED, mode(RuneScapeProfileType.DEADMAN, WorldType.DEADMAN));
		assertEquals("beta", deadman("Deadman: Annihilation", WorldType.BETA_WORLD));
		assertEquals("tournament", deadman("Deadman: Annihilation", WorldType.TOURNAMENT_WORLD));
		assertEquals("pvp_arena", mode(RuneScapeProfileType.PVP_ARENA, WorldType.PVP_ARENA));
		assertEquals("quest_speedrunning", mode(null, WorldType.QUEST_SPEEDRUNNING));
		assertEquals("fresh_start", mode(null, WorldType.FRESH_START_WORLD, WorldType.MEMBERS));
	}

	@Test
	public void aLeaguesBetaWorldIsBetaNotTheLiveLeague()
	{
		// RuneLite's profile type reads this world as the live league; the resolver must not
		assertEquals("beta", mode(LEAGUE, WorldType.SEASONAL, WorldType.BETA_WORLD));
		assertEquals("beta", mode(LEAGUE, WorldType.SEASONAL, WorldType.NOSAVE_MODE));
		assertEquals("tournament", mode(LEAGUE, WorldType.SEASONAL, WorldType.TOURNAMENT_WORLD));
	}

	@Test
	public void aSeasonalDeadmanWorldIsNamedByItsOwnSeasonNotTheLeague()
	{
		// RuneLite reads any seasonal world as the newest league: that names no Deadman season
		Set<WorldType> types = EnumSet.of(WorldType.SEASONAL, WorldType.DEADMAN);
		assertEquals("seasonal-deadman-deadman_reborn", GameMode.of(types, LEAGUE, "Deadman Reborn"));
		assertEquals("seasonal-deadman-deadman_reborn", GameMode.of(types, RuneScapeProfileType.DEADMAN_REBORN,
			"Deadman Reborn"));
		assertEquals(GameMode.UNIDENTIFIED, GameMode.of(types, LEAGUE, null));
	}

	@Test
	public void twoDeadmanSeasonsAreTwoModes()
	{
		assertNotEquals(deadman("Deadman: Annihilation"), deadman("Deadman: Armageddon"));
		assertEquals(deadman("Deadman: Annihilation"), deadman("  DEADMAN -  annihilation!"));
	}

	@Test
	public void aSeasonThatCannotBeNamedRecordsNothing()
	{
		for (String activity : new String[]{null, "", "   ", "-", "!!!"})
		{
			assertEquals(String.valueOf(activity), GameMode.UNIDENTIFIED, deadman(activity));
		}
		assertEquals(GameMode.UNIDENTIFIED, mode(RuneScapeProfileType.STANDARD, WorldType.SEASONAL));
		assertEquals(GameMode.UNIDENTIFIED, mode(null, WorldType.SEASONAL));
		assertFalse(GameMode.saves(GameMode.UNIDENTIFIED));
		assertFalse(GameMode.wiped(GameMode.UNIDENTIFIED)); // real play: quarantined, not dropped
	}

	@Test
	public void everySlugIsALegalFolderName()
	{
		for (RuneScapeProfileType p : RuneScapeProfileType.values())
		{
			for (WorldType t : WorldType.values())
			{
				String slug = mode(p, WorldType.SEASONAL, t);
				assertTrue(slug, slug.matches("[a-z0-9_-]{1,64}"));
				slug = mode(p, t);
				assertTrue(slug, slug.matches("[a-z0-9_-]{1,64}"));
			}
		}
		StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 30; i++)
		{
			longName.append("Deadman ");
		}
		for (String activity : new String[]{"Deadman: Annihilation", "Ünïcödé Ⅻ", "../..\\CON", "a. ", "AUX",
			longName.toString(), "x_______________________________________y"})
		{
			for (String slug : new String[]{deadman(activity), deadman(activity, WorldType.SEASONAL)})
			{
				assertTrue(slug, slug.matches("[a-z0-9_-]{1,64}"));
				assertTrue(slug, slug.startsWith("deadman-") || slug.startsWith("seasonal-deadman-"));
				assertFalse(slug, slug.endsWith("_"));
			}
		}
	}

	@Test
	public void aCutSeasonNameKeepsAHashSoLongNamesCannotCollide()
	{
		String stem = "Deadman: Annihilation of the Wilderness Season ";
		String a = deadman(stem + "One");
		String b = deadman(stem + "Two");
		assertNotEquals(a, b); // identical for the first 40 characters
		assertEquals(a, deadman(stem + "One")); // stable
		assertTrue(a, a.matches("deadman-[a-z0-9_]{1,31}_[0-9a-f]{8}"));
		assertTrue(a, a.length() <= "deadman-".length() + GameMode.SEASON_MAX);
		assertEquals("deadman-deadman_annihilation", deadman("Deadman: Annihilation")); // short names carry no hash
	}

	@Test
	public void wipedWorldsAreNotSaved()
	{
		assertFalse(GameMode.saves("beta"));
		assertFalse(GameMode.saves("tournament"));
		assertFalse(GameMode.saves(null));
		assertTrue(GameMode.saves("main"));
		assertTrue(GameMode.saves("deadman-deadman_annihilation"));
		assertTrue(GameMode.wiped("beta"));
		assertFalse(GameMode.wiped("main"));
	}

	@Test
	public void theCurrentModeUsesRuneLitesProfileTypeOnlyForTheSeasonName() throws IOException
	{
		GameFake g = new GameFake();
		assertEquals("main", GameMode.current(g.client, g.seasons));
		g.worldTypes = EnumSet.of(WorldType.SEASONAL, WorldType.MEMBERS);
		assertEquals("seasonal-" + RuneScapeProfileType.getCurrent(g.client).name().toLowerCase(),
			GameMode.current(g.client, g.seasons));
		g.worldTypes = EnumSet.of(WorldType.SEASONAL, WorldType.BETA_WORLD);
		assertEquals("beta", GameMode.current(g.client, g.seasons));
		assertEquals(0, g.listRefreshes); // the world list is not asked off a Deadman world
	}

	@Test
	public void theCurrentDeadmanSeasonIsNamedByARecentWorldList() throws IOException
	{
		GameFake g = new GameFake();
		g.world = 345;
		g.activities.put(345, "Deadman: Annihilation");
		g.worldTypes = EnumSet.of(WorldType.DEADMAN);
		assertEquals(GameMode.UNIDENTIFIED, GameMode.current(g.client, g.seasons)); // no list yet: one is asked for
		assertEquals(1, g.listRefreshes);
		assertEquals("deadman-deadman_annihilation", GameMode.current(g.client, g.seasons));
		g.worldTypes = EnumSet.of(WorldType.DEADMAN, WorldType.BETA_WORLD);
		assertEquals("beta", GameMode.current(g.client, g.seasons));
	}
}
