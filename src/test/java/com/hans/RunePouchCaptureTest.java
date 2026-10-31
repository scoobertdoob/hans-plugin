package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.EnumSet;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/** The rune pouch snapshot: slots, changes, empty versus unavailable, accounts and modes. Synthetic data only. */
public class RunePouchCaptureTest
{
	private static final int AIR = 556;
	private static final int WATER = 555;
	private static final int FIRE = 554;
	private static final int NATURE = 561;
	private static final int POUCH = 12791;
	private static final String LEAGUE = "seasonal-demonic_pacts_league";

	private static void runeEnum(GameFake g)
	{
		g.enums.put(EnumID.RUNEPOUCH_RUNE, new int[][]{{1, 2, 3, 4}, {AIR, WATER, FIRE, NATURE}});
	}

	private static void slot(GameFake g, int i, int type, int qty)
	{
		g.setVarbit(ItemGainsCapture.RUNE_POUCH_TYPES[i], type);
		g.setVarbit(ItemGainsCapture.RUNE_POUCH_QUANTITIES[i], qty);
	}

	private static GameFake game() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new RunePouchCapture(g.ctx, () -> true));
		g.containers.put(InventoryID.INV, new Item[]{new Item(POUCH, 1)}); // a rune pouch in the backpack
		g.containers.put(InventoryID.WORN, new Item[0]);
		return g;
	}

	private static StorageSnapshot pouch(GameFake g) throws IOException
	{
		return g.read(RunePouchCapture.FILE, StorageSnapshot.class);
	}

	@Test
	public void fourSlotsAreSavedAsACarriedCountedStore() throws IOException
	{
		GameFake g = game();
		runeEnum(g);
		slot(g, 0, 1, 1000);
		slot(g, 1, 2, 200);
		slot(g, 2, 3, 30);
		slot(g, 3, 4, 4);
		g.logIn();
		g.executor.runAll();

		StorageSnapshot s = pouch(g);
		assertEquals(RunePouchCapture.KEY, s.key);
		assertEquals(StorageSnapshot.COUNTED, s.kind);
		assertTrue(s.carried);
		assertEquals(4, s.item_count);
		assertEquals(AIR, s.items.get(0).id);
		assertEquals(1000, s.items.get(0).quantity);
		assertEquals(NATURE, s.items.get(3).id);
		assertEquals(4, s.items.get(3).quantity);
		assertNull(s.items.get(0).slot);
	}

	@Test
	public void aChangeIsWrittenAndAnUnchangedPouchIsNot() throws IOException
	{
		GameFake g = game();
		runeEnum(g);
		slot(g, 0, 4, 100);
		g.logIn();
		g.executor.runAll();
		g.ticks(3);
		assertEquals(0, g.executor.pendingCount()); // nothing changed: nothing written again

		slot(g, 0, 4, 60); // cast a few spells
		g.tick();
		g.executor.runAll();
		assertEquals(60, pouch(g).items.get(0).quantity);

		slot(g, 0, 0, 0); // the last charge used: the pouch is empty
		g.tick();
		g.executor.runAll();
		assertEquals(0, pouch(g).item_count);
	}

	@Test
	public void beforeTheRuneEnumLoadsNothingIsWrittenAndTheKnownPouchStays() throws IOException
	{
		GameFake g = game();
		slot(g, 0, 4, 100);
		g.logIn();
		g.ticks(3);
		g.executor.runAll();
		assertNull(pouch(g)); // unavailable, not empty

		runeEnum(g);
		g.tick();
		g.executor.runAll();
		assertEquals(100, pouch(g).items.get(0).quantity);

		g.enums.clear(); // unavailable again
		slot(g, 0, 4, 1);
		g.tick();
		g.executor.runAll();
		assertEquals(100, pouch(g).items.get(0).quantity);
	}

	@Test
	public void aRuneTheEnumCannotNameIsNotAReading() throws IOException
	{
		GameFake g = game();
		runeEnum(g);
		slot(g, 0, 4, 100);
		g.logIn();
		g.executor.runAll();
		slot(g, 1, 99, 5); // a type the enum does not know
		g.tick();
		g.executor.runAll();
		assertEquals(1, pouch(g).item_count);
	}

	@Test
	public void aPresentButEmptyPouchIsSavedAsEmpty() throws IOException
	{
		GameFake g = game();
		runeEnum(g);
		g.logIn();
		g.executor.runAll();
		assertEquals(0, pouch(g).item_count);
		assertTrue(pouch(g).carried);
	}

	@Test
	public void noPouchIsNotAnEmptyPouch() throws IOException
	{
		GameFake g = game();
		g.containers.put(InventoryID.INV, new Item[0]); // the player has no rune pouch: the varbits read empty
		runeEnum(g);
		g.logIn();
		g.ticks(3);
		g.executor.runAll();
		assertNull(pouch(g));
	}

	@Test
	public void anAbsentOrBankedPouchKeepsTheKnownSnapshot() throws IOException
	{
		GameFake g = game();
		g.containers.put(InventoryID.INV, new Item[]{new Item(ItemID.DIVINE_RUNE_POUCH, 1)});
		runeEnum(g);
		slot(g, 0, 4, 100);
		g.logIn();
		g.executor.runAll();
		assertEquals(100, pouch(g).items.get(0).quantity);

		g.setContainer(InventoryID.INV); // banked
		slot(g, 0, 0, 0);
		g.ticks(3);
		g.executor.runAll();
		assertEquals(100, pouch(g).items.get(0).quantity);

		g.logOut(); // and on a later login without it
		g.logIn();
		g.ticks(3);
		g.executor.runAll();
		assertEquals(100, pouch(g).items.get(0).quantity);

		g.setContainer(InventoryID.INV, new Item(ItemID.DIVINE_RUNE_POUCH, 1)); // taken out again: read afresh
		g.tick();
		g.executor.runAll();
		assertEquals(0, pouch(g).item_count);
	}

	@Test
	public void eachAccountAndModeKeepsItsOwnPouch() throws IOException
	{
		GameFake g = game();
		runeEnum(g);
		slot(g, 0, 1, 10);
		g.logIn();
		g.executor.runAll();
		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL);
		slot(g, 0, 2, 20);
		g.settle();
		g.tick();
		g.executor.runAll();
		g.logOut();
		g.accountHash = 2L;
		g.name = "Other";
		g.world = 301;
		g.worldTypes = EnumSet.of(WorldType.MEMBERS);
		slot(g, 0, 3, 30);
		g.logIn();
		g.executor.runAll();

		assertEquals(AIR, g.readIn(1L, GameMode.MAIN, RunePouchCapture.FILE, StorageSnapshot.class).items.get(0).id);
		assertEquals(WATER, g.readIn(1L, LEAGUE, RunePouchCapture.FILE, StorageSnapshot.class).items.get(0).id);
		assertEquals(FIRE, g.readIn(2L, GameMode.MAIN, RunePouchCapture.FILE, StorageSnapshot.class).items.get(0).id);
	}

	@Test
	public void emptyingThePouchIsStillATransferForItemGains() throws IOException
	{
		GameFake g = new GameFake();
		CharacterCapture character = new CharacterCapture(g.ctx);
		SessionCapture sessions = new SessionCapture(g.ctx, character, (a, s, x) -> { });
		g.captures.add(character);
		g.captures.add(sessions);
		g.captures.add(new ItemGainsCapture(g.ctx, sessions));
		g.captures.add(new RunePouchCapture(g.ctx, () -> true));
		runeEnum(g);
		g.containers.put(InventoryID.WORN, new Item[0]);
		g.containers.put(InventoryID.INV, new Item[]{new Item(POUCH, 1)});
		slot(g, 0, 4, 100);
		g.logIn();
		g.ticks(5);

		g.setContainer(InventoryID.INV, new Item(POUCH, 1), new Item(NATURE, 100)); // the pouch emptied
		slot(g, 0, 0, 0);
		g.ticks(3);
		g.logOut();
		g.executor.runAll();

		ItemGainsLog.GainsFile gains = g.readIn(1L, GameMode.MAIN, ItemGainsCapture.FILE, ItemGainsLog.GainsFile.class);
		assertEquals("[]", gains == null ? "[]" : gains.sessions.stream().flatMap(s -> s.gains.stream())
			.map(x -> x.id + ":" + x.qty).collect(java.util.stream.Collectors.toList()).toString());
		assertEquals(0, g.readIn(1L, GameMode.MAIN, RunePouchCapture.FILE, StorageSnapshot.class).item_count);
	}

	@Test
	public void theVarbitsAreTheGamesOwn()
	{
		assertEquals(VarbitID.RUNE_POUCH_TYPE_1, ItemGainsCapture.RUNE_POUCH_TYPES[0]);
		assertEquals(VarbitID.RUNE_POUCH_QUANTITY_1, ItemGainsCapture.RUNE_POUCH_QUANTITIES[0]);
	}
}
