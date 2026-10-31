package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.runelite.api.Item;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;

/**
 * Plugin Hub audit (card #123): the hub's thread hand-off and fault isolation, position coalescing, and the worst-case
 * disk activity of every file capture at once. Synthetic account only. The measured numbers are written to
 * build/reports/hub-audit/disk.txt and recorded in docs/plans/hub-audit-deep.md.
 */
public class HubAuditTest
{
	/** Every file switch on; the upload and sign-in off (they write no files of their own). */
	private static RsbuddyConfig allOn()
	{
		return (RsbuddyConfig) Proxy.newProxyInstance(RsbuddyConfig.class.getClassLoader(),
			new Class<?>[]{RsbuddyConfig.class}, (self, m, args) ->
			{
				if (m.getReturnType() == boolean.class)
				{
					return !"uploadLive".equals(m.getName()) && !"signIn".equals(m.getName());
				}
				return null;
			});
	}

	// ------------------------------------------------------------------ the hub

	@Test
	public void aCaptureThatThrowsDoesNotStopTheOthers() throws IOException
	{
		GameFake g = new GameFake();
		List<String> ran = new ArrayList<>();
		g.captures.add(new Capture()
		{
			@Override
			public void onGameTick(Player local, Instant now)
			{
				throw new IllegalStateException("a script that is gone after a game update");
			}

			@Override
			public Future<?> onLogout(Instant now)
			{
				throw new IllegalStateException("again");
			}
		});
		g.captures.add(new Capture()
		{
			@Override
			public void onGameTick(Player local, Instant now)
			{
				ran.add("tick");
			}

			@Override
			public Future<?> onLogout(Instant now)
			{
				ran.add("logout");
				return null;
			}
		});
		g.logIn();
		g.tick();
		g.logOut();
		assertTrue(ran.contains("tick"));
		assertEquals("logout", ran.get(ran.size() - 1));
	}

	@Test
	public void closingHandsTheLogoutToTheClientThreadAndWaitsForItsWrites() throws IOException
	{
		GameFake g = new GameFake();
		CaptureContext ctx = new CaptureContext(g.client, allOn(), g.json, null, g.login, g.clientThread);
		g.captures.add(new PositionCapture(ctx));
		List<String> order = new ArrayList<>();
		g.captures.add(new Capture()
		{
			@Override
			public Future<?> onLogout(Instant now)
			{
				order.add("logout");
				return g.json.writeLater(g.login.logoutKey(), "probe.json", () -> "x");
			}
		});
		g.logIn();
		g.tick();

		List<Runnable> clientThread = new ArrayList<>();
		List<Future<?>> waited = new ArrayList<>();
		g.hub.closing(g.now, clientThread::add, waited::add); // as on Swing's thread: nothing runs yet
		assertTrue(order.isEmpty());
		assertEquals(1, waited.size());
		assertFalse("the client waits for the hand-off", waited.get(0).isDone());

		clientThread.forEach(Runnable::run); // the client thread's next turn
		assertEquals(1, order.size());
		assertTrue(waited.get(0).isDone());
		// the returned write, then the barrier after every logout write, added before the hand-off completed
		assertEquals(3, waited.size());
		assertFalse(waited.get(1).isDone());
		assertFalse(waited.get(2).isDone());
		g.executor.runAll();
		assertTrue(waited.stream().allMatch(Future::isDone));
	}

	// ------------------------------------------------------------------ position

	private static GameFake walker() throws IOException
	{
		GameFake g = new GameFake();
		CaptureContext ctx = new CaptureContext(g.client, allOn(), g.json, null, g.login, g.clientThread);
		g.captures.add(new PositionCapture(ctx));
		g.logIn();
		g.executor.runAll();
		return g;
	}

	private static int positionX(GameFake g) throws IOException
	{
		Map<?, ?> m = g.read("position.json", Map.class);
		return ((Number) m.get("x")).intValue();
	}

