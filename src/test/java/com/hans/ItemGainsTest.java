package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hans.ItemGainsLog.Gain;
import com.hans.ItemGainsLog.GainsFile;
import com.hans.ItemGainsLog.SessionGains;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The inventory diff behind item_gains.json (gains and losses) and the bounds of the file. Synthetic ids only. */
public class ItemGainsTest
{
	private static final int HERB = 199;
	private static final int ORE = 440;
	private static final int SWORD = 1277;
	private static final int ARROW = 882;
	private static final int NATURE = 561;
	private static final int DEATH = 560;
	private static final int FIRE = 554;
	private static final int BOW = 851;
	private static final int COINS = 995;
	private static final int GRACEFUL_HOOD_INV = 11850;
	private static final int GRACEFUL_HOOD_WORN = 11851;
	private static final int GRACEFUL_CAPE_INV = 11852;
	private static final int GRACEFUL_CAPE_WORN = 11853;
	private static final int DHAROK_HELM_100 = 4880;
	private static final int DHAROK_HELM_75 = 4881;
	private static final int PRAYER_POTION_4 = 2434;
	private static final int PRAYER_POTION_3 = 139;
	private static final int RUNE_POUCH = 12791;
	private static final int RING_OF_RECOIL = 2550;
	private static final int NOTED_SWORD = 1278;
	private static final int DARK_BOW = 11235;
	private static final int RUNE_ARROW = 892;
	private static final int PURE_ESSENCE = 7936;
	private static final int RUNE_ESSENCE = 1436;
	private static final int GUARDIAN_ESSENCE = 26879;
	private static final int BLOOD_RUNE = 565;
	private static final int MEDIUM_POUCH = 5510;
	private static final int MEDIUM_POUCH_DEGRADED = 5511;
	private static final int GIANT_POUCH = 5514;
	private static final int GIANT_POUCH_DEGRADED = 5515;
	private static final int PLANK = 960;
	private static final int OAK_PLANK = 8778;
	private static final int TEAK_PLANK = 8780;
	private static final int PLANK_SACK = 24882;
	private static final int ESSENCE = ItemGainsCapture.ESSENCE;

	@Rule
	public final TemporaryFolder temporary = new TemporaryFolder();

