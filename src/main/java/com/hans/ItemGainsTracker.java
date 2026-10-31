package com.hans;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Works out which items entered or left the inventory, one game tick at a time, from the inventory, worn-equipment and
 * carried-storage (rune pouch, quiver) totals (item id to quantity, all slots added up). No game access, so it can be
 * tested on its own.
 *
 * An item that left the worn equipment or carried storage this tick or the tick before is taken to be the same item
 * arriving in the inventory (unequipping, the old item of an equip swap, or emptying a rune pouch) only when the slot
 * was emptied and the same quantity arrived, so it is not counted as a gain. Likewise an item
 * that entered the worn equipment or carried storage this tick or the tick before is taken to be the item leaving the
 * inventory (equipping, or filling a rune pouch), so it is not counted as a loss. Both event orders (inventory-first
 * and equipment/pouch-first) are buffered across one tick. A partly used stack (ammunition fired, runes cast) never
 * grants transfer credit. A slot that runs out credits only an inventory gain of the same item of exactly the quantity
 * that left it: casting never puts the rune into the inventory and firing never puts the ammo there, so emptying a rune
 * pouch or quiver, or taking off ammunition, cancels while skilling or fighting too, and a pickup of a different amount
 * after the last ammo or runes are used up still counts. Worn and pouch/quiver credits are kept apart: a gain must match
 * the worn credit, the pouch/quiver credit, or both together exactly, so the last worn arrow fired on the tick a quiver
 * of the same arrows is emptied does not stop the quiver's arrows from cancelling. Whether the player is animating or interacting tells nothing
 * (fishing spots and pickpocket targets are NPCs, skilling animates), so it is not asked. The one accepted error is a
 * pickup of exactly the amount just used up, within about two ticks.
 *
 * Counted skilling stores (essence pouches, the plank sack, the bolt pouch) are a separate kind of carried storage,
 * read slot by slot from their count variables ({@link Slot}). They are not matched by amount: a count that moves (a
 * fill, an empty, a pouch's kind changing while it holds something) opens a suppression window for its store key (every
 * essence kind shares one key, as the counts do not say which kind they hold), from the tick before the move to the
 * tick after it, and every inventory change of that key inside the window records nothing. A window is extended across
 * ticks that are not recorded (a bank, a cooldown, the quiet ticks after login), so a move on the last of them still
 * covers the first recorded tick. Outside a window nothing changes. This can only miss a change, never invent one; the
 * accepted misses are any real change of a stored item (a pickup, a craft, a drop, essence made at a workbench) on the
 * tick before a store's count moves, on that tick or on the tick after, or on the first recorded tick after a bank
 * visit during which it moved. Items used straight from a store (planks built from the sack, essence removed at a
 * minigame exit) never pass through the inventory, so they are never logged as used, as with runes cast from the rune
 * pouch. A store read from an item container (the seed box, the tackle box) has one slot per item kind it holds; a slot
 * that turns up or goes (the box loaded late, or an item kind put in or taken out) is a move of all of it. Where a
 * store's contents cannot be trusted (a box carried but not loaded, or just used from its menu), the caller names the
 * items it could hold instead ({@link #blind}): a window over that whole set, with the same reach.
 *
 * Equivalent item forms (worn/inventory id variants like graceful, and charged/degraded variants of the same item) are
 * matched through their base variation id while keeping the raw inventory item ids in the returned changes.
 * Not thread safe; the client thread owns it.
 */
final class ItemGainsTracker
{
	private static final class Change
	{
		final int rawId;
		final int equivId;
		final int storeId;
		int qty; // positive: gain, negative: loss

		Change(int rawId, int equivId, int storeId, int qty)
		{
			this.rawId = rawId;
			this.equivId = equivId;
			this.storeId = storeId;
			this.qty = qty;
		}
	}

	/** One counted-store slot's reading: one essence pouch, or one kind of plank in the plank sack. */
	static final class Slot
	{
		final int key;
		final int qty;
		final int kind;

		/**
		 * @param key  the store key of what it holds
		 * @param qty  how many it holds
		 * @param kind what kind it holds when the key does not say (an essence pouch's type), else 0: a change of kind
		 *             while it holds something both before and after is an empty and a fill
		 */
		Slot(int key, int qty, int kind)
		{
			this.key = key;
			this.qty = qty;
			this.kind = kind;
		}
	}

	/** One tick's transfer credits by equivalent id: items that left the worn equipment, and items that left the pouch or quiver. */
	private static final class Credits
	{
		final Map<Integer, Integer> worn = new HashMap<>();
		final Map<Integer, Integer> pouch = new HashMap<>();

		void clear()
		{
			worn.clear();
			pouch.clear();
		}

		/** Uses up the credit that exactly matches: the worn one, the pouch one, or both together. Returns what it cancels. */
		int use(int id, int wanted)
		{
			if (useCredit(worn, id, wanted) > 0 || useCredit(pouch, id, wanted) > 0)
			{
				return wanted;
			}
			Integer w = worn.get(id);
			Integer p = pouch.get(id);
			if (w == null || p == null || wanted <= 0 || w + p != wanted)
			{
				return 0;
			}
			worn.remove(id);
			pouch.remove(id);
			return wanted;
		}
	}

	private final IntUnaryOperator equivalentId;
	private final IntPredicate consumable;
	private final IntUnaryOperator storeKey;
	private Map<Integer, Integer> inventory; // null until the first reading after a reset
	private Map<Integer, Integer> worn;
	private Map<Integer, Integer> pouch;
	private Map<Integer, Slot> store; // counted skilling stores, by slot
	private final Set<Integer> storeKeys = new HashSet<>(); // every store key a reading has named since the baseline
	// items that left worn/pouch (cancel inventory gains) or entered worn/pouch (cancel inventory losses), keyed by equivalent id
	private Credits creditPrevious = new Credits();
	private Credits creditNow = new Credits();
	private Map<Integer, Integer> debitPrevious = new HashMap<>();
	private Map<Integer, Integer> debitNow = new HashMap<>();
	// counted-store empties and fills of this tick, and those whose window still covers the next tick, by store key
	private final Map<Integer, Integer> movedOut = new HashMap<>();
	private final Map<Integer, Integer> movedIn = new HashMap<>();
	private final Map<Integer, Integer> openOut = new HashMap<>();
	private final Map<Integer, Integer> openIn = new HashMap<>();
	// item sets inside a window without a count to show it (a box whose contents are unknown): this tick's, and those
	// still covering the next tick. Few, and the same instances each tick, so the sets stay small
	private final Set<IntPredicate> blindNow = new HashSet<>();
	private final Set<IntPredicate> blindOpen = new HashSet<>();
	// unmatched inventory changes from the previous tick, buffered for 1 tick in case the matching worn/pouch transfer arrives on the next tick
	private final List<Change> pendingInvPrevious = new ArrayList<>();

	ItemGainsTracker()
	{
		this(ItemVariationMapping::map, id -> false);
	}

	ItemGainsTracker(IntUnaryOperator equivalentId)
	{
		this(equivalentId, id -> false);
	}

	/**
	 * @param equivalentId an item id to the id its equivalent forms share (worn/inventory variants, charges, noted)
	 * @param consumable   true for an item that is eaten or drunk
	 */
	ItemGainsTracker(IntUnaryOperator equivalentId, IntPredicate consumable)
	{
		this(equivalentId, consumable, IntUnaryOperator.identity());
	}

	/**
	 * @param storeKey an equivalent id to the key a counted skilling store counts it under (every essence kind to one
	 *                 key); the id itself for anything else
	 */
	ItemGainsTracker(IntUnaryOperator equivalentId, IntPredicate consumable, IntUnaryOperator storeKey)
	{
		this.equivalentId = equivalentId;
		this.consumable = consumable;
		this.storeKey = storeKey;
	}

	/** Forgets the last readings (logout, world hop): the next reading is a new baseline. */
	void reset()
	{
		inventory = null;
		worn = null;
		pouch = null;
		store = null;
		storeKeys.clear();
		creditPrevious.clear();
		creditNow.clear();
		debitPrevious.clear();
		debitNow.clear();
		clearStoreChanges();
		pendingInvPrevious.clear();
	}

	private void clearStoreChanges()
	{
		movedOut.clear();
		movedIn.clear();
		openOut.clear();
		openIn.clear();
		blindNow.clear();
		blindOpen.clear();
	}

	/**
	 * For the next {@link #tick} only: every inventory change of an item the predicate accepts (by raw item id) is
	 * inside a store's window, as if that store had moved this tick, whatever the amount. For a store whose contents
	 * are not known, so its moves cannot be seen item by item.
	 */
	void blind(IntPredicate items)
	{
		blindNow.add(items);
	}

	/**
	 * At an ordinary logout, before {@link #flush}: the changes still held of items a counted store holds ("held", raw
	 * item id to quantity), with the store moves whose window is still open ("credits" for empties, "debits" for
	 * fills, by store key). The other half of a fill or empty could come only on a tick that never follows, so they are
	 * neither a gain nor a loss: the caller quarantines them. An open window with no held change (logging out at a bank
	 * after filling the pouches) has nothing to set aside, so it is empty then. The held changes of other items stay
	 * for {@link #flush}.
	 */
	Map<String, Map<Integer, Integer>> storeUnresolved()
	{
		return storeUnresolved(false);
	}

	/**
	 * As {@link #storeUnresolved()}; with {@code anyItem} (a store that can hold many kinds of item is carried or
	 * loaded: the seed box, the tackle box), every held change is set aside, since it could be going into that store as
	 * a kind it has not held yet.
	 */
	Map<String, Map<Integer, Integer>> storeUnresolved(boolean anyItem)
	{
		Map<String, Map<Integer, Integer>> out = new TreeMap<>();
		Map<Integer, Integer> held = new TreeMap<>();
		for (Iterator<Change> it = pendingInvPrevious.iterator(); it.hasNext();)
		{
			Change c = it.next();
			if (c.qty != 0 && (anyItem || storeKeys.contains(c.storeId)))
			{
				held.merge(c.rawId, c.qty, Integer::sum);
				it.remove();
			}
		}
		held.values().removeIf(v -> v == 0);
		if (!held.isEmpty())
		{
			out.put("held", held);
			putIfAny(out, "credits", amounts(openOut));
			putIfAny(out, "debits", amounts(openIn));
		}
		clearStoreChanges();
		return out;
	}

	private static void putIfAny(Map<String, Map<Integer, Integer>> out, String name, Map<Integer, Integer> m)
	{
		if (!m.isEmpty())
		{
			out.put(name, m);
		}
	}

	private static Map<Integer, Integer> amounts(Map<Integer, Integer> moves)
	{
		Map<Integer, Integer> out = new TreeMap<>(moves);
		out.values().removeIf(v -> v == 0);
		return out;
	}

	/** Flushes any inventory changes still waiting one tick for a transfer (e.g. at logout). */
	Map<Integer, Integer> flush()
	{
		Map<Integer, Integer> out = new TreeMap<>();
		for (Change c : pendingInvPrevious)
		{
			if (c.qty != 0)
			{
				out.merge(c.rawId, c.qty, Integer::sum);
			}
		}
		out.values().removeIf(v -> v == 0);
		pendingInvPrevious.clear();
		return out;
	}

	/**
	 * The transfer candidates still open, for a boundary after which no reading follows this baseline (the client
	 * stopped reporting this login's account or mode, or a hop into another mode; not a same-mode hop or region load,
	 * which keep reconciling): the inventory changes held one
	 * tick for a worn or pouch transfer ("held", raw item id to quantity), and the worn, pouch or counted-store credits
	 * ("credits", items that left them) and debits ("debits", items that entered them) not yet matched, by equivalent
	 * id (by store key for a counted store). Never a
	 * gain or a loss: the matching half may arrive only after the boundary. Empty when nothing is open. Changes the
	 * tracker's state like {@link #reset}.
	 */
	Map<String, Map<Integer, Integer>> unresolved()
	{
		Map<String, Map<Integer, Integer>> out = new TreeMap<>();
		Map<Integer, Integer> held = flush();
		if (!held.isEmpty())
		{
			out.put("held", held);
		}
		Map<Integer, Integer> credits = merged(merged(merged(creditPrevious.worn, creditPrevious.pouch),
			merged(creditNow.worn, creditNow.pouch)), amounts(openOut));
		if (!credits.isEmpty())
		{
			out.put("credits", credits);
		}
		Map<Integer, Integer> debits = merged(merged(debitPrevious, debitNow), amounts(openIn));
		if (!debits.isEmpty())
		{
			out.put("debits", debits);
		}
		reset();
		return out;
	}

	private static Map<Integer, Integer> merged(Map<Integer, Integer> a, Map<Integer, Integer> b)
	{
		Map<Integer, Integer> out = new TreeMap<>(a);
		for (Map.Entry<Integer, Integer> e : b.entrySet())
		{
			out.merge(e.getKey(), e.getValue(), Integer::sum);
		}
		out.values().removeIf(v -> v == 0);
		return out;
	}

	Map<Integer, Integer> tick(Map<Integer, Integer> inventoryNow, Map<Integer, Integer> wornNow, boolean record)
	{
		return tick(inventoryNow, wornNow, null, record);
	}

	Map<Integer, Integer> tick(Map<Integer, Integer> inventoryNow, Map<Integer, Integer> wornNow,
		Map<Integer, Integer> pouchNow, boolean record)
	{
		return tick(inventoryNow, wornNow, pouchNow, null, record);
	}

	/**
	 * One game tick.
	 *
	 * @param inventoryNow the inventory totals, or null when it was not read this tick (unchanged)
	 * @param wornNow      the worn-equipment totals, or null when not read this tick (unchanged)
	 * @param pouchNow     the carried-pouch/quiver totals, or null when not read this tick (unchanged)
	 * @param storeNow     the counted skilling stores' slots (any stable slot id to its reading), or null when not read
	 *                     this tick (unchanged)
	 * @param record      false while changes must be ignored (a bank or shop open, just after login): the readings
	 *                     still become the new baseline, after emitting any eligible buffered change from the previous
	 *                     recorded tick
	 * @return the changes of this tick, item id to quantity (positive: gained, negative: lost), in id order; empty
	 *         when there are none
	 */
	Map<Integer, Integer> tick(Map<Integer, Integer> inventoryNow, Map<Integer, Integer> wornNow,
		Map<Integer, Integer> pouchNow, Map<Integer, Slot> storeNow, boolean record)
	{
		// equipment/pouch/store credits last this tick and the next, in case the two containers arrive a tick apart
		Credits expiredCredits = creditPrevious;
		creditPrevious = creditNow;
		creditNow = expiredCredits;
		creditNow.clear();
		Map<Integer, Integer> expired = debitPrevious;
		debitPrevious = debitNow;
		debitNow = expired;
		debitNow.clear();
		movedOut.clear();
		movedIn.clear();

		if (wornNow != null)
		{
			if (worn != null)
			{
				recordWornTransfers(worn, wornNow);
			}
			worn = wornNow;
		}
		if (pouchNow != null)
		{
			if (pouch != null)
			{
				recordPouchTransfers(pouch, pouchNow);
			}
			pouch = pouchNow;
		}
		if (storeNow != null)
		{
			for (Slot s : storeNow.values())
			{
				storeKeys.add(s.key);
			}
			if (store != null)
			{
				recordStoreTransfers(store, storeNow);
			}
			store = storeNow;
		}

		Map<Integer, Integer> changes = new TreeMap<>();

		// Reconcile buffered changes from the previous recorded tick with this tick's worn/pouch transfers
		for (Change prev : pendingInvPrevious)
		{
			if (prev.qty > 0)
			{
				prev.qty -= creditNow.use(prev.equivId, prev.qty);
			}
			else if (prev.qty < 0)
			{
				prev.qty += useCredit(debitNow, prev.equivId, -prev.qty);
			}
		}

		List<Change> currentInv = new ArrayList<>();
		if (record && inventoryNow != null && inventory != null)
		{
			for (int id : new TreeMap<>(unionMap(inventory, inventoryNow)).keySet())
			{
				int change = inventoryNow.getOrDefault(id, 0) - inventory.getOrDefault(id, 0);
				if (change != 0)
				{
					currentInv.add(new Change(id, equiv(id), storeId(id), change));
				}
			}

			// Cancel same-tick in-inventory variant/charge transitions (e.g. item degrading or charging in inventory)
			cancelEquivalentSwaps(currentInv);

			// Cancel current-tick inventory changes against previous-tick and same-tick worn/pouch transfers
			for (Change c : currentInv)
			{
				if (c.qty > 0)
				{
					c.qty -= creditPrevious.use(c.equivId, c.qty);
					c.qty -= creditNow.use(c.equivId, c.qty);
				}
				else if (c.qty < 0)
				{
					c.qty += useCredit(debitPrevious, c.equivId, -c.qty);
					c.qty += useCredit(debitNow, c.equivId, -c.qty);
				}
			}
		}

		// a counted store's window: the held changes (the tick before) for this tick's moves, this tick's changes for
		// this tick's moves and those still open from before
		suppress(pendingInvPrevious, movedOut, movedIn, openOut, openIn);
		suppress(currentInv, movedOut, movedIn, openOut, openIn);
		suppressItems(pendingInvPrevious, blindNow);
		suppressItems(currentInv, blindNow);
		suppressItems(currentInv, blindOpen);
		// this tick's moves cover the next tick; on a tick not recorded, the earlier ones stay open with them
		if (record)
		{
			openOut.clear();
			openIn.clear();
			blindOpen.clear();
		}
		movedOut.forEach((k, v) -> openOut.merge(k, v, Integer::sum));
		movedIn.forEach((k, v) -> openIn.merge(k, v, Integer::sum));
		blindOpen.addAll(blindNow);
		blindNow.clear();
		for (Change prev : pendingInvPrevious)
		{
			if (prev.qty != 0)
			{
				changes.merge(prev.rawId, prev.qty, Integer::sum);
			}
		}
		pendingInvPrevious.clear();
		for (Change c : currentInv)
		{
			if (c.qty != 0)
			{
				pendingInvPrevious.add(c);
			}
		}

		if (!record)
		{
			// the readings are only the new baseline (a store's window stays open to the first recorded tick)
			creditPrevious.clear();
			creditNow.clear();
			debitPrevious.clear();
			debitNow.clear();
		}
		if (inventoryNow != null)
		{
			inventory = inventoryNow;
		}
		changes.values().removeIf(v -> v == 0);
		return changes;
	}

	/**
	 * Worn-equipment transfers: an item entering worn (change > 0) is an equip debit. An item leaving worn (change < 0)
	 * is only an unequip credit if the worn slot was emptied (after == 0); the credit cancels only an inventory gain of
	 * exactly that quantity, so the last arrows fired alongside a pickup of a different amount still count. A partial
	 * decrease (after > 0) is ammunition fired and never credits a pickup.
	 */
	private void recordWornTransfers(Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		Map<Integer, Integer> netByEquiv = new HashMap<>();
		Map<Integer, Integer> afterByEquiv = new HashMap<>();
		for (int id : union(before, after))
		{
			int eq = equiv(id);
			int b = before.getOrDefault(id, 0);
			int a = after.getOrDefault(id, 0);
			if (a != b)
			{
				netByEquiv.merge(eq, a - b, Integer::sum);
			}
			if (a > 0)
			{
				afterByEquiv.merge(eq, a, Integer::sum);
			}
		}
		for (Map.Entry<Integer, Integer> e : netByEquiv.entrySet())
		{
			int eq = e.getKey();
			int change = e.getValue();
			int rem = afterByEquiv.getOrDefault(eq, 0);
			if (change < 0)
			{
				if (rem == 0)
				{
					creditNow.worn.merge(eq, -change, Integer::sum);
				}
			}
			else if (change > 0)
			{
				debitNow.merge(eq, change, Integer::sum);
			}
		}
	}

	/**
	 * Carried-pouch/quiver transfers: an increase (change > 0) is filling the pouch/quiver from the inventory.
	 * A decrease (change < 0) is only a transfer to the inventory when the slot was emptied (after == 0), whatever the
	 * quantity (a slot of one rune too), and it cancels only an inventory gain of exactly that quantity: a cast or shot
	 * never puts the rune or ammo into the inventory. Partial decreases (after > 0) are rune/ammo consumption and never
	 * credit a pickup.
	 */
	private void recordPouchTransfers(Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		Map<Integer, Integer> netByEquiv = new HashMap<>();
		Map<Integer, Integer> afterByEquiv = new HashMap<>();
		for (int id : union(before, after))
		{
			int eq = equiv(id);
			int b = before.getOrDefault(id, 0);
			int a = after.getOrDefault(id, 0);
			if (a != b)
			{
				netByEquiv.merge(eq, a - b, Integer::sum);
			}
			if (a > 0)
			{
				afterByEquiv.merge(eq, a, Integer::sum);
			}
		}
		for (Map.Entry<Integer, Integer> e : netByEquiv.entrySet())
		{
			int eq = e.getKey();
			int change = e.getValue();
			if (change < 0 && afterByEquiv.getOrDefault(eq, 0) == 0)
			{
				creditNow.pouch.merge(eq, -change, Integer::sum);
			}
			else if (change > 0)
			{
				debitNow.merge(eq, change, Integer::sum);
			}
		}
	}

	/**
	 * Counted-store moves, slot by slot before they are added up by store key: an increase is a fill and a decrease an
	 * empty. A slot whose kind changed while holding something before and after was emptied and filled again.
	 */
	private void recordStoreTransfers(Map<Integer, Slot> before, Map<Integer, Slot> after)
	{
		for (Map.Entry<Integer, Slot> e : after.entrySet())
		{
			Slot a = e.getValue();
			Slot b = before.get(e.getKey());
			int was = b == null ? 0 : b.qty;
			if (b != null && b.kind != a.kind && was > 0 && a.qty > 0)
			{
				movedOut.merge(b.key, was, Integer::sum);
				movedIn.merge(a.key, a.qty, Integer::sum);
			}
			else if (a.qty > was)
			{
				movedIn.merge(a.key, a.qty - was, Integer::sum);
			}
			else if (a.qty < was)
			{
				movedOut.merge(b.key, was - a.qty, Integer::sum);
			}
		}
		for (Map.Entry<Integer, Slot> e : before.entrySet())
		{
			Slot b = e.getValue();
			if (!after.containsKey(e.getKey()) && b.qty > 0)
			{
				movedOut.merge(b.key, b.qty, Integer::sum);
			}
		}
	}

	/** Every change of a store key that one of the given move sets names records nothing, whatever its amount. */
	@SafeVarargs
	private static void suppress(List<Change> list, Map<Integer, Integer>... windows)
	{
		for (Change c : list)
		{
			for (Map<Integer, Integer> w : windows)
			{
				if (w.containsKey(c.storeId))
				{
					c.qty = 0;
				}
			}
		}
	}

	/** Every change of an item one of the given sets accepts records nothing, whatever its amount. */
	private static void suppressItems(List<Change> list, Set<IntPredicate> sets)
	{
		for (Change c : list)
		{
			for (IntPredicate p : sets)
			{
				if (c.qty != 0 && p.test(c.rawId))
				{
					c.qty = 0;
				}
			}
		}
	}

	private void cancelEquivalentSwaps(List<Change> list)
	{
		for (int i = 0; i < list.size(); i++)
		{
			Change a = list.get(i);
			if (a.qty == 0 || consumable.test(a.rawId))
			{
				continue;
			}
			for (int j = i + 1; j < list.size(); j++)
			{
				Change b = list.get(j);
				if (b.qty == 0 || a.equivId != b.equivId || (a.qty > 0) == (b.qty > 0) || consumable.test(b.rawId))
				{
					continue;
				}
				int cancel = Math.min(Math.abs(a.qty), Math.abs(b.qty));
				a.qty += a.qty > 0 ? -cancel : cancel;
				b.qty += b.qty > 0 ? -cancel : cancel;
				if (a.qty == 0)
				{
					break;
				}
			}
		}
		for (Iterator<Change> it = list.iterator(); it.hasNext();)
		{
			if (it.next().qty == 0)
			{
				it.remove();
			}
		}
	}

	private int equiv(int id)
	{
		return equivalentId.applyAsInt(id);
	}

	private int storeId(int id)
	{
		return storeKey.applyAsInt(equiv(id));
	}

	private static Set<Integer> union(Map<Integer, Integer> a, Map<Integer, Integer> b)
	{
		Set<Integer> ids = new HashSet<>(a.keySet());
		ids.addAll(b.keySet());
		return ids;
	}

	private static Map<Integer, Integer> unionMap(Map<Integer, Integer> a, Map<Integer, Integer> b)
	{
		Map<Integer, Integer> m = new HashMap<>(a);
		m.putAll(b);
		return m;
	}

	private static int useCredit(Map<Integer, Integer> credit, int id, int wanted)
	{
		Integer have = credit.get(id);
		if (have == null || wanted <= 0 || have != wanted)
		{
			return 0;
		}
		credit.remove(id);
		return wanted;
	}

	/** Adds up a container's slots: item id to total quantity. Empty slots (id -1 or quantity 0) are skipped. */
	static Map<Integer, Integer> totals(int[] ids, int[] quantities)
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (int i = 0; i < ids.length; i++)
		{
			if (ids[i] >= 0 && quantities[i] > 0)
			{
				out.merge(ids[i], quantities[i], Integer::sum);
			}
		}
		return out;
	}
}