	@Test
	public void aWalkIsWrittenAtMostEveryFewTicksAndTheLastTileAtLogout() throws IOException
	{
		GameFake g = walker();
		int writes = 0;
		for (int i = 1; i <= 20; i++)
		{
			g.at = new WorldPoint(3200 + i, 3200, 0);
			g.tick();
			writes += g.executor.pendingCount();
			g.executor.runAll();
		}
		assertTrue("20 steps, " + writes + " writes", writes <= 20 / PositionCapture.EVERY_TICKS + 1);
		assertTrue(positionX(g) >= 3220 - PositionCapture.EVERY_TICKS);

		g.at = new WorldPoint(3221, 3200, 0); // one more step, then out
		g.tick();
		g.logOut();
		g.executor.runAll();
		assertEquals(3221, positionX(g));
	}

	@Test
	public void standingStillWritesTheLastTileOnceTheGapIsOver() throws IOException
	{
		GameFake g = walker();
		g.at = new WorldPoint(3201, 3200, 0);
		g.tick(); // may be written at once (the gap since login is over)
		g.at = new WorldPoint(3202, 3200, 0);
		g.ticks(PositionCapture.EVERY_TICKS + 1); // stopped here
		g.executor.runAll();
		assertEquals(3202, positionX(g));
		g.ticks(20);
		assertEquals("nothing more while still", 0, g.executor.pendingCount());
	}

	@Test
	public void aWorldHopIsWrittenOnItsTick() throws IOException
	{
		GameFake g = walker();
		g.at = new WorldPoint(3201, 3200, 0);
		g.tick();
		g.executor.runAll();
		g.hop(302);
		g.tick(); // the first tick there, not EVERY_TICKS later
		g.executor.runAll();
		assertEquals(302, ((Number) g.read("position.json", Map.class).get("world")).intValue());
	}

