package com.hans;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One store the player keeps items in outside the bank, as the plugin last saw it: rune_pouch.json, quiver.json and
 * potion_storage.json. The shape is the one the app's "storages" list reads (docs/plans/plan-22.md, "Shared design
 * with #17"), so no field needs decoding here.
 *
 * A file is written only from a complete reading the game gave: a store that was never opened, or could not be read,
 * has no file (or keeps its last one), so an empty item list always means the game showed it empty.
 */
final class StorageSnapshot
{
	/** An item list with slots, like the bank (the seed vault). */
	static final String CONTAINER = "container";
	/** Items and amounts without slots that matter (500 blood runes in a pouch, doses in potion storage). */
	static final String COUNTED = "counted";

	String observed_at;
	int format_version = RsbuddyData.FORMAT_VERSION;
	String source = RsbuddyData.SOURCE;
	String key;
	String label;
	String kind;
	/** Carried with the player but not shown in the backpack (rune pouch, quiver): shown beside the backpack. */
	boolean carried;
	int item_count;
	List<Entry> items = new ArrayList<>();

	/** One stored item. */
	static final class Entry
	{
		Integer slot; // container stores only
		int id;
		int quantity;
		String name;
		// potion storage: quantity is doses; the item is the potion as it would be withdrawn (withdraw_doses each)
		String unit;
		Integer withdraw_doses;
		Integer potion_enum;
	}

	StorageSnapshot()
	{
	}

	StorageSnapshot(String key, String label, String kind, boolean carried, String observedAt)
	{
		this.key = key;
		this.label = label;
		this.kind = kind;
		this.carried = carried;
		this.observed_at = observedAt;
	}

	Entry add(Integer slot, int id, int quantity, String name)
	{
		Entry e = new Entry();
		e.slot = slot;
		e.id = id;
		e.quantity = quantity;
		e.name = name;
		items.add(e);
		item_count = items.size();
		return e;
	}

	/** An item's name, on the client thread (through ItemManager, which caches them, when there is one). */
	static String itemName(CaptureContext ctx, int id)
	{
		return ctx.itemManager != null ? ctx.itemManager.getItemComposition(id).getName()
			: ctx.client.getItemDefinition(id).getName();
	}

	/** The same items in the same order (the reading time and names aside): nothing to write again. */
	boolean sameItems(StorageSnapshot o)
	{
		if (o == null || o.items.size() != items.size())
		{
			return false;
		}
		for (int i = 0; i < items.size(); i++)
		{
			Entry a = items.get(i);
			Entry b = o.items.get(i);
			if (!Objects.equals(a.slot, b.slot) || a.id != b.id || a.quantity != b.quantity
				|| !Objects.equals(a.withdraw_doses, b.withdraw_doses))
			{
				return false;
			}
		}
		return true;
	}
}
