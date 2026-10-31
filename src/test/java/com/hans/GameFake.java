package com.hans;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.HashTable;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.ScriptEvent;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.util.Filepath;
import net.runelite.http.api.worlds.WorldResult;

/**
 * A stand-in for the game in flow tests: a client and a player that answer from plain fields, a writer into a temp
 * folder, and an executor that runs only when told to, so the order of the background writes can be chosen.
 * Logins, ticks, hops and logouts go through the plugin's own {@link CaptureHub}.
 * Test code only (the dynamic proxies never ship in the plugin).
 */
final class GameFake
{
	final Map<Integer, Integer> varbits = new HashMap<>();
	final Map<Integer, Integer> varps = new HashMap<>();
	final Map<Integer, Integer> questStatus = new HashMap<>();
	final Map<Integer, Item[]> containers = new HashMap<>();
	final Map<Integer, String> itemNames = new HashMap<>(); // others are named "Item <id>"
	final Map<Integer, int[][]> enums = new HashMap<>(); // enum id to {keys, int values}; others are not loaded
	final Map<Integer, String> widgets = new HashMap<>(); // open widgets: component id to text; others are closed
	final Map<Integer, int[]> varTriggers = new HashMap<>(); // a widget's var transmit triggers
	// script id to (its last argument to the int it returns); scripts not listed answer the quest status
	final Map<Integer, Map<Integer, Integer>> scripts = new HashMap<>();
	final Set<Integer> openInterfaces = new HashSet<>();
	int defaultVarbit;
	int xp; // every skill's experience
	int world = 301;
	int revision = 230; // the game client's
	Set<WorldType> worldTypes = EnumSet.of(WorldType.MEMBERS);
	long accountHash = 1L;
	String name = "Tester";
	WorldPoint at = new WorldPoint(3200, 3200, 0);
	Instant now = Instant.parse("2026-01-01T00:00:00Z");
	private final int[] intStack = new int[4];
	// what the captures asked of the client: each menuAction as "param1 option", each argument-less runScript's id
	final List<String> menuActions = new ArrayList<>();
	final List<Integer> scriptsRun = new ArrayList<>();

	final Path dir;
	final ManualExecutor executor = new ManualExecutor();
	// invokeLater work waits here until a test runs it (the real client thread runs it after the current script)
	final List<Runnable> later = new ArrayList<>();
	final net.runelite.client.callback.ClientThread clientThread = new net.runelite.client.callback.ClientThread()
	{
		@Override
		public void invokeLater(Runnable r)
		{
			later.add(r);
		}
	};
	final Client client = proxy(Client.class, this::client);
	final Player player = proxy(Player.class, this::player);
	final RsbuddyConfig config = proxy(RsbuddyConfig.class, (m, a) -> null);
	// the world list RuneLite keeps: each Deadman world's activity (the season's name). A refresh gives a new list at
	// once; `listNanos` is the clock the list's age is judged by
	final Map<Integer, String> activities = new HashMap<>();
	WorldResult worldList;
	int listRefreshes;
	long listNanos;
	final WorldSeasons seasons = new WorldSeasons(() -> worldList, this::refreshWorldList, () -> listNanos);
	final LoginState login = new LoginState(client, seasons);
	// the plugin's real WorldsFetch handler, so a fetched list reaches the login the way it does in production
	final RsbuddyPlugin plugin = pluginFor(login);
	final JsonWriter json;
	final CaptureContext ctx;
	final CaptureHub hub;
	final List<Capture> captures;
	private final Gson gson = new Gson();

	GameFake() throws IOException
	{
		dir = Files.createTempDirectory("rsbuddy-test");
		json = new JsonWriter(executor, gson, () -> Filepath.Unchecked.getRooted(dir), () -> true, t -> { });
		ctx = new CaptureContext(client, config, json, null, login, clientThread);
		hub = new CaptureHub(ctx);
		captures = hub.captures;
	}

	/** Logs in and ticks until the login has settled (or 10 ticks, for a login that never settles). */
	void logIn()
	{
		hub.loggedIn(now);
		settle();
	}

	/** Ticks until the login has settled, at most 10 ticks. */
	void settle()
	{
		for (int i = 0; i < 10 && !login.settled(); i++)
		{
			tick();
		}
	}

	/** Runs what was queued with invokeLater, as the client thread does once the current script is over. */
	void runLater()
	{
		List<Runnable> now = new ArrayList<>(later);
		later.clear();
		now.forEach(Runnable::run);
	}