	private static Map<Integer, Integer> items(int... idQty)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int i = 0; i < idQty.length; i += 2)
		{
			m.put(idQty[i], idQty[i + 1]);
		}
		return m;
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler h)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) ->
		{
			Object v = h.invoke(p, m, a);
			if (v != null)
			{
				return v;
			}
			Class<?> rt = m.getReturnType();
			if (rt == boolean.class)
			{
				return false;
			}
			if (rt == int.class)
			{
				return 0;
			}
			if (rt == long.class)
			{
				return 0L;
			}
			if (rt == EnumSet.class)
			{
				return EnumSet.noneOf(WorldType.class);
			}
			return null;
		});
	}

	@Test
	public void firstReadingIsOnlyABaseline()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		assertTrue(t.tick(items(HERB, 5), items(), true).isEmpty());
		assertTrue(t.tick(items(HERB, 7), null, true).isEmpty());
		assertEquals(items(HERB, 2), t.tick(null, null, true)); // emitted after the 1-tick transfer window
		assertTrue(t.tick(null, null, true).isEmpty()); // nothing read: nothing changes
	}

	@Test
	public void addsUpSlotsAndCountsNewStacks()
	{
		Map<Integer, Integer> totals = ItemGainsTracker.totals(new int[]{HERB, -1, HERB, ORE}, new int[]{1, 0, 1, 1});
		assertEquals(items(HERB, 2, ORE, 1), totals);
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(totals, items(), true);
		assertTrue(t.tick(items(HERB, 3, ORE, 4), null, true).isEmpty());
		assertEquals(items(HERB, 1, ORE, 3), t.tick(items(HERB, 1), null, true)); // dropped or used on the next tick
		assertEquals(items(HERB, -2, ORE, -4), t.flush());
	}

	@Test
	public void alchingShowsTheGainAndWhatWasUsedUp()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(BOW, 5, NATURE, 100, FIRE, 500, COINS, 1000), items(), true);
		assertTrue(t.tick(items(BOW, 4, NATURE, 99, FIRE, 495, COINS, 1768), null, true).isEmpty());
		assertEquals(items(BOW, -1, NATURE, -1, FIRE, -5, COINS, 768), t.tick(null, null, true));
	}

	@Test
	public void ignoredTicksStillMoveTheBaseline()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(), items(), true);
		assertTrue(t.tick(items(ORE, 28), null, false).isEmpty()); // withdrawn with the bank open
		assertTrue(t.tick(items(ORE, 28), null, true).isEmpty()); // not counted once it closes
		assertTrue(t.tick(items(), null, false).isEmpty()); // deposited with the bank open: not a loss either
	}

	@Test
	public void openingBankEmitsBufferedGainFromPrecedingRecordedTick()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(), items(), true);
		// Tick 1 (record=true): mine 1 ore; held for 1 tick in pendingInvPrevious
		assertTrue(t.tick(items(ORE, 1), null, true).isEmpty());
		// Tick 2 (record=false): open bank and withdraw 27 more ore; the preceding-tick +1 ore is emitted while bank withdrawal is suppressed
		assertEquals(items(ORE, 1), t.tick(items(ORE, 28), null, false));
		assertTrue(t.flush().isEmpty());
		assertTrue(t.tick(items(ORE, 28), null, true).isEmpty());

		// An inventory-first equip on the tick right before opening the bank still reconciles against the worn arrival
		assertTrue(t.tick(items(ORE, 28, SWORD, 1), null, true).isEmpty());
		assertEquals(items(SWORD, 1), t.tick(items(ORE, 28), null, true)); // emits sword gain, buffers sword equip loss (-1)
		assertTrue(t.tick(items(ORE, 28), items(SWORD, 1), false).isEmpty());
	}

	@Test
	public void unequippingAndSwappingAreNotGains()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(HERB, 1), items(SWORD, 1, ARROW, 100), true);
		// unequip the sword: it leaves the equipment and enters the inventory on the same tick
		assertTrue(t.tick(items(HERB, 1, SWORD, 1), items(ARROW, 100), true).isEmpty());
		// remove the arrows; the inventory change arrives a tick after the equipment change
		assertTrue(t.tick(null, items(), true).isEmpty());
		assertTrue(t.tick(items(HERB, 1, SWORD, 1, ARROW, 100), null, true).isEmpty());
		// picking up more arrows later is a gain again
		assertTrue(t.tick(items(HERB, 1, SWORD, 1, ARROW, 120), null, true).isEmpty());
		assertEquals(items(ARROW, 20), t.tick(null, null, true));
	}

	@Test
	public void equippingAndSwappingAreNotLosses()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(SWORD, 1, BOW, 1, ARROW, 100), items(), true);
		// equip the sword: it leaves the inventory and enters the equipment on the same tick
		assertTrue(t.tick(items(BOW, 1, ARROW, 100), items(SWORD, 1), true).isEmpty());
		// swap to the bow: the sword comes back as the bow leaves
		assertTrue(t.tick(items(SWORD, 1, ARROW, 100), items(BOW, 1), true).isEmpty());
		// equip the arrows; the inventory change arrives a tick after the equipment change
		assertTrue(t.tick(null, items(BOW, 1, ARROW, 100), true).isEmpty());
		assertTrue(t.tick(items(SWORD, 1), null, true).isEmpty());
		// dropping the sword later is a loss again
		assertTrue(t.tick(items(), null, true).isEmpty());
		assertEquals(items(SWORD, -1), t.tick(null, null, true));
	}

	@Test
	public void equipmentCreditExpiresAfterOneTick()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(), items(SWORD, 1), true);
		t.tick(null, items(), true); // the sword is gone from the equipment (e.g. it broke)
		t.tick(null, null, true);
		assertTrue(t.tick(items(SWORD, 1), null, true).isEmpty());
		assertEquals(items(SWORD, 1), t.tick(null, null, true)); // a later pick-up counts
	}

	@Test
	public void gracefulEquipAndUnequipInBothEventOrdersAndDegradationAreNotGainsOrLosses()
	{
		ItemGainsTracker t = new ItemGainsTracker(ItemVariationMapping::map,
			id -> id == PRAYER_POTION_4 || id == PRAYER_POTION_3);
		t.tick(items(GRACEFUL_HOOD_INV, 1, GRACEFUL_CAPE_INV, 1, DHAROK_HELM_100, 1, PRAYER_POTION_4, 1), items(), true);

		// same-tick equip of graceful hood (11850 inv -> 11851 worn)
		assertTrue(t.tick(items(GRACEFUL_CAPE_INV, 1, DHAROK_HELM_100, 1, PRAYER_POTION_4, 1), items(GRACEFUL_HOOD_WORN, 1), true).isEmpty());

		// inventory-first equip of graceful cape (11852 leaves inv on tick 1, 11853 enters worn on tick 2)
		assertTrue(t.tick(items(DHAROK_HELM_100, 1, PRAYER_POTION_4, 1), null, true).isEmpty());
		assertTrue(t.tick(null, items(GRACEFUL_HOOD_WORN, 1, GRACEFUL_CAPE_WORN, 1), true).isEmpty());

		// worn-first unequip of graceful hood (11851 leaves worn on tick 1, 11850 enters inv on tick 2)
		assertTrue(t.tick(null, items(GRACEFUL_CAPE_WORN, 1), true).isEmpty());
		assertTrue(t.tick(items(GRACEFUL_HOOD_INV, 1, DHAROK_HELM_100, 1, PRAYER_POTION_4, 1), null, true).isEmpty());

		// inventory-first unequip of graceful cape (11852 enters inv on tick 1, 11853 leaves worn on tick 2)
		assertTrue(t.tick(items(GRACEFUL_HOOD_INV, 1, GRACEFUL_CAPE_INV, 1, DHAROK_HELM_100, 1, PRAYER_POTION_4, 1), null, true).isEmpty());
		assertTrue(t.tick(null, items(), true).isEmpty());

		// degradation in inventory (Dharok's helm 100 -> 75) is not an acquisition or loss
		assertTrue(t.tick(items(GRACEFUL_HOOD_INV, 1, GRACEFUL_CAPE_INV, 1, DHAROK_HELM_75, 1, PRAYER_POTION_4, 1), null, true).isEmpty());

		// drinking a consumable dose (Prayer potion(4) -> (3)) is still recorded with raw ids
		assertTrue(t.tick(items(GRACEFUL_HOOD_INV, 1, GRACEFUL_CAPE_INV, 1, DHAROK_HELM_75, 1, PRAYER_POTION_3, 1), null, true).isEmpty());
		assertEquals(items(PRAYER_POTION_4, -1, PRAYER_POTION_3, 1), t.tick(null, null, true));

		// real drop of graceful hood still keeps its raw id (11850) after the 1-tick transfer window
		assertTrue(t.tick(items(GRACEFUL_CAPE_INV, 1, DHAROK_HELM_75, 1, PRAYER_POTION_3, 1), null, true).isEmpty());
		assertEquals(items(GRACEFUL_HOOD_INV, -1), t.tick(null, null, true));
	}

	@Test
	public void ammoShotDoesNotHideRealPickup()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(ARROW, 10), items(ARROW, 100), true);
		// fire one arrow (worn 100 -> 99) and pick up one arrow on the ground (inv 10 -> 11) on the same tick
		assertTrue(t.tick(items(ARROW, 11), items(ARROW, 99), true).isEmpty());
		assertEquals(items(ARROW, 1), t.tick(null, items(ARROW, 98), true)); // fire another arrow on tick 2 while tick 1 pickup emits
		// pick up an arrow on tick 3 after the shot on tick 2
		assertTrue(t.tick(items(ARROW, 12), null, true).isEmpty());
		assertEquals(items(ARROW, 1), t.tick(null, null, true));
	}

	@Test
	public void finalEquippedArrowShotDoesNotHidePickupWhileTrueUnequipCancelsInEitherOrder()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		// Start with 1 final arrow equipped and 10 in inventory: firing it (worn 1 -> 0) alongside a pickup of 3 (inv 10 -> 13) records +3
		t.tick(items(ARROW, 10), items(ARROW, 1), true);
		assertTrue(t.tick(items(ARROW, 13), items(), true).isEmpty());
		assertEquals(items(ARROW, 3), t.tick(null, null, true));

		// Also when starting with an empty inventory and firing the final arrow (worn 1 -> 0) on the tick before or after a pickup
		t.reset();
		t.tick(items(), items(ARROW, 1), true);
		assertTrue(t.tick(null, items(), true).isEmpty()); // worn-first final shot
		assertTrue(t.tick(items(ARROW, 4), null, true).isEmpty()); // pickup of 4 on the next tick
		assertEquals(items(ARROW, 4), t.tick(null, null, true));

		t.reset();
		t.tick(items(), items(ARROW, 1), true);
		assertTrue(t.tick(items(ARROW, 4), null, true).isEmpty()); // inventory-first pickup of 4
		assertEquals(items(ARROW, 4), t.tick(null, items(), true)); // final shot on the next tick does not cancel the pickup

		// Accepted coincidence: picking up exactly the 1 arrow just fired looks like taking it off, so it is not counted
		t.reset();
		t.tick(items(ARROW, 10), items(ARROW, 1), true);
		assertTrue(t.tick(items(ARROW, 11), items(), true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());

		// True unequip of an arrow stack (50 arrows) and a sword in both event orders (worn-first and inventory-first) still cancels
		t.reset();
		t.tick(items(), items(SWORD, 1, ARROW, 50), true);
		assertTrue(t.tick(null, items(SWORD, 1), true).isEmpty()); // worn-first arrow stack unequip
		assertTrue(t.tick(items(ARROW, 50), null, true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());

		// Re-equip arrows (inventory-first), then unequip them (inventory-first)
		assertTrue(t.tick(items(), null, true).isEmpty());
		assertTrue(t.tick(null, items(SWORD, 1, ARROW, 50), true).isEmpty());
		assertTrue(t.tick(items(SWORD, 1, ARROW, 50), null, true).isEmpty()); // inventory-first unequip of both sword and arrow stack
		assertTrue(t.tick(null, items(), true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());
	}

	@Test
	public void runePouchAndQuiverMovementsAndConsumptionAlongsidePickups()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		// Start with rune pouch in inventory, 500 fire runes in inventory, 100 nature runes in pouch and 100 arrows in quiver
		t.tick(items(RUNE_POUCH, 1, FIRE, 500), items(), items(NATURE, 100, ARROW, 100), true);
		// Fill 500 fire runes into the pouch: leaves inventory, enters pouch
		assertTrue(t.tick(items(RUNE_POUCH, 1), null, items(NATURE, 100, FIRE, 500, ARROW, 100), true).isEmpty());
		// Empty 100 nature runes from the pouch: leaves pouch, enters inventory
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 100), null, items(FIRE, 500, ARROW, 100), true).isEmpty());
		// Inventory-first fill (varbits update a tick after inventory)
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 40), null, null, true).isEmpty());
		assertTrue(t.tick(null, null, items(FIRE, 500, NATURE, 60, ARROW, 100), true).isEmpty());
		// Pouch-first empty (varbits update a tick before inventory)
		assertTrue(t.tick(null, null, items(FIRE, 500, ARROW, 100), true).isEmpty());
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 100), null, null, true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());

		// Inventory-first empty of quiver arrows (100 arrows enter inv on tick 1, leave quiver on tick 2)
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 100, ARROW, 100), null, null, true).isEmpty());
		assertTrue(t.tick(null, null, items(FIRE, 500), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());

		// Put 60 nature runes and 50 arrows back into pouch/quiver
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 40, ARROW, 50), null, items(FIRE, 500, NATURE, 60, ARROW, 50), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());

		// Casting 1 nature rune from pouch (60 -> 59) while picking up 1 nature rune (40 -> 41) records +1 nature rune
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 41, ARROW, 50), null, items(FIRE, 500, NATURE, 59, ARROW, 50), true).isEmpty());
		assertEquals(items(NATURE, 1), t.tick(null, null, null, true));

		// Firing 1 quiver arrow (50 -> 49) while picking up 1 arrow (50 -> 51) records +1 arrow
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 41, ARROW, 51), null, items(FIRE, 500, NATURE, 59, ARROW, 49), true).isEmpty());
		assertEquals(items(ARROW, 1), t.tick(null, null, null, true));

		// Consuming the final rune (1 -> 0) and final quiver arrow (1 -> 0) alongside pickups of other amounts records the pickups
		t.reset();
		t.tick(items(NATURE, 5, ARROW, 5), items(), items(NATURE, 1, ARROW, 1), true);
		assertTrue(t.tick(items(NATURE, 8, ARROW, 9), null, items(), true).isEmpty());
		assertEquals(items(NATURE, 3, ARROW, 4), t.tick(null, null, null, true));
	}

	/** A tracker with the capture's essence pool, as the plugin builds it. */
	private static ItemGainsTracker stores()
	{
		return new ItemGainsTracker(ItemVariationMapping::map, id -> false, ItemGainsCapture::storeKey);
	}

	/** Counted-store slots, one per store key (the slot id is the key). */
	private static Map<Integer, ItemGainsTracker.Slot> slots(Map<Integer, Integer> keyQty)
	{
		Map<Integer, ItemGainsTracker.Slot> out = new HashMap<>();
		for (Map.Entry<Integer, Integer> e : keyQty.entrySet())
		{
			out.put(e.getKey(), new ItemGainsTracker.Slot(e.getKey(), e.getValue(), 0));
		}
		return out;
	}

	/** Essence pouches, slot by slot: quantity and kind. */
	private static Map<Integer, ItemGainsTracker.Slot> pouches(int... qtyKind)
	{
		Map<Integer, ItemGainsTracker.Slot> out = new HashMap<>();
		for (int i = 0; i < qtyKind.length; i += 2)
		{
			out.put(i / 2, new ItemGainsTracker.Slot(ESSENCE, qtyKind[i], qtyKind[i + 1]));
		}
		return out;
	}

	/** One recorded tick with only the inventory (null: unchanged) and the counted stores read, as the capture does. */
	private static Map<Integer, Integer> tick(ItemGainsTracker t, Map<Integer, Integer> inventory,
		Map<Integer, Integer> stores)
	{
		return t.tick(inventory, null, null, stores == null ? null : slots(stores), true);
	}

	private static Map<Integer, Integer> tickSlots(ItemGainsTracker t, Map<Integer, Integer> inventory,
		Map<Integer, ItemGainsTracker.Slot> stores)
	{
		return t.tick(inventory, null, null, stores, true);
	}

	/** A tick that is not recorded (a bank, a cooldown) with only the counted stores read. */
	private static Map<Integer, Integer> unrecorded(ItemGainsTracker t, Map<Integer, Integer> inventory,
		Map<Integer, Integer> stores)
	{
		return t.tick(inventory, null, null, slots(stores), false);
	}

	@Test
	public void fillingAndEmptyingAnEssencePouchIsAMoveInEveryEventOrder()
	{
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1, PURE_ESSENCE, 24), items(), null, slots(items()), true);
		// fill and empty, the count on the same tick as the inventory
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 12), items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 24), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		// inventory first: the count arrives a tick later
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 12), items()).isEmpty());
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 24), items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		// count first: the inventory arrives a tick later
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 12), items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 24), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
	}

	@Test
	public void craftingOutsideAPouchsWindowCountsAndInsideItIsMissed()
	{
		// 27 pure essence; the giant pouch takes 12; the 15 left are crafted after a tick's walk; the pouch is emptied,
		// and after another tick those 12 are crafted
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1, PURE_ESSENCE, 27), items(), null, slots(items()), true);
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 15), items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, BLOOD_RUNE, 15), items(ESSENCE, 12)).isEmpty());
		assertEquals(items(PURE_ESSENCE, -15, BLOOD_RUNE, 15), tick(t, null, items(ESSENCE, 12)));
		assertTrue(tick(t, items(GIANT_POUCH, 1, BLOOD_RUNE, 15, PURE_ESSENCE, 12), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, BLOOD_RUNE, 27), items()).isEmpty());
		assertEquals(items(PURE_ESSENCE, -12, BLOOD_RUNE, 12), tick(t, null, items()));

		// crafting on the tick after a fill: the runes count, the essence used is missed (inside the window)
		t = stores();
		t.tick(items(GIANT_POUCH, 1, PURE_ESSENCE, 27), items(), null, slots(items()), true);
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 15), items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, BLOOD_RUNE, 15), items(ESSENCE, 12)).isEmpty());
		assertEquals(items(BLOOD_RUNE, 15), tick(t, null, items(ESSENCE, 12)));
	}

	@Test
	public void aDegradedPouchOrAMixedEmptyRecordsNothing()
	{
		// a degraded pouch takes 9 of the 12 that left the inventory, then gives back 12: nothing either way
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH_DEGRADED, 1, PURE_ESSENCE, 20), items(), null, pouches(0, 0), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH_DEGRADED, 1, PURE_ESSENCE, 8), pouches(9, 2)).isEmpty());
		assertTrue(tickSlots(t, items(GIANT_POUCH_DEGRADED, 1, PURE_ESSENCE, 20), pouches(0, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 2)).isEmpty());
		// the pouch degrading in the inventory (another item id) is no loss and no gain
		t = stores();
		t.tick(items(MEDIUM_POUCH, 1), items(), null, slots(items()), true);
		assertTrue(tick(t, items(MEDIUM_POUCH_DEGRADED, 1), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());

		// two pouches of different essence emptied on one tick
		t = stores();
		t.tick(items(GIANT_POUCH, 1, MEDIUM_POUCH, 1), items(), null, pouches(12, 1, 6, 4), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, MEDIUM_POUCH, 1, RUNE_ESSENCE, 12, GUARDIAN_ESSENCE, 6),
			pouches(0, 1, 0, 4)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 1, 0, 4)).isEmpty());
		// two fills on consecutive ticks, the inventory a tick behind each
		assertTrue(tickSlots(t, null, pouches(0, 1, 6, 4)).isEmpty());
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, MEDIUM_POUCH, 1, RUNE_ESSENCE, 12), pouches(12, 1, 6, 4))
			.isEmpty());
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, MEDIUM_POUCH, 1), pouches(12, 1, 6, 4)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(12, 1, 6, 4)).isEmpty());
	}

	@Test
	public void essenceGainsNextToAFillAreMissedAndLaterOnesCount()
	{
		// Guardians of the Rift: the workbench makes one essence on the tick of a fill and on the tick after (both
		// missed), and one more a tick later (counts)
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 12), items(), null, slots(items()), true);
		assertTrue(tick(t, items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 1), items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 2), items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 3), items(ESSENCE, 12)).isEmpty());
		assertEquals(items(GUARDIAN_ESSENCE, 1), tick(t, null, items(ESSENCE, 12)));
		// mining after the pouch was filled, with nothing moving
		assertTrue(tick(t, items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 6), items(ESSENCE, 12)).isEmpty());
		assertEquals(items(GUARDIAN_ESSENCE, 3), tick(t, null, items(ESSENCE, 12)));
		// essence removed from the pouch without reaching the inventory (a minigame exit) is no loss; a pickup on the
		// next tick is missed, and one after that counts
		assertTrue(tick(t, null, items()).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 7), items()).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 8), items()).isEmpty());
		assertEquals(items(GUARDIAN_ESSENCE, 1), tick(t, null, items()));
	}

	@Test
	public void thePlankSackFillsEmptiesAndBuildsWithoutFalseChanges()
	{
		ItemGainsTracker t = stores();
		t.tick(items(PLANK_SACK, 1, PLANK, 10, OAK_PLANK, 14), items(), null, slots(items()), true);
		// fill both kinds at once
		assertTrue(tick(t, items(PLANK_SACK, 1), items(PLANK, 10, OAK_PLANK, 14)).isEmpty());
		assertTrue(tick(t, null, items(PLANK, 10, OAK_PLANK, 14)).isEmpty());
		// building with 8 oak planks straight from the sack: the inventory never had them, so never logged as used
		assertTrue(tick(t, null, items(PLANK, 10, OAK_PLANK, 6)).isEmpty());
		assertTrue(tick(t, null, items(PLANK, 10, OAK_PLANK, 6)).isEmpty());
		assertTrue(tick(t, null, items(PLANK, 10, OAK_PLANK, 6)).isEmpty());
		// emptying only as far as there is room: 5 of the 10 plain planks
		assertTrue(tick(t, items(PLANK_SACK, 1, PLANK, 5), items(PLANK, 5, OAK_PLANK, 6)).isEmpty());
		assertTrue(tick(t, null, items(PLANK, 5, OAK_PLANK, 6)).isEmpty());
		// oak planks made later count, and building with the inventory's planks is a loss
		assertTrue(tick(t, items(PLANK_SACK, 1, PLANK, 5, OAK_PLANK, 20), items(PLANK, 5, OAK_PLANK, 6)).isEmpty());
		assertEquals(items(OAK_PLANK, 20), tick(t, items(PLANK_SACK, 1, OAK_PLANK, 20), items(PLANK, 5,
			OAK_PLANK, 6)));
		assertEquals(items(PLANK, -5), tick(t, null, items(PLANK, 5, OAK_PLANK, 6)));
	}

	@Test
	public void storeCountsAtABankOrLoginNeverInventAChange()
	{
		// emptying the pouch straight into an open bank: nothing is recorded, a pickup on the first recorded tick
		// after is missed, and one after that counts
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1), items(), null, slots(items(ESSENCE, 12)), true);
		assertTrue(unrecorded(t, null, items()).isEmpty());
		assertTrue(unrecorded(t, null, items()).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 3), items()).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 5), items()).isEmpty());
		assertEquals(items(PURE_ESSENCE, 2), tick(t, null, items()));

		// a full pouch at login, then emptied: the first count is the baseline
		t = stores();
		t.tick(items(GIANT_POUCH, 1), items(), null, slots(items(ESSENCE, 12)), true);
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 12), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		// the count read only a tick after the inventory, or arriving during the quiet ticks after login
		t = stores();
		t.tick(items(GIANT_POUCH, 1), items(), null, null, true);
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 12), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		t = stores();
		t.tick(items(GIANT_POUCH, 1), items(), null, slots(items()), false);
		unrecorded(t, null, items(ESSENCE, 12));
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 12), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		// a count that turns up late after the quiet ticks, with nothing moving, records nothing
		t = stores();
		t.tick(items(GIANT_POUCH, 1), items(), null, slots(items()), true);
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());
	}

	@Test
	public void storeChangesStillOpenAtABoundaryAreReportedNotCredited()
	{
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1, PURE_ESSENCE, 12), items(), null, slots(items()), true);
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty()); // count first: the inventory change is still to come
		Map<String, Map<Integer, Integer>> open = t.unresolved();
		assertEquals(items(ESSENCE, 12), open.get("debits"));
		assertTrue(tick(t, items(GIANT_POUCH, 1), items(ESSENCE, 12)).isEmpty()); // a new baseline
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());

		t = stores();
		t.tick(items(GIANT_POUCH, 1), items(), null, slots(items(ESSENCE, 12)), true);
		assertTrue(tick(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 12), null).isEmpty()); // inventory first, held
		assertEquals(items(PURE_ESSENCE, 12), t.unresolved().get("held"));
	}

	// ---------------------------------------------- #69 group 1, review round 1: one regression test per finding

	@Test
	public void aPickupNextToAnEmptyIsMissedNeverInflated()
	{
		// finding 1: a pickup of 1 oak plank, then an empty of 10 the next tick: nothing, never +10
		ItemGainsTracker t = stores();
		t.tick(items(PLANK_SACK, 1), items(), null, slots(items(OAK_PLANK, 10)), true);
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 1), items(OAK_PLANK, 10)).isEmpty());
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 11), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		// a pickup two ticks before the empty counts
		t = stores();
		t.tick(items(PLANK_SACK, 1), items(), null, slots(items(OAK_PLANK, 10)), true);
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 1), items(OAK_PLANK, 10)).isEmpty());
		assertEquals(items(OAK_PLANK, 1), tick(t, null, items(OAK_PLANK, 10)));
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 11), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
	}

	@Test
	public void storeItemsStillHeldAtLogoutAreSetAsideNotFlushed()
	{
		// finding 2: the inventory lost 12 essence for a fill; logout comes before the count update
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1, PURE_ESSENCE, 12, ORE, 1), items(), null, pouches(0, 0), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, ORE, 2), pouches(0, 0)).isEmpty());
		Map<String, Map<Integer, Integer>> open = t.storeUnresolved();
		assertEquals(items(PURE_ESSENCE, -12), open.get("held"));
		assertEquals(items(ORE, 1), t.flush()); // the ore was no store item: flushed as before
		// a held essence change with another store's window still open (oak planks into the sack): both set aside
		t = stores();
		t.tick(items(GIANT_POUCH, 1, PLANK_SACK, 1, PURE_ESSENCE, 12, OAK_PLANK, 5), items(), null,
			slots(items(ESSENCE, 0, OAK_PLANK, 0)), true);
		assertTrue(tick(t, items(GIANT_POUCH, 1, PLANK_SACK, 1), items(ESSENCE, 0, OAK_PLANK, 5)).isEmpty());
		open = t.storeUnresolved();
		assertEquals(items(PURE_ESSENCE, -12), open.get("held"));
		assertEquals(items(OAK_PLANK, 5), open.get("debits"));
		assertTrue(t.flush().isEmpty());
	}

	@Test
	public void aBareOpenWindowAtLogoutSetsNothingAside()
	{
		// review round 4: pouches filled at a bank, then logging out with the window still open and nothing held
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1, PURE_ESSENCE, 12), items(), null, slots(items()), true);
		assertTrue(tick(t, items(GIANT_POUCH, 1), items(ESSENCE, 12)).isEmpty());
		assertTrue(t.storeUnresolved().isEmpty());
		assertTrue(t.flush().isEmpty());
		// count first, the inventory never changed
		t = stores();
		t.tick(items(GIANT_POUCH, 1, PURE_ESSENCE, 12), items(), null, slots(items()), true);
		assertTrue(tick(t, null, items(ESSENCE, 12)).isEmpty());
		assertTrue(t.storeUnresolved().isEmpty());
		assertTrue(t.flush().isEmpty());
	}

	@Test
	public void anotherPlankTypeInsideOnePlankTypesWindowStillCounts()
	{
		// review round 4: oak planks emptied from the sack while 3 teak planks are picked up: the teak counts
		ItemGainsTracker t = stores();
		t.tick(items(PLANK_SACK, 1), items(), null, slots(items(OAK_PLANK, 10)), true);
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 10, TEAK_PLANK, 3), items()).isEmpty());
		assertEquals(items(TEAK_PLANK, 3), tick(t, null, items()));
		assertTrue(tick(t, null, items()).isEmpty());
	}

	@Test
	public void emptyingOnePouchWhileFillingAnotherIsBothMoves()
	{
		// finding 3: 6 rune essence out of one pouch and 6 pure essence into another on one tick; the total is unchanged
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1, MEDIUM_POUCH, 1, PURE_ESSENCE, 6), items(), null, pouches(6, 1, 0, 0), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, MEDIUM_POUCH, 1, RUNE_ESSENCE, 6), pouches(0, 1, 6, 2))
			.isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 1, 6, 2)).isEmpty());
		// one pouch emptied of rune essence and refilled with pure on one tick: its kind changed, its count did not
		t = stores();
		t.tick(items(GIANT_POUCH, 1, PURE_ESSENCE, 6), items(), null, pouches(6, 1), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, RUNE_ESSENCE, 6), pouches(6, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(6, 2)).isEmpty());
	}

	@Test
	public void aMismatchedFillDoesNotTakeTheNextFillsCount()
	{
		// finding 4: a degraded pouch counts +9 for 12 essence, the inventory a tick late; the next tick another fill
		// counts +12 while the first inventory update arrives, and its own update comes a tick after
		ItemGainsTracker t = stores();
		t.tick(items(PURE_ESSENCE, 24), items(), null, pouches(0, 0, 0, 0), true);
		assertTrue(tickSlots(t, null, pouches(9, 2, 0, 0)).isEmpty());
		assertTrue(tickSlots(t, items(PURE_ESSENCE, 12), pouches(9, 2, 12, 2)).isEmpty());
		assertTrue(tickSlots(t, items(), pouches(9, 2, 12, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(9, 2, 12, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(9, 2, 12, 2)).isEmpty());
	}

	@Test
	public void aBankEmptyNeverInventsAChange()
	{
		// finding 5: pick up 1 oak plank, then empty the sack straight into an open bank the next tick: the pickup is
		// in the window (missed), and so is one on the first recorded tick after; the one after that counts
		ItemGainsTracker t = stores();
		t.tick(items(PLANK_SACK, 1), items(), null, slots(items(OAK_PLANK, 10)), true);
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 1), items(OAK_PLANK, 10)).isEmpty());
		assertTrue(unrecorded(t, null, items()).isEmpty());
		assertTrue(unrecorded(t, null, items()).isEmpty());
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 2), items()).isEmpty());
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 3), items()).isEmpty());
		assertEquals(items(OAK_PLANK, 1), tick(t, null, items()));
		// a fill whose count arrives on the tick the bank opens is still a move
		t = stores();
		t.tick(items(PLANK_SACK, 1, OAK_PLANK, 10), items(), null, slots(items()), true);
		assertTrue(tick(t, items(PLANK_SACK, 1), items()).isEmpty());
		assertTrue(unrecorded(t, null, items(OAK_PLANK, 10)).isEmpty());
		assertTrue(unrecorded(t, null, items(OAK_PLANK, 10)).isEmpty());
	}

	@Test
	public void buildingStraightFromTheSackIsNeverAChange()
	{
		// finding 6: 10 oak planks built straight from the sack are never logged as used; 1 oak plank into the
		// inventory on the next tick is in the window (missed), and one two ticks later counts
		ItemGainsTracker t = stores();
		t.tick(items(PLANK_SACK, 1), items(), null, slots(items(OAK_PLANK, 10)), true);
		assertTrue(tick(t, null, items()).isEmpty());
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 1), items()).isEmpty());
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 2), items()).isEmpty());
		assertEquals(items(OAK_PLANK, 1), tick(t, null, items()));
	}

	// ---------------------------------------------- #69 group 1, review round 2: one regression test per finding

	@Test
	public void aHeldPickupDoesNotUseUpAnEmptyWhoseEssenceArrivesLater()
	{
		// finding 1: 1 essence picked up; the next tick a degraded pouch's count drops from 9 to 0 with the inventory
		// unchanged; the tick after, its 12 essence arrive. Nothing counts (the +1 is missed)
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH_DEGRADED, 1), items(), null, pouches(9, 2), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH_DEGRADED, 1, PURE_ESSENCE, 1), pouches(9, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 2)).isEmpty());
		assertTrue(tickSlots(t, items(GIANT_POUCH_DEGRADED, 1, PURE_ESSENCE, 13), pouches(0, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 2)).isEmpty());
		// the same with an exact amount: a pickup of 12, then an empty of 12 whose essence arrives a tick later
		t = stores();
		t.tick(items(GIANT_POUCH, 1), items(), null, pouches(12, 2), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 12), pouches(12, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 2)).isEmpty());
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 24), pouches(0, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 2)).isEmpty());
	}

	@Test
	public void essenceMadeWhileFillingIsNoLossInEitherEventOrder()
	{
		// finding 2: 12 guardian essence; 1 made while 12 are filled, leaving 1; the count and the inventory a tick apart
		for (boolean countFirst : new boolean[]{false, true})
		{
			ItemGainsTracker t = stores();
			t.tick(items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 12), items(), null, pouches(0, 0), true);
			Map<Integer, Integer> inventory = items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 1);
			assertTrue(tickSlots(t, countFirst ? null : inventory, pouches(countFirst ? 12 : 0, 3)).isEmpty());
			assertTrue(tickSlots(t, countFirst ? inventory : null, pouches(12, 3)).isEmpty());
			assertTrue(tickSlots(t, null, pouches(12, 3)).isEmpty());
			assertTrue(tickSlots(t, null, pouches(12, 3)).isEmpty());
		}
		// and the empty: 1 made while 12 are emptied, 13 arriving, a tick apart from the count either way
		for (boolean countFirst : new boolean[]{false, true})
		{
			ItemGainsTracker t = stores();
			t.tick(items(GIANT_POUCH, 1), items(), null, pouches(12, 3), true);
			Map<Integer, Integer> inventory = items(GIANT_POUCH, 1, GUARDIAN_ESSENCE, 13);
			assertTrue(tickSlots(t, countFirst ? null : inventory, pouches(countFirst ? 0 : 12, 3)).isEmpty());
			assertTrue(tickSlots(t, countFirst ? inventory : null, pouches(0, 3)).isEmpty());
			assertTrue(tickSlots(t, null, pouches(0, 3)).isEmpty());
			assertTrue(tickSlots(t, null, pouches(0, 3)).isEmpty());
		}
	}

	// ---------------------------------------------- #69 group 1, review round 3: one regression test per finding

	@Test
	public void oppositeMovesOfOneEssenceKindOnOneTickRecordNothing()
	{
		// finding 1: 10 pure essence emptied from one pouch while 7 are filled into another: the inventory gains 3
		for (boolean countFirst : new boolean[]{false, true})
		{
			ItemGainsTracker t = stores();
			t.tick(items(GIANT_POUCH, 1, MEDIUM_POUCH, 1, PURE_ESSENCE, 7), items(), null, pouches(10, 2, 0, 0),
				true);
			Map<Integer, Integer> inventory = items(GIANT_POUCH, 1, MEDIUM_POUCH, 1, PURE_ESSENCE, 10);
			assertTrue(tickSlots(t, countFirst ? null : inventory, pouches(0, 2, 7, 2)).isEmpty());
			assertTrue(tickSlots(t, countFirst ? inventory : null, pouches(0, 2, 7, 2)).isEmpty());
			assertTrue(tickSlots(t, null, pouches(0, 2, 7, 2)).isEmpty());
		}
	}

	@Test
	public void twoMovesATickApartWithOneInventoryUpdateRecordNothing()
	{
		// finding 2: two fills of 10 a tick apart, both inventory changes landing on the second tick
		ItemGainsTracker t = stores();
		t.tick(items(PURE_ESSENCE, 20), items(), null, pouches(0, 0, 0, 0), true);
		assertTrue(tickSlots(t, null, pouches(10, 2, 0, 0)).isEmpty());
		assertTrue(tickSlots(t, items(), pouches(10, 2, 10, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(10, 2, 10, 2)).isEmpty());
		// and two empties of 10, both arriving on the second tick
		assertTrue(tickSlots(t, null, pouches(0, 2, 10, 2)).isEmpty());
		assertTrue(tickSlots(t, items(PURE_ESSENCE, 20), pouches(0, 2, 0, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 2, 0, 2)).isEmpty());
	}

	@Test
	public void aMoveOnTheLastCooldownTickCoversTheFirstRecordedTick()
	{
		// finding 3: withdraw, close the bank, fill: the count changes on the second cooldown tick and the inventory
		// follows on the first recorded tick; then the empty the same way
		ItemGainsTracker t = stores();
		t.tick(items(PLANK_SACK, 1, OAK_PLANK, 10), items(), null, slots(items()), true);
		assertTrue(unrecorded(t, null, items()).isEmpty());
		assertTrue(unrecorded(t, null, items(OAK_PLANK, 10)).isEmpty());
		assertTrue(tick(t, items(PLANK_SACK, 1), items(OAK_PLANK, 10)).isEmpty());
		assertTrue(tick(t, null, items(OAK_PLANK, 10)).isEmpty());
		assertTrue(unrecorded(t, null, items(OAK_PLANK, 10)).isEmpty());
		assertTrue(unrecorded(t, null, items()).isEmpty());
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 10), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
	}

	@Test
	public void aLossBiggerThanAFillRecordsNothingAndLaterUseCountsOnce()
	{
		// finding 4: 10 essence filled while 5 more leave the inventory
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1, PURE_ESSENCE, 20), items(), null, pouches(0, 0), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, PURE_ESSENCE, 5), pouches(10, 2)).isEmpty());
		assertTrue(tickSlots(t, null, pouches(10, 2)).isEmpty());
		// the plank sack: 10 planks filled while 4 are used, then (outside the window) emptied and all 10 used
		t = stores();
		t.tick(items(PLANK_SACK, 1, OAK_PLANK, 14), items(), null, slots(items()), true);
		assertTrue(tick(t, items(PLANK_SACK, 1), items(OAK_PLANK, 10)).isEmpty());
		assertTrue(tick(t, null, items(OAK_PLANK, 10)).isEmpty());
		assertTrue(tick(t, null, items(OAK_PLANK, 10)).isEmpty());
		assertTrue(tick(t, items(PLANK_SACK, 1, OAK_PLANK, 10), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		assertTrue(tick(t, items(PLANK_SACK, 1), items()).isEmpty());
		assertEquals(items(OAK_PLANK, -10), tick(t, null, items()));
		assertTrue(tick(t, null, items()).isEmpty());
	}

	@Test
	public void aDegradedEmptyTheTickBeforeTheBankOpensRecordsNothing()
	{
		// finding 5: a degraded pouch counting 9 gives 12 essence; the count drops on the tick the bank opens
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH_DEGRADED, 1), items(), null, pouches(9, 2), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH_DEGRADED, 1, PURE_ESSENCE, 12), pouches(9, 2)).isEmpty());
		assertTrue(t.tick(null, null, null, pouches(0, 2), false).isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 2)).isEmpty());
	}

	@Test
	public void anEmptyWhoseEssenceArrivesOverTwoTicksRecordsNothing()
	{
		// finding 6: one tick's empty of 20, the inventory gaining 10 on that tick and 10 on the next
		ItemGainsTracker t = stores();
		t.tick(items(GIANT_POUCH, 1, MEDIUM_POUCH, 1), items(), null, pouches(12, 2, 8, 2), true);
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, MEDIUM_POUCH, 1, PURE_ESSENCE, 10), pouches(0, 2, 0, 2))
			.isEmpty());
		assertTrue(tickSlots(t, items(GIANT_POUCH, 1, MEDIUM_POUCH, 1, PURE_ESSENCE, 20), pouches(0, 2, 0, 2))
			.isEmpty());
		assertTrue(tickSlots(t, null, pouches(0, 2, 0, 2)).isEmpty());
	}

	@Test
	public void carryingSpareCopiesDoesNotMakeTheWornItemStackable()
	{
		// Wearing one ring of recoil while carrying two: unequipping it is not a gain, in either event order
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(RING_OF_RECOIL, 2), items(RING_OF_RECOIL, 1), true);
		assertTrue(t.tick(items(RING_OF_RECOIL, 3), items(), true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());
		assertTrue(t.tick(items(RING_OF_RECOIL, 2), items(RING_OF_RECOIL, 1), true).isEmpty()); // put back on
		assertTrue(t.tick(null, items(), true).isEmpty()); // worn-first unequip
		assertTrue(t.tick(items(RING_OF_RECOIL, 3), null, true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());

		// Wielding a sword with noted swords in the inventory: unequipping it is not a gain
		t = new ItemGainsTracker(id -> ItemVariationMapping.map(id == NOTED_SWORD ? SWORD : id));
		t.tick(items(NOTED_SWORD, 5), items(SWORD, 1), true);
		assertTrue(t.tick(items(NOTED_SWORD, 5, SWORD, 1), items(), true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());
	}

	@Test
	public void emptyingASingleRuneIsNotAGain()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(RUNE_POUCH, 1), items(), items(NATURE, 1, FIRE, 1), true);
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 1, FIRE, 1), null, items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());
		// pouch-first order
		assertTrue(t.tick(items(RUNE_POUCH, 1, FIRE, 1), null, items(NATURE, 1), true).isEmpty()); // fill one back
		assertTrue(t.tick(null, null, items(), true).isEmpty());
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 1, FIRE, 1), null, null, true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());
		// and the last arrow taken off while not attacking
		t.reset();
		t.tick(items(), items(BOW, 1, ARROW, 1), true);
		assertTrue(t.tick(items(ARROW, 1), items(BOW, 1), true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());
	}

	@Test
	public void usingUpTheLastAmmoOrRunesNeverHidesAPickup()
	{
		// A dark bow fires the last 2 arrows; 50 arrows are picked up a tick later
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(ARROW, 10), items(DARK_BOW, 1, ARROW, 2), true);
		assertTrue(t.tick(null, items(DARK_BOW, 1), true).isEmpty());
		assertTrue(t.tick(items(ARROW, 60), null, true).isEmpty());
		assertEquals(items(ARROW, 50), t.tick(null, null, true));

		// Casting the last 2 nature runes from the pouch while picking up 5 (same tick, and pouch a tick late)
		t.reset();
		t.tick(items(RUNE_POUCH, 1, NATURE, 3), items(), items(NATURE, 2), true);
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 8), null, items(), true).isEmpty());
		assertEquals(items(NATURE, 5), t.tick(null, null, null, true));
		t.reset();
		t.tick(items(RUNE_POUCH, 1, NATURE, 3), items(), items(NATURE, 2), true);
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 8), null, null, true).isEmpty());
		assertEquals(items(NATURE, 5), t.tick(null, null, items(), true)); // runes leave the pouch the next tick

		// Accepted coincidence: casting the last 2 nature runes while picking up exactly 2 looks like emptying the pouch,
		// so the pickup is not counted (same tick, and pouch a tick late)
		t.reset();
		t.tick(items(RUNE_POUCH, 1, NATURE, 3), items(), items(NATURE, 2), true);
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 5), null, items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());
		t.reset();
		t.tick(items(RUNE_POUCH, 1, NATURE, 3), items(), items(NATURE, 2), true);
		assertTrue(t.tick(items(RUNE_POUCH, 1, NATURE, 5), null, null, true).isEmpty());
		assertTrue(t.tick(null, null, items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());

		// Swapping a whole ammo stack mid-fight still cancels
		t.reset();
		t.tick(items(RUNE_ARROW, 50), items(DARK_BOW, 1, ARROW, 40), true);
		assertTrue(t.tick(items(ARROW, 40), items(DARK_BOW, 1, RUNE_ARROW, 50), true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());
	}

	@Test
	public void emptyingStorageOrTakingOffAmmoWhileSkillingIsNotAGain()
	{
		// AFK fishing (animating and interacting with the fishing spot NPC), then emptying a rune pouch of 2,000 death
		// runes: same tick, pouch-first and inventory-first
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(RUNE_POUCH, 1), items(), items(DEATH, 2000, NATURE, 300), true);
		assertTrue(t.tick(items(RUNE_POUCH, 1, DEATH, 2000, NATURE, 300), null, items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());
		t.reset();
		t.tick(items(RUNE_POUCH, 1), items(), items(DEATH, 2000), true);
		assertTrue(t.tick(null, null, items(), true).isEmpty());
		assertTrue(t.tick(items(RUNE_POUCH, 1, DEATH, 2000), null, null, true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());
		t.reset();
		t.tick(items(RUNE_POUCH, 1), items(), items(DEATH, 2000), true);
		assertTrue(t.tick(items(RUNE_POUCH, 1, DEATH, 2000), null, null, true).isEmpty());
		assertTrue(t.tick(null, null, items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());

		// Talking to an NPC while emptying Dizana's quiver of 300 arrows
		t.reset();
		t.tick(items(), items(), items(ARROW, 300), true);
		assertTrue(t.tick(items(ARROW, 300), null, items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());

		// Taking off the last 1 or 2 worn arrows while skilling, in either event order
		t.reset();
		t.tick(items(), items(BOW, 1, ARROW, 1), true);
		assertTrue(t.tick(items(ARROW, 1), items(BOW, 1), true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());
		t.reset();
		t.tick(items(ARROW, 5), items(BOW, 1, ARROW, 2), true);
		assertTrue(t.tick(null, items(BOW, 1), true).isEmpty());
		assertTrue(t.tick(items(ARROW, 7), null, true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());
		t.reset();
		t.tick(items(ARROW, 5), items(BOW, 1, ARROW, 2), true);
		assertTrue(t.tick(items(ARROW, 7), null, true).isEmpty());
		assertTrue(t.tick(null, items(BOW, 1), true).isEmpty());
		assertTrue(t.tick(null, null, true).isEmpty());

		// A fish caught on the tick the pouch is emptied still counts
		t.reset();
		t.tick(items(RUNE_POUCH, 1), items(), items(DEATH, 2000), true);
		assertTrue(t.tick(items(RUNE_POUCH, 1, DEATH, 2000, HERB, 1), null, items(), true).isEmpty());
		assertEquals(items(HERB, 1), t.tick(null, null, null, true));
	}

	@Test
	public void wornAndQuiverCreditsForTheSameArrowAreMatchedSeparately()
	{
		// The last worn arrow is fired on the tick Dizana's quiver is emptied of 300 of the same arrows: +300 is the quiver
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(BOW, 1), items(ARROW, 1), items(ARROW, 300), true);
		assertTrue(t.tick(items(BOW, 1, ARROW, 300), items(), items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());
		// worn and quiver first, inventory a tick later
		t.reset();
		t.tick(items(BOW, 1), items(ARROW, 1), items(ARROW, 300), true);
		assertTrue(t.tick(null, items(), items(), true).isEmpty());
		assertTrue(t.tick(items(BOW, 1, ARROW, 300), null, null, true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());
		// inventory first, worn and quiver a tick later
		t.reset();
		t.tick(items(BOW, 1), items(ARROW, 1), items(ARROW, 300), true);
		assertTrue(t.tick(items(BOW, 1, ARROW, 300), null, null, true).isEmpty());
		assertTrue(t.tick(null, items(), items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());
		// last worn arrow fired a tick before the quiver is emptied
		t.reset();
		t.tick(items(BOW, 1), items(ARROW, 1), items(ARROW, 300), true);
		assertTrue(t.tick(null, items(), null, true).isEmpty());
		assertTrue(t.tick(items(BOW, 1, ARROW, 300), null, items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());

		// Only the worn credit matches: 50 arrows taken off while the quiver's last arrow is fired
		t.reset();
		t.tick(items(BOW, 1), items(ARROW, 50), items(ARROW, 1), true);
		assertTrue(t.tick(items(BOW, 1, ARROW, 50), items(), items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());

		// Both really moved on one tick (50 arrows taken off, the quiver's 300 emptied): +350 still cancels
		t.reset();
		t.tick(items(BOW, 1), items(ARROW, 50), items(ARROW, 300), true);
		assertTrue(t.tick(items(BOW, 1, ARROW, 350), items(), items(), true).isEmpty());
		assertTrue(t.tick(null, null, null, true).isEmpty());

		// A pickup of a different amount alongside is still logged: neither credit nor both together match
		t.reset();
		t.tick(items(BOW, 1), items(ARROW, 1), items(ARROW, 300), true);
		assertTrue(t.tick(items(BOW, 1, ARROW, 305), items(), items(), true).isEmpty());
		assertEquals(items(ARROW, 305), t.tick(null, null, null, true));
		// and a pickup of 5 on the tick after the quiver's 300 cancelled is logged
		t.reset();
		t.tick(items(BOW, 1), items(ARROW, 1), items(ARROW, 300), true);
		assertTrue(t.tick(items(BOW, 1, ARROW, 300), items(), items(), true).isEmpty());
		assertTrue(t.tick(items(BOW, 1, ARROW, 305), null, null, true).isEmpty());
		assertEquals(items(ARROW, 5), t.tick(null, null, null, true));
		// a different pickup on the worn-only tick is logged too: the 50 taken off cancel, the 7 picked up count
		t.reset();
		t.tick(items(BOW, 1), items(ARROW, 50), items(ARROW, 1), true);
		assertTrue(t.tick(items(BOW, 1, ARROW, 50), items(), items(), true).isEmpty());
		assertTrue(t.tick(items(BOW, 1, ARROW, 57), null, null, true).isEmpty());
		assertEquals(items(ARROW, 7), t.tick(null, null, null, true));
	}

	@Test
	public void resetStartsANewBaseline()
	{
		ItemGainsTracker t = new ItemGainsTracker();
		t.tick(items(), items(), true);
		t.reset();
		assertTrue(t.tick(items(HERB, 10), items(), true).isEmpty());
	}

	@Test
	public void fileShape()
	{
		ItemGainsLog log = new ItemGainsLog();
		log.add("2026-10-27T10:00:00Z", Arrays.asList(new Gain("2026-10-27T10:05:00Z", HERB, 3),
			new Gain("2026-10-27T10:05:00Z", ORE, -1)));
		JsonObject json = new JsonParser().parse(new Gson().toJson(log.compose("Synthetic Friend",
			"2026-10-27T10:06:00Z"))).getAsJsonObject();
		assertEquals("{\"format_version\":2,\"account_name\":\"Synthetic Friend\",\"observed_at\":\"2026-10-27T10:06:00Z\","
			+ "\"sessions\":[{\"session_start\":\"2026-10-27T10:00:00Z\",\"with_losses\":true,\"capture_revs\":[1],\"dropped\":0,"
			+ "\"gains\":[{\"time\":\"2026-10-27T10:05:00Z\",\"id\":199,\"qty\":3},"
			+ "{\"time\":\"2026-10-27T10:05:00Z\",\"id\":440,\"qty\":-1}]}]}", json.toString());
	}

	@Test
	public void sessionsFromAFormat1FileAreMarkedGainsOnly()
	{
		ItemGainsLog log = new ItemGainsLog();
		log.load(new Gson().fromJson("{\"format_version\":1,\"sessions\":[{\"session_start\":\"old\",\"dropped\":0,"
			+ "\"gains\":[{\"time\":\"t\",\"id\":199,\"qty\":1}]}]}", ItemGainsLog.GainsFile.class));
		log.add("new", Collections.singletonList(new Gain("t2", HERB, -1)));
		ItemGainsLog.GainsFile f = log.compose("a", "t2");
		assertEquals(2, f.formatVersion);
		assertFalse(f.sessions.get(0).withLosses);
		assertTrue(f.sessions.get(1).withLosses);
		// #103: a file from before capture_revs still loads; its session has none, the new one has today's
		assertNull(f.sessions.get(0).captureRevs);
		assertEquals(Collections.singletonList(ItemGainsLog.CAPTURE_REV), f.sessions.get(1).captureRevs);
	}

	/** A file of one session as read from disk, with the given capture_revs JSON (none when null). */
	private static GainsFile oneSession(String start, String revs)
	{
		return new Gson().fromJson("{\"format_version\":2,\"sessions\":[{\"session_start\":\"" + start + "\",\"with_losses\":true,"
			+ (revs == null ? "" : "\"capture_revs\":" + revs + ",") + "\"dropped\":0,"
			+ "\"gains\":[{\"time\":\"t\",\"id\":11850,\"qty\":-1}]}]}", GainsFile.class);
	}

	@Test
	public void continuingASessionWithNoRevisionNeverVouchesForIt()
	{
		// #103: the 27 Oct session was recorded before the equip fix and before capture_revs; adding to it later (same
		// session_start, newer code) must not make its old losses look trustworthy
		ItemGainsLog log = new ItemGainsLog();
		log.load("acct", oneSession("s", null));
		log.add("s", Collections.singletonList(new Gain("t2", HERB, 1)));
		GainsFile f = log.compose("a", "t2");
		assertEquals(1, f.sessions.size());
		assertNull(f.sessions.get(0).captureRevs);
		// written with nulls (as JsonWriter does): the field reads back as unknown, not as a revision
		GainsFile back = new Gson().fromJson(new GsonBuilder().serializeNulls().create().toJson(f), GainsFile.class);
		assertNull(back.sessions.get(0).captureRevs);
	}

	@Test
	public void aSessionListsEveryRevisionThatWroteToIt()
	{
		// #103 review round 1: a session spanning revisions keeps them all, so a later bad one is never hidden behind a
		// good one (the lowest alone would read as good)
		ItemGainsLog log = new ItemGainsLog();
		log.load("acct", oneSession("span", "[" + (ItemGainsLog.CAPTURE_REV + 1) + "]"));
		log.add("span", Collections.singletonList(new Gain("t2", HERB, 1)));
		log.add("span", Collections.singletonList(new Gain("t3", HERB, 1)));
		assertEquals(Arrays.asList(ItemGainsLog.CAPTURE_REV, ItemGainsLog.CAPTURE_REV + 1),
			log.compose("a", "t3").sessions.get(0).captureRevs);

		// same account reloaded: a disk copy of a held session adds its revisions, and one with none makes it unknown
		log.add("held", Collections.singletonList(new Gain("t4", HERB, 1)));
		log.load("acct", oneSession("span", "[7]"));
		assertEquals(Arrays.asList(ItemGainsLog.CAPTURE_REV, ItemGainsLog.CAPTURE_REV + 1, 7),
			log.compose("a", "t4").sessions.get(0).captureRevs);
		log.load("acct", oneSession("held", null));
		GainsFile f = log.compose("a", "t4");
		assertEquals("held", f.sessions.get(1).sessionStart);
		assertNull(f.sessions.get(1).captureRevs);
		// and unknown stays unknown when this code adds to it again
		log.add("held", Collections.singletonList(new Gain("t5", HERB, 1)));
		assertNull(log.compose("a", "t5").sessions.get(1).captureRevs);

		assertNull(ItemGainsLog.union(null, Collections.singletonList(3)));
		assertNull(ItemGainsLog.union(Collections.singletonList(3), null));
		assertNull(ItemGainsLog.union(Arrays.asList(3, null), Collections.singletonList(1)));
		assertEquals(Arrays.asList(1, 2, 3), ItemGainsLog.union(Arrays.asList(3, 1), Arrays.asList(2, 3)));
	}

	@Test
	public void anEmptyOrInvalidRevisionListIsUnknownForGood()
	{
		// #103 review round 2: a session read back with "capture_revs": [] and old losses, then appended to, became [1]
		// and so vouched for those losses; every invalid list is unknown (null) before anything is merged
		for (String bad : Arrays.asList("[]", "[0]", "[-2]", "[1,null]"))
		{
			// the append path
			ItemGainsLog log = new ItemGainsLog();
			log.load("acct", oneSession("s", bad));
			assertNull(bad, log.compose("a", "t").sessions.get(0).captureRevs); // written back as unknown
			log.add("s", Collections.singletonList(new Gain("t2", HERB, 1)));
			assertNull(bad, log.compose("a", "t2").sessions.get(0).captureRevs);

			// the disk-merge path: a held session with a known list, then a disk copy of it with an invalid one
			log = new ItemGainsLog();
			log.load("acct", null);
			log.add("held", Collections.singletonList(new Gain("t", HERB, 1)));
			log.load("acct", oneSession("held", bad));
			assertNull(bad, log.compose("a", "t").sessions.get(0).captureRevs);
			log.add("held", Collections.singletonList(new Gain("t2", HERB, 1)));
			assertNull(bad, log.compose("a", "t2").sessions.get(0).captureRevs);

			// a disk-only session prepended on a same-account reload is normalised the same way
			log.load("acct", oneSession("prepended", bad));
			GainsFile f = log.compose("a", "t3");
			assertEquals("prepended", f.sessions.get(0).sessionStart);
			assertNull(bad, f.sessions.get(0).captureRevs);
			// and unknown is written as no list at all, the one form of unknown in the file
			JsonObject json = new JsonParser().parse(new GsonBuilder().serializeNulls().create().toJson(f)).getAsJsonObject();
			assertTrue(json.getAsJsonArray("sessions").get(0).getAsJsonObject().get("capture_revs").isJsonNull());
		}
		assertNull(ItemGainsLog.union(Collections.emptyList(), Collections.singletonList(1)));
		assertNull(ItemGainsLog.union(Collections.singletonList(1), Collections.emptyList()));
		assertNull(ItemGainsLog.union(Collections.singletonList(0), Collections.singletonList(1)));
	}

	@Test
	public void archivesCarryEachSessionsRevisions()
	{
		ItemGainsLog log = new ItemGainsLog();
		log.load("acct", oneSession("old", null));
		for (int s = 0; s < ItemGainsLog.MAX_SESSIONS; s++)
		{
			log.add("session-" + s, Collections.singletonList(new Gain("t", ORE, 1)));
		}
		ItemGainsLog.ArchiveFile arch = log.planArchive("a", "t", "x.json");
		assertNotNull(arch);
		assertEquals("old", arch.file.sessions.get(0).sessionStart);
		assertNull(arch.file.sessions.get(0).captureRevs);
		assertTrue(ItemGainsLog.archiveMatches(arch.file, oneSession("old", null)));
		// a read-back copy that lost or changed the revisions is not the archive that was planned
		assertFalse(ItemGainsLog.archiveMatches(arch.file, oneSession("old", "[1]")));
	}

	/** Plans the next archive and commits it as if it had been written and verified on disk (as the capture does). */
	private static void rotate(ItemGainsLog log, List<ItemGainsLog.ArchiveFile> into)
	{
		String stamp = "2026-10-27T10:00:00Z";
		ItemGainsLog.ArchiveFile arch = log.planArchive("a", stamp, ItemGainsLog.archiveFileName(stamp, into.size() + 1));
		if (arch != null)
		{
			log.commitArchive(arch);
			into.add(arch);
		}
	}

	@Test
	public void logRotationKeepsAllEntriesAcrossArchives()
	{
		ItemGainsLog log = new ItemGainsLog();
		List<ItemGainsLog.ArchiveFile> rotated = new ArrayList<>();
		for (int s = 0; s < ItemGainsLog.MAX_SESSIONS + 5; s++)
		{
			log.add("session-" + s, Collections.singletonList(new Gain("t-" + s, HERB, 1)));
			rotate(log, rotated);
		}
		log.add("session-" + (ItemGainsLog.MAX_SESSIONS + 4), Collections.singletonList(new Gain("t2", ORE, 1)));
		rotate(log, rotated);

		ItemGainsLog.GainsFile f = log.compose("a", "2026-10-27T10:00:00Z");
		assertEquals(ItemGainsLog.MAX_SESSIONS, f.sessions.size());
		assertEquals("session-5", f.sessions.get(0).sessionStart);
		assertEquals(2, f.sessions.get(f.sessions.size() - 1).gains.size());

		List<Gain> many = new ArrayList<>();
		for (int i = 0; i < ItemGainsLog.MAX_PER_SESSION + 7; i++)
		{
			many.add(new Gain("t", ORE, 1));
		}
		log.add("busy", many);
		rotate(log, rotated);
		f = log.compose("a", "2026-10-27T10:00:00Z");
		ItemGainsLog.SessionGains busy = f.sessions.get(f.sessions.size() - 1);
		assertEquals(7, busy.gains.size());
		assertEquals(0, busy.dropped);

		int total = 0;
		for (ItemGainsLog.SessionGains s : f.sessions)
		{
			total += s.gains.size();
		}
		assertTrue(total <= ItemGainsLog.MAX_GAINS);

		int archivedEntries = 0;
		for (ItemGainsLog.ArchiveFile arch : rotated)
		{
			assertTrue(arch.fileName.startsWith("item_gains_archive_2026-10-27T10-00-00Z_"));
			assertTrue(arch.fileName.endsWith(".json"));
			for (ItemGainsLog.SessionGains s : arch.file.sessions)
			{
				archivedEntries += s.gains.size();
			}
		}
		// Total entries added: (MAX_SESSIONS + 5) + 1 + (MAX_PER_SESSION + 7)
		assertEquals((ItemGainsLog.MAX_SESSIONS + 5) + 1 + (ItemGainsLog.MAX_PER_SESSION + 7), total + archivedEntries);
	}

	@Test
	public void captureRetainsUnarchivedEntriesWhenArchiveWriteFailsAndTrimsOnceArchivePersists() throws Exception
	{
		Path root = temporary.newFolder().toPath();
		ProfileKey alphaKey = new ProfileKey(0L, GameMode.MAIN, "Alpha");
		Path accountDir = root.resolve("accounts").resolve("0").resolve("main");
		Files.createDirectories(accountDir);

		// Seed item_gains.json with MAX_SESSIONS existing sessions so the next session triggers archive rotation
		GainsFile seed = new GainsFile();
		seed.formatVersion = ItemGainsLog.FORMAT_VERSION;
		seed.accountName = "Alpha";
		seed.observedAt = "2026-10-27T09:00:00Z";
		seed.sessions = new ArrayList<>();
		for (int i = 0; i < ItemGainsLog.MAX_SESSIONS; i++)
		{
			SessionGains sg = new SessionGains();
			sg.sessionStart = "2026-10-27T08:" + String.format("%02d", i) + ":00Z";
			sg.withLosses = true;
			sg.gains.add(new Gain(sg.sessionStart, HERB, 1));
			seed.sessions.add(sg);
		}

		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		try
		{
			JsonWriter json = new JsonWriter(executor, new Gson(), () -> Filepath.Unchecked.getRooted(root), () -> true, t -> { });
			json.write(alphaKey, ItemGainsCapture.FILE, seed);
			executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

			AtomicReference<Item[]> invItems = new AtomicReference<>(new Item[]{new Item(ORE, 1)});
			ItemContainer invContainer = proxy(ItemContainer.class, (p, m, a) ->
				m.getName().equals("getItems") ? invItems.get() : null);
			ItemContainer emptyContainer = proxy(ItemContainer.class, (p, m, a) ->
				m.getName().equals("getItems") ? new Item[0] : null);
			Player alpha = proxy(Player.class, (p, m, a) -> m.getName().equals("getName") ? "Alpha" : null);
			Client client = proxy(Client.class, (p, m, a) ->
			{
				if (m.getName().equals("getLocalPlayer"))
				{
					return alpha;
				}
				if (m.getName().equals("getItemContainer"))
				{
					int id = (Integer) a[0];
					return id == InventoryID.INV ? invContainer : id == InventoryID.WORN ? emptyContainer : null;
				}
				return null;
			});
			RsbuddyConfig config = proxy(RsbuddyConfig.class, (p, m, a) ->
				m.getName().equals("exportItems") || m.getName().equals("writeFiles"));
			CaptureContext ctx = new CaptureContext(client, config, json, null, new LoginState(client));
			CharacterCapture character = new CharacterCapture(ctx);
			SessionCapture sessions = new SessionCapture(ctx, character, (acc, st, xp) -> { });
			ItemGainsCapture capture = new ItemGainsCapture(ctx, sessions);

			// Settle login and advance past quiet ticks
			ctx.login.loggedIn();
			capture.onLogin();
			Instant t0 = Instant.parse("2026-10-27T10:00:00Z");
			for (int i = 0; i < LoginState.SETTLE_TICKS + 1; i++)
			{
				if (ctx.login.tick(alpha))
				{
					character.onSettled(alpha, t0);
					sessions.onSettled(alpha, t0);
					capture.onSettled(alpha, t0);
				}
				capture.onGameTick(alpha, t0.plusSeconds(i + 1));
			}

			// Baseline reading after quiet ticks
			capture.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, invContainer));
			capture.onGameTick(alpha, t0.plusSeconds(10));

			// Gain +2 ORE in the new session (session #51)
			invItems.set(new Item[]{new Item(ORE, 3)});
			capture.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, invContainer));
			capture.onGameTick(alpha, t0.plusSeconds(11));

			// Block the first archive write by pre-creating a non-empty directory at the target archive .tmp path
			String firstStamp = t0.plusSeconds(12).toString();
			String blockedArchiveName = ItemGainsLog.archiveFileName(firstStamp, 1);
			Path blockedTmp = accountDir.resolve(blockedArchiveName + ".tmp");
			Files.createDirectories(blockedTmp);
			Files.write(blockedTmp.resolve("blocker"), new byte[]{1});

			// Logout triggers write(): archive write fails because blockedTmp is a non-empty directory
			capture.onLogout(t0.plusSeconds(12)).get(5, TimeUnit.SECONDS);

			assertFalse(Files.exists(accountDir.resolve(blockedArchiveName)));
			GainsFile afterFailedArchive = json.read(alphaKey, ItemGainsCapture.FILE, GainsFile.class);
			assertNotNull(afterFailedArchive);
			// Because the archive write failed, item_gains.json MUST retain all 51 sessions (nothing dropped!)
			assertEquals(ItemGainsLog.MAX_SESSIONS + 1, afterFailedArchive.sessions.size());
			assertEquals("2026-10-27T08:00:00Z", afterFailedArchive.sessions.get(0).sessionStart);
			// #103: the seeded sessions had no capture_revs and keep none; the session recorded now has today's
			assertNull(afterFailedArchive.sessions.get(0).captureRevs);
			assertEquals(Collections.singletonList(ItemGainsLog.CAPTURE_REV),
				afterFailedArchive.sessions.get(ItemGainsLog.MAX_SESSIONS).captureRevs);

			// Now relogin and log out cleanly without any new gains; retryArchive persists the archive and trims main
			ctx.login.loggedOut();
			ctx.login.loggedIn();
			capture.onLogin();
			Instant t1 = Instant.parse("2026-10-27T10:05:00Z");
			for (int i = 0; i < LoginState.SETTLE_TICKS; i++)
			{
				if (ctx.login.tick(alpha))
				{
					character.onSettled(alpha, t1);
					sessions.onSettled(alpha, t1);
					capture.onSettled(alpha, t1);
				}
			}
			capture.onLogout(t1.plusSeconds(10)).get(5, TimeUnit.SECONDS);

			String secondArchiveName = ItemGainsLog.archiveFileName(t1.plusSeconds(10).toString(), 2);
			GainsFile persistedArchive = json.read(alphaKey, secondArchiveName, GainsFile.class);
			assertNotNull(persistedArchive);
			assertEquals(1, persistedArchive.sessions.size());
			assertEquals("2026-10-27T08:00:00Z", persistedArchive.sessions.get(0).sessionStart);
			assertNull(persistedArchive.sessions.get(0).captureRevs);

			GainsFile afterSuccessArchive = json.read(alphaKey, ItemGainsCapture.FILE, GainsFile.class);
			assertNotNull(afterSuccessArchive);
			assertEquals("0", afterSuccessArchive.accountHash); // which folder it belongs to, for the importer
			assertEquals("main", afterSuccessArchive.mode);
			assertEquals("main", persistedArchive.mode);
			assertEquals(ItemGainsLog.MAX_SESSIONS, afterSuccessArchive.sessions.size());
			assertEquals("2026-10-27T08:01:00Z", afterSuccessArchive.sessions.get(0).sessionStart);
			Gain flushed = afterSuccessArchive.sessions.get(afterSuccessArchive.sessions.size() - 1).gains.get(0);
			assertEquals(2, flushed.qty);
			// held at logout: stamped with the logout time, after the last tick (t0 + 11 s), never sharing its time
			assertEquals(t0.plusSeconds(12).toString(), flushed.time);

			// Clean up blocker so TemporaryFolder can delete everything cleanly
			Files.deleteIfExists(blockedTmp.resolve("blocker"));
			Files.deleteIfExists(blockedTmp);
		}
		finally
		{
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	// ------------------------------------------------------ #69 group 2: the seed box and tackle box, item by item

	private static final int SEED_BOX = 13639;
	private static final int RANARR_SEED = 5295;
	private static final int SNAPDRAGON_SEED = 5300;

	@Test
	public void aBoxDepositHeldBeforeAnUnrecordedTickIsNoLoss()
	{
		// the old path: the box's change made its own tick unrecorded, and the deposit held from the tick before was
		// emitted there as a loss. Now the box's move covers the held change, whatever the amount
		ItemGainsTracker t = stores();
		t.tick(items(SEED_BOX, 1, RANARR_SEED, 10), items(), null, slots(items()), true);
		assertTrue(tick(t, items(SEED_BOX, 1, RANARR_SEED, 2), items()).isEmpty());
		assertTrue(unrecorded(t, null, items(RANARR_SEED, 9)).isEmpty());
		assertTrue(tick(t, null, items(RANARR_SEED, 9)).isEmpty());
		assertTrue(tick(t, null, items(RANARR_SEED, 9)).isEmpty());
	}

	@Test
	public void aPartialBoxMoveCoversOnlyItsOwnKind()
	{
		// 6 of 10 ranarr taken out while 2 snapdragon seeds are picked up: the snapdragons count, the ranarr do not
		ItemGainsTracker t = stores();
		t.tick(items(SEED_BOX, 1), items(), null, slots(items(RANARR_SEED, 10)), true);
		assertTrue(tick(t, items(SEED_BOX, 1, RANARR_SEED, 6, SNAPDRAGON_SEED, 2), items(RANARR_SEED, 4)).isEmpty());
		assertEquals(items(SNAPDRAGON_SEED, 2), tick(t, null, items(RANARR_SEED, 4)));
		// a kind taken out completely: its slot goes, and that is a move too
		assertTrue(tick(t, items(SEED_BOX, 1, RANARR_SEED, 10, SNAPDRAGON_SEED, 2), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		// a ranarr picked up later, with nothing moving, counts
		assertTrue(tick(t, items(SEED_BOX, 1, RANARR_SEED, 11, SNAPDRAGON_SEED, 2), items()).isEmpty());
		assertEquals(items(RANARR_SEED, 1), tick(t, null, items()));
	}

	@Test
	public void aBoxThatTurnsUpLateIsAMoveNotABaseline()
	{
		// the box's contents unknown at the baseline; they arrive with a fill of 3 seeds (box shows 13): nothing
		ItemGainsTracker t = stores();
		t.tick(items(SEED_BOX, 1, RANARR_SEED, 3), items(), null, slots(items()), true);
		assertTrue(tick(t, items(SEED_BOX, 1), items(RANARR_SEED, 13)).isEmpty());
		assertTrue(tick(t, null, items(RANARR_SEED, 13)).isEmpty());
		// the box dropping out again (unknown) while 13 come out
		assertTrue(tick(t, items(SEED_BOX, 1, RANARR_SEED, 13), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
	}

	@Test
	public void withABoxInUseEveryHeldChangeIsSetAsideAtLogout()
	{
		ItemGainsTracker t = stores();
		t.tick(items(SEED_BOX, 1, SNAPDRAGON_SEED, 3, ORE, 1), items(), null, slots(items(RANARR_SEED, 4)), true);
		assertTrue(tick(t, items(SEED_BOX, 1, ORE, 2), items(RANARR_SEED, 4)).isEmpty());
		assertEquals(items(SNAPDRAGON_SEED, -3, ORE, 1), t.storeUnresolved(true).get("held"));
		assertTrue(t.flush().isEmpty());
		// without a box only the store items are set aside, as before
		t = stores();
		t.tick(items(SEED_BOX, 1, RANARR_SEED, 3, ORE, 1), items(), null, slots(items(RANARR_SEED, 4)), true);
		assertTrue(tick(t, items(SEED_BOX, 1, ORE, 2), items(RANARR_SEED, 4)).isEmpty());
		assertEquals(items(RANARR_SEED, -3), t.storeUnresolved(false).get("held"));
		assertEquals(items(ORE, 1), t.flush());
	}

	@Test
	public void aBlindItemSetCoversTheTickBeforeAndAfterAndAnUnrecordedSpan()
	{
		java.util.function.IntPredicate seeds = id -> id == RANARR_SEED || id == SNAPDRAGON_SEED;
		// a held seed loss the tick before the box goes blind, and a seed gain on the tick after: nothing
		ItemGainsTracker t = stores();
		t.tick(items(SEED_BOX, 1, RANARR_SEED, 3), items(), null, slots(items()), true);
		assertTrue(tick(t, items(SEED_BOX, 1), items()).isEmpty());
		t.blind(seeds);
		assertTrue(tick(t, null, items()).isEmpty());
		assertTrue(tick(t, items(SEED_BOX, 1, SNAPDRAGON_SEED, 2, ORE, 1), items()).isEmpty());
		assertEquals(items(ORE, 1), tick(t, null, items()));
		// blind on an unrecorded tick: still covers the first recorded tick after it
		t.blind(seeds);
		assertTrue(unrecorded(t, null, items()).isEmpty());
		assertTrue(unrecorded(t, null, items()).isEmpty());
		assertTrue(tick(t, items(SEED_BOX, 1, ORE, 1), items()).isEmpty());
		assertTrue(tick(t, null, items()).isEmpty());
		// and then it has passed
		assertTrue(tick(t, items(SEED_BOX, 1, ORE, 1, RANARR_SEED, 1), items()).isEmpty());
		assertEquals(items(RANARR_SEED, 1), tick(t, null, items()));
	}
}
