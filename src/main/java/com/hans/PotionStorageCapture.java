package com.hans;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;

/**
 * The bank's potion storage (potion_storage.json). It is no item container: each potion's stored doses come from the
 * game's own script ({@code POTIONSTORE_DOSES}, 3750) and the dose it is withdrawn as from {@code
 * POTIONSTORE_WITHDRAW_DOSES} (4818), over the potions listed in {@code EnumID.POTIONSTORE_POTIONS} and {@code
 * POTIONSTORE_UNFINISHED_POTIONS}; empty vials are a varp. This is how RuneLite's own Bank Tags plugin reads it
 * (client 1.13.4, {@code banktags.tabs.PotionStorage}): on the client thread, trusted only while the bank is open,
 * and read again when one of the potion store's vars changes.
 *
 * So it is read only while the bank interface is open, the bank has been sent this login, and {@link #READY_TICKS}
 * ticks have passed after the tick it opened on (the store's vars arrive with the bank). A read that is not complete
 * (an enum not loaded, a stored potion whose withdrawn form cannot be named) writes nothing, so the last known file
 * stays; a complete read with no doses is an empty potion storage. Each potion's quantity is in doses ({@code unit} "dose"),
 * its item the potion as withdrawn ({@code withdraw_doses} each).
 */
final class PotionStorageCapture implements Capture
{
	static final String FILE = "potion_storage.json";
	static final String KEY = "potion_storage";
	static final String LABEL = "Potion storage";
	static final int READY_TICKS = 2;
	private static final int[] POTION_LISTS = {EnumID.POTIONSTORE_POTIONS, EnumID.POTIONSTORE_UNFINISHED_POTIONS};
	/** The store's vars RuneLite's Bank Tags plugin also watches, besides the potion list widget's own triggers. */
	private static final int[] STORE_VARPS = {
		VarPlayerID.POTIONSTORE_BASE_VAR_1, VarPlayerID.POTIONSTORE_BASE_VAR_2, VarPlayerID.POTIONSTORE_BASE_VAR_3,
		VarPlayerID.POTIONSTORE_BASE_VAR_4, VarPlayerID.POTIONSTORE_BASE_VAR_5, VarPlayerID.POTIONSTORE_BASE_VAR_6,
		VarPlayerID.POTIONSTORE_VIALS,
	};

	private final CaptureContext ctx;
	private final BooleanSupplier on;
	private Set<Integer> triggers; // the potion list widget's var transmit triggers, once read
	private int tick;
	private int openSince = -1; // the tick the bank interface opened; -1 while it is closed
	private boolean bankSent; // the bank's contents arrived this login
	private boolean dirty;
	private StorageSnapshot last;
	private ProfileKey lastKey;

	PotionStorageCapture(CaptureContext ctx, BooleanSupplier on)
	{
		this.ctx = ctx;
		this.on = on;
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		bankSent |= event.getContainerId() == InventoryID.BANK;
	}

	@Override
	public void onVarbitChanged(VarbitChanged event)
	{
		int varp = event.getVarpId();
		if (triggers != null && triggers.contains(varp))
		{
			dirty = true;
		}
		for (int v : STORE_VARPS)
		{
			dirty |= v == varp;
		}
	}

	@Override
	public Future<?> onLogout(Instant now)
	{
		bankSent = false;
		openSince = -1;
		dirty = false;
		last = null;
		lastKey = null;
		return null;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		tick++;
		Widget bank = ctx.client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bank == null || bank.isHidden())
		{
			openSince = -1;
			return;
		}
		if (openSince < 0)
		{
			openSince = tick;
			dirty = true;
		}
		if (triggers == null)
		{
			Widget list = ctx.client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
			int[] t = list == null ? null : list.getVarTransmitTrigger();
			if (t != null)
			{
				triggers = new HashSet<>();
				for (int v : t)
				{
					triggers.add(v);
				}
			}
		}
		ProfileKey key = ctx.login.reading();
		if (!dirty || !bankSent || tick - openSince < READY_TICKS || key == null || !ctx.keeps(on.getAsBoolean()))
		{
			return; // stays dirty until a reading may be trusted
		}
		StorageSnapshot s = read(now.toString());
		if (s == null)
		{
			return; // incomplete: tried again next tick while the bank stays open
		}
		dirty = false;
		if (key.equals(lastKey) && s.sameItems(last))
		{
			return;
		}
		if (ctx.writes(on.getAsBoolean()))
		{
			ctx.json.write(key, FILE, s);
		}
		last = s;
		lastKey = key;
	}

	/** The last complete reading of this store for {@code key}, or null. Client thread. */
	StorageSnapshot latest(ProfileKey key)
	{
		return key.equals(lastKey) ? last : null;
	}

	/** One complete reading of the store; null when any part of it cannot be read. Client thread, bank open. */
	private StorageSnapshot read(String observedAt)
	{
		StorageSnapshot s = new StorageSnapshot(KEY, LABEL, StorageSnapshot.COUNTED, false, observedAt);
		int listed = 0;
		for (int listId : POTION_LISTS)
		{
			EnumComposition list = ctx.client.getEnum(listId);
			if (list == null)
			{
				return null;
			}
			for (int potion : list.getIntVals())
			{
				listed++;
				EnumComposition forms = ctx.client.getEnum(potion); // dose to that potion's item
				if (forms == null)
				{
					return null;
				}
				int doses = script(ScriptID.POTIONSTORE_DOSES, potion);
				if (doses <= 0)
				{
					continue;
				}
				int withdraw = script(ScriptID.POTIONSTORE_WITHDRAW_DOSES, potion);
				int item = withdraw > 0 ? forms.getIntValue(withdraw) : -1;
				if (item <= 0)
				{
					return null; // stored, but the game does not say as what
				}
				StorageSnapshot.Entry e = s.add(null, item, doses, StorageSnapshot.itemName(ctx, item));
				e.unit = "dose";
				e.withdraw_doses = withdraw;
				e.potion_enum = potion;
			}
		}
		if (listed == 0)
		{
			return null; // no potions listed: the enums are not what they should be
		}
		int vials = ctx.client.getVarpValue(VarPlayerID.POTIONSTORE_VIALS);
		if (vials > 0)
		{
			s.add(null, ItemID.VIAL_EMPTY, vials, StorageSnapshot.itemName(ctx, ItemID.VIAL_EMPTY));
		}
		return s;
	}

	private int script(int scriptId, int potion)
	{
		ctx.client.runScript(scriptId, potion);
		return ctx.client.getIntStack()[0];
	}
}
