package com.hans;

import com.google.gson.annotations.SerializedName;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;

/**
 * vars.json: named groups of raw game variables (diaries, combat achievements, music, slayer, dailies, collection
 * log counts, clue counts, quest progress), each value with the time it was last seen to change. Nothing is decoded
 * here.
 *
 * Read at settle and after a change of any tracked variable, at most once per tick. The values are persistent
 * variables, so a 0 is a real 0; the one exception is Kingdom of Miscellania (coffer and approval are only
 * transmitted in the kingdom), which follows RuneLite's rule: update only in the kingdom or when both are non-zero.
 * The times carry over from the previous file when a value is unchanged since. The first read after each login also
 * stamps the file, changed or not: {@code confirmed_at} (when it was read), {@code client_revision} (the game client's)
 * and {@code reading_hash} (of the values read, see {@link #readingHash}), so a reader knows this account and mode's
 * values were confirmed at that login. A later read in the same login that changes a value writes the file without
 * the stamp: those values have not been confirmed at a login yet.
 *
 * music.json (see {@link MusicTracks}) comes with it: the game's Music table decoded against the music varps, read
 * at settle and after a change of a music varp, and written only when it says something new. A music varp change is
 * also kept with the account and mode it arrived under, so that one logout (or a hop) catches before the next tick
 * is still written for that key, from the change itself, without reading the game then. Logout forgets the cached
 * file, so the next login compares with what is really on disk (a failed write is then retried).
 */
@Slf4j
final class VarsCapture implements Capture
{
	private static final int MUSIC_TRIES = 5;
	private static final int[] KINGDOM_REGIONS = {10044, 10300};
	private static final int MISC_COFFERS = VarbitID.MISC_COFFERS;
	private static final int MISC_APPROVAL = VarbitID.MISC_APPROVAL;

	static final class Entry
	{
		int v;
		String t;

		Entry(int v, String t)
		{
			this.v = v;
			this.t = t;
		}
	}

	static final class Group
	{
		Map<Integer, Entry> varps = new TreeMap<>();
		Map<Integer, Entry> varbits = new TreeMap<>();
	}

	static final class File
	{
		@SerializedName("observed_at")
		String observedAt;
		@SerializedName("confirmed_at")
		String confirmedAt;
		@SerializedName("client_revision")
		Integer clientRevision;
		@SerializedName("reading_hash")
		String readingHash;
		Map<String, Group> groups;
	}

	private final CaptureContext ctx;
	private final PriorFile<File> prior;
	private final Map<String, int[][]> defs = VarIds.groups();
	private final Set<Integer> trackedVarps = new HashSet<>();
	private final Set<Integer> trackedVarbits = new HashSet<>();
	private Map<String, Group> state;
	private int stateGeneration = -1;
	private boolean dirty = true;
	private boolean loginRead; // the next read is the first since a login: it stamps the file
	private String observedAt; // the observed_at of the file as last read or written (with state)

	private final PriorFile<MusicTracks.File> musicPrior;
	private final MusicTracks.Source musicSource;
	private MusicTracks.Catalogue catalogue; // the Music table, read once per login
	private MusicTracks.File music; // what music.json holds for musicGeneration's key
	private int musicGeneration = -1;
	private boolean musicDirty = true;
	private int musicTries;
	private ProfileKey musicKey; // whose music is
	private MusicChange change; // music varp changes not yet read on a tick
	private String lastStamp; // the time of the last tick read

	/** Music varp changes seen between ticks, with the key they arrived under and what that key's file held. */
	private static final class MusicChange
	{
		final ProfileKey key;
		final MusicTracks.Catalogue catalogue;
		final MusicTracks.File base;
		final Map<Integer, Integer> varps = new HashMap<>();
		String stamp;

		MusicChange(ProfileKey key, MusicTracks.Catalogue catalogue, MusicTracks.File base)
		{
			this.key = key;
			this.catalogue = catalogue;
			this.base = base;
		}
	}

	VarsCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
		this.prior = new PriorFile<>(ctx, "vars.json", File.class);
		this.musicPrior = new PriorFile<>(ctx, MusicTracks.FILE, MusicTracks.File.class);
		this.musicSource = new MusicTracks.ClientSource(ctx.client);
		for (int[][] d : defs.values())
		{
			for (int id : d[0])
			{
				trackedVarps.add(id);
			}
			for (int id : d[1])
			{
				trackedVarbits.add(id);
			}
		}
	}

	@Override
	public void onLogin()
	{
		dirty = true;
		loginRead = true;
		markMusicDirty();
		catalogue = null;
	}

	@Override
	public void onConfigChanged(String key)
	{
		if ("exportVars".equals(key))
		{
			dirty = true;
			markMusicDirty();
		}
	}

	@Override
	public void onVarbitChanged(VarbitChanged event)
	{
		if (trackedVarps.contains(event.getVarpId()) || trackedVarbits.contains(event.getVarbitId()))
		{
			dirty = true;
		}
		if (MusicTracks.isVarp(event.getVarpId()))
		{
			musicDirty = true;
			noteMusicChange(event.getVarpId(), event.getValue());
		}
	}

	@Override
	public Future<?> onLogout(Instant now)
	{
		Future<?> last = flushMusicChange();
		musicPrior.reset(); // the next login reads music.json from disk again
		music = null;
		musicKey = null;
		return last;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (change != null && key != null && !key.equals(change.key))
		{
			flushMusicChange(); // a hop to another account or mode came before a tick read the change
		}
		if (key == null || !ctx.config.exportVars() || !ctx.json.enabled())
		{
			return;
		}
		lastStamp = now.toString();
		if (dirty)
		{
			readVars(key, local, now);
		}
		if (musicDirty)
		{
			readMusic(key, now);
		}
	}

	private void readVars(ProfileKey key, Player local, Instant now)
	{
		prior.ensure(key);
		if (!prior.loaded())
		{
			return;
		}
		File before = prior.get();
		boolean changed = false;
		if (state == null || stateGeneration != prior.generation())
		{
			state = new LinkedHashMap<>();
			stateGeneration = prior.generation();
			changed = before == null || before.groups == null;
			observedAt = before == null ? null : before.observedAt;
		}

		int region = local.getWorldLocation().getRegionID();
		boolean inKingdom = false;
		for (int r : KINGDOM_REGIONS)
		{
			inKingdom |= region == r;
		}
		boolean kingdomReadable = inKingdom
			|| (ctx.client.getVarbitValue(MISC_COFFERS) > 0 && ctx.client.getVarbitValue(MISC_APPROVAL) > 0);
		String stamp = now.toString();

		for (Map.Entry<String, int[][]> g : defs.entrySet())
		{
			Group group = state.computeIfAbsent(g.getKey(), k -> new Group());
			Group old = before == null || before.groups == null ? null : before.groups.get(g.getKey());
			for (int id : g.getValue()[0])
			{
				changed |= update(group.varps, old == null ? null : old.varps, id, ctx.client.getVarpValue(id), stamp);
			}
			for (int id : g.getValue()[1])
			{
				if ((id == MISC_COFFERS || id == MISC_APPROVAL) && !kingdomReadable)
				{
					keepPrevious(group.varbits, old == null ? null : old.varbits, id);
					continue; // not transmitted outside the kingdom: keep what we had
				}
				changed |= update(group.varbits, old == null ? null : old.varbits, id,
					ctx.client.getVarbitValue(id), stamp);
			}
		}
		dirty = false;
		boolean confirm = loginRead;
		loginRead = false;
		if (changed || confirm)
		{
			File out = new File();
			out.observedAt = changed || observedAt == null ? stamp : observedAt;
			observedAt = out.observedAt;
			out.groups = state;
			if (confirm)
			{
				out.confirmedAt = stamp;
				out.clientRevision = ctx.client.getRevision();
				out.readingHash = readingHash(state);
			}
			ctx.json.write(key, "vars.json", out);
		}
	}

	/**
	 * SHA-256 (lowercase hex) of the values read, as UTF-8 text: for each group in name order, one line
	 * {@code name:p:id=v,id=v:b:id=v,id=v} with the varps then the varbits in id order. The importer computes the same
	 * text from the file's groups (rsbuddy_core.importer.reading_hash).
	 */
	static String readingHash(Map<String, Group> groups)
	{
		StringBuilder s = new StringBuilder();
		for (Map.Entry<String, Group> g : new TreeMap<>(groups).entrySet())
		{
			s.append(g.getKey()).append(":p:");
			join(s, g.getValue().varps);
			s.append(":b:");
			join(s, g.getValue().varbits);
			s.append('\n');
		}
		try
		{
			StringBuilder hex = new StringBuilder();
			for (byte b : MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8)))
			{
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e); // every Java platform has SHA-256
		}
	}

	private static void join(StringBuilder s, Map<Integer, Entry> values)
	{
		String sep = "";
		for (Map.Entry<Integer, Entry> e : new TreeMap<>(values).entrySet())
		{
			s.append(sep).append(e.getKey()).append('=').append(e.getValue().v);
			sep = ",";
		}
	}

	private void markMusicDirty()
	{
		musicDirty = true;
		musicTries = 0;
	}

	/** Decodes the Music table against the music varps; writes music.json only when it says something new. */
	private void readMusic(ProfileKey key, Instant now)
	{
		musicPrior.ensure(key);
		if (!musicPrior.loaded())
		{
			return;
		}
		if (musicGeneration != musicPrior.generation())
		{
			music = musicPrior.get();
			musicKey = key;
			musicGeneration = musicPrior.generation();
		}
		if (catalogue == null)
		{
			catalogue = MusicTracks.catalogue(musicSource);
			if (catalogue == null)
			{
				// not loaded yet, or not readable whole: try a few ticks, then record nothing this login
				if (++musicTries >= MUSIC_TRIES)
				{
					musicDirty = false;
					log.debug("Hans: the music table was not available");
				}
				return;
			}
		}
		musicDirty = false;
		if (change != null && change.key.equals(key))
		{
			change = null; // this reading has it
		}
		MusicTracks.File out = MusicTracks.build(catalogue, ctx.client::getVarpValue, music, now.toString());
		if (out != null)
		{
			music = out;
			ctx.json.write(key, MusicTracks.FILE, out);
		}
	}

	/** Keeps a music varp change with the key it arrived under, while that key's file and the table are known. */
	private void noteMusicChange(int varp, int value)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null || !key.equals(musicKey) || music == null || catalogue == null)
		{
			return; // nothing to build on: the next tick's reading takes it
		}
		if (change != null && !change.key.equals(key))
		{
			flushMusicChange();
		}
		if (change == null)
		{
			change = new MusicChange(key, catalogue, music);
		}
		change.varps.put(varp, value);
		change.stamp = lastStamp;
	}

	/** Writes the changes not yet read on a tick, for their own key, from the changes and the file they came on. */
	private Future<?> flushMusicChange()
	{
		MusicChange c = change;
		change = null;
		if (c == null || c.base.varps == null || !ctx.config.exportVars() || !ctx.json.enabled())
		{
			return null;
		}
		for (int id : MusicTracks.VARPS)
		{
			if (!c.varps.containsKey(id) && c.base.varps.get(id) == null)
			{
				return null; // a varp the file does not hold: nothing to build the rest from
			}
		}
		MusicTracks.File out = MusicTracks.build(c.catalogue,
			id -> c.varps.containsKey(id) ? c.varps.get(id) : c.base.varps.get(id), c.base, c.stamp);
		if (out == null)
		{
			return null;
		}
		if (c.key.equals(musicKey))
		{
			music = out;
		}
		return ctx.json.writeLater(c.key, MusicTracks.FILE, () -> out, false);  // like the tick write: no null fields
	}

	/** Seeds an entry that is not readable now from the previous file, so a later write does not drop it. */
	static void keepPrevious(Map<Integer, Entry> into, Map<Integer, Entry> old, int id)
	{
		Entry was = old == null ? null : old.get(id);
		if (was != null && !into.containsKey(id))
		{
			into.put(id, was);
		}
	}

	/** Returns true when the entry is new or changed compared with what the file already says. */
	private static boolean update(Map<Integer, Entry> into, Map<Integer, Entry> old, int id, int value, String stamp)
	{
		Entry cur = into.get(id);
		if (cur == null)
		{
			Entry was = old == null ? null : old.get(id);
			if (was != null && was.v == value && was.t != null)
			{
				into.put(id, was); // unchanged since the last session: keep when it was last seen changing
				return false;
			}
			into.put(id, new Entry(value, stamp));
			return true;
		}
		if (cur.v != value)
		{
			into.put(id, new Entry(value, stamp));
			return true;
		}
		return false;
	}
}
