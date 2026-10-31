package com.hans;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * account.json: the account hash, the game mode (the IRONMAN varbit, raw), the RuneScape profile type and the world
 * with its types. Written at settle and whenever any of it changes.
 */
final class AccountCapture implements Capture
{
	private final CaptureContext ctx;
	private Map<String, Object> last;
	private ProfileKey lastKey;

	AccountCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null || !ctx.config.exportVars() || !ctx.json.enabled())
		{
			return;
		}
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("account_hash", Long.toString(ctx.client.getAccountHash())); // a string: it can exceed 2^53
		content.put("name", key.name);
		content.put("ironman", ctx.client.getVarbitValue(VarbitID.IRONMAN));
		content.put("profile_type", RuneScapeProfileType.getCurrent(ctx.client).name());
		content.put("world", ctx.client.getWorld());
		List<String> types = new ArrayList<>();
		for (WorldType t : ctx.client.getWorldType())
		{
			types.add(t.name());
		}
		Collections.sort(types);
		content.put("world_types", types);

		if (Objects.equals(content, last) && Objects.equals(lastKey, key))
		{
			return;
		}
		last = content;
		lastKey = key;
		Map<String, Object> file = new LinkedHashMap<>();
		file.put("observed_at", now.toString());
		file.putAll(content);
		ctx.json.write(key, "account.json", file);
	}

	@Override
	public void onConfigChanged(String key)
	{
		if ("exportVars".equals(key))
		{
			last = null;
		}
	}
}
