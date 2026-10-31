package com.hans;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hans.RsbuddyData.Session;
import com.hans.RsbuddyData.SkillValue;
import com.hans.RsbuddyData.Snapshot;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the side panel shows, built on the client thread from data the captures already hold, then handed to the
 * Swing thread. Mirrors the web app's "Welcome back" tile (web/src/js/05a-welcome.js): this session, last session
 * and "Nearly there" (closest skill level, and the closest quest blocked only by levels). Immutable once built.
 */
final class PanelModel
{
	static final int[] XP = new int[100];

	static
	{
		int p = 0;
		for (int l = 1; l < 99; l++)
		{
			p += (int) Math.floor(l + 300 * Math.pow(2, l / 7.0));
			XP[l + 1] = p / 4;
		}
	}

	static final class Gain
	{
		final String skill;
		final int xp;

		Gain(String skill, int xp)
		{
			this.skill = skill;
			this.xp = xp;
		}
	}

	static final class Goal
	{
		final String skill; // icon, or null
		final String label;
		final String right;
		final double fraction;
		final String tip;
		final boolean quest;

		Goal(String skill, String label, String right, double fraction, String tip, boolean quest)
		{
			this.skill = skill;
			this.label = label;
			this.right = right;
			this.fraction = fraction;
			this.tip = tip;
			this.quest = quest;
		}
	}

	String account;                       // null: logged out
	Instant sessionStart;
	long sessionXp;
	final List<Gain> gains = new ArrayList<>();
	final List<String> levelUps = new ArrayList<>();
	String lastTitle;                     // e.g. "Thu 16 Oct"; null when there is no earlier session
	String lastLine;
	final List<Goal> goals = new ArrayList<>();

	static PanelModel loggedOut(Session previous)
	{
		PanelModel m = new PanelModel();
		m.last(previous);
		return m;
	}

	static PanelModel build(String account, Snapshot now, Session live, Session previous, RsbuddyData.Quests quests,
		List<QuestRequirements.Quest> reqs)
	{
		PanelModel m = new PanelModel();
		m.account = account;
		Map<String, Integer> xp = new HashMap<>();
		Map<String, Integer> lv = new HashMap<>();
		for (SkillValue s : now.skills)
		{
			xp.put(s.name, s.xp);
			lv.put(s.name, s.real);
		}
		if (live != null)
		{
			m.sessionStart = Instant.parse(live.start);
			for (Map.Entry<String, Integer> e : xp.entrySet())
			{
				int gained = e.getValue() - live.xp_start.getOrDefault(e.getKey(), e.getValue());
				if (gained > 0)
				{
					m.sessionXp += gained;
					m.gains.add(new Gain(e.getKey(), gained));
				}
				int from = live.levels_start.getOrDefault(e.getKey(), lv.get(e.getKey()));
				if (lv.get(e.getKey()) > from)
				{
					m.levelUps.add(e.getKey() + " " + from + " → " + lv.get(e.getKey()));
				}
			}
			m.gains.sort(Comparator.comparingInt((Gain g) -> -g.xp));
		}
		m.last(previous);
		m.nearlyThere(xp, lv, quests, reqs);
		return m;
	}

	private void last(Session s)
	{
		if (s == null || s.end == null)
		{
			return;
		}
		Instant a = Instant.parse(s.start), b = Instant.parse(s.end);
		lastTitle = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(b);
		long gained = sum(s.xp_end) - sum(s.xp_start);
		List<String> ups = new ArrayList<>();
		for (Map.Entry<String, Integer> e : s.levels_end.entrySet())
		{
			Integer from = s.levels_start.get(e.getKey());
			if (from != null && e.getValue() > from)
			{
				ups.add(e.getKey() + " " + e.getValue());
			}
		}
		lastLine = duration(Duration.between(a, b)) + " · +" + String.format("%,d", gained) + " XP"
			+ (ups.isEmpty() ? "" : " · " + String.join(", ", ups));
	}

