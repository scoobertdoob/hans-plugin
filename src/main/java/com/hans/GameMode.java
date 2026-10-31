package com.hans;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * The game mode of the current world, as a folder-safe slug ({@code main}, {@code deadman-<season>},
 * {@code seasonal-<season>}, ...): main and seasonal characters never share a folder, and neither do two seasons
 * (docs/plugin-mode-separation.md section 1). Decided from the world types the client reports, checked in a fixed
 * order, because RuneLite's {@link RuneScapeProfileType} reads any seasonal world as the newest season (even a beta
 * one); the profile type only names a Leagues season. A Deadman season is named by its world's activity in the world
 * list ({@link WorldSeasons}), because RuneLite has one profile type for every Deadman season. When unsure, a mode
 * gets its own folder; a season that can't be named gets none ({@link #UNIDENTIFIED}).
 */
final class GameMode
{
	static final String MAIN = "main";
	static final String BETA = "beta";
	static final String TOURNAMENT = "tournament";
	/**
	 * A seasonal or Deadman world whose season can't be named: nothing is written (no folder could hold it without
	 * risking two seasons in one), and what arrives goes to the quarantine. No season's slug can be this one: they all
	 * start with {@code seasonal-} or {@code deadman-}.
	 */
	static final String UNIDENTIFIED = "unidentified-season";
	/** The longest season part of a slug, so a folder name stays short. */
	static final int SEASON_MAX = 40;

	/** Profile types that name no season: on a seasonal world they mean the season is not known to this RuneLite. */
	private static final Set<RuneScapeProfileType> NOT_A_SEASON = EnumSet.of(RuneScapeProfileType.STANDARD,
		RuneScapeProfileType.BETA, RuneScapeProfileType.DEADMAN, RuneScapeProfileType.PVP_ARENA,
		RuneScapeProfileType.QUEST_SPEEDRUNNING);

	private GameMode()
	{
	}

	/**
	 * The mode of the world the client is on now. The world list is asked only on a Deadman world the game saves, and
	 * RuneLite's profile type only on a seasonal world that is not a Deadman one. Client thread.
	 */
	static String current(Client client, WorldSeasons seasons)
	{
		Set<WorldType> types = worldTypes(client);
		boolean deadman = types.contains(WorldType.DEADMAN);
		RuneScapeProfileType profile = types.contains(WorldType.SEASONAL) && !deadman
			? RuneScapeProfileType.getCurrent(client) : null;
		String activity = deadman && !wipedWorld(types) ? seasons.activity(client.getWorld()) : null;
		return of(types, profile, activity);
	}

	/** The client's world types; empty when it has none yet. */
	static Set<WorldType> worldTypes(Client client)
	{
		Set<WorldType> types = client.getWorldType();
		return types == null ? Collections.emptySet() : types;
	}

	/**
	 * The mode slug for a world. Only {@code [a-z0-9_-]}, so always a legal folder name.
	 *
	 * @param profile  RuneLite's profile type, used only to name a Leagues season (may be null)
	 * @param activity the world's activity in the world list, used only to name a Deadman season (may be null)
	 */
	static String of(Set<WorldType> types, RuneScapeProfileType profile, String activity)
	{
		if (types.contains(WorldType.BETA_WORLD) || types.contains(WorldType.NOSAVE_MODE))
		{
			return BETA;
		}
		if (types.contains(WorldType.TOURNAMENT_WORLD))
		{
			return TOURNAMENT;
		}
		if (types.contains(WorldType.DEADMAN))
		{
			// every Deadman season is a fresh character: each gets its own folder, named by the season
			String season = season(activity);
			return season == null ? UNIDENTIFIED
				: (types.contains(WorldType.SEASONAL) ? "seasonal-deadman-" : "deadman-") + season;
		}
		if (types.contains(WorldType.SEASONAL))
		{
			return profile == null || NOT_A_SEASON.contains(profile) ? UNIDENTIFIED
				: "seasonal-" + profile.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
		}
		if (types.contains(WorldType.PVP_ARENA))
		{
			return "pvp_arena";
		}
		if (types.contains(WorldType.QUEST_SPEEDRUNNING))
		{
			return "quest_speedrunning";
		}
		if (types.contains(WorldType.FRESH_START_WORLD))
		{
			return "fresh_start"; // not yet known to be the main character: kept apart until checked
		}
		return MAIN;
	}

	/**
	 * The season part of a Deadman slug: the world's activity in lower case, each run of other characters one
	 * underscore, at most {@link #SEASON_MAX} long; null when nothing is left ("-" is the list's "no activity").
	 * A name that has to be cut ends in a short hash of the whole name, so two long names with the same start can't
	 * share a folder.
	 */
	static String season(String activity)
	{
		if (activity == null)
		{
			return null;
		}
		String s = activity.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		if (s.length() > SEASON_MAX)
		{
			String cut = s.substring(0, SEASON_MAX - 9).replaceAll("_+$", "");
			s = cut + "_" + hash8(s);
		}
		return s.isEmpty() ? null : s;
	}

	/** The first 8 hex digits of the SHA-256 of a name. */
	private static String hash8(String name)
	{
		try
		{
			byte[] d = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 4; i++)
			{
				hex.append(String.format("%02x", d[i] & 0xff));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e); // SHA-256 is required on every Java platform
		}
	}

	private static boolean wipedWorld(Set<WorldType> types)
	{
		return types.contains(WorldType.BETA_WORLD) || types.contains(WorldType.NOSAVE_MODE)
			|| types.contains(WorldType.TOURNAMENT_WORLD);
	}

	/** True for worlds the game wipes (beta, tournament): what arrives there is dropped, never kept (owner, 28 Oct). */
	static boolean wiped(String mode)
	{
		return BETA.equals(mode) || TOURNAMENT.equals(mode);
	}

	/**
	 * Whether a login on this mode settles and writes: not on a wiped world, and not on a season that can't be named
	 * (whose evidence is quarantined instead).
	 */
	static boolean saves(String mode)
	{
		return mode != null && !wiped(mode) && !UNIDENTIFIED.equals(mode);
	}
}
