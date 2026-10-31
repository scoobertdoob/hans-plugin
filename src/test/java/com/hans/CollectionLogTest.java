package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.hans.CollectionLogCapture.File;
import com.hans.CollectionLogCapture.Item;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/**
 * The collection log capture (collection_log.json): the Search press when the player opens their own log, one look
 * per burst of script 4100 items, the merge across looks and sessions (nothing ever un-obtained), counts, game modes
 * kept apart, someone else's log never read, and odd or partial input handled safely. Synthetic data only.
 */
public class CollectionLogTest
{
	private static final String LEAGUE = "seasonal-demonic_pacts_league";
	private static final int WHIP = 4151;
	private static final int PET = 12921;
	private static final int BOOTS = 2577;
	private static final int TOTAL = 1700;

	private static GameFake game() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new CollectionLogCapture(g.ctx));
		return g;
	}

	/** The game's own obtained and total counters. */
	private static void counters(GameFake g, int obtained)
	{
		g.varps.put(VarPlayerID.COLLECTION_COUNT, obtained);
		g.varps.put(VarPlayerID.COLLECTION_COUNT_MAX, TOTAL);
	}

	/** The player opens the log: the interface loads, then its setup script runs. */
	private static void open(GameFake g)
	{
		openUnrun(g);
		g.runLater();
	}

	/** The same, before the client thread has run the work queued during the script. */
	private static void openUnrun(GameFake g)
	{
		g.widget(InterfaceID.COLLECTION, true);
		g.openInterface(InterfaceID.COLLECTION); // its components, the Search toggle among them, are there
		g.scriptPost(CollectionLogCapture.SETUP_SCRIPT);
	}

	/** One item as the game lists it: {widget, item id, count}. */
	private static void item(GameFake g, int id, int count)
	{
		g.scriptPre(CollectionLogCapture.ITEM_SCRIPT, InterfaceID.Collection.ITEMS_CONTENTS, id, count);
	}

	/** Lets the look end (its quiet ticks pass) and runs the merges. */
	private static void settleLook(GameFake g)
	{
		g.ticks(CollectionLogCapture.QUIET_TICKS);
		g.executor.runAll();
	}

	private static File file(GameFake g) throws IOException
	{
		return g.read(CollectionLogCapture.FILE, File.class);
	}

	private static List<Map<?, ?>> quarantine(GameFake g) throws IOException
	{
		Path p = g.dir.resolve(JsonWriter.QUARANTINE_FILE);
		List<Map<?, ?>> out = new ArrayList<>();
		if (Files.exists(p))
		{
			for (String line : Files.readAllLines(p, StandardCharsets.UTF_8))
			{
				out.add(new Gson().fromJson(line, Map.class));
			}
		}
		return out;
	}

	@Test
	public void openingTheLogPressesSearchOnceAndWritesEveryObtainedItemWithItsCount() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 3);
		open(g);
		assertEquals(Collections.singletonList(InterfaceID.Collection.SEARCH_TOGGLE + " Search"), g.menuActions);
		assertEquals(Collections.singletonList(CollectionLogCapture.INIT_SCRIPT), g.scriptsRun);
		item(g, WHIP, 1);
		item(g, PET, 1);
		item(g, BOOTS, 37);
		g.ticks(CollectionLogCapture.QUIET_TICKS - 1);
		g.executor.runAll();
		assertNull("the look is still open", file(g));
		g.tick();
		g.executor.runAll();

		File f = file(g);
		assertEquals(3, f.items.size());
		assertEquals(37, f.items.get(BOOTS).quantity);
		assertEquals(1, f.items.get(WHIP).quantity);
		Item whip = f.items.get(WHIP);
		assertTrue("seen at the first look", whip.alreadyHad);
		assertNull(whip.missingAt);
		assertEquals(f.firstLookAt, whip.firstSeen);
		assertTrue(f.lastLook.complete);
		assertTrue(f.lastLook.requested);
		assertEquals("settled", f.lastLook.ended);
		assertEquals(Integer.valueOf(3), f.lastLook.gameObtained);
		assertEquals(Integer.valueOf(TOTAL), f.lastLook.gameTotal);
		assertEquals(f.lastLook.at, f.lastCompleteLook.at);
	}

	@Test
	public void searchIsPressedOnlyOncePerOpening() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		open(g);
		g.scriptPost(CollectionLogCapture.SETUP_SCRIPT); // the press re-runs the setup
		g.widget(InterfaceID.COLLECTION, true); // and reloads the interface: that is not a new opening
		item(g, WHIP, 1);
		g.scriptPost(CollectionLogCapture.SETUP_SCRIPT);
		g.runLater();
		settleLook(g);
		assertEquals(1, g.menuActions.size());
		g.widget(InterfaceID.COLLECTION, false); // closed
		open(g); // and opened again
		assertEquals(2, g.menuActions.size());
	}

	@Test
	public void reopeningAfterUnansweredSearchPressRetriesAndCaptures() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		open(g);
		assertEquals(1, g.menuActions.size());
		g.widget(InterfaceID.COLLECTION, false);
		open(g);
		assertEquals(2, g.menuActions.size());
		item(g, WHIP, 1);
		settleLook(g);
		File f = file(g);
		assertNotNull(f);
		assertEquals(1, f.items.get(WHIP).quantity);
		assertTrue(f.lastLook.requested);
	}

	@Test
	public void theSearchPressIsQueuedNeverMadeInsideTheSetupScript() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		openUnrun(g);
		assertTrue("nothing pressed inside the script", g.menuActions.isEmpty());
		assertTrue(g.scriptsRun.isEmpty());
		assertEquals(1, g.later.size());
		g.scriptPost(CollectionLogCapture.SETUP_SCRIPT); // a repeat before the queue runs queues nothing more
		assertEquals(1, g.later.size());
		g.runLater();
		assertEquals(1, g.menuActions.size());
		assertEquals(1, g.scriptsRun.size());
	}

	@Test
	public void aQueuedPressIsDroppedWhenTheLogClosedOrBecameSomeoneElsesOrTheKeyChanged() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		openUnrun(g);
		g.widget(InterfaceID.COLLECTION, false); // closed before the queue ran
		g.runLater();
		assertTrue("closed", g.menuActions.isEmpty());

		openUnrun(g);
		g.setVarbit(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN, 1);
		g.runLater();
		assertTrue("someone else's", g.menuActions.isEmpty());
		g.setVarbit(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN, 0);
		g.widget(InterfaceID.COLLECTION, false);

		openUnrun(g);
		g.hop(302, WorldType.MEMBERS); // a hop clears the reading key and the ask
		g.runLater();
		assertTrue("key changed", g.menuActions.isEmpty());
	}

	@Test
	public void aQueuedPressIsDroppedWhenTheSettingIsOff() throws IOException
	{
		GameFake g = new GameFake();
		boolean[] on = {true};
		RsbuddyConfig cfg = (RsbuddyConfig) java.lang.reflect.Proxy.newProxyInstance(
			RsbuddyConfig.class.getClassLoader(), new Class<?>[]{RsbuddyConfig.class}, (self, m, args) ->
				// the log's own switch and the upload: either one keeps the log read
				"exportCollectionLog".equals(m.getName()) || "uploadLive".equals(m.getName()) ? (Object) on[0]
					: m.invoke(g.config, args));
		CaptureContext c = new CaptureContext(g.client, cfg, g.json, null, g.login, g.clientThread);
		g.captures.add(new CollectionLogCapture(c));
		g.logIn();
		counters(g, 1);
		openUnrun(g);
		on[0] = false;
		g.runLater();
		assertTrue(g.menuActions.isEmpty());
	}

	@Test
	public void aReloadOfTheInterfaceIsNotANewOpening() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		open(g);
		g.widget(InterfaceID.COLLECTION, true); // the press reloads the log without closing it
		g.scriptPost(CollectionLogCapture.SETUP_SCRIPT);
		g.runLater();
		assertEquals(1, g.menuActions.size());
	}

	@Test
	public void aWorldHopAllowsAnotherPressOnTheNextOpening() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		open(g);
		g.hop(302, WorldType.MEMBERS);
		g.ticks(2); // a tick confirms the world again
		open(g);
		assertEquals(2, g.menuActions.size());
	}

	@Test
	public void theLookCountsEveryPositiveEntryEvenRepeats() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 2);
		open(g);
		item(g, WHIP, 1);
		item(g, WHIP, 1);
		item(g, PET, 1);
		item(g, BOOTS, 0);
		settleLook(g);
		File f = file(g);
		assertEquals(2, f.lastLook.itemsSeen);
		assertEquals(3, f.lastLook.entriesSeen);
		assertEquals(1, f.lastLook.zeroEntries);
	}

	@Test
	public void aPartialLookNeverUnobtainsAndANewItemKeepsWhenItWasStillMissing() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 2);
		open(g);
		item(g, WHIP, 1);
		item(g, BOOTS, 3);
		settleLook(g);
		String firstLook = file(g).lastCompleteLook.at;
		g.widget(InterfaceID.COLLECTION, false);
		g.ticks(5);

		// the next look is cut short: only one old item and the new one arrive
		counters(g, 3);
		open(g);
		item(g, BOOTS, 4);
		item(g, PET, 1);
		settleLook(g);

		File f = file(g);
		assertEquals(3, f.items.size());
		assertTrue("not seen this time, still obtained", f.items.containsKey(WHIP));
		assertEquals(1, f.items.get(WHIP).quantity);
		assertEquals("the newest count", 4, f.items.get(BOOTS).quantity);
		Item pet = f.items.get(PET);
		assertFalse(pet.alreadyHad);
		assertEquals("obtained after the last complete look", firstLook, pet.missingAt);
		assertEquals(f.lastLook.at, pet.firstSeen);
		assertFalse("2 items seen, the game counts 3", f.lastLook.complete);
		assertEquals(firstLook, f.lastCompleteLook.at);
		assertEquals(firstLook, f.firstLookAt);
	}

	@Test
	public void looksMergeAcrossSessionsFromTheFileOnDisk() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		open(g);
		item(g, WHIP, 1);
		settleLook(g);
		String whipSeen = file(g).items.get(WHIP).firstSeen;
		g.logOut();
		g.executor.runAll();

		g.logIn();
		counters(g, 2);
		open(g); // a new login may press again
		assertEquals(2, g.menuActions.size());
		item(g, PET, 1);
		settleLook(g);

		File f = file(g);
		assertEquals(2, f.items.size());
		assertEquals("kept from the earlier session", whipSeen, f.items.get(WHIP).firstSeen);
		assertTrue(f.items.get(WHIP).alreadyHad);
		assertFalse(f.items.get(PET).alreadyHad);
		assertEquals(whipSeen, f.items.get(PET).missingAt);
	}

	@Test
	public void mainAndALeagueKeepSeparateLogs() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		open(g);
		item(g, WHIP, 1);
		settleLook(g);
		g.widget(InterfaceID.COLLECTION, false);

		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL);
		g.settle();
		g.executor.runAll();
		counters(g, 1);
		open(g);
		item(g, PET, 1);
		settleLook(g);
		g.logOut();
		g.executor.runAll();

		File main = g.readIn(g.accountHash, GameMode.MAIN, CollectionLogCapture.FILE, File.class);
		File league = g.readIn(g.accountHash, LEAGUE, CollectionLogCapture.FILE, File.class);
		assertEquals(Collections.singleton(WHIP), main.items.keySet());
		assertEquals(Collections.singleton(PET), league.items.keySet());
	}

	@Test
	public void aHopInTheMiddleOfALookEndsItAndCreditsNothingToTheNextKey() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 2);
		open(g);
		item(g, WHIP, 1);
		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL); // the main login ends: the look is flushed under main
		item(g, PET, 1); // arrives before a tick confirmed the league world: kept nowhere
		g.settle();
		g.ticks(5);
		g.logOut();
		g.executor.runAll();

		File main = g.readIn(g.accountHash, GameMode.MAIN, CollectionLogCapture.FILE, File.class);
		assertEquals(Collections.singleton(WHIP), main.items.keySet());
		assertEquals("logout", main.lastLook.ended);
		assertFalse(main.lastLook.complete);
		assertNull("no new reading at logout", main.lastLook.gameObtained);
		assertNull(g.readIn(g.accountHash, LEAGUE, CollectionLogCapture.FILE, File.class));
		assertTrue("a resent log is not quarantined", quarantine(g).isEmpty());
	}

	@Test
	public void aRegionLoadInTheMiddleOfALookEndsItAsInterrupted() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 2);
		open(g);
		item(g, WHIP, 1);
		g.loadRegion();
		item(g, PET, 1); // no tick has confirmed the world yet
		g.tick();
		item(g, BOOTS, 1); // a new look under the same key
		settleLook(g);

		File f = file(g);
		assertEquals(2, f.items.size());
		assertFalse(f.items.containsKey(PET));
		assertEquals("settled", f.lastLook.ended);
		assertFalse(f.lastLook.complete);
	}

	@Test
	public void someoneElsesLogIsNeverPressedOrRead() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		g.setVarbit(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN, 1); // a house's Adventure Log
		open(g);
		item(g, WHIP, 1); // the host's item, if they pressed Search
		settleLook(g);
		assertTrue(g.menuActions.isEmpty());
		assertTrue(g.scriptsRun.isEmpty());
		assertNull(file(g));

		// a look of our own that the host's book interrupts is dropped too
		g.setVarbit(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN, 0);
		item(g, PET, 1);
		g.setVarbit(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN, 1);
		item(g, BOOTS, 1);
		settleLook(g);
		assertNull(file(g));
	}

	@Test
	public void nothingIsPressedOrKeptBeforeTheLoginSettles() throws IOException
	{
		GameFake g = game();
		g.hub.loggedIn(g.now); // not settled yet
		counters(g, 1);
		open(g);
		item(g, WHIP, 1);
		g.settle();
		g.ticks(5);
		g.executor.runAll();
		assertTrue(g.menuActions.isEmpty());
		assertNull(file(g));
	}

	@Test
	public void oddEntriesAreIgnoredAndZeroCountsAreNotObtained() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 1);
		open(g);
		g.scriptPre(CollectionLogCapture.ITEM_SCRIPT); // no arguments
		g.scriptPre(CollectionLogCapture.ITEM_SCRIPT, 1, WHIP); // too few
		g.scriptPre(CollectionLogCapture.ITEM_SCRIPT, 1, "4151", 1); // not numbers
		g.scriptPre(CollectionLogCapture.ITEM_SCRIPT, 1, null, 1);
		item(g, -1, 1);
		item(g, 0, 1);
		item(g, BOOTS, 0); // listed with a count of 0
		item(g, WHIP, 2);
		item(g, WHIP, 1); // listed twice: the larger count
		g.scriptPre(1234, 1, PET, 1); // another script
		settleLook(g);

		File f = file(g);
		assertEquals(Collections.singleton(WHIP), f.items.keySet());
		assertEquals(2, f.items.get(WHIP).quantity);
		assertEquals(1, f.lastLook.zeroEntries);
		assertEquals(1, f.lastLook.itemsSeen);
		assertTrue(f.lastLook.complete);
	}

	@Test
	public void aLookIsCappedAgainstAGarbledStream() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 10);
		open(g);
		for (int id = 1; id <= CollectionLogCapture.MAX_ITEMS + 50; id++)
		{
			item(g, id, 1);
		}
		item(g, 1, 9); // an item already in the look still updates
		settleLook(g);
		File f = file(g);
		assertEquals(CollectionLogCapture.MAX_ITEMS, f.items.size());
		assertEquals(9, f.items.get(1).quantity);
	}

	@Test
	public void anEmptyLogGivesAnEmptyCompleteLookSoLaterItemsAreDated() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 0);
		open(g);
		g.ticks(CollectionLogCapture.REQUEST_TICKS + 1);
		g.executor.runAll();
		File f = file(g);
		assertNotNull(f);
		assertTrue(f.items.isEmpty());
		assertTrue(f.lastCompleteLook.complete);
		String emptyAt = f.lastCompleteLook.at;
		g.widget(InterfaceID.COLLECTION, false);

		counters(g, 1);
		open(g);
		item(g, WHIP, 1);
		settleLook(g);
		Item whip = file(g).items.get(WHIP);
		assertFalse(whip.alreadyHad);
		assertEquals(emptyAt, whip.missingAt);
	}

	@Test
	public void aPressThatBroughtNothingOnAnAccountWithItemsWritesNothing() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 5);
		open(g);
		g.ticks(CollectionLogCapture.REQUEST_TICKS + 1);
		g.executor.runAll();
		assertNull(file(g));
		// nor does a press whose counters are not loaded
		g.varps.put(VarPlayerID.COLLECTION_COUNT, 0);
		g.varps.put(VarPlayerID.COLLECTION_COUNT_MAX, 0);
		g.widget(InterfaceID.COLLECTION, false);
		open(g);
		g.ticks(CollectionLogCapture.REQUEST_TICKS + 1);
		g.executor.runAll();
		assertNull(file(g));
	}

	@Test
	public void logoutFlushesALookStillArrivingAndReturnsItsWrite() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 2);
		open(g);
		item(g, WHIP, 1);
		List<Future<?>> pending = g.hub.logoutAll(g.now);
		assertEquals(1, pending.stream().filter(java.util.Objects::nonNull).count());
		g.executor.runAll();
		File f = file(g);
		assertEquals(Collections.singleton(WHIP), f.items.keySet());
		assertEquals("logout", f.lastLook.ended);
		assertNull(f.lastCompleteLook);
	}

	@Test
	public void newItemChatLinesAreKeptForTheExactDateAndQuarantinedBeforeSettle() throws IOException
	{
		GameFake g = game();
		g.hub.loggedIn(g.now);
		g.chat("New item added to your collection log: <col=ef1020>Abyssal whip</col>"); // before settle
		g.settle();
		g.chat("New item added to your collection log: <col=ef1020>Pet snakeling</col>");
		g.chat("Someone else received a new collection log item: Twisted bow"); // not our line
		g.executor.runAll();

		File f = file(g);
		assertEquals(1, f.newItemMessages.size());
		assertEquals("Pet snakeling", f.newItemMessages.get(0).name);
		assertNotNull(f.newItemMessages.get(0).at);
		assertTrue(f.items.isEmpty());
		List<Map<?, ?>> q = quarantine(g);
		assertEquals(1, q.size());
		assertEquals("collection_log_new_item", q.get(0).get("kind"));
		assertEquals("Abyssal whip", ((Map<?, ?>) q.get(0).get("value")).get("name"));

		// a later look keeps the messages
		counters(g, 1);
		open(g);
		item(g, PET, 1);
		settleLook(g);
		assertEquals(1, file(g).newItemMessages.size());
	}

	@Test
	public void aReadFailureIsNotAParseErrorAndTheFileIsNotMovedAside() throws IOException
	{
		GameFake g = game();
		g.logIn();
		g.executor.runAll();
		Path folder = g.folder(g.accountHash, GameMode.MAIN);
		Files.createDirectories(folder);
		Path f = folder.resolve(CollectionLogCapture.FILE);
		Files.createDirectory(f); // exists but cannot be read as a file: an I/O failure, not bad JSON
		ProfileKey key = g.login.reading();
		assertNotNull(key);
		try
		{
			g.json.readOrSetAside(key, CollectionLogCapture.FILE, File.class);
			org.junit.Assert.fail("an I/O failure is rethrown");
		}
		catch (IOException expected)
		{
			// the caller then skips its write
		}
		assertTrue("left where it was", Files.isDirectory(f));
		try (Stream<Path> s = Files.list(folder))
		{
			assertEquals(0, s.filter(p -> p.getFileName().toString().contains("_unreadable_")).count());
		}
	}

	@Test
	public void anUnreadableFileIsSetAsideNotOverwritten() throws IOException
	{
		GameFake g = game();
		g.logIn();
		g.executor.runAll();
		Path folder = g.folder(g.accountHash, GameMode.MAIN);
		Files.createDirectories(folder);
		byte[] damaged = "{\"items\": {\"4151\": ".getBytes(StandardCharsets.UTF_8);
		Files.write(folder.resolve(CollectionLogCapture.FILE), damaged);

		counters(g, 1);
		open(g);
		item(g, PET, 1);
		settleLook(g);

		assertEquals(Collections.singleton(PET), file(g).items.keySet());
		List<Path> aside;
		try (Stream<Path> s = Files.list(folder))
		{
			aside = s.filter(p -> p.getFileName().toString().startsWith("collection_log_unreadable_"))
				.collect(Collectors.toList());
		}
		assertEquals(1, aside.size());
		assertEquals(new String(damaged, StandardCharsets.UTF_8),
			new String(Files.readAllBytes(aside.get(0)), StandardCharsets.UTF_8));
	}

	@Test
	public void theMergeKeepsTheNewestCountAndIgnoresAnOlderLook()
	{
		CollectionLogCapture.Look newer = look("2026-01-02T00:00:00Z", true);
		CollectionLogCapture.Look older = look("2026-01-01T00:00:00Z", false);
		File f = CollectionLogCapture.mergeLook(null, newer, Collections.singletonMap(WHIP, 5));
		f = CollectionLogCapture.mergeLook(f, older, Collections.singletonMap(WHIP, 2));
		assertEquals(5, f.items.get(WHIP).quantity);
		assertEquals(newer.at, f.lastLook.at);
		assertEquals(older.at, f.firstLookAt);
		// re-applying the same look changes nothing
		f = CollectionLogCapture.mergeLook(f, newer, Collections.singletonMap(PET, 1));
		assertTrue(f.items.get(PET).alreadyHad);
		assertNull("a look is not its own earlier look", f.items.get(PET).missingAt);
	}

	private static CollectionLogCapture.Look look(String at, boolean complete)
	{
		CollectionLogCapture.Look l = new CollectionLogCapture.Look();
		l.at = at;
		l.complete = complete;
		l.ended = "settled";
		return l;
	}

	@Test
	public void nothingIsPressedWhenTheSearchToggleIsNotThere() throws IOException
	{
		GameFake g = game();
		g.logIn();
		counters(g, 3);
		g.widget(InterfaceID.COLLECTION, true); // its components not reported (a game update moved them, say)
		g.scriptPost(CollectionLogCapture.SETUP_SCRIPT);
		g.runLater();
		assertTrue(g.menuActions.isEmpty());
		assertTrue(g.scriptsRun.isEmpty());
	}
}
