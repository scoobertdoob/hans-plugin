package com.hans;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;

/** The music table capture (music.json): decoding, and the flow through VarsCapture. Synthetic data only. */
public class MusicTracksTest
{
	private static final String LEAGUE = "seasonal-demonic_pacts_league";

	/**
	 * A Music table in memory that answers as the client does (checked against its getDBTableField): a column's flat
	 * values, split per tuple index (the variable's index at 0 and its bit at 1), the table's default when the row
	 * has none (variable -1, -1; flags 0), an empty array when there is neither, and a throw for a tuple index past the
	 * column's types.
	 */
	private static final class Table implements MusicTracks.Source
	{
		final Map<Integer, Map<Integer, Object[]>> rows = new HashMap<>();
		final Set<String> failing = new HashSet<>(); // "row:column" reads that throw, as the client may
		final Map<String, Object[]> raw = new HashMap<>(); // "row:column:tuple" answered as given
		final Set<String> nulls = new HashSet<>(); // "row:column" reads answered with null
		boolean loaded = true;
		boolean combined; // the old guess: both numbers of the variable at tuple index 0

		Table track(int row, String name, int index, int bit)
		{
			Map<Integer, Object[]> r = new HashMap<>();
			r.put(DBTableID.Music.COL_DISPLAYNAME, new Object[]{name});
			r.put(DBTableID.Music.COL_VARIABLE, new Object[]{index, bit});
			rows.put(row, r);
			return this;
		}

		Table auto(int row)
		{
			rows.get(row).put(DBTableID.Music.COL_AUTOMATIC_UNLOCK, new Object[]{1});
			return this;
		}

		/** A hidden section of another track: no variable of its own. */
		Table section(int row, String name)
		{
			Map<Integer, Object[]> r = new HashMap<>();
			r.put(DBTableID.Music.COL_DISPLAYNAME, new Object[]{name});
			r.put(DBTableID.Music.COL_HIDDEN, new Object[]{1});
			rows.put(row, r);
			return this;
		}

		@Override
		public List<Integer> rows()
		{
			return loaded ? new ArrayList<>(new TreeSet<>(rows.keySet())) : null;
		}

		@Override
		public Object[] cell(int row, int column, int tupleIndex)
		{
			if (failing.contains(row + ":" + column))
			{
				throw new IllegalStateException("not loaded");
			}
			if (nulls.contains(row + ":" + column))
			{
				return null;
			}
			Object[] given = raw.get(row + ":" + column + ":" + tupleIndex);
			if (given != null)
			{
				return given;
			}
			int types = column == DBTableID.Music.COL_VARIABLE ? 2 : 1;
			if (tupleIndex >= types)
			{
				throw new IllegalArgumentException("tuple index too large");
			}
			Map<Integer, Object[]> r = rows.get(row);
			if (r == null)
			{
				throw new NullPointerException("no such row");
			}
			Object[] v = r.get(column);
			if (v == null)
			{
				v = column == DBTableID.Music.COL_VARIABLE ? new Object[]{-1, -1}
					: column == DBTableID.Music.COL_DISPLAYNAME ? null : new Object[]{0};
			}
			if (v == null)
			{
				return new Object[0];
			}
			if (combined && column == DBTableID.Music.COL_VARIABLE)
			{
				return tupleIndex == 0 ? v.clone() : new Object[0];
			}
			Object[] out = new Object[v.length / types];
			for (int i = 0; i < out.length; i++)
			{
				out[i] = v[i * types + tupleIndex];
			}
			return out;
		}
	}

	/** Three tracks, an automatic one and a hidden section: the shape of the real table. */
	private static Table table()
	{
		return new Table()
			.track(10, "First", 1, 0) // MUSICMULTI_1 bit 0
			.track(11, "Top bit", 1, 31) // the sign bit of MUSICMULTI_1
			.track(12, "Last varp", 27, 5) // MUSICMULTI_27
			.track(13, "Scape Main", 26, 27).auto(13)
			.section(14, "Part 1");
	}

	private static int bits(int... set)
	{
		int v = 0;
		for (int b : set)
		{
			v |= 1 << b;
		}
		return v;
	}

