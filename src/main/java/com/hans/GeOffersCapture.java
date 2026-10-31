// Adapted from OSRS Toolkit Exporter (c) Gorsokk, BSD-2-Clause, https://github.com/Gorsokk/osrs-toolkit
package com.hans;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.api.events.GrandExchangeOfferChanged;

/**
 * ge_offers.json: every one of the 8 Grand Exchange slots (item, buy or sell, state, offer price, quantity filled
 * and total, gp spent, the real average price). Written when a slot changes (new offer, partial fill, completed,
 * cancelled, collected). The write waits until the login has settled, because the profile key is not known yet when
 * the offers load at login.
 */
final class GeOffersCapture implements Capture
{
	private final CaptureContext ctx;
	private boolean dirty = true; // export current offers right away if the plugin is enabled while logged in

	GeOffersCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
	}

	@Override
	public void onLogin()
	{
		dirty = true;
	}

	@Override
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		dirty = true;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (!dirty || key == null || !ctx.json.enabled())
		{
			return; // no reading may be taken now: stays dirty, so the next confirmed tick writes it
		}
		dirty = false;
		if (ctx.config.exportGeOffers())
		{
			write(key, now);
		}
	}

	private void write(ProfileKey key, Instant now)
	{
		GrandExchangeOffer[] offers = ctx.client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}

		GeSnapshot snapshot = new GeSnapshot();
		snapshot.exported_at = now.toString();
		snapshot.player_name = key.name;
		snapshot.world = ctx.client.getWorld();
		snapshot.note = "price = offer price; avg_price = real average price (spent / quantity_filled). "
			+ "A BOUGHT/SOLD slot stays listed until it is collected.";

		List<GeSlot> slots = new ArrayList<>();
		for (int i = 0; i < offers.length; i++)
		{
			GrandExchangeOffer offer = offers[i];
			GeSlot slot = new GeSlot();
			slot.slot = i + 1; // 1-8, like in game

			GrandExchangeOfferState state = offer == null ? GrandExchangeOfferState.EMPTY : offer.getState();
			slot.state = state.name();

			if (offer == null || state == GrandExchangeOfferState.EMPTY)
			{
				snapshot.empty_slots++;
				slots.add(slot);
				continue;
			}

			slot.type = isBuy(state) ? "BUY" : "SELL";
			slot.item_id = offer.getItemId();
			slot.item_name = ctx.itemManager.getItemComposition(offer.getItemId()).getName();
			slot.price = (long) offer.getPrice();
			slot.quantity_filled = offer.getQuantitySold();
			slot.quantity_total = offer.getTotalQuantity();
			slot.spent = (long) offer.getSpent();
			slot.progress_pct = slot.quantity_total > 0
				? Math.round(1000.0 * slot.quantity_filled / slot.quantity_total) / 10.0
				: 0.0;
			if (slot.quantity_filled > 0)
			{
				slot.avg_price = Math.round(10.0 * slot.spent / slot.quantity_filled) / 10.0;
			}
			slot.complete = state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD;
			slot.cancelled = state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL;

			if (!slot.complete && !slot.cancelled)
			{
				if ("BUY".equals(slot.type))
				{
					// gp still locked in the offer
					snapshot.gp_locked_in_buys += (slot.quantity_total - slot.quantity_filled) * slot.price;
				}
				snapshot.active_offers++;
			}

			slots.add(slot);
		}

		snapshot.slots = slots;
		ctx.json.write(key, "ge_offers.json", snapshot);
	}

	private static boolean isBuy(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
	}

	@SuppressWarnings("unused")
	private static class GeSnapshot
	{
		String exported_at;
		String player_name;
		int world;
		int active_offers;
		int empty_slots;
		long gp_locked_in_buys;
		String note;
		List<GeSlot> slots;
	}

	@SuppressWarnings("unused")
	private static class GeSlot
	{
		int slot;
		String state;
		String type;
		Integer item_id;
		String item_name;
		Long price;
		Integer quantity_filled;
		Integer quantity_total;
		Long spent;
		Double avg_price;
		Double progress_pct;
		Boolean complete;
		Boolean cancelled;
	}
}
