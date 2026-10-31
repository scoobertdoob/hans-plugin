// Adapted from OSRS Toolkit Exporter (c) Gorsokk, BSD-2-Clause, https://github.com/Gorsokk/osrs-toolkit
package com.hans;

import java.time.Instant;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ItemContainerChanged;

/**
 * One item container written to a file whenever it changes: inventory.json, equipment.json or bank.json
 * (kind = "inventory", "equipment" or "bank"). Item names are looked up through ItemManager on the client thread.
 * The bank is only known to the client once it was opened this session, so bank.json waits for that.
 * A change is read on its tick, but the file is written at most every {@link #EVERY_TICKS} ticks: a full bank is about
 * 100 KB, and a bank-standing player changes it nearly every tick. The reading held back is written once the gap is
 * over, or at logout.
 */
final class ItemsCapture implements Capture
{
	static final int EVERY_TICKS = 5; // 3 seconds

	private final CaptureContext ctx;
	private final String kind;
	private final int containerId;
	private final BooleanSupplier on;
	private final String configKey;
	private boolean dirty = true;
	private int tick;
	private int writtenTick = -EVERY_TICKS;
	private ToolkitData.Items held; // read, not yet written (the gap was not over)
	private ProfileKey heldKey;

	/**
	 * @param kind        the file name without ".json", also written inside the file
	 * @param containerId the InventoryID of the container
	 * @param on          the setting that switches this file on
	 * @param configKey   the key of that setting
	 */
	ItemsCapture(CaptureContext ctx, String kind, int containerId, BooleanSupplier on, String configKey)
	{
		this.ctx = ctx;
		this.kind = kind;
		this.containerId = normaliseContainerId(containerId);
		this.on = on;
		this.configKey = configKey;
	}

	/** Storage containers (looting bag, seed vault, POH storage) arrive with 0x8000 added to their id. */
	static int normaliseContainerId(int id)
	{
		return id >= 0x8000 ? id - 0x8000 : id;
	}

	@Override
	public void onLogin()
	{
		dirty = true;
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (normaliseContainerId(event.getContainerId()) == containerId)
		{
			dirty = true;
		}
	}

	@Override
	public void onConfigChanged(String key)
	{
		if (configKey.equals(key))
		{
			dirty = true;
		}
	}

	@Override
	public Future<?> onLogout(Instant now)
	{
		return writeHeld(); // read and tagged before the logout, under its own key
	}

	private Future<?> writeHeld()
	{
		ToolkitData.Items last = held;
		held = null;
		if (last != null && on.getAsBoolean() && ctx.json.enabled())
		{
			writtenTick = tick;
			return ctx.json.write(heldKey, kind + ".json", last);
		}
		return null;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		tick++;
		if (held != null && tick - writtenTick >= EVERY_TICKS)
		{
			writeHeld();
		}
		ProfileKey key = ctx.login.reading();
		if (!dirty || key == null || !on.getAsBoolean() || !ctx.json.enabled())
		{
			return; // no reading may be taken now (before settle, unconfirmed, another account): stays dirty
		}
		dirty = false;
		ItemContainer container = ctx.client.getItemContainer(containerId);
		if (container == null)
		{
			// storage containers are kept under their flagged id (id | 0x8000)
			container = ctx.client.getItemContainer(containerId | 0x8000);
		}
		if (container == null)
		{
			return; // e.g. the bank before it was opened this session: keep the last file
		}
		Item[] items = container.getItems();
		int[] ids = new int[items.length];
		int[] qtys = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			qtys[i] = items[i].getQuantity();
		}
		ToolkitData.Items data = ToolkitData.items(now.toString(), kind, ids, qtys,
			id -> ctx.itemManager.getItemComposition(id).getName());
		if (tick - writtenTick < EVERY_TICKS)
		{
			held = data; // replaces any older reading held back
			heldKey = key;
			return;
		}
		held = null;
		writtenTick = tick;
		ctx.json.write(key, kind + ".json", data);
	}
}