	@Test
	public void theVarpsAreTheMusicMultiVarpsInIndexOrderAndVarsJsonKeepsThem()
	{
		assertEquals(27, MusicTracks.VARPS.length);
		assertEquals(VarPlayerID.MUSICMULTI_1, MusicTracks.VARPS[0]);
		assertEquals(20, MusicTracks.VARPS[0]);
		assertEquals(5238, MusicTracks.VARPS[26]);
		assertArrayEquals(new int[]{20, 21, 22, 23, 24, 25, 298, 311, 346, 414, 464, 598, 662, 721, 906, 1009, 1338,
			1681, 2065, 2237, 2950, 3418, 3575, 4066, 4411, 4944, 5238}, VarIds.groups().get("music")[0]);
	}

	@Test
	public void everyTrackOfTheTableIsReadAndSectionsAreSkipped()
	{
		MusicTracks.Catalogue c = MusicTracks.catalogue(table());
		assertNotNull(c);
		assertEquals(4, c.tracks.size());
		assertEquals(1, c.skipped);
		assertEquals(5238, c.tracks.get(12).varp);
		assertEquals(5, c.tracks.get(12).bit);
		assertTrue(c.tracks.get(13).auto);
		assertFalse(c.tracks.get(10).auto);
	}

	@Test
	public void aSectionWithoutANameIsSkippedNotRefused()
	{
		// first in-game test, 31 Oct: the live table (cache, table 44) has a hidden section row whose displayname is
		// "" and which has no variable; its name was read first, so the whole table was refused every login
		Table t = table().section(15, "");
		MusicTracks.Catalogue c = MusicTracks.catalogue(t);
		assertNotNull(c);
		assertEquals(4, c.tracks.size());
		assertEquals(2, c.skipped);

		t = table().section(15, "x");
		t.rows.get(15).remove(DBTableID.Music.COL_DISPLAYNAME); // no name and no default: an empty array
		assertNotNull("a section with no name at all", MusicTracks.catalogue(t));

		t = table();
		t.raw.put("11:" + DBTableID.Music.COL_DISPLAYNAME + ":0", new Object[]{""});
		assertNull("a track still needs its name", MusicTracks.catalogue(t));
	}

	@Test
	public void theVariableIsReadOneNumberPerTupleIndexAsTheClientGivesIt()
	{
		MusicTracks.Catalogue c = MusicTracks.catalogue(table());
		assertNotNull(c);
		assertEquals(31, c.tracks.get(11).bit);
		assertEquals(VarPlayerID.MUSICMULTI_1, c.tracks.get(11).varp);

		Table t = table();
		t.combined = true; // not the client's shape: refused, not guessed at
		assertNull(MusicTracks.catalogue(t));
	}

	@Test
	public void aFailedAutomaticFlagReadRecordsNothing()
	{
		// review round 1, P1: a failed read of automatic_unlock was taken as false, so Scape Main counted as locked
		Table t = table();
		t.failing.add("13:" + DBTableID.Music.COL_AUTOMATIC_UNLOCK);
		assertNull(MusicTracks.catalogue(t));

		t = table();
		t.failing.add("14:" + DBTableID.Music.COL_HIDDEN);
		assertNull("a failed hidden read", MusicTracks.catalogue(t));

		t = table();
		t.failing.add("10:" + DBTableID.Music.COL_DISPLAYNAME);
		assertNull("a failed name read", MusicTracks.catalogue(t));

		t = table();
		t.failing.add("12:" + DBTableID.Music.COL_VARIABLE);
		assertNull("a failed variable read", MusicTracks.catalogue(t));
	}

	@Test
	public void aMalformedFlagRecordsNothing()
	{
		for (Object odd : new Object[]{2, "true", 1L})
		{
			Table t = table();
			t.raw.put("13:" + DBTableID.Music.COL_AUTOMATIC_UNLOCK + ":0", new Object[]{odd});
			assertNull("automatic_unlock " + odd, MusicTracks.catalogue(t));
		}
		Table t = table();
		t.raw.put("13:" + DBTableID.Music.COL_AUTOMATIC_UNLOCK + ":0", new Object[]{1, 0});
		assertNull("two values", MusicTracks.catalogue(t));

		t = table();
		t.nulls.add("13:" + DBTableID.Music.COL_AUTOMATIC_UNLOCK); // a source answering null is a failure too
		assertNull("null", MusicTracks.catalogue(t));

		t = table();
		t.raw.put("13:" + DBTableID.Music.COL_AUTOMATIC_UNLOCK + ":0", new Object[]{Boolean.TRUE});
		assertTrue("a real boolean is fine", MusicTracks.catalogue(t).tracks.get(13).auto);

		t = table();
		t.raw.put("10:" + DBTableID.Music.COL_AUTOMATIC_UNLOCK + ":0", new Object[0]);
		assertFalse("no value and no default: false", MusicTracks.catalogue(t).tracks.get(10).auto);
	}

