package com.hans;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.kit.KitType;

/**
 * appearance.json: the local player's body type, the five colour indices (raw, not RGB) and the twelve worn
 * model slots, each as the raw value plus what it decodes to: an item (value 2048 and up, item id = value - 2048),
 * a body kit (256 up to 2047, kit id = value - 256) or empty. A body slot covered by worn gear shows the item, not
 * the kit, so the last kit seen uncovered is kept per slot ("kit_last_seen", also across sessions). Own player only.
 *
 * Written at settle and after the local player's appearance changed, a few ticks later (it fires in bursts), and only
 * when it differs from the last write.
 */
final class AppearanceCapture implements Capture
{
	private static final int DEBOUNCE_TICKS = 3;

	static final class File
	{
		@SerializedName("observed_at")
		String observedAt;
		@SerializedName("kit_last_seen")
		Map<String, Integer> kitLastSeen;
	}

	private final CaptureContext ctx;
	private final PriorFile<File> prior;
	private final Map<String, Integer> kitLastSeen = new TreeMap<>();
	private int generation = -1;
	private int wait = -1; // ticks until the next read; -1: nothing to do
	private Map<String, Object> last;
	private ProfileKey lastKey;

	AppearanceCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
		this.prior = new PriorFile<>(ctx, "appearance.json", File.class);
	}

	@Override
	public void onSettled(Player local, Instant now)
	{
		wait = 0;
	}

	@Override
	public void onPlayerChanged(PlayerChanged event)
	{
		if (event.getPlayer() != null && event.getPlayer() == ctx.client.getLocalPlayer())
		{
			wait = DEBOUNCE_TICKS;
		}
	}

	@Override
	public void onConfigChanged(String key)
	{
		if ("exportAppearance".equals(key))
		{
			wait = 0;
			last = null;
		}
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (wait < 0 || key == null || !ctx.config.exportAppearance() || !ctx.json.enabled())
		{
			return;
		}
		if (wait > 0)
		{
			wait--;
			return;
		}
		PlayerComposition comp = local.getPlayerComposition();
		if (comp == null)
		{
			return;
		}
		prior.ensure(key);
		if (!prior.loaded())
		{
			return;
		}
		if (generation != prior.generation())
		{
			generation = prior.generation();
			kitLastSeen.clear();
			File before = prior.get();
			if (before != null && before.kitLastSeen != null)
			{
				kitLastSeen.putAll(before.kitLastSeen);
			}
		}
		wait = -1;

		int[] raw = comp.getEquipmentIds();
		KitType[] slots = KitType.values();
		List<Map<String, Object>> list = new ArrayList<>();
		for (int i = 0; i < slots.length && i < raw.length; i++)
		{
			int v = raw[i];
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("slot", slots[i].name());
			s.put("raw", v);
			if (v >= PlayerComposition.ITEM_OFFSET)
			{
				s.put("kind", "item");
				s.put("id", v - PlayerComposition.ITEM_OFFSET);
			}
			else if (v >= PlayerComposition.KIT_OFFSET)
			{
				s.put("kind", "kit");
				s.put("id", v - PlayerComposition.KIT_OFFSET);
				kitLastSeen.put(slots[i].name(), v - PlayerComposition.KIT_OFFSET);
			}
			else
			{
				s.put("kind", "empty");
			}
			list.add(s);
		}
		List<Integer> colors = new ArrayList<>();
		for (int c : comp.getColors())
		{
			colors.add(c);
		}

		Map<String, Object> content = new LinkedHashMap<>();
		content.put("gender", comp.getGender());
		content.put("colors", colors);
		content.put("slots", list);
		content.put("kit_last_seen", new TreeMap<>(kitLastSeen));
		if (Objects.equals(content, last) && Objects.equals(lastKey, key))
		{
			return;
		}
		last = content;
		lastKey = key;
		Map<String, Object> file = new LinkedHashMap<>();
		file.put("observed_at", now.toString());
		file.putAll(content);
		ctx.json.write(key, "appearance.json", file);
	}
}
