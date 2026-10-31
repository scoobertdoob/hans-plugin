package com.hans;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ChatMessageType;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.util.Text;

/**
 * loot.jsonl (one line per NPC drop, appended) and killcounts.json (boss name to the last kill count seen, and each
 * clue tier to its Treasure Trail completion count).
 *
 * Drops come from {@code ServerNpcLoot}, the game's own loot message that RuneLite's loot tracker uses. The older
 * {@code NpcLootReceived} (drops inferred from the ground) is kept only when no ServerNpcLoot for the same NPC id
 * arrived in the same or the next tick, so nothing is written twice. Other players' loot is never recorded.
 * The kill counts are read from the game's "Your X kill count is: N" chat lines (the pattern RuneLite's chat
 * commands use). The clue counts come from the game's "You have completed N hard Treasure Trails." line, read as
 * RuneLite's loot tracker reads it; it gives the whole count, so a missed line corrects itself at the next one. A clue
 * count never goes down: a lower reading keeps the higher count and is listed beside it, in {@code lower_readings}.
 * Only a run of lower readings that keep rising ({@link #RESET_RUN} of them, with no reading above the count in
 * between) shows a new character on the same key (a reset account): then every tier's counts so far are archived in
 * {@code previous_clue_epochs} and the counts start again from the run's last reading. (The game also keeps clue counts in varps that a client script reads, but only three of them still change and which
 * tier each holds is not known; vars.json records them raw, as group {@code clue_counts}, to confirm in game.)
 *
 * Every drop and kill count is tagged on arrival with the login's {@link LoginState.Token}, which fixes the key it
 * is credited to. One whose token has no key (it arrived before the login settled, during a world hop or region load
 * before a tick confirmed the world, while logged out, or while the account hash was unknown) is never credited to
 * any profile: it goes to the plugin's quarantine file, raw, with why. Nothing is carried across a hop and nothing is
 * guessed. A drop or kill count that arrives while the client reports a world the game wipes (beta, tournament) is
 * dropped there and then, never held; one that arrives once the client reports a saved world follows its token.
 */
final class LootCapture implements Capture
{
	private static final Pattern KILLCOUNT = Pattern.compile("Your (?<pre>completion count for |subdued |completed )?"
		+ "(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? "
		+ "(?<post>(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?(?:count )?)is: ?"
		+ "(?:<col=[0-9a-f]{6}>|@.+?@)(?<kc>[0-9,]+)</col>");
	private static final EnumSet<ChatMessageType> KC_TYPES = EnumSet.of(ChatMessageType.GAMEMESSAGE,
		ChatMessageType.SPAM, ChatMessageType.TRADE, ChatMessageType.FRIENDSCHATNOTIFICATION);
	/** RuneLite's loot tracker pattern, read on the line without its colour tags; a count over 999 may have commas. */
	private static final Pattern CLUE_COUNT = Pattern.compile(
		"^You have completed (?<count>[0-9,]+) (?<tier>[a-z]+) Treasure Trails?\\.", Pattern.CASE_INSENSITIVE);
	/** The types RuneLite's loot tracker reads the clue line from. */
	private static final EnumSet<ChatMessageType> CLUE_TYPES = EnumSet.of(ChatMessageType.GAMEMESSAGE,
		ChatMessageType.SPAM, ChatMessageType.MESBOX);
	private static final List<String> CLUE_TIERS = Arrays.asList("beginner", "easy", "medium", "hard", "elite",
		"master");
	/** Clue tiers in the game's order; a tier we do not know yet goes after them, by name. */
	static final Comparator<String> TIER_ORDER = Comparator.<String>comparingInt(t ->
	{
		int i = CLUE_TIERS.indexOf(t);
		return i < 0 ? CLUE_TIERS.size() : i;
	}).thenComparing(Comparator.naturalOrder());
	/**
	 * How many lower readings in a row, each above the one before, confirm a new run of counts (a reset): the first
	 * lower one and two later clues. One stray lower line, or lower lines that do not rise, never do.
	 */
	static final int RESET_RUN = 3;

	static final class Count
	{
		int count;
		@SerializedName("observed_at")
		String observedAt;
	}

