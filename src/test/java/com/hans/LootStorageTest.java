package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hans.LootCapture.Count;
import com.hans.LootCapture.PendingKc;
import com.hans.LootCapture.Seen;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import com.google.gson.Gson;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Loot fallback matching, kill-count account tagging and the flagged storage container ids. Synthetic data only. */
public class LootStorageTest
{
	private static final int NPC = 100;

	@Rule
	public final TemporaryFolder temporary = new TemporaryFolder();

	private final class KillCountFixture implements AutoCloseable
	{
		final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		final AtomicInteger writes = new AtomicInteger();
		final Player alpha = player("Alpha");
		final CaptureContext ctx;
		final LootCapture capture;
		final Path root;
		final Path file;

		KillCountFixture() throws Exception
		{
			root = temporary.newFolder().toPath();
			file = root.resolve("accounts").resolve("1").resolve("main").resolve("killcounts.json");
			Client client = proxy(Client.class, (proxy, method, args) ->
				method.getName().equals("getLocalPlayer") ? alpha : method.getName().equals("getAccountHash") ? 1L : null);
			RsbuddyConfig config = proxy(RsbuddyConfig.class, (proxy, method, args) -> method.getName().equals("exportLoot"));
			JsonWriter json = new JsonWriter(executor, new Gson(), () -> Filepath.Unchecked.getRooted(root),
				() -> true, time -> writes.incrementAndGet());
			ctx = new CaptureContext(client, config, json, null, new LoginState(client));
			capture = new LootCapture(ctx);
			LootCapture.KillFile seed = new LootCapture.KillFile();
			seed.killcounts = new TreeMap<>();
			Count count = new Count();
			count.count = 10;
			count.observedAt = "t0";
			seed.killcounts.put("Zulrah", count);
			json.write(ALPHA, "killcounts.json", seed);
			drain();
		}

		void drain() throws Exception
		{
			executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
		}

		void login() throws Exception
		{
			ctx.login.loggedIn();
			for (int tick = 0; tick < LoginState.SETTLE_TICKS; tick++)
			{
				ctx.login.tick(alpha);
			}
			tick();
			tick();
		}

		void tick() throws Exception
		{
			capture.onGameTick(alpha, Instant.now());
			drain();
		}

