package com.hans;

import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.ScriptEvent;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/**
 * collection_log.json: every collection log item the player has been seen to have, item by item, with its count as
 * the log shows it, per account and game mode. Raw: item ids exactly as the game sends them (names, pages and variant
 * ids are the app's business).
 *
 * How it is read, the method WikiSync, Group Ironmen Tracker and RuneTools use: the game sends the log only while it
 * is open. When the player opens their own log (its setup script 7797 has run), this capture presses the log's
 * Search toggle once, with the one {@code menuAction} call RuneLite's Plugin Hub rules allow, and re-runs the log's
 * own init script (2240) to put the view back. The press is queued with {@code ClientThread.invokeLater}, never made
 * inside the script event (runScript is not reentrant), and re-checks the log is still the player's own, open, and
 * not yet pressed. The game then lists every obtained item: one script 4100 per item,
 * with the arguments {widget, item id, count}. The items arriving together make one look, which ends
 * {@link #QUIET_TICKS} ticks after its last item. A player who presses Search themselves gives a look the same way.
 *
 * A look is merged into the file on the executor, read from disk at that moment: an item is added, or its count and
 * last-seen time move on; nothing is ever removed, so an item never becomes un-obtained because a look was partial. A
 * look is complete when it held at least as many items as the game's obtained counter (varp 2943); only a complete
 * look shows that an item missing from it was not obtained yet. So each item keeps when it was first seen, and
 * "already_had" is true when no earlier complete look had it missing (first_seen is then only when we could first
 * look); otherwise "missing_at" is that look's time, and the item was obtained between the two.
 *
 * The game's own "New item added to your collection log" chat line (shown when that game option is on) is kept too,
 * by name and time, for the exact date of a new slot. One that arrives while no key is confirmed goes to quarantine.
 *
 * Never read: someone else's log, opened through a house's Adventure Log (varbit 12061 is 1). Nothing is pressed then,
 * and what arrives is not kept. Items arriving while no reading key is valid (a hop, a region load, another account)
 * end the look as interrupted and are not kept or quarantined: the whole log is sent again at the next opening.
 * Client thread, except the merge.
 */
@Slf4j
final class CollectionLogCapture implements Capture
{
	static final String FILE = "collection_log.json";
	static final int FORMAT = 1;
	static final int SETUP_SCRIPT = 7797; // the log interface has been set up (no public constant)
	static final int ITEM_SCRIPT = 4100; // one obtained item: arguments {widget, item id, count}
	static final int INIT_SCRIPT = 2240; // the log's own init script: puts the view back after the Search toggle
	static final int QUIET_TICKS = 3; // a look ends on the third tick with no new item
	static final int REQUEST_TICKS = 10; // a press that brought no item by then did not take
	static final int MAX_ITEMS = 5000; // the real log has under 2,000 items: a cap against a garbled stream
	private static final Pattern NEW_ITEM = Pattern.compile("New item added to your collection log: (.+)");

	/** One look at the log: the items that arrived together, and what the game's counters said at its end. */
	static final class Look
	{
		String at;
		@SerializedName("items_seen")
		int itemsSeen;
		@SerializedName("entries_seen")
		int entriesSeen; // positive-count entries received, repeats included (items_seen counts distinct ids)
		@SerializedName("zero_entries")
		int zeroEntries; // entries sent with a count of 0 (not counted as obtained)
		@SerializedName("game_obtained")
		Integer gameObtained; // varp 2943 at the end of the look; null when it could not be read
		@SerializedName("game_total")
		Integer gameTotal; // varp 2944
		boolean complete;
		boolean requested; // RSBuddy pressed Search for it (false: the player did)
		String ended; // "settled", "interrupted" or "logout"
	}

	static final class Item
	{
		int quantity;
		@SerializedName("first_seen")
		String firstSeen;
		@SerializedName("already_had")
		boolean alreadyHad;
		@SerializedName("missing_at")
		String missingAt;
		@SerializedName("last_seen")
		String lastSeen;
	}

	static final class NewItemMessage
	{
		String name;
		String at;

		NewItemMessage(String name, String at)
		{
			this.name = name;
			this.at = at;
		}
	}

