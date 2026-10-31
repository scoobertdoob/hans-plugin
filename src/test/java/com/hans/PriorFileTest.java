package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.util.Filepath;
import org.junit.Test;

/**
 * PriorFile across a mode switch, with the executor run by hand so a load can finish at any point. The executor only
 * publishes its result (key, generation, value); the client thread decides whether it is current. So the race the
 * review found (main's load passing its check, pausing, and publishing after the switch to the league) comes down to
 * main's result arriving after {@code ensure(league)}, in either order with the league's own. The last two tests run
 * the load on a real executor thread and stop it with latches between its read and its publication, while the test
 * thread (standing in for the client thread) switches key: the interleaving the review found. Synthetic account.
 */
public class PriorFileTest
{
	private static final String LEAGUE = "seasonal-demonic_pacts_league";
	private static final ProfileKey MAIN = new ProfileKey(1L, "main", "Tester");
	private static final ProfileKey SEASONAL = new ProfileKey(1L, LEAGUE, "Tester");

	private static LootCapture.KillFile killFile(int zulrah)
	{
		LootCapture.Count c = new LootCapture.Count();
		c.count = zulrah;
		LootCapture.KillFile f = new LootCapture.KillFile();
		f.killcounts = new TreeMap<>();
		f.killcounts.put("Zulrah", c);
		return f;
	}

	private static GameFake game() throws IOException
	{
		GameFake g = new GameFake();
		g.write(1L, "main", "killcounts.json", killFile(10));
		g.write(1L, LEAGUE, "killcounts.json", killFile(2));
		return g;
	}

	private static int zulrah(PriorFile<LootCapture.KillFile> prior)
	{
		return prior.get().killcounts.get("Zulrah").count;
	}

	@Test
	public void mainsLoadFinishingAfterTheSwitchIsNeverTheLeaguesFile() throws IOException
	{
		GameFake g = game();
		PriorFile<LootCapture.KillFile> prior = new PriorFile<>(g.ctx, "killcounts.json", LootCapture.KillFile.class);
		prior.ensure(MAIN);
		prior.ensure(SEASONAL);
		g.executor.runOne(); // main's load publishes now, after the key moved on to the league
		assertFalse(prior.loaded());
		assertNull(prior.get());
		g.executor.runOne();
		assertTrue(prior.loaded());
		assertEquals(2, zulrah(prior));
	}

	@Test
	public void aSlowerOlderLoadNeverReplacesTheNewerOne() throws IOException
	{
		GameFake g = game();
		PriorFile<LootCapture.KillFile> prior = new PriorFile<>(g.ctx, "killcounts.json", LootCapture.KillFile.class);
		prior.ensure(MAIN);
		prior.ensure(SEASONAL);
		g.executor.runLast(); // the league's load finishes first...
		assertTrue(prior.loaded());
		assertEquals(2, zulrah(prior));
		g.executor.runAll(); // ...and main's, finishing late, changes nothing
		assertTrue(prior.loaded());
		assertEquals(2, zulrah(prior));
	}

	@Test
	public void aLoadFinishingAfterResetOrASwitchBackIsNotUsed() throws IOException
	{
		GameFake g = game();
		PriorFile<LootCapture.KillFile> prior = new PriorFile<>(g.ctx, "killcounts.json", LootCapture.KillFile.class);
		prior.ensure(MAIN);
		prior.reset(); // logout before the load finished
		g.executor.runAll();
		assertFalse(prior.loaded());

		// main, the league, and back to main: only the last request's load counts, even though it is main's again
		prior.ensure(MAIN);
		int first = prior.generation();
		prior.ensure(SEASONAL);
		prior.ensure(MAIN);
		g.executor.runOne(); // the first main load: same key, but an older request
		assertFalse(prior.loaded());
		g.executor.runOne(); // the league's
		assertFalse(prior.loaded());
		g.executor.runOne();
		assertTrue(prior.loaded());
		assertEquals(10, zulrah(prior));
		assertTrue(prior.generation() > first);
	}

	/** A real single-thread executor whose loads pause before publishing, released one by one by the test. */
	private static final class Paused implements AutoCloseable
	{
		final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		final CountDownLatch[] reached = {new CountDownLatch(1), new CountDownLatch(1)};
		final CountDownLatch[] go = {new CountDownLatch(1), new CountDownLatch(1)};
		final PriorFile<LootCapture.KillFile> prior;

		Paused(GameFake g)
		{
			JsonWriter json = new JsonWriter(executor, new Gson(), () -> Filepath.Unchecked.getRooted(g.dir), () -> true,
				t -> { });
			prior = new PriorFile<>(new CaptureContext(g.client, g.config, json, null, g.login), "killcounts.json",
				LootCapture.KillFile.class);
			AtomicInteger loads = new AtomicInteger();
			prior.beforePublish = () ->
			{
				int n = loads.getAndIncrement();
				if (n < reached.length)
				{
					reached[n].countDown(); // this load has read its file and is about to publish
					await(go[n]);
				}
			};
		}

		void release(int n)
		{
			go[n].countDown();
		}

		void drain() throws Exception
		{
			executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
		}

		@Override
		public void close() throws InterruptedException
		{
			for (CountDownLatch l : go)
			{
				l.countDown();
			}
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	private static void await(CountDownLatch latch)
	{
		try
		{
			assertTrue(latch.await(5, TimeUnit.SECONDS));
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	@Test
	public void mainsLoadPausedBeforePublishingIsNeverReadAsTheLeaguesFile() throws Exception
	{
		GameFake g = game();
		try (Paused p = new Paused(g))
		{
			p.prior.ensure(MAIN);
			await(p.reached[0]); // main's file is read, its result not yet published...
			p.prior.ensure(SEASONAL); // ...when the client thread switches to the league
			p.release(0);
			await(p.reached[1]); // main's result is published now; the league's waits to publish
			assertFalse(p.prior.loaded());
			assertNull(p.prior.get());
			p.release(1);
			p.drain();
			assertTrue(p.prior.loaded());
			assertEquals(2, zulrah(p.prior));
		}
	}

	@Test
	public void aLoadPausedAcrossARelogIsNeverReadAsTheNewFile() throws Exception
	{
		GameFake g = game();
		try (Paused p = new Paused(g))
		{
			p.prior.ensure(MAIN);
			await(p.reached[0]); // the first login's load has read 10 kills
			g.write(1L, "main", "killcounts.json", killFile(11)); // a later write before the relogin
			p.prior.reset(); // logout...
			p.prior.ensure(MAIN); // ...and the same key logs in again
			p.release(0);
			await(p.reached[1]);
			assertFalse(p.prior.loaded()); // the stale load, same key but an older request, is not used
			p.release(1);
			p.drain();
			assertTrue(p.prior.loaded());
			assertEquals(11, zulrah(p.prior));
		}
	}
}
