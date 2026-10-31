package com.hans;

import com.google.gson.annotations.SerializedName;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WidgetNode;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;

/**
 * farming.json: the raw farming state the game transmits, per region. The same varbit id means a different patch
 * in each region, so a reading is only meaningful together with the region it was taken in; deciding which regions
 * hold patches, and what a value means, happens later in the app. Nothing is decoded here.
 *
 * Read like RuneLite's time tracking does: on a settled tick with no modal widget open, not the post-login welcome
 * screen, not the tick after the patch area or plane changed (the varbits are not yet updated), and only where the
 * area's varbits are reliable (its bounds, below). An entry is recorded when the area is entered, when a farming
 * varbit changes, and every 5 minutes while in the area. The latest entry per patch area is kept: the region id, split
 * where two areas share a region.
 *
 * A reading where every patch varbit is 0 is never recorded, and never overwrites an earlier one: 0 is both "not
 * transmitted here" (stairs, edges of a region) and "fully grown weeds", and the two cannot be told apart. Clearing,
 * harvesting or digging up a patch leaves it raked (non-zero), which is recorded as usual.
 */
final class FarmingCapture implements Capture
{
	private static final Duration REFRESH = Duration.ofMinutes(5);

	/** FARMING_TRANSMIT_A..E, F..P and the grape patches: what each patch of the current region transmits. */
	static final int[] PATCH_VARBITS = patchVarbits();
	/** Autoweed and the Farming Guild contract: not tied to a region, stored beside the patches. */
	static final int[] OTHER_VARBITS = {VarbitID.FARMING_BLOCKWEEDS, VarbitID.FARMGUILD_CONTRACT_TYPE,
		VarbitID.FARMGUILD_CONTRACT_COMPLETE};

	static final class Entry
	{
		@SerializedName("region_id")
		int regionId;
		int plane;
		/**
		 * Patch area within a shared region ("falador", "port_sarim", "catherby", "catherby_fruit_tree");
		 * null when the region id alone is the area.
		 */
		String area;
		/** The player's tile when read (x, y). */
		Integer x;
		Integer y;
		@SerializedName("first_seen_at")
		String firstSeenAt;
		@SerializedName("observed_at")
		String observedAt;
		Map<Integer, Integer> varbits;
	}

	static final class File
	{
		@SerializedName("observed_at")
		String observedAt;
		Map<String, Entry> regions;
	}

	private final CaptureContext ctx;
	private final PriorFile<File> prior;
	private Map<String, Entry> regions;
	private int regionsGeneration = -1;
	private String lastKey; // the area and plane of the previous tick; null when it was not a trusted reading spot
	private String recordedKey;
	private Instant recordedAt;
	private ProfileKey recordedFor; // whose patches the last reading was
	private boolean force;

	FarmingCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
		this.prior = new PriorFile<>(ctx, "farming.json", File.class);
	}

	@Override
	public void onLogin()
	{
		lastKey = null; // a login or a world hop is an area change as far as the varbits are concerned
	}

	@Override
	public void onVarbitChanged(VarbitChanged event)
	{
		int id = event.getVarbitId();
		for (int v : PATCH_VARBITS)
		{
			if (v == id)
			{
				force = true;
				return;
			}
		}
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey profile = ctx.login.reading();
		if (profile == null)
		{
			lastKey = null; // nothing read now: the first tick after must not be trusted either
			return;
		}
		if (!ctx.config.exportFarming() || !ctx.json.enabled())
		{
			return;
		}
		WorldPoint at = local.getWorldLocation();
		String key = ctx.client.isInInstancedRegion() || at == null ? null : areaKey(at);
		String keyAndPlane = key == null ? null : key + "@" + at.getPlane();
		if (keyAndPlane == null || !keyAndPlane.equals(lastKey))
		{
			lastKey = keyAndPlane; // out of bounds, or the tick after an area or plane change: not trusted
			return;
		}
		if (welcomeScreenUp(ctx.client) || modalOpen(ctx.client))
		{
			return;
		}
		int region = at.getRegionID();
		boolean due = force || !key.equals(recordedKey) || !profile.equals(recordedFor) || recordedAt == null
			|| Duration.between(recordedAt, now).compareTo(REFRESH) >= 0;
		if (!due)
		{
			return;
		}
		prior.ensure(profile);
		if (!prior.loaded())
		{
			return;
		}
		if (regions == null || regionsGeneration != prior.generation())
		{
			File before = prior.get();
			regions = new TreeMap<>();
			if (before != null && before.regions != null)
			{
				regions.putAll(before.regions);
			}
			regionsGeneration = prior.generation();
		}
		force = false;
		recordedKey = key;
		recordedFor = profile;
		recordedAt = now;

		Map<Integer, Integer> varbits = new TreeMap<>();
		boolean anyPatch = false;
		for (int id : PATCH_VARBITS)
		{
			int v = ctx.client.getVarbitValue(id);
			varbits.put(id, v);
			anyPatch |= v != 0;
		}
		if (!anyPatch)
		{
			return; // nothing transmitted here, or all weeds: keep whatever was recorded before
		}
		Entry old = regions.get(key);
		for (int id : OTHER_VARBITS)
		{
			varbits.put(id, ctx.client.getVarbitValue(id));
		}

		int plane = at.getPlane();
		Entry e = new Entry();
		e.regionId = region;
		e.plane = plane;
		e.area = areaName(at);
		e.x = at.getX();
		e.y = at.getY();
		e.observedAt = now.toString();
		e.varbits = varbits;
		boolean same = old != null && Objects.equals(old.varbits, varbits) && old.plane == plane;
		e.firstSeenAt = same && old.firstSeenAt != null ? old.firstSeenAt : now.toString();
		regions.put(key, e);

		File out = new File();
		out.observedAt = now.toString();
		out.regions = regions;
		ctx.json.write(profile, "farming.json", out);
	}

	// Where each patch area's varbits can be trusted, ported as plain data from RuneLite's time tracking
	// (FarmingWorld and its isInBounds rules). Regions not listed are one area each, trusted everywhere.

	/** Fossil Island: the hardwood trees are sent anywhere on plane 0, except on some stairs and ladders. */
	private static final int[] FOSSIL_ISLAND = {14650, 14651, 14652, 14906, 14907, 14908, 15162, 15163, 15164};
	/** Catherby allotments (11062, also sent in 11061 and 11318) and fruit tree (11317) share region 11317. */
	private static final int CATHERBY_SHARED = 11317;
	/** Falador allotments (y 3272 and up) and the Port Sarim spirit tree (below) share region 12083. */
	private static final int FALADOR_SHARED = 12083;

	/**
	 * The patch area at a tile, within its region: "falador" or "port_sarim" in 12083, "catherby" or
	 * "catherby_fruit_tree" in 11317, and null where the region id alone is the area (Ardougne's north and south
	 * allotments are two patches of one area in 10548, as in RuneLite's FarmingWorld).
	 */
	static String areaName(WorldPoint p)
	{
		int region = p.getRegionID();
		if (region == FALADOR_SHARED)
		{
			return p.getY() >= 3272 ? "falador" : "port_sarim";
		}
		if (region == CATHERBY_SHARED)
		{
			// the allotments are sent in the corner by the beach, on the ground floor; upstairs there the fruit tree
			return p.getX() < 2840 && p.getY() >= 3440 && p.getPlane() == 0 ? "catherby" : "catherby_fruit_tree";
		}
		return null;
	}

	/** Whether the varbits read at this tile belong to its area; false where they are not (yet) sent. */
	static boolean inBounds(WorldPoint p)
	{
		int region = p.getRegionID();
		int x = p.getX();
		int y = p.getY();
		for (int r : FOSSIL_ISLAND)
		{
			if (r == region)
			{
				// the varbits change a tick early on these stairs and ladders
				boolean stairs = x == 3753 && y >= 3868 && y <= 3870;
				boolean ladders = (x == 3728 || x == 3729 || x == 3746 || x == 3747) && y >= 3830 && y <= 3832;
				return !stairs && !ladders && p.getPlane() == 0;
			}
		}
		if (region == CATHERBY_SHARED && x < 2840 && y >= 3440)
		{
			return p.getPlane() <= 1; // allotments on the ground floor, fruit tree on the first: higher, neither
		}
		return true;
	}

	/** The key of the latest-entry map (the region id, plus the area where regions are shared); null out of bounds. */
	static String areaKey(WorldPoint p)
	{
		if (!inBounds(p))
		{
			return null;
		}
		String area = areaName(p);
		int region = p.getRegionID();
		return area == null ? Integer.toString(region) : region + ":" + area;
	}

	/** True while the post-login welcome screen is up (the farming varbits are not sent then). */
	static boolean welcomeScreenUp(Client client)
	{
		Widget w = client.getWidget(InterfaceID.WelcomeScreen.MOTW);
		return w != null && !w.isHidden();
	}

	/** True while any modal interface is open: the game does not send varbits then. */
	static boolean modalOpen(Client client)
	{
		for (WidgetNode node : client.getComponentTable())
		{
			if (node.getModalMode() != WidgetModalMode.NON_MODAL)
			{
				return true;
			}
		}
		return false;
	}

	private static int[] patchVarbits()
	{
		List<Integer> ids = new ArrayList<>();
		for (int id = 4771; id <= 4775; id++) // FARMING_TRANSMIT_A..E
		{
			ids.add(id);
		}
		for (int id = 7904; id <= 7914; id++) // FARMING_TRANSMIT_F..P
		{
			ids.add(id);
		}
		for (int id = 4953; id <= 4964; id++) // grape patches A1..F2
		{
			ids.add(id);
		}
		int[] out = new int[ids.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = ids.get(i);
		}
		return out;
	}
}
