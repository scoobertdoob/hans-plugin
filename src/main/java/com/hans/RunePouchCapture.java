package com.hans;

import java.util.function.BooleanSupplier;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.gameval.ItemID;

/**
 * The rune pouch (rune_pouch.json): each slot's rune and count, read from the pouch's type and count varbits and the
 * game's rune enum, as {@link ItemGainsCapture} reads them for its transfers (which this capture leaves alone). The
 * varbits read empty for a player with no pouch, so they are read only while a rune pouch is carried; an all-empty
 * reading then is an empty pouch. Until the rune enum loads, or while a slot's rune cannot be named, the reading is
 * unavailable and nothing is written.
 */
final class RunePouchCapture extends CarriedStorageCapture
{
	static final String FILE = "rune_pouch.json";
	static final String KEY = "rune_pouch";
	static final String LABEL = "Rune pouch";

	/** Every rune pouch: the rune pouch and the divine rune pouch, and their Trouver-locked forms. */
	static final int[] POUCHES = {
		ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER, ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER,
	};

	RunePouchCapture(CaptureContext ctx, BooleanSupplier on)
	{
		super(ctx, FILE, on);
	}

	@Override
	int[] storeItems()
	{
		return POUCHES;
	}

	@Override
	StorageSnapshot read(String observedAt, boolean carried)
	{
		EnumComposition runeEnum = ctx.client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runeEnum == null)
		{
			return null;
		}
		StorageSnapshot s = new StorageSnapshot(KEY, LABEL, StorageSnapshot.COUNTED, carried, observedAt);
		for (int i = 0; i < ItemGainsCapture.RUNE_POUCH_TYPES.length; i++)
		{
			int type = ctx.client.getVarbitValue(ItemGainsCapture.RUNE_POUCH_TYPES[i]);
			int qty = ctx.client.getVarbitValue(ItemGainsCapture.RUNE_POUCH_QUANTITIES[i]);
			if (type <= 0 || qty <= 0)
			{
				continue; // an empty slot
			}
			int rune = ItemGainsCapture.pouchRune(runeEnum, type);
			if (rune <= 0)
			{
				return null; // a rune the enum does not name: not a complete reading
			}
			s.add(null, rune, qty, StorageSnapshot.itemName(ctx, rune));
		}
		return s;
	}
}