	/** One game tick, a second after the last. */
	void tick()
	{
		now = now.plusSeconds(1);
		hub.tick(player, now);
	}

	/** A world hop: HOPPING, then the new world's types, then LOGGED_IN (no tick in between). */
	void hop(int toWorld, WorldType... types)
	{
		hub.hopping();
		world = toWorld;
		worldTypes = types.length == 0 ? EnumSet.noneOf(WorldType.class) : EnumSet.of(types[0], types);
		hub.loggedIn(now);
	}

	private static RsbuddyPlugin pluginFor(LoginState login)
	{
		RsbuddyPlugin p = new RsbuddyPlugin();
		p.login = login;
		return p;
	}

	/** RuneLite fetches the world list again: a new list with every Deadman world's activity as it is now. */
	void refreshWorldList()
	{
		listRefreshes++;
		List<net.runelite.http.api.worlds.World> list = new ArrayList<>();
		for (Map.Entry<Integer, String> e : activities.entrySet())
		{
			list.add(net.runelite.http.api.worlds.World.builder().id(e.getKey())
				.types(EnumSet.of(net.runelite.http.api.worlds.WorldType.DEADMAN)).activity(e.getValue()).build());
		}
		worldList = new WorldResult();
		worldList.setWorlds(list);
		plugin.onWorldsFetch(new net.runelite.client.events.WorldsFetch(worldList)); // RuneLite's event
	}

	/** A region load: LOADING, then LOGGED_IN on the same world (no tick in between). */
	void loadRegion()
	{
		hub.hopping();
		hub.loggedIn(now);
	}

	/** Sets an item container's contents and tells the captures, as the game would. */
	void setContainer(int id, Item... items)
	{
		containers.put(id, items);
		ItemContainerChanged e = new ItemContainerChanged(id, container(id));
		for (Capture c : captures)
		{
			c.onItemContainerChanged(e);
		}
	}

	private ItemContainer container(int id)
	{
		Item[] items = containers.get(id);
		return items == null ? null : proxy(ItemContainer.class, (m, x) -> "getItems".equals(m) ? items : null);
	}

	/** A menu option chosen on an item (the target as the game shows it, colour tags included). */
	void menu(String option, String target, int itemId)
	{
		menu(option, target, itemId, 0);
	}

	/** A menu option chosen on an item or a widget (param1: the widget's component id, else 0). */
	void menu(String option, String target, int itemId, int param1)
	{
		MenuEntry entry = proxy(MenuEntry.class, (m, x) -> "getOption".equals(m) ? option
			: "getTarget".equals(m) ? target : "getItemId".equals(m) ? (Object) itemId
			: "getParam1".equals(m) ? (Object) param1 : null);
		MenuOptionClicked e = new MenuOptionClicked(entry);
		for (Capture c : captures)
		{
			c.onMenuOptionClicked(e);
		}
	}

	/** An interface opened so its root widget is reported showing. */
	void openInterface(int group)
	{
		openInterfaces.add(group);
	}

	/** Closes an open interface. */
	void closeInterface(int group)
	{
		openInterfaces.remove(group);
	}

	/** A game chat line, as the game would send it. */
	void chat(String message)
	{
		ChatMessage e = new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", message, null, 0);
		for (Capture c : captures)
		{
			c.onChatMessage(e);
		}
	}

	/** The game's own loot report for a kill (no items, so no item names are looked up). */
	void serverLoot(int npcId, String npcName)
	{
		NPCComposition npc = proxy(NPCComposition.class, (m, x) -> "getId".equals(m) ? (Object) npcId
			: "getName".equals(m) ? npcName : null);
		ServerNpcLoot e = new ServerNpcLoot(npc, Collections.emptyList());
		for (Capture c : captures)
		{
			c.onServerNpcLoot(e);
		}
	}

	/** RuneLite's drop inferred from the ground (the fallback), for a kill. */
	void groundLoot(int npcId, String npcName)
	{
		NPC npc = proxy(NPC.class, (m, x) -> "getId".equals(m) ? (Object) npcId
			: "getName".equals(m) ? npcName : null);
		NpcLootReceived e = new NpcLootReceived(npc, Collections.emptyList());
		for (Capture c : captures)
		{
			c.onNpcLootReceived(e);
		}
	}

