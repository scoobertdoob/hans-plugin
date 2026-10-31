package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ScriptID;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;

/** Potion storage: mixed doses, updates, empty versus unavailable, reopening and modes. Synthetic data only. */
public class PotionStorageCaptureTest
{
	private static final int PRAYER = 9001; // potion enums (synthetic ids)
	private static final int SUPER_RESTORE = 9002;
	private static final int GUAM_UNF = 9003;
	private static final int PRAYER_4 = 2434;
	private static final int PRAYER_3 = 139;
	private static final int RESTORE_4 = 3024;
	private static final int GUAM_POTION_UNF = 91;
	private static final int TRIGGER_VAR = 4300;
	private static final String LEAGUE = "seasonal-demonic_pacts_league";

	private final Map<Integer, Integer> doses = new HashMap<>();
	private final Map<Integer, Integer> withdraw = new HashMap<>();

	private GameFake game() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new PotionStorageCapture(g.ctx, () -> true));
		g.enums.put(EnumID.POTIONSTORE_POTIONS, new int[][]{{0, 1}, {PRAYER, SUPER_RESTORE}});
		g.enums.put(EnumID.POTIONSTORE_UNFINISHED_POTIONS, new int[][]{{0}, {GUAM_UNF}});
		g.enums.put(PRAYER, new int[][]{{1, 2, 3, 4}, {143, 141, PRAYER_3, PRAYER_4}});
		g.enums.put(SUPER_RESTORE, new int[][]{{1, 2, 3, 4}, {3030, 3028, 3026, RESTORE_4}});
		g.enums.put(GUAM_UNF, new int[][]{{1}, {GUAM_POTION_UNF}});
		g.scripts.put(ScriptID.POTIONSTORE_DOSES, doses);
		g.scripts.put(ScriptID.POTIONSTORE_WITHDRAW_DOSES, withdraw);
		withdraw.put(PRAYER, 4);
		withdraw.put(SUPER_RESTORE, 4);
		withdraw.put(GUAM_UNF, 1);
		g.varTriggers.put(InterfaceID.Bankmain.POTIONSTORE_ITEMS, new int[]{TRIGGER_VAR});
		g.logIn();
		return g;
	}

	private static void openBank(GameFake g)
	{
		g.widgets.put(InterfaceID.Bankmain.ITEMS, "");
		g.widgets.put(InterfaceID.Bankmain.POTIONSTORE_ITEMS, "");
		g.setContainer(InventoryID.BANK, new Item(995, 1000));
	}

	private static void closeBank(GameFake g)
	{
		g.widgets.remove(InterfaceID.Bankmain.ITEMS);
		g.widgets.remove(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
		g.tick();
	}

	private static StorageSnapshot read(GameFake g) throws IOException
	{
		g.executor.runAll();
		return g.read(PotionStorageCapture.FILE, StorageSnapshot.class);
	}

	@Test
	public void mixedDosesAreSavedOnceTheBankIsReady() throws IOException
	{
		GameFake g = game();
		doses.put(PRAYER, 4 * 10 + 3); // ten 4-dose and a 3-dose prayer potion, as the store keeps them: in doses
		doses.put(GUAM_UNF, 12);
		g.varps.put(VarPlayerID.POTIONSTORE_VIALS, 50);
		openBank(g);
		g.ticks(PotionStorageCapture.READY_TICKS); // the tick it opened on, and one more
		assertNull(read(g)); // the bank has only just opened
		g.tick();

		StorageSnapshot s = read(g);
		assertEquals(PotionStorageCapture.KEY, s.key);
		assertEquals(StorageSnapshot.COUNTED, s.kind);
		assertFalse(s.carried);
		assertEquals(3, s.item_count);
		assertEquals(PRAYER_4, s.items.get(0).id);
		assertEquals(43, s.items.get(0).quantity);
		assertEquals("dose", s.items.get(0).unit);
		assertEquals(Integer.valueOf(4), s.items.get(0).withdraw_doses);
		assertEquals(GUAM_POTION_UNF, s.items.get(1).id);
		assertEquals(ItemID.VIAL_EMPTY, s.items.get(2).id);
		assertEquals(50, s.items.get(2).quantity);
		assertNull(s.items.get(2).unit);
	}

	@Test
	public void neverOpenedIsNotEmpty() throws IOException
	{
		GameFake g = game();
		doses.put(PRAYER, 8);
		g.ticks(5);
		assertNull(read(g)); // the bank never opened: not captured

		g.widgets.put(InterfaceID.Bankmain.ITEMS, ""); // the interface, but the bank not sent yet
		g.ticks(5);
		assertNull(read(g));
	}

	@Test
	public void aChangeWhileOpenIsWrittenAndReopeningUnchangedIsNot() throws IOException
	{
		GameFake g = game();
		doses.put(PRAYER, 8);
		openBank(g);
		g.ticks(3);
		assertEquals(8, read(g).items.get(0).quantity);

		doses.put(PRAYER, 4); // one potion withdrawn
		doses.put(SUPER_RESTORE, 16);
		g.setVarp(TRIGGER_VAR, 1);
		g.tick();
		StorageSnapshot s = read(g);
		assertEquals(4, s.items.get(0).quantity);
		assertEquals(RESTORE_4, s.items.get(1).id);

		closeBank(g);
		openBank(g);
		g.ticks(3);
		assertEquals(0, g.executor.pendingCount()); // read again on reopening, unchanged: not written again

		closeBank(g);
		doses.clear(); // emptied (in another session's terms: the game now says no doses)
		openBank(g);
		g.ticks(3);
		assertEquals(0, read(g).item_count); // a complete reading with nothing stored: empty
	}

	@Test
	public void anIncompleteReadingKeepsTheKnownStore() throws IOException
	{
		GameFake g = game();
		doses.put(PRAYER, 8);
		openBank(g);
		g.ticks(3);
		assertEquals(8, read(g).items.get(0).quantity);

		g.enums.remove(EnumID.POTIONSTORE_UNFINISHED_POTIONS); // a list not loaded
		doses.put(PRAYER, 0);
		g.setVarp(VarPlayerID.POTIONSTORE_BASE_VAR_1, 2);
		g.ticks(2);
		assertEquals(8, read(g).items.get(0).quantity);

		withdraw.remove(PRAYER); // stored, but its withdrawn form unknown
		doses.put(PRAYER, 3);
		g.enums.put(EnumID.POTIONSTORE_UNFINISHED_POTIONS, new int[][]{{0}, {GUAM_UNF}});
		g.ticks(2);
		assertEquals(8, read(g).items.get(0).quantity);
	}

	@Test
	public void eachModeKeepsItsOwnStore() throws IOException
	{
		GameFake g = game();
		doses.put(PRAYER, 8);
		openBank(g);
		g.ticks(3);
		closeBank(g);
		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL);
		g.settle();
		doses.put(PRAYER, 0);
		doses.put(SUPER_RESTORE, 4);
		openBank(g);
		g.ticks(3);
		g.executor.runAll();

		assertEquals(PRAYER_4, g.readIn(1L, GameMode.MAIN, PotionStorageCapture.FILE, StorageSnapshot.class).items.get(0).id);
		assertEquals(RESTORE_4, g.readIn(1L, LEAGUE, PotionStorageCapture.FILE, StorageSnapshot.class).items.get(0).id);
	}
}
