package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import net.runelite.api.Item;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;

/** Dizana's quiver snapshot: changes, empty versus unavailable, modes, and no worn ammo. Synthetic data only. */
public class QuiverCaptureTest
{
	private static final int AMETHYST_ARROW = 21326;
	private static final int DRAGON_ARROW = 11212;
	private static final String LEAGUE = "seasonal-demonic_pacts_league";

	private static GameFake game(Item... worn)
	{
		try
		{
			GameFake g = new GameFake();
			g.captures.add(new QuiverCapture(g.ctx, () -> true));
			g.containers.put(InventoryID.INV, new Item[0]);
			g.containers.put(InventoryID.WORN, worn);
			return g;
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static void quiver(GameFake g, int ammo, int amount)
	{
		g.setVarp(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO, ammo);
		g.setVarp(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT, amount);
	}

	private static StorageSnapshot read(GameFake g) throws IOException
	{
		return g.read(QuiverCapture.FILE, StorageSnapshot.class);
	}

	@Test
	public void theQuiversOwnAmmoIsSavedAndNotTheWornAmmo() throws IOException
	{
		GameFake g = game(new Item(ItemID.DIZANAS_QUIVER_CHARGED, 1), new Item(AMETHYST_ARROW, 500));
		quiver(g, DRAGON_ARROW, 250);
		g.logIn();
		g.executor.runAll();

		StorageSnapshot s = read(g);
		assertEquals(QuiverCapture.KEY, s.key);
		assertEquals(StorageSnapshot.COUNTED, s.kind);
		assertTrue(s.carried);
		assertEquals(1, s.item_count); // the 500 worn amethyst arrows are the equipment's
		assertEquals(DRAGON_ARROW, s.items.get(0).id);
		assertEquals(250, s.items.get(0).quantity);
	}

	@Test
	public void changesAreWrittenAndTheLastArrowEmptiesIt() throws IOException
	{
		GameFake g = game(new Item(ItemID.SKILLCAPE_MAX_DIZANAS, 1));
		quiver(g, DRAGON_ARROW, 250);
		g.logIn();
		g.executor.runAll();
		quiver(g, DRAGON_ARROW, 249);
		g.tick();
		g.executor.runAll();
		assertEquals(249, read(g).items.get(0).quantity);
		g.tick();
		assertEquals(0, g.executor.pendingCount()); // unchanged: not written again

		quiver(g, -1, 0);
		g.tick();
		g.executor.runAll();
		assertEquals(0, read(g).item_count); // carried and holding nothing: empty
	}

	@Test
	public void aQuiverNotCarriedIsUnavailableAndTheKnownOneStays() throws IOException
	{
		GameFake g = game();
		quiver(g, DRAGON_ARROW, 250);
		g.logIn();
		g.executor.runAll();
		assertNull(read(g)); // never seen carried: not captured, not empty

		g.setContainer(InventoryID.INV, new Item(ItemID.DIZANAS_QUIVER_INFINITE, 1));
		g.tick();
		g.executor.runAll();
		assertEquals(250, read(g).items.get(0).quantity);

		g.setContainer(InventoryID.INV); // banked
		quiver(g, 0, 0);
		g.tick();
		g.executor.runAll();
		assertEquals(250, read(g).items.get(0).quantity);
	}

	@Test
	public void eachModeKeepsItsOwnQuiver() throws IOException
	{
		GameFake g = game(new Item(ItemID.DIZANAS_QUIVER_CHARGED, 1));
		quiver(g, DRAGON_ARROW, 100);
		g.logIn();
		g.executor.runAll();
		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL);
		quiver(g, AMETHYST_ARROW, 7);
		g.settle();
		g.tick();
		g.executor.runAll();

		assertEquals(DRAGON_ARROW, g.readIn(1L, GameMode.MAIN, QuiverCapture.FILE, StorageSnapshot.class).items.get(0).id);
		assertEquals(AMETHYST_ARROW, g.readIn(1L, LEAGUE, QuiverCapture.FILE, StorageSnapshot.class).items.get(0).id);
	}
}
