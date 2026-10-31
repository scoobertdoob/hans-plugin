package com.hans;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * item_gains.json of one account, as held in memory between writes: the inventory changes of each play session,
 * oldest session first. The live file is kept bounded: at most {@link #MAX_SESSIONS} sessions and about
 * {@link #MAX_GAINS} entries in all, and at most {@link #MAX_PER_SESSION} entries in one session chunk. Overflowing
 * sessions and chunks are rotated out to archive files instead of discarded.
 *
 * Entries are never dropped from memory or from the composed main file until the archive file holding them has been
 * persisted and verified on disk.
 *
 * Format 2 added losses (negative {@code qty}). Sessions written by format 1 recorded gains only; they read back with
 * {@code with_losses} false, so a reader can tell them apart in a file that holds both.
 *
 * Each session also lists every {@link #CAPTURE_REV} of the code that recorded changes in it ({@code capture_revs},
 * additive): a reader ignores the losses of a session when any of its revisions is known to get them wrong, or when it
 * has none (sessions from before the field). Unknown has one form in the file: no list ({@code null}). A list read
 * back empty or with a revision below 1 is unknown too and becomes null on load, and a session that was ever unknown
 * stays unknown at every later write, so newer code never vouches for the changes older code recorded.
 * Not thread safe; the caller serialises access (it is used on the executor only).
 */
final class ItemGainsLog
{
	static final int FORMAT_VERSION = 2;
	/**
	 * Revision of the item-gain capture behaviour. Bump it with every change to what gets recorded as a gain or loss
	 * (docs/plugin-plan.md), so readers can tell sessions recorded before the change apart. 1: the equip fix of 27 Oct
	 * and everything since.
	 */
	static final int CAPTURE_REV = 1;
	static final int MAX_SESSIONS = 50;
	static final int MAX_GAINS = 40000; // gains and losses, ~2 MB
	static final int MAX_PER_SESSION = 20000;
	static final String ARCHIVE_PREFIX = "item_gains_archive_";

	/**
	 * One change of an item's quantity in the inventory on one tick: positive when it went up, negative when it went
	 * down. Raw values: no names, no prices.
	 */
	static final class Gain
	{
		String time;
		int id;
		int qty;

		Gain(String time, int id, int qty)
		{
			this.time = time;
			this.id = id;
			this.qty = qty;
		}
	}

	/** The changes of one play session; session_start matches the session's start in sessions.json. */
	static final class SessionGains
	{
		@SerializedName("session_start")
		String sessionStart;
		/** True when losses were recorded too (format 2); false for a session kept from a format 1 file. */
		@SerializedName("with_losses")
		boolean withLosses;
		/**
		 * Every {@link #CAPTURE_REV} that recorded changes in this session, ascending; null (unknown) when some of
		 * its changes come from code of unknown revision, such as a session written before the field. Never empty.
		 */
		@SerializedName("capture_revs")
		List<Integer> captureRevs;
		int dropped;
		List<Gain> gains = new ArrayList<>();
	}

	static final class GainsFile
	{
		@SerializedName("format_version")
		int formatVersion;
		@SerializedName("account_name")
		String accountName;
		/** Additive (layout 2): which mode folder the file belongs to; null in older files. */
		@SerializedName("account_hash")
		String accountHash;
		String mode;
		@SerializedName("observed_at")
		String observedAt;
		List<SessionGains> sessions;
	}

	/** An archived batch ready to be written to its own file in the account's plugin directory. */
	static final class ArchiveFile
	{
		final String fileName;
		final GainsFile file;
		private final int dropSessions;
		private final int[] trimGains;

		ArchiveFile(String fileName, GainsFile file)
		{
			this(fileName, file, 0, new int[0]);
		}

		ArchiveFile(String fileName, GainsFile file, int dropSessions, int[] trimGains)
		{
			this.fileName = fileName;
			this.file = file;
			this.dropSessions = dropSessions;
			this.trimGains = trimGains;
		}
	}

	private final List<SessionGains> sessions = new ArrayList<>();
	private String loadedAccount;

	/** Adopts the file already on disk (null: none, or unreadable). */
	void load(GainsFile existing)
	{
		loadedAccount = null;
		sessions.clear();
		adopt(existing);
	}

	/**
	 * Ensures the log holds {@code account}'s saved sessions without losing any unsaved in-memory batches for the
	 * same account if an earlier write or world hop happened before disk caught up.
	 */
	void load(String account, GainsFile existing)
	{
		if (!Objects.equals(account, loadedAccount))
		{
			loadedAccount = account;
			sessions.clear();
			adopt(existing);
			return;
		}
		if (sessions.isEmpty())
		{
			adopt(existing);
			return;
		}
		if (existing == null || existing.sessions == null)
		{
			return;
		}
		List<SessionGains> prepend = new ArrayList<>();
		for (SessionGains s : existing.sessions)
		{
			if (s == null || s.sessionStart == null)
			{
				continue;
			}
			SessionGains held = findSession(s.sessionStart);
			if (held != null)
			{
				held.captureRevs = union(held.captureRevs, s.captureRevs);
			}
			else
			{
				if (s.gains == null)
				{
					s.gains = new ArrayList<>();
				}
				s.captureRevs = known(s.captureRevs);
				prepend.add(s);
			}
		}
		if (!prepend.isEmpty())
		{
			sessions.addAll(0, prepend);
		}
	}

	private void adopt(GainsFile existing)
	{
		if (existing == null || existing.sessions == null)
		{
			return;
		}
		for (SessionGains s : existing.sessions)
		{
			if (s != null && s.sessionStart != null)
			{
				if (s.gains == null)
				{
					s.gains = new ArrayList<>();
				}
				s.captureRevs = known(s.captureRevs);
				sessions.add(s);
			}
		}
	}

	private SessionGains findSession(String sessionStart)
	{
		SessionGains found = null;
		for (SessionGains s : sessions)
		{
			if (s.sessionStart.equals(sessionStart))
			{
				found = s;
			}
		}
		return found;
	}

	/**
	 * Adds changes to the session that started at {@code sessionStart} (a new entry when it is not there yet).
	 * Overflowing sessions and chunks stay in {@link #sessions} until an archive write is planned, persisted and
	 * committed via {@link #commitArchive}.
	 */
	void add(String sessionStart, List<Gain> gains)
	{
		SessionGains live = findSession(sessionStart);
		if (live == null)
		{
			live = new SessionGains();
			live.sessionStart = sessionStart;
			live.withLosses = true;
			live.captureRevs = new ArrayList<>(Collections.singletonList(CAPTURE_REV));
			sessions.add(live);
		}
		else
		{
			live.captureRevs = union(live.captureRevs, Collections.singletonList(CAPTURE_REV));
		}
		live.gains.addAll(gains);
	}

	/**
	 * {@code revs} when it is a known list of revisions; null (unknown) when it is null, empty or holds a null or a
	 * revision below 1, the same rule the importer applies.
	 */
	static List<Integer> known(List<Integer> revs)
	{
		if (revs == null || revs.isEmpty())
		{
			return null;
		}
		for (Integer rev : revs)
		{
			if (rev == null || rev < 1)
			{
				return null;
			}
		}
		return revs;
	}

	/**
	 * The revisions of both lists, ascending and once each; null when either is unknown ({@link #known}), since a
	 * session with changes of unknown revision stays unknown whatever recorded the rest.
	 */
	static List<Integer> union(List<Integer> a, List<Integer> b)
	{
		a = known(a);
		b = known(b);
		if (a == null || b == null)
		{
			return null;
		}
		List<Integer> out = new ArrayList<>(a);
		for (Integer rev : b)
		{
			if (!out.contains(rev))
			{
				out.add(rev);
			}
		}
		Collections.sort(out);
		return out;
	}

	static String archiveFileName(String observedAt, int seq)
	{
		String safeStamp = (observedAt == null ? "0" : observedAt).replaceAll("[^0-9A-Za-z._-]", "-");
		return ARCHIVE_PREFIX + safeStamp + "_" + seq + ".json";
	}

	/**
	 * Plans the next archive batch without removing anything from {@link #sessions}. Returns null when within bounds.
	 */
	ArchiveFile planArchive(String accountName, String observedAt, String fileName)
	{
		if (sessions.isEmpty())
		{
			return null;
		}
		int n = sessions.size();
		int totalRemainder = 0;
		for (int i = 0; i < n; i++)
		{
			totalRemainder += remainderSize(sessions.get(i).gains.size());
		}
		int dropSessions = 0;
		while (n - dropSessions > 1 && (n - dropSessions > MAX_SESSIONS || totalRemainder > MAX_GAINS))
		{
			totalRemainder -= remainderSize(sessions.get(dropSessions).gains.size());
			dropSessions++;
		}

		List<SessionGains> archived = new ArrayList<>();
		int[] trimGains = new int[n];
		for (int i = 0; i < dropSessions; i++)
		{
			SessionGains s = sessions.get(i);
			int size = s.gains.size();
			if (size == 0)
			{
				archived.add(copySlice(s, 0, 0));
			}
			else
			{
				for (int start = 0; start < size; start += MAX_PER_SESSION)
				{
					int end = Math.min(size, start + MAX_PER_SESSION);
					archived.add(copySlice(s, start, end));
				}
			}
			trimGains[i] = size;
		}
		for (int i = dropSessions; i < n; i++)
		{
			SessionGains s = sessions.get(i);
			int size = s.gains.size();
			int fullChunks = size > MAX_PER_SESSION ? (size - 1) / MAX_PER_SESSION : 0;
			for (int c = 0; c < fullChunks; c++)
			{
				archived.add(copySlice(s, c * MAX_PER_SESSION, (c + 1) * MAX_PER_SESSION));
			}
			trimGains[i] = fullChunks * MAX_PER_SESSION;
		}
		if (archived.isEmpty())
		{
			return null;
		}
		GainsFile out = new GainsFile();
		out.formatVersion = FORMAT_VERSION;
		out.accountName = accountName;
		out.observedAt = observedAt;
		out.sessions = archived;
		return new ArchiveFile(fileName, out, dropSessions, trimGains);
	}

	/** Removes the entries included in {@code archive} after the archive file has been confirmed on disk. */
	void commitArchive(ArchiveFile archive)
	{
		if (archive == null)
		{
			return;
		}
		int limit = Math.min(sessions.size(), archive.trimGains.length);
		for (int i = archive.dropSessions; i < limit; i++)
		{
			int trim = archive.trimGains[i];
			if (trim > 0)
			{
				List<Gain> g = sessions.get(i).gains;
				g.subList(0, Math.min(trim, g.size())).clear();
			}
		}
		if (archive.dropSessions > 0)
		{
			sessions.subList(0, Math.min(archive.dropSessions, sessions.size() - 1)).clear();
		}
	}

	/** True when {@code persisted} read back from disk contains the sessions and gains of {@code expected}. */
	static boolean archiveMatches(GainsFile expected, GainsFile actual)
	{
		if (expected == null || actual == null || expected.sessions == null || actual.sessions == null)
		{
			return false;
		}
		if (expected.sessions.size() != actual.sessions.size())
		{
			return false;
		}
		for (int i = 0; i < expected.sessions.size(); i++)
		{
			SessionGains e = expected.sessions.get(i);
			SessionGains a = actual.sessions.get(i);
			if (e == null || a == null || !Objects.equals(e.sessionStart, a.sessionStart)
				|| e.withLosses != a.withLosses || !Objects.equals(e.captureRevs, a.captureRevs)
				|| a.gains == null || e.gains.size() != a.gains.size())
			{
				return false;
			}
		}
		return true;
	}

	private static int remainderSize(int size)
	{
		return size <= 0 ? 0 : ((size - 1) % MAX_PER_SESSION) + 1;
	}

	private static SessionGains copySlice(SessionGains src, int from, int to)
	{
		SessionGains copy = new SessionGains();
		copy.sessionStart = src.sessionStart;
		copy.withLosses = src.withLosses;
		copy.captureRevs = src.captureRevs == null ? null : new ArrayList<>(src.captureRevs);
		copy.dropped = src.dropped;
		copy.gains = new ArrayList<>(src.gains.subList(from, to));
		return copy;
	}

	GainsFile compose(String accountName, String observedAt)
	{
		GainsFile out = new GainsFile();
		out.formatVersion = FORMAT_VERSION;
		out.accountName = accountName;
		out.observedAt = observedAt;
		out.sessions = new ArrayList<>(sessions);
		return out;
	}
}
