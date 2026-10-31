package com.hans;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;

/**
 * music.json: which music tracks are unlocked, from the game's own Music table (DBTableID.Music) and the 27
 * MUSICMULTI varps. Written by {@link VarsCapture} ("Export progress variables").
 *
 * Each row's {@code variable} column is (MUSICMULTI index 1-27, bit 0-31). A track is unlocked when its bit is set,
 * or when the row is an {@code automatic_unlock} track (Scape Main, Newbie Melody and others: unlocked for every
 * player, their bits are never set). Rows without a variable are hidden sections of other tracks and are not
 * tracks of their own; they are counted in {@code skipped_rows}. One bit of the varps belongs to no track, so a
 * bit count overstates by one while missing the automatic tracks.
 *
 * The table is read whole or not at all: a missing table, any read that fails or gives a value of the wrong shape, a
 * visible row without a variable, or a variable that points past the known varps records nothing rather than a
 * wrong count. The client gives a column's value per tuple index (the variable's index at 0 and its bit at 1), the
 * table's default when the row has none, and an empty array when there is neither.
 */
final class MusicTracks
{
	static final String FILE = "music.json";
	static final String SOURCE = RsbuddyData.SOURCE_PREFIX + ", game client database table Music (DBTableID.Music):"
		+ " displayname, variable (MUSICMULTI index 1-27 and bit), automatic_unlock, hidden";

	/** MUSICMULTI_1 to MUSICMULTI_27, in index order: the varp of the table's index n is VARPS[n - 1]. */
	static final int[] VARPS = {VarPlayerID.MUSICMULTI_1, VarPlayerID.MUSICMULTI_2, VarPlayerID.MUSICMULTI_3,
		VarPlayerID.MUSICMULTI_4, VarPlayerID.MUSICMULTI_5, VarPlayerID.MUSICMULTI_6, VarPlayerID.MUSICMULTI_7,
		VarPlayerID.MUSICMULTI_8, VarPlayerID.MUSICMULTI_9, VarPlayerID.MUSICMULTI_10, VarPlayerID.MUSICMULTI_11,
		VarPlayerID.MUSICMULTI_12, VarPlayerID.MUSICMULTI_13, VarPlayerID.MUSICMULTI_14, VarPlayerID.MUSICMULTI_15,
		VarPlayerID.MUSICMULTI_16, VarPlayerID.MUSICMULTI_17, VarPlayerID.MUSICMULTI_18, VarPlayerID.MUSICMULTI_19,
		VarPlayerID.MUSICMULTI_20, VarPlayerID.MUSICMULTI_21, VarPlayerID.MUSICMULTI_22, VarPlayerID.MUSICMULTI_23,
		VarPlayerID.MUSICMULTI_24, VarPlayerID.MUSICMULTI_25, VarPlayerID.MUSICMULTI_26, VarPlayerID.MUSICMULTI_27};

	private MusicTracks()
	{
	}

	/** Reads of the game's Music table. A read that fails throws (or answers null); it never looks like a value. */
	interface Source
	{
		/** The row ids of the Music table; null when it is not loaded. */
		List<Integer> rows();

		/** The values of one column of one row at a tuple index: empty when the row and the table have none. */
		Object[] cell(int row, int column, int tupleIndex);
	}

	/** A cell that was not read, or not of the shape the column has. */
	private static final class Unreadable extends RuntimeException
	{
		Unreadable()
		{
			super(null, null, false, false);
		}
	}

	/** One track of the table. */
	static final class Track
	{
		final String name;
		final int varp;
		final int bit;
		final boolean auto;

		Track(String name, int varp, int bit, boolean auto)
		{
			this.name = name;
			this.varp = varp;
			this.bit = bit;
			this.auto = auto;
		}
	}

	/** The whole table: its tracks by row id, and how many rows were sections without a variable. */
	static final class Catalogue
	{
		final Map<Integer, Track> tracks;
		final int skipped;

		Catalogue(Map<Integer, Track> tracks, int skipped)
		{
			this.tracks = tracks;
			this.skipped = skipped;
		}
	}

