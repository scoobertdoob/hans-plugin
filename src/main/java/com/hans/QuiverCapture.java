package com.hans;

import java.util.function.BooleanSupplier;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Dizana's quiver (quiver.json): the ammunition in the quiver's own slot, read from its ammo id and amount varps
 * ({@code DIZANAS_QUIVER_TEMP_AMMO} and {@code _AMOUNT}), the ones {@link ItemGainsCapture} reads for its transfers.
 * Ammunition in the worn ammo slot is the equipment's (equipment.json) and is never counted here.
 *
 * It is not yet checked in game whether the client keeps these varps while the quiver is banked, so the quiver is
 * read only while one is carried (in the backpack or worn, the max cape included); otherwise the reading is
 * unavailable and the last known file stays. A carried quiver with no ammo id or amount is empty.
 */
final class QuiverCapture extends CarriedStorageCapture
{
	static final String FILE = "quiver.json";
	static final String KEY = "quiver";
	static final String LABEL = "Dizana's quiver";
	/** Every quiver: charged, infinite, uncharged, the Trouver-locked ones and the Dizana's max capes. */
	static final int[] QUIVERS = {
		ItemID.DIZANAS_QUIVER_CHARGED, ItemID.DIZANAS_QUIVER_CHARGED_TROUVER, ItemID.DIZANAS_QUIVER_INFINITE,
		ItemID.DIZANAS_QUIVER_INFINITE_TROUVER, ItemID.SKILLCAPE_MAX_DIZANAS, ItemID.SKILLCAPE_MAX_DIZANAS_TROUVER,
		ItemID.DIZANAS_QUIVER_UNCHARGED, ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER,
	};

	QuiverCapture(CaptureContext ctx, BooleanSupplier on)
	{
		super(ctx, FILE, on);
	}

	@Override
	int[] storeItems()
	{
		return QUIVERS;
	}

	@Override
	StorageSnapshot read(String observedAt, boolean carried)
	{
		StorageSnapshot s = new StorageSnapshot(KEY, LABEL, StorageSnapshot.COUNTED, carried, observedAt);
		int ammo = ctx.client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
		int amount = ctx.client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
		if (ammo > 0 && amount > 0)
		{
			s.add(null, ammo, amount, StorageSnapshot.itemName(ctx, ammo));
		}
		return s;
	}
}
