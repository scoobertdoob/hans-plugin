package com.hans;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Player;

/**
 * birdhouses.json: the four bird house varps (1626..1629: Mushroom Meadow north and south, Verdant Valley northeast
 * and southwest), each with the time the value was last seen to change (the 50 minute clock starts there). The varps
 * are only transmitted on Fossil Island, so they are only read there (plane 0), and not on the tick after a region
 * change or login. A reading that would empty three or more houses at once is dropped as "not updated yet", like
 * RuneLite does. The raw values are kept; 0 is empty, the rest is decoded in the app.
 */
final class BirdHousesCapture implements Capture
{
	private static final int[] REGIONS = {14650, 14651, 14652, 14906, 14907, 15162, 15163};
	private static final int[] VARPS = {1626, 1627, 1628, 1629};
	private static final String[] SPACES = {"mushroom_meadow_north", "mushroom_meadow_south",
		"verdant_valley_northeast", "verdant_valley_southwest"};

	static final class House
	{
		String space;
		int varp;
		int value;
		@SerializedName("changed_at")
		String changedAt;
	}

	static final class File
	{
		@SerializedName("observed_at")
		String observedAt;
		List<House> houses;
	}

	private final CaptureContext ctx;
	private final PriorFile<File> prior;
	private List<House> state;
	private int stateGeneration = -1;
	private int lastRegion = -1;

	BirdHousesCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
		this.prior = new PriorFile<>(ctx, "birdhouses.json", File.class);
	}

	@Override
	public void onLogin()
	{
		lastRegion = -1;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null)
		{
			lastRegion = -1; // nothing read now: the first tick after waits, like a region change
			return;
		}
		if (!ctx.config.exportFarming() || !ctx.json.enabled())
		{
			return;
		}
		int region = local.getWorldLocation().getRegionID();
		boolean regionChanged = region != lastRegion;
		lastRegion = region;
		if (regionChanged || local.getWorldLocation().getPlane() != 0 || !onFossilIsland(region)
			|| FarmingCapture.welcomeScreenUp(ctx.client))
		{
			return;
		}
		prior.ensure(key);
		if (!prior.loaded())
		{
			return;
		}
		if (state == null || stateGeneration != prior.generation())
		{
			File before = prior.get();
			state = before != null && before.houses != null && before.houses.size() == VARPS.length
				? new ArrayList<>(before.houses) : null;
			stateGeneration = prior.generation();
		}

		int[] values = new int[VARPS.length];
		int cleared = 0;
		for (int i = 0; i < VARPS.length; i++)
		{
			values[i] = ctx.client.getVarpValue(VARPS[i]);
			if (state != null && state.get(i).value != 0 && values[i] == 0)
			{
				cleared++;
			}
		}
		if (cleared > 2)
		{
			return; // the varps were not updated yet
		}
		String stamp = now.toString();
		boolean changed = state == null;
		List<House> next = new ArrayList<>();
		for (int i = 0; i < VARPS.length; i++)
		{
			House old = state == null ? null : state.get(i);
			if (old != null && old.value == values[i])
			{
				next.add(old);
				continue;
			}
			House h = new House();
			h.space = SPACES[i];
			h.varp = VARPS[i];
			h.value = values[i];
			h.changedAt = stamp;
			next.add(h);
			changed = true;
		}
		state = next;
		if (changed)
		{
			File out = new File();
			out.observedAt = stamp;
			out.houses = state;
			ctx.json.write(key, "birdhouses.json", out);
		}
	}

	private static boolean onFossilIsland(int region)
	{
		for (int r : REGIONS)
		{
			if (r == region)
			{
				return true;
			}
		}
		return false;
	}
}
