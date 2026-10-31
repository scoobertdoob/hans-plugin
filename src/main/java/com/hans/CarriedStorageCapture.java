package com.hans;

import java.time.Instant;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.gameval.InventoryID;

/**
 * A store read from game variables every tick (the rune pouch, Dizana's quiver), written to its own file when its
 * contents change and once per login. Its availability is established apart from its contents: it is read only while
 * the store's own item is carried (in the backpack or worn), so the variables of a store the player does not have,
 * or has banked, are never taken for an empty one. A reading the game cannot give completely now is "unavailable":
 * nothing is written, so the last known file stays as it was. Each account and game mode writes to its own folder.
 */
abstract class CarriedStorageCapture implements Capture
{
	final CaptureContext ctx;
	private final String fileName;
	private final BooleanSupplier on;
	private StorageSnapshot last; // the last snapshot written, and for which key (client thread)
	private ProfileKey lastKey;

	CarriedStorageCapture(CaptureContext ctx, String fileName, BooleanSupplier on)
	{
		this.ctx = ctx;
		this.fileName = fileName;
		this.on = on;
	}

	/**
	 * The store's contents now, called only while its item is carried; null when the game cannot tell them completely
	 * (unavailable, never "empty").
	 *
	 * @param carried whether the store's item is in the backpack or worn now
	 */
	abstract StorageSnapshot read(String observedAt, boolean carried);

	/** The last complete reading of this store for {@code key}, or null. Client thread. */
	StorageSnapshot latest(ProfileKey key)
	{
		return key.equals(lastKey) ? last : null;
	}

	/** The item ids of the store itself (every variant); one must be carried for the store to be read. */
	abstract int[] storeItems();

	/** Whether the container holds one of the ids; false while the container has not been sent. */
	static boolean holdsAny(ItemContainer c, int[] ids)
	{
		if (c == null)
		{
			return false;
		}
		for (Item it : c.getItems())
		{
			for (int id : ids)
			{
				if (it.getId() == id)
				{
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public Future<?> onLogout(Instant now)
	{
		last = null; // the next login writes its first reading again, under its own key
		lastKey = null;
		return null;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null || !ctx.keeps(on.getAsBoolean()))
		{
			return;
		}
		int[] ids = storeItems();
		boolean carried = holdsAny(ctx.client.getItemContainer(InventoryID.INV), ids)
			|| holdsAny(ctx.client.getItemContainer(InventoryID.WORN), ids);
		if (!carried)
		{
			return; // not positively present (absent, banked, or the containers not sent): the known file stays
		}
		StorageSnapshot snapshot = read(now.toString(), carried);
		if (snapshot == null || key.equals(lastKey) && snapshot.sameItems(last))
		{
			return;
		}
		if (ctx.writes(on.getAsBoolean()))
		{
			ctx.json.write(key, fileName, snapshot);
		}
		last = snapshot;
		lastKey = key;
	}
}