	@Test
	public void unlockedBitsAreDecodedAndAutomaticTracksCount()
	{
		MusicTracks.Catalogue c = MusicTracks.catalogue(table());
		Map<Integer, Integer> varps = new HashMap<>();
		varps.put(20, bits(31)); // only the top bit: a negative varp
		varps.put(5238, bits(5));
		MusicTracks.File f = MusicTracks.build(c, id -> varps.getOrDefault(id, 0), null, "t1");
		assertNotNull(f);
		assertFalse(f.tracks.get(10).unlocked);
		assertTrue(f.tracks.get(11).unlocked);
		assertTrue(f.tracks.get(12).unlocked);
		assertTrue(f.tracks.get(13).unlocked); // its bit is not set: unlocked for everyone
		assertEquals(Boolean.TRUE, f.tracks.get(13).auto);
		assertNull(f.tracks.get(10).auto);
		assertEquals(3, f.unlockedCount);
		assertEquals(4, f.total);
		assertEquals(1, f.skippedRows);
		assertEquals(27, f.varps.size());
		assertEquals(Integer.valueOf(bits(31)), f.varps.get(20));
		assertEquals("t1", f.tracks.get(11).since);
		assertNull(f.tracks.get(10).since);
	}

	@Test
	public void aBitBelongingToNoTrackIsNotCounted()
	{
		// the real table leaves one slot without a track (MUSICMULTI_3 bit 25), and the owner's varps have it set
		MusicTracks.Catalogue c = MusicTracks.catalogue(table());
		MusicTracks.File f = MusicTracks.build(c, id -> id == 20 ? bits(7) : 0, null, "t1");
		assertEquals(1, f.unlockedCount); // only the automatic track
	}

	@Test
	public void anUnchangedReadingIsNotRewrittenAndUnlockTimesCarryOver()
	{
		MusicTracks.Catalogue c = MusicTracks.catalogue(table());
		MusicTracks.File first = MusicTracks.build(c, id -> id == 20 ? bits(31) : 0, null, "t1");
		assertNull(MusicTracks.build(c, id -> id == 20 ? bits(31) : 0, first, "t2"));

		MusicTracks.File next = MusicTracks.build(c, id -> id == 20 ? bits(0, 31) : 0, first, "t3");
		assertNotNull(next);
		assertEquals("t1", next.firstObservedAt);
		assertEquals("t3", next.observedAt);
		assertEquals("t1", next.tracks.get(11).since);
		assertEquals("t3", next.tracks.get(10).since);
	}

	@Test
	public void aMissingOrPartialTableRecordsNothing()
	{
		Table t = table();
		t.loaded = false;
		assertNull("not loaded", MusicTracks.catalogue(t));
		assertNull("empty", MusicTracks.catalogue(new Table()));

		t = table();
		t.rows.get(11).remove(DBTableID.Music.COL_DISPLAYNAME);
		assertNull("a row without a name", MusicTracks.catalogue(t));

		t = table();
		t.rows.get(11).remove(DBTableID.Music.COL_VARIABLE);
		assertNull("a visible track without a bit", MusicTracks.catalogue(t));

		t = table();
		t.rows.get(11).put(DBTableID.Music.COL_VARIABLE, new Object[]{"x", 1});
		assertNull("an unreadable variable", MusicTracks.catalogue(t));

		t = table();
		t.raw.put("11:" + DBTableID.Music.COL_VARIABLE + ":1", new Object[0]);
		assertNull("half a variable", MusicTracks.catalogue(t));

		t = table().track(15, "Next update", 28, 0);
		assertNull("a varp this plugin does not know", MusicTracks.catalogue(t));

		t = table().track(15, "Bad bit", 1, 32);
		assertNull("a bit past 31", MusicTracks.catalogue(t));

		t = new Table().section(14, "Part 1");
		assertNull("sections only", MusicTracks.catalogue(t));
	}

	// ---- the flow through VarsCapture, against GameFake ----