		void count(String boss, String count)
		{
			capture.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "",
				"Your " + boss + " kill count is: <col=ff0000>" + count + "</col>.", null, 0));
		}

		void logout() throws Exception
		{
			capture.onLogout(Instant.now());
			ctx.login.loggedOut();
			drain();
		}

		@Override
		public void close() throws Exception
		{
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void sameAccountReloginKeepsLatestKillCounts() throws Exception
	{
		for (boolean saveOnLogout : new boolean[]{false, true})
		{
			try (KillCountFixture fixture = new KillCountFixture())
			{
				fixture.login();
				fixture.count("Zulrah", "11");
				if (!saveOnLogout)
				{
					fixture.tick();
				}
				fixture.logout();
				fixture.login();
				fixture.count("Vorkath", "1");
				fixture.tick();
				LootCapture.KillFile saved = fixture.ctx.json.read(ALPHA, "killcounts.json", LootCapture.KillFile.class);
				assertEquals(11, saved.killcounts.get("Zulrah").count);
				assertEquals(1, saved.killcounts.get("Vorkath").count);
			}
		}
	}

	@Test
	public void unchangedOrUnparsableLogoutDoesNotWriteOrReplaceFileWithNull() throws Exception
	{
		for (boolean loadBeforeLogout : new boolean[]{false, true})
		{
			try (KillCountFixture fixture = new KillCountFixture())
			{
				if (loadBeforeLogout)
				{
					fixture.login();
				}
				String before = Files.readString(fixture.file, StandardCharsets.UTF_8);
				int writesBefore = fixture.writes.get();
				fixture.count("Zulrah", "10");
				fixture.count("Vorkath", "999999999999999999999");
				fixture.logout();
				String after = Files.readString(fixture.file, StandardCharsets.UTF_8);
				assertEquals(before, after);
				assertFalse("null".equals(after.trim()));
				// logged out, the two lines go to quarantine (never discarded); logged in, nothing is written
				assertEquals(writesBefore + (loadBeforeLogout ? 0 : 2), fixture.writes.get());
				assertEquals(!loadBeforeLogout, Files.exists(fixture.root.resolve(JsonWriter.QUARANTINE_FILE)));
			}
		}
	}

	@Test
	public void eachServerReportCoversOnlyOneFallback()
	{
		List<Seen> seen = new ArrayList<>(Arrays.asList(new Seen(10, NPC, 0)));
		assertTrue(LootCapture.consumeSeen(seen, NPC, 10, 0)); // the same kill, reported twice: one line
		assertFalse(LootCapture.consumeSeen(seen, NPC, 10, 0)); // a second, distinct kill still gets recorded
		assertTrue(seen.isEmpty());
	}

	@Test
	public void twoReportsCoverTwoFallbacksAndOnlyNearbyOnesMatch()
	{
		List<Seen> seen = new ArrayList<>(Arrays.asList(new Seen(10, NPC, 0), new Seen(11, NPC, 0)));
		assertFalse(LootCapture.consumeSeen(seen, NPC + 1, 10, 0)); // another NPC type
		assertFalse(LootCapture.consumeSeen(seen, NPC, 20, 0)); // too far apart in time
		assertFalse(LootCapture.consumeSeen(seen, NPC, 10, 1)); // another token: the far side of a hop
		assertTrue(LootCapture.consumeSeen(seen, NPC, 10, 0));
		assertTrue(LootCapture.consumeSeen(seen, NPC, 10, 0));
		assertFalse(LootCapture.consumeSeen(seen, NPC, 10, 0));
	}

	@Test
	public void killCountsAreCreditedOnlyToTheirOwnAccount()
	{
		List<PendingKc> pending = new ArrayList<>(Arrays.asList(
			new PendingKc(ALPHA, "Zulrah", "12", "t1"),
			new PendingKc(BETA, "Vorkath", "5", "t2"),
			new PendingKc(ALPHA_LEAGUE, "Vorkath", "9", "t2"),
			new PendingKc(null, "Vorkath", "8", "t2"),
			new PendingKc(BETA, "Zulrah", "7", "t3")));
		Map<String, Count> counts = new TreeMap<>();
		assertTrue(LootCapture.applyKillCounts(counts, pending, BETA));
		assertEquals(2, counts.size());
		assertEquals(7, counts.get("Zulrah").count); // Alpha's 12 never lands on Beta
		assertEquals(5, counts.get("Vorkath").count);
		assertTrue(pending.isEmpty()); // flushed or dropped, never kept for the next player
	}

	@Test
	public void unchangedOrBrokenCountsChangeNothing()
	{
		Map<String, Count> counts = new TreeMap<>();
		List<PendingKc> first = new ArrayList<>(Arrays.asList(new PendingKc(ALPHA, "Zulrah", "12", "t1")));
		assertTrue(LootCapture.applyKillCounts(counts, first, ALPHA));
		List<PendingKc> again = new ArrayList<>(Arrays.asList(
			new PendingKc(ALPHA, "Zulrah", "12", "t2"), new PendingKc(ALPHA, "Vorkath", "x", "t2")));
		assertFalse(LootCapture.applyKillCounts(counts, again, ALPHA));
		assertEquals("t1", counts.get("Zulrah").observedAt);
	}

	@Test
	public void flaggedStorageContainerIdsMatch()
	{
		int lootingBag = 516;
		assertEquals(lootingBag, ItemsCapture.normaliseContainerId(lootingBag + 0x8000));
		assertEquals(lootingBag, ItemsCapture.normaliseContainerId(lootingBag));
		assertEquals(93, ItemsCapture.normaliseContainerId(93));
	}

	private static final AtomicInteger TASKS = new AtomicInteger();
	private static final ProfileKey ALPHA = new ProfileKey(1L, GameMode.MAIN, "Alpha");
	private static final ProfileKey ALPHA_LEAGUE = new ProfileKey(1L, "seasonal-demonic_pacts_league", "Alpha");
	private static final ProfileKey BETA = new ProfileKey(2L, GameMode.MAIN, "Beta");

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler h)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, h);
	}

	private static CaptureContext context(Client client, boolean exportLoot)
	{
		ScheduledExecutorService executor = proxy(ScheduledExecutorService.class, (p, m, a) ->
		{
			if (m.getName().equals("execute") || m.getName().equals("submit"))
			{
				TASKS.incrementAndGet();
			}
			return null;
		});
		JsonWriter json = new JsonWriter(executor, new Gson(), () -> null, () -> true, t -> { });
		RsbuddyConfig config = proxy(RsbuddyConfig.class, (p, m, a) -> m.getName().equals("exportLoot") ? exportLoot : false);
		return new CaptureContext(client, config, json, null, new LoginState(client));
	}

	private static Player player(String name)
	{
		return proxy(Player.class, (p, m, a) -> m.getName().equals("getName") ? name : null);
	}

	@Test
	public void flaggedStorageContainerIsWrittenOnTheTick()
	{
		ItemContainer empty = proxy(ItemContainer.class, (p, m, a) -> m.getName().equals("getItems") ? new net.runelite.api.Item[0] : null);
		Client client = proxy(Client.class, (p, m, a) ->
			m.getName().equals("getItemContainer") && (Integer) a[0] == (637 | 0x8000) ? empty
				: m.getName().equals("getAccountHash") ? 1L : null);
		CaptureContext ctx = context(client, true);
		ItemsCapture cap = new ItemsCapture(ctx, "costume_room", 637, () -> true, "key");
		TASKS.set(0);
		ctx.login.loggedIn();
		cap.onLogin();
		cap.onGameTick(player("Alpha"), Instant.now());
		assertEquals(0, TASKS.get()); // before settle there is no key: it waits, still dirty
		for (int i = 0; i < LoginState.SETTLE_TICKS; i++)
		{
			ctx.login.tick(player("Alpha"));
		}
		cap.onGameTick(player("Alpha"), Instant.now());
		assertEquals(1, TASKS.get());
	}

	@Test
	public void killCountBeforeTheFileLoadedIsSavedOnLogout()
	{
		Player alpha = player("Alpha");
		Client client = proxy(Client.class, (p, m, a) -> m.getName().equals("getLocalPlayer") ? alpha
			: m.getName().equals("getAccountHash") ? 1L : null);
		CaptureContext ctx = context(client, true);
		ctx.login.loggedIn();
		for (int i = 0; i < LoginState.SETTLE_TICKS; i++)
		{
			ctx.login.tick(alpha);
		}
		LootCapture cap = new LootCapture(ctx);
		cap.onChatMessage(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", "Your Zulrah kill count is: <col=ff0000>12</col>.", null, 0));
		TASKS.set(0);
		java.util.concurrent.Future<?> f = cap.onLogout(Instant.now());
		assertEquals(1, TASKS.get()); // composed and written on the executor, never dropped
		assertTrue(f == null || !f.isCancelled());
	}

	@Test
	public void mergeKeepsTheSavedFileWhenNothingIsInMemory()
	{
		LootCapture.KillFile disk = new LootCapture.KillFile();
		disk.killcounts = new TreeMap<>();
		Count old = new Count();
		old.count = 5;
		old.observedAt = "t0";
		disk.killcounts.put("Vorkath", old);
		List<PendingKc> pending = new ArrayList<>(Arrays.asList(new PendingKc(ALPHA, "Zulrah", "12", "t1")));
		LootCapture.KillFile out = LootCapture.mergeKillCounts(null, disk, pending, new ArrayList<>(), ALPHA, "now");
		assertEquals(5, out.killcounts.get("Vorkath").count);
		assertEquals(12, out.killcounts.get("Zulrah").count);
	}
}