	/** One clue tier's completion count, which never goes down. */
	static final class ClueCount
	{
		int count;
		@SerializedName("observed_at")
		String observedAt;
		/** Readings below {@code count}, oldest first, kept but never applied; left out when there were none. */
		@SerializedName("lower_readings")
		List<Count> lowerReadings;
		/**
		 * How many of the last lower readings rise one after another since the count last rose (the run that may show
		 * a reset); left out when none.
		 */
		@SerializedName("lower_run")
		Integer lowerRun;
	}

	/** The clue counts of an earlier run on the same key, archived when a reset was confirmed. */
	static final class ClueEpoch
	{
		/** When the reading that confirmed the new run arrived. */
		@SerializedName("ended_at")
		String endedAt;
		Map<String, ClueCount> clues;
	}

	static final class KillFile
	{
		@SerializedName("observed_at")
		String observedAt;
		Map<String, Count> killcounts;
		/** Clue tier (beginner to master, lower case) to its completion count; left out until the first one. */
		Map<String, ClueCount> clues;
		/** Earlier runs' clue counts, oldest first; left out until a reset is confirmed. */
		@SerializedName("previous_clue_epochs")
		List<ClueEpoch> previousClueEpochs;
	}

	/**
	 * A drop inferred by NpcLootReceived, held back one tick until we know whether ServerNpcLoot reported it too.
	 * Its token, fixed on arrival, decides where it goes: the token's key, or quarantine (with its whereabouts).
	 */
	private static final class Pending
	{
		final int tick;
		final int npcId;
		final LoginState.Token token;
		final Map<String, Object> whereabouts; // null when the token has a key
		final Map<String, Object> line;

		Pending(int tick, int npcId, LoginState.Token token, Map<String, Object> whereabouts, Map<String, Object> line)
		{
			this.tick = tick;
			this.npcId = npcId;
			this.token = token;
			this.whereabouts = whereabouts;
			this.line = line;
		}
	}

	/**
	 * A kill-count or clue-count chat line, held until its key's file has loaded; tagged with the key it arrived under.
	 * For a clue count, {@code boss} is the tier.
	 */
	static final class PendingKc
	{
		final ProfileKey key;
		final String boss;
		final String count;
		final String time;

		PendingKc(ProfileKey key, String boss, String count, String time)
		{
			this.key = key;
			this.boss = boss;
			this.count = count;
			this.time = time;
		}
	}

	/** A ServerNpcLoot report, kept a few ticks to match a fallback with the same NPC id and the same token. */
	static final class Seen
	{
		final int tick;
		final int npcId;
		final int token;

		Seen(int tick, int npcId, int token)
		{
			this.tick = tick;
			this.npcId = npcId;
			this.token = token;
		}
	}

	private final CaptureContext ctx;
	private final PriorFile<KillFile> prior;
	private final List<Pending> pending = new ArrayList<>();
	private final List<Seen> seen = new ArrayList<>();
	private final List<PendingKc> pendingKc = new ArrayList<>();
	private final List<PendingKc> pendingClues = new ArrayList<>();
	private ProfileKey countsKey; // whose killcounts.json `counts` and `clues` hold
	private Map<String, Count> counts;
	private Map<String, ClueCount> clues;
	private List<ClueEpoch> clueEpochs;
	private int countsGeneration = -1;
	// this login's credited drops, for the upload's session summary (client thread); a new login starts again
	private final Map<String, Integer> loginKills = new TreeMap<>();
	private final Map<Integer, Integer> loginDrops = new TreeMap<>();
	private ProfileKey tallyKey;
	private boolean tallyEnded;
	private int tick;

	LootCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
		this.prior = new PriorFile<>(ctx, "killcounts.json", KillFile.class);
	}

	@Override
	public void onSettled(Player local, Instant now)
	{
		if (tallyEnded)
		{
			tallyEnded = false;
			tallyKey = null;
			loginKills.clear();
			loginDrops.clear();
		}
	}

	/** How many drops, reports and kill counts are held in memory now (tests: nothing piles up). */
	int held()
	{
		return pending.size() + seen.size() + pendingKc.size() + pendingClues.size();
	}

	// ------------------------------------------------------------------ loot

	@Override
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (!ctx.keeps(ctx.config.exportLoot()))
		{
			return;
		}
		NPCComposition npc = event.getComposition();
		if (npc == null || ctx.login.dropsEvidence())
		{
			return;
		}
		LoginState.Token token = ctx.login.token();
		seen.add(new Seen(tick, npc.getId(), token.seq));
		Map<String, Object> line = line("server_npc_loot", npc.getId(), npc.getName(), event.getItems());
		if (token.key != null)
		{
			record(token.key, line);
		}
		else
		{
			quarantine(token, ctx.login.whereabouts(), "loot", line);
		}
	}

	@Override
	public void onNpcLootReceived(NpcLootReceived event)
	{
		NPC npc = event.getNpc();
		if (!ctx.keeps(ctx.config.exportLoot()) || npc == null || ctx.login.dropsEvidence())
		{
			return;
		}
		LoginState.Token token = ctx.login.token();
		pending.add(new Pending(tick, npc.getId(), token, token.key == null ? ctx.login.whereabouts() : null,
			line("npc_loot_received", npc.getId(), npc.getName(), event.getItems())));
	}

	private Map<String, Object> line(String source, int npcId, String npcName, Iterable<ItemStack> items)
	{
		Player local = ctx.client.getLocalPlayer();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("observed_at", Instant.now().toString());
		m.put("source", source);
		m.put("npc_id", npcId);
		m.put("npc_name", npcName);
		List<Map<String, Object>> list = new ArrayList<>();
		for (ItemStack s : items)
		{
			Map<String, Object> i = new LinkedHashMap<>();
			i.put("id", s.getId());
			i.put("qty", s.getQuantity());
			i.put("name", ctx.itemManager.getItemComposition(s.getId()).getName());
			list.add(i);
		}
		m.put("items", list);
		m.put("world", ctx.client.getWorld());
		m.put("region", local == null ? -1 : local.getWorldLocation().getRegionID());
		return m;
	}

	/**
	 * Drops the NpcLootReceived fallbacks that ServerNpcLoot reported too (same NPC id, same token), and writes the
	 * others to their token's key or to quarantine. {@code all} (logout): every fallback is decided now.
	 */
	private void flushPending(boolean all)
	{
		for (Iterator<Pending> it = pending.iterator(); it.hasNext();)
		{
			Pending p = it.next();
			if (!all && p.tick >= tick - 1)
			{
				continue; // wait until a full tick has passed
			}
			it.remove();
			if (consumeSeen(seen, p.npcId, p.tick, p.token.seq))
			{
				continue; // ServerNpcLoot reported this drop: one line
			}
			if (p.token.key != null)
			{
				record(p.token.key, p.line);
			}
			else
			{
				quarantine(p.token, p.whereabouts, "loot", p.line);
			}
		}
	}

	/**
	 * A drop credited to its key: its line goes to loot.jsonl (when the loot file is wanted) and it counts in this
	 * login's tally, the kill by the NPC's name and each item by id.
	 */
	@SuppressWarnings("unchecked")
	private void record(ProfileKey key, Map<String, Object> line)
	{
		if (ctx.config.exportLoot())
		{
			ctx.json.appendLine(key, "loot.jsonl", line);
		}
		if (!key.equals(tallyKey))
		{
			tallyKey = key;
			loginKills.clear();
			loginDrops.clear();
		}
		Object name = line.get("npc_name");
		if (name instanceof String)
		{
			loginKills.merge((String) name, 1, Integer::sum);
		}
		for (Map<String, Object> item : (List<Map<String, Object>>) line.get("items"))
		{
			loginDrops.merge((Integer) item.get("id"), (Integer) item.get("qty"), (a, b) -> (int) Math.min(Integer.MAX_VALUE,
				(long) a + b));
		}
	}

	/** This login's kills by NPC name (from the drops credited to {@code key}), or null. A copy. Client thread. */
	Map<String, Integer> loginKills(ProfileKey key)
	{
		return key.equals(tallyKey) ? new TreeMap<>(loginKills) : null;
	}

	/** This login's dropped items, by id and total quantity, or null. A copy. Client thread. */
	Map<Integer, Integer> loginDrops(ProfileKey key)
	{
		return key.equals(tallyKey) ? new TreeMap<>(loginDrops) : null;
	}

	/** Evidence whose token has no key: one raw line in the plugin's quarantine file, with why. Never credited. */
	private void quarantine(LoginState.Token token, Map<String, Object> whereabouts, String kind,
		Map<String, Object> value)
	{
		if (!ctx.config.exportLoot())
		{
			return; // read for the upload only: no file, and an unconfirmed drop is never credited anyway
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("kept_at", Instant.now().toString());
		m.put("kind", kind);
		m.put("why", token.why);
		m.put("token", token.seq);
		m.putAll(whereabouts);
		m.put("value", value);
		ctx.json.appendQuarantine(m);
	}

	/**
	 * Removes and reports one ServerNpcLoot for this NPC id within a tick of {@code tick} and with the same token: one
	 * report covers one fallback, and a report from another stretch of the login (the far side of a hop) none.
	 */
	static boolean consumeSeen(List<Seen> seen, int npcId, int tick, int token)
	{
		for (Iterator<Seen> it = seen.iterator(); it.hasNext();)
		{
			Seen s = it.next();
			if (s.npcId == npcId && s.token == token && s.tick >= tick - 1 && s.tick <= tick + 1)
			{
				it.remove();
				return true;
			}
		}
		return false;
	}

	/**
	 * Applies the pending kill counts of {@code key} to {@code counts} and empties the list; lines tagged with
	 * another key (or none) are left out, never credited to this one.
	 *
	 * @return whether {@code counts} changed
	 */
	static boolean applyKillCounts(Map<String, Count> counts, List<PendingKc> pending, ProfileKey key)
	{
		boolean changed = false;
		for (PendingKc kc : pending)
		{
			if (!key.equals(kc.key))
			{
				continue;
			}
			int n;
			try
			{
				n = Integer.parseInt(kc.count);
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			Count old = counts.get(kc.boss);
			if (old == null || old.count != n)
			{
				Count c = new Count();
				c.count = n;
				c.observedAt = kc.time;
				counts.put(kc.boss, c);
				changed = true;
			}
		}
		pending.clear();
		return changed;
	}

	/**
	 * Applies the pending clue counts of {@code key} to {@code clues} and empties the list, as
	 * {@link #applyKillCounts} does. A count never goes down: a reading below the saved count keeps the saved count and
	 * is added to its {@code lower_readings} (once while the same lower reading repeats). When {@link #RESET_RUN} lower
	 * readings in a row each rise above the one before (a reset character on the same key), every tier's counts so
	 * far go to a new entry of {@code epochs} and the tier starts again at its latest reading. Entries and epochs are
	 * replaced or added, never changed in place, so copies handed to the executor stay as they were.
	 *
	 * @return whether {@code clues} or {@code epochs} changed
	 */
	static boolean applyClueCounts(Map<String, ClueCount> clues, List<ClueEpoch> epochs, List<PendingKc> pending,
		ProfileKey key)
	{
		boolean changed = false;
		for (PendingKc kc : pending)
		{
			if (!key.equals(kc.key))
			{
				continue;
			}
			int n;
			try
			{
				n = Integer.parseInt(kc.count);
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			ClueCount old = clues.get(kc.boss);
			if (old != null && old.count == n)
			{
				continue;
			}
			if (old == null || n > old.count)
			{
				changed = true;
				// a new or higher count; a rising run of lower readings, if any, ends here
				clues.put(kc.boss, clue(n, kc.time, old == null ? null : old.lowerReadings, null));
				continue;
			}
			List<Count> lower = old.lowerReadings == null ? new ArrayList<>() : new ArrayList<>(old.lowerReadings);
			Count last = lower.isEmpty() ? null : lower.get(lower.size() - 1);
			int run = old.lowerRun == null || last == null ? 0 : old.lowerRun;
			if (run > 0 && last.count == n)
			{
				continue; // the same lower reading again: already said
			}
			changed = true;
			Count reading = new Count();
			reading.count = n;
			reading.observedAt = kc.time;
			lower.add(reading);
			run = run > 0 && n > last.count ? run + 1 : 1;
			ClueCount kept = clue(old.count, old.observedAt, lower, run);
			if (run < RESET_RUN)
			{
				clues.put(kc.boss, kept);
				continue;
			}
			// a new run of counts on this key: archive every tier's counts so far, discarding nothing
			ClueEpoch epoch = new ClueEpoch();
			epoch.endedAt = kc.time;
			epoch.clues = copyClues(clues);
			epoch.clues.put(kc.boss, kept);
			epochs.add(epoch);
			clues.clear();
			clues.put(kc.boss, clue(n, kc.time, null, null));
		}
		pending.clear();
		return changed;
	}

	private static ClueCount clue(int count, String observedAt, List<Count> lowerReadings, Integer lowerRun)
	{
		ClueCount c = new ClueCount();
		c.count = count;
		c.observedAt = observedAt;
		c.lowerReadings = lowerReadings;
		c.lowerRun = lowerRun;
		return c;
	}

	/** The file to write: the kill counts, and the clue map and epochs left out while they are empty. */
	private static KillFile killFile(String observedAt, Map<String, Count> counts, Map<String, ClueCount> clues,
		List<ClueEpoch> epochs)
	{
		KillFile out = new KillFile();
		out.observedAt = observedAt;
		out.killcounts = counts;
		out.clues = clues == null || clues.isEmpty() ? null : clues;
		out.previousClueEpochs = epochs == null || epochs.isEmpty() ? null : epochs;
		return out;
	}

	private static Map<String, ClueCount> copyClues(Map<String, ClueCount> clues)
	{
		Map<String, ClueCount> copy = new TreeMap<>(TIER_ORDER);
		if (clues != null)
		{
			copy.putAll(clues);
		}
		return copy;
	}

	// ------------------------------------------------------------ kill counts

	@Override
	public void onChatMessage(ChatMessage event)
	{
		if (!ctx.keeps(ctx.config.exportLoot()))
		{
			return;
		}
		if (CLUE_TYPES.contains(event.getType()) && clueCount(event.getMessage()))
		{
			return;
		}
		if (!KC_TYPES.contains(event.getType()))
		{
			return;
		}
		Matcher m = KILLCOUNT.matcher(event.getMessage());
		if (!m.find())
		{
			return;
		}
		String pre = m.group("pre");
		String post = m.group("post");
		if ((pre == null || pre.isEmpty()) && (post == null || post.isEmpty()))
		{
			return; // not a kill count
		}
		if (ctx.login.dropsEvidence())
		{
			return;
		}
		String boss = m.group("boss").replace(":", "");
		if ("Barrows chest".equals(boss))
		{
			boss = "Barrows Chests";
		}
		String count = m.group("kc").replace(",", "");
		String time = Instant.now().toString();
		LoginState.Token token = ctx.login.token();
		if (token.key != null)
		{
			pendingKc.add(new PendingKc(token.key, boss, count, time));
			return;
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("boss", boss);
		value.put("count", count);
		value.put("observed_at", time);
		quarantine(token, ctx.login.whereabouts(), "kill_count", value);
	}

	/**
	 * A clue completion line: held for its token's key, or quarantined when the token has none, like a kill count.
	 *
	 * @return whether the line was a clue completion count
	 */
	private boolean clueCount(String message)
	{
		Matcher m = CLUE_COUNT.matcher(Text.removeTags(message));
		if (!m.find())
		{
			return false;
		}
		if (ctx.login.dropsEvidence())
		{
			return true;
		}
		String tier = m.group("tier").toLowerCase(Locale.ROOT);
		String count = m.group("count").replace(",", "");
		String time = Instant.now().toString();
		LoginState.Token token = ctx.login.token();
		if (token.key != null)
		{
			pendingClues.add(new PendingKc(token.key, tier, count, time));
			return true;
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("tier", tier);
		value.put("count", count);
		value.put("observed_at", time);
		quarantine(token, ctx.login.whereabouts(), "clue_count", value);
		return true;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		flushPending(false);
		seen.removeIf(s -> s.tick < tick - 3);
		tick++;
		// no game reading here: only kill counts already tagged with this login's key are applied to its file
		ProfileKey key = ctx.login.logoutKey();
		if (key == null || !ctx.keeps(ctx.config.exportLoot()))
		{
			return;
		}
		// Start loading this key's counts as soon as it settles, so they are ready before the first kill count.
		prior.ensure(key);
		if (!prior.loaded())
		{
			return;
		}
		if (counts == null || countsGeneration != prior.generation())
		{
			KillFile before = prior.get();
			counts = new TreeMap<>();
			if (before != null && before.killcounts != null)
			{
				counts.putAll(before.killcounts);
			}
			clues = copyClues(before == null ? null : before.clues);
			clueEpochs = copyEpochs(before == null ? null : before.previousClueEpochs);
			countsGeneration = prior.generation();
		}
		countsKey = key;
		if (pendingKc.isEmpty() && pendingClues.isEmpty())
		{
			return;
		}
		boolean changed = applyKillCounts(counts, pendingKc, key);
		changed |= applyClueCounts(clues, clueEpochs, pendingClues, key);
		if (changed && ctx.config.exportLoot())
		{
			ctx.json.write(key, "killcounts.json", killFile(now.toString(), counts, clues, clueEpochs));
		}
	}

	/** The kill counts held for {@code key} (a copy, by name), or null when they are not loaded for it. Client thread. */
	Map<String, Integer> killCounts(ProfileKey key)
	{
		if (counts == null || !key.equals(countsKey))
		{
			return null;
		}
		Map<String, Integer> out = new TreeMap<>();
		counts.forEach((name, c) -> out.put(name, c.count));
		return out;
	}

	/** The clue completions held for {@code key} (a copy, by tier), or null when they are not loaded for it. */
	Map<String, Integer> clueCounts(ProfileKey key)
	{
		if (clues == null || !key.equals(countsKey))
		{
			return null;
		}
		Map<String, Integer> out = new TreeMap<>();
		clues.forEach((tier, c) -> out.put(tier, c.count));
		return out;
	}

	/**
	 * The saved kill and clue counts with pending lines applied, or null if unchanged; {@code known} (the counts in
	 * memory, both maps) wins over the file.
	 */
	static KillFile mergeKillCounts(KillFile known, KillFile onDisk, List<PendingKc> pending,
		List<PendingKc> pendingClues, ProfileKey key, String observedAt)
	{
		KillFile base = known != null ? known : onDisk;
		Map<String, Count> merged = new TreeMap<>();
		if (base != null && base.killcounts != null)
		{
			merged.putAll(base.killcounts);
		}
		Map<String, ClueCount> mergedClues = copyClues(base == null ? null : base.clues);
		List<ClueEpoch> mergedEpochs = copyEpochs(base == null ? null : base.previousClueEpochs);
		boolean changed = applyKillCounts(merged, pending, key);
		changed |= applyClueCounts(mergedClues, mergedEpochs, pendingClues, key);
		return changed ? killFile(observedAt, merged, mergedClues, mergedEpochs) : null;
	}

	private static List<ClueEpoch> copyEpochs(List<ClueEpoch> epochs)
	{
		return epochs == null ? new ArrayList<>() : new ArrayList<>(epochs);
	}

	@Override
	public java.util.concurrent.Future<?> onLogout(Instant now)
	{
		flushPending(true);
		tallyEnded = true; // kept for the upload's last session reading; cleared when the next login settles
		seen.clear(); // a report of this login can never match a later fallback (that has another token)
		// Kill and clue counts read just before logout are saved for their own key, whether or not its file had loaded.
		java.util.concurrent.Future<?> last = null;
		if ((!pendingKc.isEmpty() || !pendingClues.isEmpty()) && ctx.config.exportLoot())
		{
			String observedAt = now.toString();
			// copied here, on the client thread; entries are replaced, never changed, so the copies stay as they are
			KillFile known = counts != null && countsKey != null
				? killFile(null, new TreeMap<>(counts), copyClues(clues), copyEpochs(clueEpochs)) : null;
			ProfileKey knownKey = countsKey;
			Set<ProfileKey> keys = new LinkedHashSet<>();
			for (PendingKc kc : pendingKc)
			{
				keys.add(kc.key);
			}
			for (PendingKc kc : pendingClues)
			{
				keys.add(kc.key);
			}
			for (ProfileKey key : keys)
			{
				List<PendingKc> mine = new ArrayList<>(pendingKc);
				List<PendingKc> mineClues = new ArrayList<>(pendingClues);
				KillFile base = key.equals(knownKey) ? known : null;
				// composed on the executor, which may read the key's file from disk
				// (optional fields left out, as on the tick path)
				java.util.concurrent.Future<?> f = ctx.json.writeLater(key, "killcounts.json", () ->
					mergeKillCounts(base, base == null ? ctx.json.read(key, "killcounts.json", KillFile.class) : null,
						mine, mineClues, key, observedAt), false);
				if (f != null)
				{
					last = f;
				}
			}
		}
		pendingKc.clear();
		pendingClues.clear();
		counts = null;
		clues = null;
		clueEpochs = null;
		countsKey = null;
		prior.reset();
		return last;
	}
}