	static final class File
	{
		int format = FORMAT;
		@SerializedName("observed_at")
		String observedAt;
		@SerializedName("first_look_at")
		String firstLookAt;
		@SerializedName("last_look")
		Look lastLook;
		@SerializedName("last_complete_look")
		Look lastCompleteLook;
		Map<Integer, Item> items = new TreeMap<>();
		@SerializedName("new_item_messages")
		List<NewItemMessage> newItemMessages = new ArrayList<>();
	}

	/** The newest complete look of this plugin session: whose log, the game's counters, and every item it showed. */
	static final class Complete
	{
		final ProfileKey key;
		final int obtained;
		final int total;
		final Map<Integer, Integer> items;
		final Instant at;

		Complete(ProfileKey key, int obtained, int total, Map<Integer, Integer> items, Instant at)
		{
			this.key = key;
			this.obtained = obtained;
			this.total = total;
			this.items = items;
			this.at = at;
		}
	}

	/** A look still arriving. */
	private static final class Burst
	{
		final ProfileKey key;
		final Map<Integer, Integer> items = new LinkedHashMap<>();
		boolean requested;
		int zeroEntries;
		int entriesSeen;
		int lastTick;

		Burst(ProfileKey key)
		{
			this.key = key;
		}
	}

	private final CaptureContext ctx;
	private Burst burst;
	private boolean asked; // a press is queued or made since the log was last closed, or since login or a hop
	private boolean pressed; // the press itself has been made
	private boolean logOpen; // the collection log interface is open
	private ProfileKey askedKey; // the reading key the queued press was scheduled under
	private int requestTick = -1; // when it was pressed, while no item has arrived for it
	private ProfileKey requestKey;
	private int tick;
	private Instant lastTickAt;
	private Complete complete; // for the upload (client thread)

	CollectionLogCapture(CaptureContext ctx)
	{
		this.ctx = ctx;
	}

	/** The log is read: for its file, or for the upload. */
	private boolean on()
	{
		return ctx.keeps(ctx.config.exportCollectionLog());
	}

	/** True while the open log is someone else's (a house's Adventure Log): it is never read. */
	private boolean othersLog()
	{
		return ctx.client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
	}

	@Override
	public void onLogin()
	{
		resetAsk(); // a login or hop starts afresh: the log is closed then
		requestTick = -1; // and a press in flight is moot
		requestKey = null;
	}