	/** A client script about to run with these arguments, as the game would report it. */
	void scriptPre(int scriptId, Object... args)
	{
		ScriptPreFired e = new ScriptPreFired(scriptId);
		e.setScriptEvent(proxy(ScriptEvent.class, (m, x) -> "getArguments".equals(m) ? args : null));
		for (Capture c : captures)
		{
			c.onScriptPreFired(e);
		}
	}

	/** A client script that has run. */
	void scriptPost(int scriptId)
	{
		ScriptPostFired e = new ScriptPostFired(scriptId);
		for (Capture c : captures)
		{
			c.onScriptPostFired(e);
		}
	}

	/** An interface opened ({@code open}) or closed. */
	void widget(int groupId, boolean open)
	{
		WidgetLoaded loaded = new WidgetLoaded();
		loaded.setGroupId(groupId);
		WidgetClosed closed = new WidgetClosed(groupId, 0, false);
		for (Capture c : captures)
		{
			if (open)
			{
				c.onWidgetLoaded(loaded);
			}
			else
			{
				c.onWidgetClosed(closed);
			}
		}
	}

	void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick();
		}
	}

	void logOut()
	{
		now = now.plusSeconds(1);
		hub.loggedOut(now);
	}

	/** Sets a varbit and tells the captures, as the game would. */
	void setVarbit(int id, int value)
	{
		varbits.put(id, value);
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(id);
		e.setVarpId(-1);
		e.setValue(value);
		for (Capture c : captures)
		{
			c.onVarbitChanged(e);
		}
	}

	/** Sets a varp and tells the captures, as the game would. */
	void setVarp(int id, int value)
	{
		varps.put(id, value);
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(-1);
		e.setVarpId(id);
		e.setValue(value);
		for (Capture c : captures)
		{
			c.onVarbitChanged(e);
		}
	}

	/** Sets every skill's experience and tells the captures, as the game would. */
	void setXp(int value)
	{
		xp = value;
		StatChanged e = new StatChanged(Skill.ATTACK, value, 1, 1);
		for (Capture c : captures)
		{
			c.onStatChanged(e);
		}
	}

	/** {@code accounts/<hash>/<mode>/} under the temp plugin folder. */
	Path folder(long hash, String mode)
	{
		return dir.resolve(JsonWriter.ACCOUNTS).resolve(Long.toString(hash)).resolve(mode);
	}

	/** A file of the account and world mode the fake is on now; null when absent. */
	<T> T read(String file, Class<T> type) throws IOException
	{
		return readIn(accountHash, GameMode.current(client, seasons), file, type);
	}

	<T> T readIn(long hash, String mode, String file, Class<T> type) throws IOException
	{
		Path p = folder(hash, mode).resolve(file);
		return Files.exists(p) ? gson.fromJson(new String(Files.readAllBytes(p), StandardCharsets.UTF_8), type) : null;
	}

	void write(long hash, String mode, String file, Object data) throws IOException
	{
		Files.createDirectories(folder(hash, mode));
		Files.write(folder(hash, mode).resolve(file), gson.toJson(data).getBytes(StandardCharsets.UTF_8));
	}

	private Object client(String method, Object[] a)
	{
		switch (method)
		{
			case "getVarbitValue":
				return varbits.getOrDefault((Integer) a[0], defaultVarbit);
			case "getVarpValue":
				return varps.getOrDefault((Integer) a[0], 0);
			case "getWorld":
				return world;
			case "getRevision":
				return revision;
			case "getAccountHash":
				return accountHash;
			case "getItemContainer":
				return container((Integer) a[0]);
			case "getEnum":
			{
				int[][] e = enums.get((Integer) a[0]);
				return e == null ? null : proxy(EnumComposition.class, (m, x) -> "getKeys".equals(m) ? e[0]
					: "getIntVals".equals(m) ? e[1] : "size".equals(m) ? (Object) e[0].length
					: "getIntValue".equals(m) ? (Object) enumValue(e, (Integer) x[0]) : null);
			}
			case "getItemDefinition":
			{
				String itemName = itemNames.getOrDefault((Integer) a[0], "Item " + a[0]);
				return proxy(ItemComposition.class, (m, x) -> "getName".equals(m) ? itemName : null);
			}
			case "getRealSkillLevel":
			case "getBoostedSkillLevel":
				return 1;
			case "getSkillExperience":
				return xp;
			case "getWorldType":
				return worldTypes.isEmpty() ? EnumSet.noneOf(WorldType.class) : EnumSet.copyOf(worldTypes);
			case "getComponentTable":
				return proxy(HashTable.class, (m, x) -> "iterator".equals(m) ? Collections.emptyIterator() : null);
			case "getDBTableRows":
				return Arrays.asList(1, 2);
			case "getDBTableField":
				return new Object[]{"Quest " + a[0]};
			case "menuAction":
				menuActions.add(a[1] + " " + a[5]);
				return null;
			case "runScript":
			{
				Object[] scriptArgs = (Object[]) a[0];
				if (scriptArgs.length < 2)
				{
					scriptsRun.add((Integer) scriptArgs[0]);
					return null;
				}
				int row = (Integer) scriptArgs[1];
				Map<Integer, Integer> answers = scripts.get((Integer) scriptArgs[0]);
				intStack[0] = answers != null ? answers.getOrDefault(row, 0) : questStatus.getOrDefault(row, 1);
				return null;
			}
			case "getIntStack":
				return intStack;
			case "getWidget":
			{
				int component = a.length == 2 ? (Integer) a[0] << 16 | (Integer) a[1] : (Integer) a[0];
				String text = widgets.get(component);
				if (text != null)
				{
					return proxy(Widget.class, (m, x) -> "getText".equals(m) ? text
						: "getVarTransmitTrigger".equals(m) ? varTriggers.get(component) : null);
				}
				int group = component >> 16;
				if (!openInterfaces.contains(group))
				{
					return null;
				}
				return proxy(Widget.class, (m, x) -> "isHidden".equals(m) ? Boolean.FALSE : null);
			}
			default:
				return null;
		}
	}

	/** An enum's value for a key; -1 (as the game's default) when the key is not in it. */
	private static int enumValue(int[][] e, int key)
	{
		for (int i = 0; i < e[0].length; i++)
		{
			if (e[0][i] == key)
			{
				return e[1][i];
			}
		}
		return -1;
	}

	private Object player(String method, Object[] a)
	{
		switch (method)
		{
			case "getName":
				return name;
			case "getWorldLocation":
				return at;
			case "getCombatLevel":
				return 3;
			default:
				return null;
		}
	}

	interface Answer
	{
		Object answer(String method, Object[] args);
	}

	/** An interface whose methods answer from the given function; unanswered ones return 0, false or null. */
	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Answer answer)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, m, args) ->
		{
			if (m.getDeclaringClass() == Object.class)
			{
				return "equals".equals(m.getName()) ? self == args[0] : "hashCode".equals(m.getName())
					? System.identityHashCode(self) : type.getSimpleName();
			}
			Object out = answer.answer(m.getName(), args);
			if (out != null)
			{
				return out;
			}
			Class<?> r = m.getReturnType();
			if (r == boolean.class)
			{
				// the config switches are all on
				return type == RsbuddyConfig.class;
			}
			if (r == int.class || r == short.class || r == byte.class || r == char.class)
			{
				return r == char.class ? (Object) (char) 0 : r == short.class ? (Object) (short) 0
					: r == byte.class ? (Object) (byte) 0 : (Object) 0;
			}
			if (r == long.class)
			{
				return 0L;
			}
			if (r == float.class)
			{
				return 0f;
			}
			if (r == double.class)
			{
				return 0d;
			}
			return null;
		});
	}

	/** Runs the submitted work only when {@link #runAll} is called, in submission order. */
	static final class ManualExecutor extends AbstractExecutorService implements ScheduledExecutorService
	{
		private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

		int pendingCount()
		{
			return queue.size();
		}

		/** Runs the oldest queued task only. */
		void runOne()
		{
			Runnable r = queue.poll();
			if (r != null)
			{
				r.run();
			}
		}

		/** Runs the newest queued task only: a later task finishing before an earlier one. */
		void runLast()
		{
			Runnable r = queue.pollLast();
			if (r != null)
			{
				r.run();
			}
		}

		void runAll()
		{
			Runnable r;
			while ((r = queue.poll()) != null)
			{
				r.run();
			}
		}

		@Override
		public void execute(Runnable command)
		{
			queue.add(command);
		}

		@Override
		public void shutdown()
		{
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			return new ArrayList<>(queue);
		}

		@Override
		public boolean isShutdown()
		{
			return false;
		}

		@Override
		public boolean isTerminated()
		{
			return false;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit)
		{
			return true;
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
			TimeUnit unit)
		{
			throw new UnsupportedOperationException();
		}
	}
}