	/** One track as written. */
	static final class Entry
	{
		String name;
		int varp;
		int bit;
		Boolean auto; // true for an automatic unlock; left out otherwise
		boolean unlocked;
		String since; // first seen unlocked by this plugin (first_observed_at for those unlocked by then)

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Entry))
			{
				return false;
			}
			Entry e = (Entry) o;
			return varp == e.varp && bit == e.bit && unlocked == e.unlocked && Objects.equals(name, e.name)
				&& Objects.equals(auto, e.auto) && Objects.equals(since, e.since);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(name, varp, bit, auto, unlocked, since);
		}
	}

	static final class File
	{
		@SerializedName("observed_at")
		String observedAt;
		@SerializedName("first_observed_at")
		String firstObservedAt;
		String source;
		int total;
		@SerializedName("unlocked_count")
		int unlockedCount;
		@SerializedName("skipped_rows")
		int skippedRows;
		Map<Integer, Integer> varps;
		Map<Integer, Entry> tracks;
	}

	/** The Music table, read whole; null when it is missing or any read of it fails or is malformed. */
	static Catalogue catalogue(Source src)
	{
		try
		{
			return read(src);
		}
		catch (RuntimeException e)
		{
			return null; // the client's own failure, or Unreadable: record nothing and try again later
		}
	}

	private static Catalogue read(Source src)
	{
		List<Integer> rows = src.rows();
		if (rows == null || rows.isEmpty())
		{
			return null;
		}
		Map<Integer, Track> tracks = new TreeMap<>();
		int skipped = 0;
		for (Integer row : rows)
		{
			if (row == null)
			{
				return null;
			}
			int[] var = variable(src, row);
			if (var[0] == -1 && var[1] == -1)
			{
				if (!flag(src.cell(row, DBTableID.Music.COL_HIDDEN, 0)))
				{
					return null; // a track that could be unlocked but has no bit: the count would be wrong
				}
				skipped++; // a section may have no name at all (the live table has one with ""): it is not read
				continue;
			}
			String name = text(src.cell(row, DBTableID.Music.COL_DISPLAYNAME, 0));
			if (var[0] < 1 || var[0] > VARPS.length || var[1] < 0 || var[1] > 31)
			{
				return null; // a varp this plugin does not know yet
			}
			tracks.put(row, new Track(name, VARPS[var[0] - 1], var[1],
				flag(src.cell(row, DBTableID.Music.COL_AUTOMATIC_UNLOCK, 0))));
		}
		return tracks.isEmpty() ? null : new Catalogue(tracks, skipped);
	}

	/**
	 * The file for the varps read now; null when it would say the same as {@code before}. A track's unlock time
	 * carries over from {@code before} while it stays unlocked.
	 */
	static File build(Catalogue cat, IntUnaryOperator varp, File before, String stamp)
	{
		File out = new File();
		out.observedAt = stamp;
		out.firstObservedAt = before != null && before.firstObservedAt != null ? before.firstObservedAt : stamp;
		out.source = SOURCE;
		out.varps = new TreeMap<>();
		for (int id : VARPS)
		{
			out.varps.put(id, varp.applyAsInt(id));
		}
		out.tracks = new TreeMap<>();
		for (Map.Entry<Integer, Track> e : cat.tracks.entrySet())
		{
			Track t = e.getValue();
			Entry was = before == null || before.tracks == null ? null : before.tracks.get(e.getKey());
			Entry n = new Entry();
			n.name = t.name;
			n.varp = t.varp;
			n.bit = t.bit;
			n.auto = t.auto ? Boolean.TRUE : null;
			n.unlocked = t.auto || (out.varps.get(t.varp) >>> t.bit & 1) != 0;
			if (n.unlocked)
			{
				n.since = was != null && was.unlocked && was.since != null ? was.since : stamp;
				out.unlockedCount++;
			}
			out.tracks.put(e.getKey(), n);
		}
		out.total = out.tracks.size();
		out.skippedRows = cat.skipped;
		if (before != null && before.total == out.total && before.skippedRows == out.skippedRows
			&& out.varps.equals(before.varps) && out.tracks.equals(before.tracks))
		{
			return null;
		}
		return out;
	}

	static boolean isVarp(int id)
	{
		for (int v : VARPS)
		{
			if (v == id)
			{
				return true;
			}
		}
		return false;
	}

	/** (index, bit), one at each tuple index; {-1, -1} when the row has none (the table's default). */
	private static int[] variable(Source src, int row)
	{
		Object[] a = src.cell(row, DBTableID.Music.COL_VARIABLE, 0);
		Object[] b = src.cell(row, DBTableID.Music.COL_VARIABLE, 1);
		if (a == null || b == null || a.length != b.length || a.length > 1)
		{
			throw new Unreadable();
		}
		return a.length == 0 ? new int[]{-1, -1} : new int[]{number(a[0]), number(b[0])};
	}

	private static int number(Object v)
	{
		if (!(v instanceof Integer))
		{
			throw new Unreadable();
		}
		return (Integer) v;
	}

	private static String text(Object[] cell)
	{
		if (cell == null || cell.length != 1 || !(cell[0] instanceof String) || ((String) cell[0]).trim().isEmpty())
		{
			throw new Unreadable();
		}
		return (String) cell[0];
	}

	/** A boolean column: empty is false (the table's default); a failed or odd read is Unreadable. */
	private static boolean flag(Object[] cell)
	{
		if (cell == null || cell.length > 1)
		{
			throw new Unreadable();
		}
		if (cell.length == 0)
		{
			return false;
		}
		Object v = cell[0];
		if (v instanceof Boolean)
		{
			return (Boolean) v;
		}
		int n = number(v);
		if (n != 0 && n != 1)
		{
			throw new Unreadable();
		}
		return n == 1;
	}

	/** The Music table, read from the client. Client thread only. */
	static final class ClientSource implements Source
	{
		private final Client client;

		ClientSource(Client client)
		{
			this.client = client;
		}

		@Override
		public List<Integer> rows()
		{
			return client.getDBTableRows(DBTableID.Music.ID); // a failure throws: catalogue() records nothing
		}

		@Override
		public Object[] cell(int row, int column, int tupleIndex)
		{
			return client.getDBTableField(row, column, tupleIndex);
		}
	}
}