	private void nearlyThere(Map<String, Integer> xp, Map<String, Integer> lv, RsbuddyData.Quests quests,
		List<QuestRequirements.Quest> reqs)
	{
		// the skill closest to its next level
		String bestSkill = null;
		double bestFrac = -1;
		for (Map.Entry<String, Integer> e : lv.entrySet())
		{
			int l = e.getValue();
			if (l >= 99)
			{
				continue;
			}
			double f = (xp.get(e.getKey()) - XP[l]) / (double) (XP[l + 1] - XP[l]);
			if (f > bestFrac)
			{
				bestFrac = f;
				bestSkill = e.getKey();
			}
		}
		if (bestSkill != null)
		{
			int l = lv.get(bestSkill), x = xp.get(bestSkill);
			goals.add(new Goal(bestSkill, bestSkill + " " + l + " → " + (l + 1), shortXp(XP[l + 1] - x) + " XP",
				bestFrac, String.format("%,d of %,d XP", x, XP[l + 1]), false));
		}

		// the quest whose other requirements are all met and needs the least XP (as the web app picks it)
		if (quests == null || quests.quests == null)
		{
			return;
		}
		Map<String, String> st = new HashMap<>();
		for (RsbuddyData.QuestState q : quests.quests)
		{
			st.put(q.name, q.state);
		}
		QuestRequirements.Quest best = null;
		long bestNeed = Long.MAX_VALUE, bestHave = 0, bestWant = 1;
		String bestTip = null;
		for (QuestRequirements.Quest q : reqs)
		{
			if (!RsbuddyData.NOT_STARTED.equals(st.get(q.name)) || quests.quest_points < q.reqQp || !prereqsMet(q, st)
				|| !rulesMet(q, lv))
			{
				continue;
			}
			long need = 0, have = 0, want = 0;
			List<String> miss = new ArrayList<>();
			boolean usable = true;
			for (QuestRequirements.Level r : q.levels)
			{
				int cur = lv.getOrDefault(r.skill, 1);
				if (cur >= r.level)
				{
					continue;
				}
				if (!xp.containsKey(r.skill) || r.level > 99)
				{
					usable = false; // e.g. a combat level: no XP to count
					break;
				}
				need += XP[r.level] - xp.get(r.skill);
				have += xp.get(r.skill);
				want += XP[r.level];
				miss.add(r.skill + " " + r.level + " (" + (r.level - cur) + " to go)");
			}
			if (usable && !miss.isEmpty() && need < bestNeed)
			{
				best = q;
				bestNeed = need;
				bestHave = have;
				bestWant = want;
				bestTip = "Needs " + String.join(", ", miss) + "; everything else is done.";
			}
		}
		if (best != null)
		{
			goals.add(new Goal(null, best.name, shortXp(bestNeed) + " XP", bestHave / (double) bestWant, bestTip, true));
		}
	}

	private static boolean prereqsMet(QuestRequirements.Quest q, Map<String, String> st)
	{
		if (q.requires == null)
		{
			return true;
		}
		for (Map.Entry<String, Integer> r : q.requires.entrySet())
		{
			String s = st.get(r.getKey());
			if (s == null || r.getKey().equals(q.name))
			{
				continue; // not a quest of the game's table: the web app ignores these too
			}
			if (r.getValue() == 2 ? !RsbuddyData.FINISHED.equals(s) : RsbuddyData.NOT_STARTED.equals(s))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean rulesMet(QuestRequirements.Quest q, Map<String, Integer> lv)
	{
		if (q.rules != null)
		{
			for (JsonObject r : q.rules)
			{
				if (!ruleMet(r, lv))
				{
					return false;
				}
			}
		}
		return true;
	}

	/** The catalog's rule tree: anyOf / allOf / sum of skills / one skill, each "atLeast" (base levels). */
	static boolean ruleMet(JsonObject r, Map<String, Integer> lv)
	{
		if (r.has("anyOf") || r.has("allOf"))
		{
			boolean any = r.has("anyOf");
			for (JsonElement e : r.getAsJsonArray(any ? "anyOf" : "allOf"))
			{
				boolean met = ruleMet(e.getAsJsonObject(), lv);
				if (any && met)
				{
					return true;
				}
				if (!any && !met)
				{
					return false;
				}
			}
			return !any;
		}
		int atLeast = r.get("atLeast").getAsInt();
		if (r.has("sum"))
		{
			int t = 0;
			for (JsonElement e : r.getAsJsonArray("sum"))
			{
				t += lv.getOrDefault(e.getAsString(), 1);
			}
			return t >= atLeast;
		}
		return lv.getOrDefault(r.get("skill").getAsString(), 1) >= atLeast;
	}

	private static long sum(Map<String, Integer> m)
	{
		long t = 0;
		for (int v : m.values())
		{
			t += v;
		}
		return t;
	}

	static String duration(Duration d)
	{
		long m = Math.max(0, d.toMinutes());
		return m >= 60 ? String.format("%dh %02dm", m / 60, m % 60) : m + "m";
	}

	/** The web app's short(): 1.24M, 31k, or the full number under 10,000. */
	static String shortXp(long n)
	{
		if (n >= 1_000_000_000)
		{
			return String.format("%.2fB", n / 1e9);
		}
		if (n >= 1_000_000)
		{
			return String.format(n >= 10_000_000 ? "%.1fM" : "%.2fM", n / 1e6);
		}
		if (n >= 10_000)
		{
			return Math.round(n / 1e3) + "k";
		}
		return String.format("%,d", n);
	}
}