	@Override
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.COLLECTION)
		{
			logOpen = true; // asked is not reset here: the press itself reloads the interface
		}
	}

	private void resetAsk()
	{
		asked = false;
		pressed = false;
		logOpen = false;
		askedKey = null;
	}

	@Override
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.COLLECTION)
		{
			resetAsk(); // the next opening may press Search once
			requestTick = -1;
			requestKey = null;
		}
	}

	@Override
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != SETUP_SCRIPT || !on())
		{
			return;
		}
		if (othersLog())
		{
			burst = null; // anything that arrived with their log is not ours
			return;
		}
		ProfileKey key = ctx.login.reading();
		if (key == null || asked || requestTick >= 0)
		{
			return; // at most once per opening; never twice before the first press has answered
		}
		if (ctx.clientThread == null)
		{
			return;
		}
		logOpen = true;
		asked = true;
		askedKey = key;
		// Not inside this script event: runScript is not reentrant. Run once the script has finished.
		ctx.clientThread.invokeLater(() -> press(key));
	}

	/** The queued press, on the client thread outside any script: only if everything still holds. */
	private void press(ProfileKey key)
	{
		if (pressed || !logOpen || !on() || othersLog() || !key.equals(askedKey) || !key.equals(ctx.login.reading()))
		{
			return;
		}
		if (ctx.client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE) == null)
		{
			return; // the toggle is not there (a game update moved it, say): nothing is pressed blind
		}
		pressed = true;
		requestTick = tick;
		requestKey = key;
		if (burst != null)
		{
			burst.requested = true;
		}
		// The one call the Plugin Hub accepts for this (fetching collection log data), kept exactly as published.
		ctx.client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		ctx.client.runScript(INIT_SCRIPT);
	}

	@Override
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != ITEM_SCRIPT || !on() || ctx.login.dropsEvidence())
		{
			return;
		}
		if (othersLog())
		{
			burst = null;
			return;
		}
		ScriptEvent se = event.getScriptEvent();
		Object[] args = se == null ? null : se.getArguments();
		if (args == null || args.length < 3 || !(args[1] instanceof Integer) || !(args[2] instanceof Integer))
		{
			return; // not the shape the log sends: ignore it
		}
		ProfileKey key = ctx.login.reading();
		if (burst != null && !burst.key.equals(key))
		{
			finish(clock(), "interrupted", false);
		}
		if (key == null)
		{
			return;
		}
		if (burst == null)
		{
			burst = new Burst(key);
			burst.requested = requestTick >= 0 && key.equals(requestKey);
		}
		requestTick = -1;
		burst.lastTick = tick;
		int id = (Integer) args[1];
		int count = (Integer) args[2];
		if (id <= 0)
		{
			return;
		}
		if (count <= 0)
		{
			burst.zeroEntries++;
			return;
		}
		burst.entriesSeen++;
		Integer had = burst.items.get(id);
		if (had != null || burst.items.size() < MAX_ITEMS)
		{
			burst.items.put(id, had == null ? count : Math.max(had, count));
		}
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		tick++;
		lastTickAt = now;
		if (burst != null && tick - burst.lastTick >= QUIET_TICKS)
		{
			finish(now, "settled", true);
		}
		if (requestTick >= 0 && tick - requestTick > REQUEST_TICKS)
		{
			noAnswer(now);
		}
	}

	@Override
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || event.getMessage() == null || !on())
		{
			return;
		}
		Matcher m = NEW_ITEM.matcher(Text.removeTags(event.getMessage()).trim());
		if (!m.matches() || ctx.login.dropsEvidence())
		{
			return;
		}
		String name = m.group(1).trim();
		String at = Instant.now().toString();
		LoginState.Token token = ctx.login.token();
		if (token.key != null)
		{
			save(token.key, f -> mergeMessage(f, name, at));
			return;
		}
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("name", name);
		value.put("observed_at", at);
		Map<String, Object> q = new LinkedHashMap<>();
		q.put("kept_at", at);
		q.put("kind", "collection_log_new_item");
		q.put("why", token.why);
		q.put("token", token.seq);
		q.putAll(ctx.login.whereabouts());
		q.put("value", value);
		ctx.json.appendQuarantine(q);
	}

	@Override
	public Future<?> onLogout(Instant now)
	{
		Future<?> done = burst == null ? null : finish(now, "logout", false);
		resetAsk();
		requestTick = -1;
		requestKey = null;
		return done;
	}

	private Instant clock()
	{
		return lastTickAt == null ? Instant.now() : lastTickAt;
	}

	/**
	 * Ends the look still arriving and queues its merge.
	 *
	 * @param readCounts whether the game's counters may be read now (never at logout: no new reading then)
	 */
	private Future<?> finish(Instant now, String ended, boolean readCounts)
	{
		Burst b = burst;
		burst = null;
		requestTick = -1;
		requestKey = null;
		if (b == null || !on())
		{
			return null;
		}
		Look look = new Look();
		look.at = now.toString();
		look.itemsSeen = b.items.size();
		look.entriesSeen = b.entriesSeen;
		look.zeroEntries = b.zeroEntries;
		look.requested = b.requested;
		look.ended = ended;
		if (readCounts && b.key.equals(ctx.login.reading()))
		{
			readCounters(look);
		}
		look.complete = look.gameObtained != null && look.itemsSeen >= look.gameObtained;
		Map<Integer, Integer> items = Collections.unmodifiableMap(b.items);
		if (look.complete)
		{
			complete = new Complete(b.key, look.gameObtained, look.gameTotal, items, now);
		}
		return save(b.key, f -> mergeLook(f, look, items));
	}

	/**
	 * A press that brought no item: an account whose counters say it has none yet gets an empty, complete look (so a
	 * first-time player's later items are dated); otherwise the press did not take, and nothing is written.
	 */
	private void noAnswer(Instant now)
	{
		ProfileKey key = requestKey;
		requestTick = -1;
		requestKey = null;
		if (key == null || !key.equals(ctx.login.reading()) || !on() || othersLog())
		{
			return;
		}
		Look look = new Look();
		look.at = now.toString();
		look.requested = true;
		look.ended = "settled";
		readCounters(look);
		if (look.gameObtained == null || look.gameObtained != 0)
		{
			return;
		}
		look.complete = true;
		complete = new Complete(key, 0, look.gameTotal, Collections.emptyMap(), now);
		save(key, f -> mergeLook(f, look, Collections.emptyMap()));
	}

	/** The game's obtained and total counters; left null while the total is not loaded (0). */
	private void readCounters(Look look)
	{
		int total = ctx.client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		if (total > 0)
		{
			look.gameTotal = total;
			look.gameObtained = Math.max(0, ctx.client.getVarpValue(VarPlayerID.COLLECTION_COUNT));
		}
	}

	/** The newest complete look of this plugin session, of any key, or null. Client thread. */
	Complete latestComplete()
	{
		return complete;
	}

	/** Queues a merge into the file as it is on disk when the merge runs (an unreadable file is set aside first). */
	private Future<?> save(ProfileKey key, UnaryOperator<File> change)
	{
		if (!ctx.writes(ctx.config.exportCollectionLog()))
		{
			return null; // read for the upload only: no file
		}
		return ctx.json.writeLater(key, FILE, () ->
		{
			File before;
			try
			{
				before = ctx.json.readOrSetAside(key, FILE, File.class);
			}
			catch (IOException e)
			{
				log.warn("Hans: {} could not be read or set aside; left as it is", FILE, e);
				return null;
			}
			return change.apply(before);
		});
	}

	// ------------------------------------------------------------------ the merge (executor; pure, so tested alone)

	/** Merges one look into the file (null: there is none yet). Adds and updates items; never removes one. */
	static File mergeLook(File file, Look look, Map<Integer, Integer> items)
	{
		File out = normalised(file);
		Instant at = instant(look.at);
		Look complete = out.lastCompleteLook;
		String missingAt = complete != null && isBefore(instant(complete.at), at) ? complete.at : null;
		for (Map.Entry<Integer, Integer> e : items.entrySet())
		{
			Item it = out.items.get(e.getKey());
			if (it == null)
			{
				it = new Item();
				it.quantity = e.getValue();
				it.firstSeen = look.at;
				it.alreadyHad = missingAt == null;
				it.missingAt = missingAt;
				it.lastSeen = look.at;
				out.items.put(e.getKey(), it);
			}
			else if (it.lastSeen == null || !isBefore(at, instant(it.lastSeen)))
			{
				it.quantity = e.getValue(); // the newest look's count
				it.lastSeen = look.at;
			}
		}
		if (out.firstLookAt == null || isBefore(at, instant(out.firstLookAt)))
		{
			out.firstLookAt = look.at;
		}
		if (out.lastLook == null || !isBefore(at, instant(out.lastLook.at)))
		{
			out.lastLook = look;
		}
		if (look.complete && (out.lastCompleteLook == null || !isBefore(at, instant(out.lastCompleteLook.at))))
		{
			out.lastCompleteLook = look;
		}
		out.observedAt = latest(out.observedAt, look.at);
		return out;
	}

	/** Adds one "New item added" chat line (the same name at the same time only once). */
	static File mergeMessage(File file, String name, String at)
	{
		File out = normalised(file);
		for (NewItemMessage m : out.newItemMessages)
		{
			if (name.equals(m.name) && at.equals(m.at))
			{
				return out;
			}
		}
		out.newItemMessages.add(new NewItemMessage(name, at));
		out.observedAt = latest(out.observedAt, at);
		return out;
	}

	/** A file as read (or none) with its collections present and the items in id order. */
	private static File normalised(File file)
	{
		File out = file == null ? new File() : file;
		Map<Integer, Item> items = new TreeMap<>();
		if (out.items != null)
		{
			for (Map.Entry<Integer, Item> e : out.items.entrySet())
			{
				if (e.getKey() != null && e.getValue() != null)
				{
					items.put(e.getKey(), e.getValue());
				}
			}
		}
		out.items = items;
		List<NewItemMessage> messages = new ArrayList<>();
		if (out.newItemMessages != null)
		{
			for (NewItemMessage m : out.newItemMessages)
			{
				if (m != null && m.name != null && m.at != null)
				{
					messages.add(m);
				}
			}
		}
		out.newItemMessages = messages;
		out.format = FORMAT;
		return out;
	}

	private static Instant instant(String s)
	{
		try
		{
			return s == null ? null : Instant.parse(s);
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	/** False when either time is missing or unreadable. */
	private static boolean isBefore(Instant a, Instant b)
	{
		return a != null && b != null && a.isBefore(b);
	}

	private static String latest(String a, String b)
	{
		return a == null || isBefore(instant(a), instant(b)) ? b : a;
	}
}