	/** GameFake with a VarsCapture whose client answers the Music table from {@code table}. */
	private static GameFake game(Table table) throws IOException
	{
		GameFake g = new GameFake();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(self, m, args) ->
			{
				if ("getDBTableRows".equals(m.getName()) && (Integer) args[0] == DBTableID.Music.ID)
				{
					return table.rows();
				}
				if ("getDBTableField".equals(m.getName()))
				{
					return table.cell((Integer) args[0], (Integer) args[1], (Integer) args[2]);
				}
				try
				{
					return m.invoke(g.client, args);
				}
				catch (InvocationTargetException e)
				{
					throw e.getCause();
				}
			});
		g.captures.add(new VarsCapture(new CaptureContext(client, g.config, g.json, null, g.login)));
		return g;
	}

	/** Sets a varp and tells the captures, as the game would. */
	private static void setVarp(GameFake g, int id, int value)
	{
		g.varps.put(id, value);
		VarbitChanged e = new VarbitChanged();
		e.setVarpId(id);
		e.setVarbitId(-1);
		e.setValue(value);
		for (Capture c : g.captures)
		{
			c.onVarbitChanged(e);
		}
	}

	/** Logs in, lets the previous files load, and lets the reading be written. */
	private static void logInAndWrite(GameFake g)
	{
		g.logIn();
		g.tick();
		g.executor.runAll(); // the previous files are read...
		g.tick();
		g.executor.runAll(); // ...and then written
	}

	private static MusicTracks.File music(GameFake g, String mode) throws IOException
	{
		return g.readIn(1L, mode, MusicTracks.FILE, MusicTracks.File.class);
	}

	@Test
	public void theLoginWritesMusicJsonAndAnUnlockRewritesIt() throws IOException
	{
		GameFake g = game(table());
		g.varps.put(20, bits(31));
		logInAndWrite(g);
		MusicTracks.File f = music(g, "main");
		assertNotNull(f);
		assertEquals(2, f.unlockedCount);
		assertEquals(4, f.total);
		String firstStamp = f.tracks.get(11).since;

		g.ticks(3);
		g.executor.runAll();
		assertEquals(firstStamp, music(g, "main").observedAt); // nothing new: not rewritten

		setVarp(g, 20, bits(0, 31)); // a new track unlocked
		g.tick();
		g.executor.runAll();
		MusicTracks.File after = music(g, "main");
		assertEquals(3, after.unlockedCount);
		assertTrue(after.tracks.get(10).unlocked);
		assertEquals(g.now.toString(), after.tracks.get(10).since);
		assertEquals(firstStamp, after.tracks.get(11).since);
	}

	@Test
	public void aReloginWithNothingNewDoesNotRewrite() throws IOException
	{
		GameFake g = game(table());
		g.varps.put(20, bits(31));
		logInAndWrite(g);
		String stamp = music(g, "main").observedAt;
		g.logOut();
		g.executor.runAll();
		logInAndWrite(g);
		assertEquals(stamp, music(g, "main").observedAt);
	}

	@Test
	public void modesKeepTheirOwnMusic() throws IOException
	{
		GameFake g = game(table());
		g.varps.put(20, bits(0, 31));
		logInAndWrite(g);
		assertEquals(3, music(g, "main").unlockedCount);

		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL);
		g.varps.put(20, 0); // the league character has heard nothing yet
		g.tick();
		g.settle();
		g.executor.runAll();
		g.tick();
		g.executor.runAll();

		assertEquals(1, music(g, LEAGUE).unlockedCount); // the automatic track only
		assertEquals(3, music(g, "main").unlockedCount);
	}

	@Test
	public void aMissingTableWritesNoMusicButVarsStillWrite() throws IOException
	{
		Table t = table();
		t.loaded = false;
		GameFake g = game(t);
		g.varps.put(20, bits(31));
		logInAndWrite(g);
		g.ticks(10);
		g.executor.runAll();
		assertFalse(Files.exists(g.folder(1L, "main").resolve(MusicTracks.FILE)));
		assertNotNull(g.readIn(1L, "main", "vars.json", VarsCapture.File.class));
	}

	@Test
	public void aTableThatLoadsLateIsStillRead() throws IOException
	{
		Table t = table();
		t.loaded = false;
		GameFake g = game(t);
		g.varps.put(20, bits(31));
		logInAndWrite(g);
		t.loaded = true; // within the few ticks allowed
		g.tick();
		g.executor.runAll();
		assertEquals(2, music(g, "main").unlockedCount);
	}

	@Test
	public void theSettingOffWritesNoMusic() throws IOException
	{
		GameFake g = new GameFake();
		Client client = g.client;
		RsbuddyConfig off = (RsbuddyConfig) Proxy.newProxyInstance(RsbuddyConfig.class.getClassLoader(),
			new Class<?>[]{RsbuddyConfig.class}, (self, m, args) ->
				"exportVars".equals(m.getName()) ? false : m.invoke(g.config, args));
		g.captures.add(new VarsCapture(new CaptureContext(client, off, g.json, null, g.login)));
		logInAndWrite(g);
		assertFalse(Files.exists(g.folder(1L, "main").resolve(MusicTracks.FILE)));
	}

	@Test
	public void aFailedAutomaticFlagReadInTheGameWritesNothingAndRetries() throws IOException
	{
		// review round 1, P1 probe: the client's throw was turned into "not automatic" and a count of 0 cached
		Table t = table();
		t.failing.add("13:" + DBTableID.Music.COL_AUTOMATIC_UNLOCK);
		GameFake g = game(t);
		logInAndWrite(g);
		assertFalse(Files.exists(g.folder(1L, "main").resolve(MusicTracks.FILE)));
		t.failing.clear(); // loaded within the few ticks allowed
		g.tick();
		g.executor.runAll();
		assertEquals(1, music(g, "main").unlockedCount);
	}

	@Test
	public void aFailedWriteIsRetriedAtTheNextLogin() throws IOException
	{
		// review round 1, P2 probe: the cache said written, so an unchanged relogin left the disk stale
		GameFake g = game(table());
		logInAndWrite(g);
		assertEquals(1, music(g, "main").unlockedCount);

		Path file = g.folder(1L, "main").resolve(MusicTracks.FILE);
		Files.delete(file);
		Files.createDirectories(file.resolve("blocked")); // the next write cannot replace it
		setVarp(g, 20, bits(31));
		g.tick();
		g.executor.runAll();
		assertTrue(Files.isDirectory(file)); // the write failed
		Files.delete(file.resolve("blocked"));
		Files.delete(file);

		g.logOut();
		g.executor.runAll();
		logInAndWrite(g);
		assertEquals(2, music(g, "main").unlockedCount);
	}

	@Test
	public void anUnlockJustBeforeLogoutIsWrittenAtLogoutWithItsTime() throws IOException
	{
		// review round 1, P2 probe: the change only marked the file dirty, and logout came before the next tick
		GameFake g = game(table());
		g.varps.put(20, bits(31));
		logInAndWrite(g);
		String lastTick = g.now.toString();
		setVarp(g, 20, bits(0, 31));
		g.varps.put(20, 0); // nothing may be read from the game at logout
		g.logOut();
		g.executor.runAll();
		MusicTracks.File f = music(g, "main");
		assertEquals(3, f.unlockedCount);
		assertEquals(lastTick, f.tracks.get(10).since);

		g.varps.put(20, bits(0, 31));
		g.now = g.now.plusSeconds(3600);
		logInAndWrite(g);
		assertEquals(lastTick, music(g, "main").tracks.get(10).since); // not dated by the later login
	}

	@Test
	public void anUnlockBeforeAHopIsCreditedToTheModeItHappenedIn() throws IOException
	{
		GameFake g = game(table());
		g.varps.put(20, bits(31));
		logInAndWrite(g);
		setVarp(g, 20, bits(0, 31)); // main, just before the hop

		g.hub.hopping();
		setVarp(g, 20, 0); // during the hop: no key, not credited to either mode
		g.world = 500;
		g.worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL);
		g.hub.loggedIn(g.now);
		g.tick();
		g.settle();
		g.executor.runAll();
		g.tick();
		g.executor.runAll();

		assertEquals(3, music(g, "main").unlockedCount);
		assertEquals(1, music(g, LEAGUE).unlockedCount);
	}

	@Test
	public void anUnlockReadOnATickIsNotWrittenAgainAtLogout() throws IOException
	{
		GameFake g = game(table());
		logInAndWrite(g);
		setVarp(g, 20, bits(0));
		g.tick();
		g.executor.runAll();
		String stamp = music(g, "main").observedAt;
		g.logOut();
		assertEquals(0, g.executor.pendingCount());
		assertEquals(stamp, music(g, "main").observedAt);
	}
}