	@Test
	public void aBankChangingEveryTickIsWrittenEveryFewTicksAndItsLastReadingAtLogout() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new ItemsCapture(g.ctx, "bank", InventoryID.BANK, () -> true, "exportBank"));
		g.containers.put(InventoryID.BANK, new Item[]{new Item(-1, 0)});
		g.logIn();
		g.executor.runAll();
		int writes = 0;
		Instant last = null;
		for (int i = 0; i < 10; i++)
		{
			g.setContainer(InventoryID.BANK, new Item(-1, 0)); // a deposit or withdrawal every tick
			g.tick();
			last = g.now;
			writes += g.executor.pendingCount();
			g.executor.runAll();
		}
		assertTrue("10 changes, " + writes + " writes", writes <= 10 / ItemsCapture.EVERY_TICKS + 1);
		g.logOut();
		g.executor.runAll();
		assertEquals(last.toString(), g.read("bank.json", Map.class).get("exported_at"));
	}

	/** Closes the client with the executor still busy: true when every future the client waits for is done. */
	private static boolean closeWithBusyExecutor(GameFake g)
	{
		List<Runnable> clientThread = new ArrayList<>();
		List<Future<?>> waited = new ArrayList<>();
		g.hub.closing(g.now, clientThread::add, waited::add);
		clientThread.forEach(Runnable::run);
		assertTrue("a held reading was queued", g.executor.pendingCount() > 0);
		return waited.stream().allMatch(Future::isDone);
	}

	@Test
	public void closingWaitsForAHeldPositionToBeWritten() throws IOException
	{
		GameFake g = walker();
		g.at = new WorldPoint(3201, 3200, 0);
		g.tick(); // written
		g.executor.runAll();
		g.at = new WorldPoint(3202, 3200, 0);
		g.tick(); // held back by the gap
		assertEquals(0, g.executor.pendingCount());
		assertFalse("the client must wait for the last tile", closeWithBusyExecutor(g));
		g.executor.runAll();
		assertEquals(3202, positionX(g));
	}

	@Test
	public void closingWaitsForAHeldBankToBeWritten() throws IOException
	{
		GameFake g = new GameFake();
		g.captures.add(new ItemsCapture(g.ctx, "bank", InventoryID.BANK, () -> true, "exportBank"));
		g.containers.put(InventoryID.BANK, new Item[]{new Item(-1, 0)});
		g.logIn();
		g.executor.runAll();
		g.setContainer(InventoryID.BANK, new Item(-1, 0));
		g.tick(); // held back by the gap since the login's write
		Instant held = g.now;
		assertEquals(0, g.executor.pendingCount());
		assertFalse("the client must wait for the last bank reading", closeWithBusyExecutor(g));
		g.executor.runAll();
		assertEquals(held.toString(), g.read("bank.json", Map.class).get("exported_at"));
	}

	@Test
	public void aRelogOnTheTileLastWrittenBeforeTheFlushIsWrittenAgain() throws IOException
	{
		GameFake g = walker();
		g.ticks(PositionCapture.EVERY_TICKS); // the gap since the login's write is over
		g.at = new WorldPoint(3199, 3200, 0);
		g.tick(); // written at once
		g.at = new WorldPoint(3200, 3200, 0);
		g.ticks(PositionCapture.EVERY_TICKS); // held, then written on the gap's last tick
		g.executor.runAll();
		assertEquals(3200, positionX(g));
		g.at = new WorldPoint(3201, 3200, 0);
		g.tick(); // held
		assertEquals(0, g.executor.pendingCount());
		g.logOut(); // the held 3201 is written
		g.executor.runAll();
		assertEquals(3201, positionX(g));

		g.at = new WorldPoint(3200, 3200, 0); // same account, same world, back on the tile written before
		g.logIn();
		g.ticks(10);
		g.executor.runAll();
		assertEquals(3200, positionX(g));
	}

	// ------------------------------------------------------------------ worst-case disk activity

	/** Counts the disk tasks each phase queues and the bytes they write, running the executor after every tick. */
	private static final class Meter
	{
		final GameFake g;
		final StringBuilder report = new StringBuilder();
		int tasks;
		int maxPerTick;
		int maxQueue;
		long bytes;
		int ticks;

		Meter(GameFake g)
		{
			this.g = g;
		}

		void tick()
		{
			g.tick();
			ticks++;
			drain();
		}

		void drain()
		{
			int queued = g.executor.pendingCount();
			maxPerTick = Math.max(maxPerTick, queued);
			while (g.executor.pendingCount() > 0)
			{
				maxQueue = Math.max(maxQueue, g.executor.pendingCount());
				tasks++; // each task run, including any a task queued in turn
				Map<Path, long[]> before = files();
				g.executor.runOne();
				Map<Path, long[]> after = files();
				for (Map.Entry<Path, long[]> f : after.entrySet())
				{
					long[] was = before.get(f.getKey());
					long[] now = f.getValue();
					if (was == null)
					{
						bytes += now[0];
					}
					else if (was[1] != now[1] || was[0] != now[0])
					{
						bytes += f.getKey().toString().endsWith(".jsonl") ? Math.max(0, now[0] - was[0]) : now[0];
					}
				}
			}
		}

		private Map<Path, long[]> files()
		{
			Map<Path, long[]> out = new HashMap<>();
			try (Stream<Path> s = Files.walk(g.dir))
			{
				s.filter(Files::isRegularFile).forEach(p ->
				{
					try
					{
						out.put(p, new long[]{Files.size(p), Files.getLastModifiedTime(p).to(TimeUnit.MICROSECONDS)});
					}
					catch (IOException e)
					{
						throw new IllegalStateException(e);
					}
				});
			}
			catch (IOException e)
			{
				throw new IllegalStateException(e);
			}
			return out;
		}

		void phase(String name)
		{
			report.append(String.format("%-34s ticks %4d  disk tasks %5d  max/tick %3d  max queue %3d  bytes %8d%n",
				name, ticks, tasks, maxPerTick, maxQueue, bytes));
			tasks = 0;
			maxPerTick = 0;
			maxQueue = 0;
			bytes = 0;
			ticks = 0;
		}
	}

	@Test
	public void worstCaseDiskActivityStaysBounded() throws IOException
	{
		GameFake g = new GameFake();
		CaptureContext ctx = new CaptureContext(g.client, allOn(), g.json, null, g.login, g.clientThread);
		// every file capture the plugin registers, in its order; the item containers hold only empty slots, as the
		// fake has no item names (a real bank's file is sized below)
		CharacterCapture character = new CharacterCapture(ctx);
		SessionCapture sessions = new SessionCapture(ctx, character, (a, s, x) -> { });
		g.captures.add(character);
		g.captures.add(sessions);
		g.captures.add(new QuestsCapture(ctx));
		g.captures.add(ProgressCaptures.diaries(ctx));
		g.captures.add(ProgressCaptures.combatAchievements(ctx));
		g.captures.add(new CombatAchievementsCapture(ctx));
		g.captures.add(new ItemsCapture(ctx, "inventory", InventoryID.INV, () -> true, "exportItems"));
		g.captures.add(new ItemsCapture(ctx, "equipment", InventoryID.WORN, () -> true, "exportItems"));
		g.captures.add(new ItemsCapture(ctx, "bank", InventoryID.BANK, () -> true, "exportBank"));
		g.captures.add(new ItemGainsCapture(ctx, sessions));
		g.captures.add(new GeOffersCapture(ctx));
		g.captures.add(new GameQuestsCapture(ctx));
		g.captures.add(new PositionCapture(ctx));
		g.captures.add(new AccountCapture(ctx));
		g.captures.add(new VarsCapture(ctx));
		g.captures.add(new FarmingCapture(ctx));
		g.captures.add(new BirdHousesCapture(ctx));
		g.captures.add(new LootCapture(ctx));
		g.captures.add(new RunePouchCapture(ctx, () -> true));
		g.captures.add(new QuiverCapture(ctx, () -> true));
		g.captures.add(new PotionStorageCapture(ctx, () -> true));
		g.captures.add(new AppearanceCapture(ctx));
		g.captures.add(new CollectionLogCapture(ctx));
		g.containers.put(InventoryID.INV, new Item[]{new Item(-1, 0)});
		g.containers.put(InventoryID.WORN, new Item[]{new Item(-1, 0)});
		g.xp = 1_000_000;
		Meter m = new Meter(g);

		g.hub.loggedIn(g.now);
		for (int i = 0; i < 12; i++)
		{
			m.tick();
		}
		m.phase("login (12 ticks)");

		for (int i = 1; i <= 100; i++)
		{
			g.at = new WorldPoint(3200 + 2 * i, 3200, 0); // running: two tiles a tick
			m.tick();
		}
		m.phase("running 100 ticks (60 s)");

		for (int i = 0; i < 100; i++)
		{
			m.tick();
		}
		m.phase("standing 100 ticks");

		g.openInterface(InterfaceID.BANKMAIN);
		for (int i = 0; i < 20; i++)
		{
			g.setContainer(InventoryID.BANK, new Item(-1, 0), new Item(-1, i)); // a change every tick
			g.setContainer(InventoryID.INV, new Item(-1, 0), new Item(-1, i));
			m.tick();
		}
		g.closeInterface(InterfaceID.BANKMAIN);
		m.phase("bank, a change every tick (20)");

		g.widget(InterfaceID.COLLECTION, true);
		g.openInterface(InterfaceID.COLLECTION);
		g.scriptPost(CollectionLogCapture.SETUP_SCRIPT);
		g.runLater();
		for (int i = 0; i < 1_500; i++)
		{
			g.scriptPre(CollectionLogCapture.ITEM_SCRIPT, InterfaceID.Collection.ITEMS_CONTENTS, 20_000 + i, 1);
		}
		for (int i = 0; i < CollectionLogCapture.QUIET_TICKS + 2; i++)
		{
			m.tick();
		}
		g.widget(InterfaceID.COLLECTION, false);
		m.phase("collection log, 1,500 items");

		g.logOut();
		m.drain();
		m.phase("logout");

		// a full bank's file (1,410 slots, typical name lengths), written once per tick with a change while open
		int[] ids = new int[1_410];
		int[] qtys = new int[1_410];
		for (int i = 0; i < ids.length; i++)
		{
			ids[i] = 1_000 + i;
			qtys[i] = 1_000_000 + i;
		}
		String bank = new com.google.gson.Gson().toJson(ToolkitData.items(g.now.toString(), "bank", ids, qtys,
			id -> "Rune platebody (g) " + id));
		m.report.append(String.format("full bank.json (1,410 slots)       bytes %8d%n", bank.length()));

		Path out = Paths.get("build", "reports", "hub-audit", "disk.txt");
		Files.createDirectories(out.getParent());
		Files.write(out, m.report.toString().getBytes(StandardCharsets.UTF_8));
		assertNotNull(m.report);
	}
}
