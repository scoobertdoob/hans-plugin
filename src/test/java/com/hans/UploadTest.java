package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.runelite.api.Item;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.util.Filepath;
import org.junit.Test;

/** The opt-in upload: its envelope, this PC's key, batching and backoff, and the capture's timing. Synthetic only. */
public class UploadTest
{
	private static final Gson GSON = new Gson();
	private static final ProfileKey ALPHA = new ProfileKey(-4611686018427387904L, "main", "Synth Alpha");
	private static final String KEY_ID = "0123456789abcdef0123456789abcdef";

	// ------------------------------------------------------------------ envelope

	@Test
	public void theEnvelopeMatchesTheFixtureTheWorkerTestsUse() throws IOException
	{
		Map<String, Integer> xp = new LinkedHashMap<>();
		xp.put("ATTACK", 1154);
		xp.put("HITPOINTS", 1300);
		xp.put("FARMING", 101333);
		JsonObject built = UploadEnvelope.build(ALPHA, KEY_ID, 10001, Instant.parse("2026-01-01T00:00:05Z"), Arrays.asList(
			UploadEnvelope.skills(xp, Instant.parse("2026-01-01T00:00:03Z")),
			UploadEnvelope.inventory(new int[]{5295, -1, 952, 6036}, new int[]{3, 0, 1, 12},
				Instant.parse("2026-01-01T00:00:04Z"))));
		// a copy of tests/fixtures/synthetic/upload/envelope-v1.json, so the plugin builds on its own;
		// tests/test_plugin_fixtures.py keeps the two identical
		JsonObject expected;
		try (InputStream in = UploadTest.class.getResourceAsStream("envelope-v1.json"))
		{
			expected = GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
		}
		assertEquals(expected, built);
		assertEquals("Synth Alpha", built.getAsJsonObject("account").get("name").getAsString()); // no NBSP
	}

	@Test
	public void anUploadFileHoldsTheSameEnvelopes()
	{
		JsonObject one = UploadEnvelope.build(ALPHA, KEY_ID, 1, Instant.EPOCH, Collections.emptyList());
		JsonObject file = UploadEnvelope.file(Collections.singletonList(one));
		assertEquals("rsbuddy-upload-file", file.get("format").getAsString());
		assertEquals(1, file.get("version").getAsInt());
		assertEquals(one, file.getAsJsonArray("envelopes").get(0));
	}

	// ------------------------------------------------------------------ this PC's key

	private static Filepath temp() throws IOException
	{
		return Filepath.Unchecked.getRooted(Files.createTempDirectory("rsbuddy-upload"));
	}

	@Test
	public void theKeyIsMadeOnceKeptAndItsNumbersOnlyGoUp() throws IOException
	{
		Path dir = Files.createTempDirectory("rsbuddy-upload");
		Filepath root = Filepath.Unchecked.getRooted(dir);
		DeviceKey first = DeviceKey.loadOrCreate(root, GSON, new SecureRandom());
		assertTrue(first.keyId.matches("[0-9a-f]{32}"));
		assertTrue(first.bearer().matches("Bearer [0-9a-f]{32}\\.[0-9a-f]{64}"));
		assertFalse(first.toString().contains(first.bearer().substring(40)));
		long a = first.nextSeq();
		long b = first.nextSeq();
		assertTrue(b > a);

		DeviceKey again = DeviceKey.loadOrCreate(root, GSON, new SecureRandom()); // a restart
		assertEquals(first.keyId, again.keyId);
		assertEquals(first.bearer(), again.bearer());
		assertTrue(again.nextSeq() > b);
		assertTrue(Files.exists(dir.resolve("upload").resolve("device.json")));
	}

	@Test
	public void aCrashBetweenBlocksNeverRepeatsANumber() throws IOException
	{
		Filepath root = temp();
		DeviceKey k = DeviceKey.loadOrCreate(root, GSON, new SecureRandom());
		long last = 0;
		for (int i = 0; i < DeviceKey.BLOCK + 5; i++)
		{
			last = k.nextSeq(); // runs into the second block, saving it before use
		}
		// no clean shutdown: the next start begins after the last block reserved
		assertTrue(DeviceKey.loadOrCreate(root, GSON, new SecureRandom()).nextSeq() > last);
	}

	@Test
	public void anUnreadableKeyFileIsMovedAsideNotDeleted() throws IOException
	{
		Path dir = Files.createTempDirectory("rsbuddy-upload");
		Files.createDirectories(dir.resolve("upload"));
		Files.write(dir.resolve("upload").resolve("device.json"), "{not json".getBytes(StandardCharsets.UTF_8));
		DeviceKey k = DeviceKey.loadOrCreate(Filepath.Unchecked.getRooted(dir), GSON, new SecureRandom());
		assertTrue(k.keyId.matches("[0-9a-f]{32}"));
		try (Stream<Path> files = Files.list(dir.resolve("upload")))
		{
			assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("device.json.unreadable-")));
		}
	}

	// ------------------------------------------------------------------ sender

	/** Records every POST; the test answers them. */
	private static final class Wire implements UploadSender.Transport
	{
		final List<JsonObject> bodies = new ArrayList<>();
		final List<String> auth = new ArrayList<>();
		final List<UploadSender.Answer> answers = new ArrayList<>();
		final List<String> urls = new ArrayList<>();
		long bytes; // every body posted, UTF-8
		int cancelled;
		Runnable duringPost = () -> { };

		@Override
		public Runnable post(String url, String authorization, String json, UploadSender.Answer answer)
		{
			urls.add(url);
			bytes += json.getBytes(StandardCharsets.UTF_8).length;
			bodies.add(GSON.fromJson(json, JsonObject.class));
			Runnable hook = duringPost;
			duringPost = () -> { };
			hook.run();
			auth.add(authorization);
			answers.add(answer);
			return () -> cancelled++;
		}

		JsonObject last()
		{
			return bodies.get(bodies.size() - 1);
		}

		void answer(int code)
		{
			answer(code, null, "{\"ok\":true}");
		}

		void answer(int code, String retryAfter, String body)
		{
			answers.get(answers.size() - 1).status(code, retryAfter, body);
		}
	}

	/** Runs scheduled tasks only when asked, and moves the clock to their time. */
	private static final class Timers implements UploadSender.Scheduler
	{
		final AtomicLong clock = new AtomicLong(1_000_000);
		final List<FutureTask<?>> tasks = new ArrayList<>();
		final List<Long> due = new ArrayList<>();
		boolean hold; // the executor is busy: work waits until release()
		final List<Runnable> held = new ArrayList<>();

		@Override
		public void execute(Runnable task)
		{
			if (hold)
			{
				held.add(task);
			}
			else
			{
				task.run(); // inline: the mailbox runs at once, on the test's thread
			}
		}

		void release()
		{
			hold = false;
			List<Runnable> run = new ArrayList<>(held);
			held.clear();
			run.forEach(Runnable::run);
		}

		@Override
		public FutureTask<?> schedule(Runnable task, long delayMs)
		{
			FutureTask<?> f = new FutureTask<>(task, null);
			tasks.add(f);
			due.add(clock.get() + delayMs);
			return f;
		}

		/** The delay of the newest timer still waiting, from now; -1 when none. */
		long waiting()
		{
			for (int i = tasks.size() - 1; i >= 0; i--)
			{
				if (!tasks.get(i).isDone())
				{
					return due.get(i) - clock.get();
				}
			}
			return -1;
		}

		/** Runs the timers due by {@code t} (in the order set), then moves the clock to {@code t}. */
		void runBy(long t)
		{
			for (int i = 0; i < tasks.size(); i++)
			{
				FutureTask<?> f = tasks.get(i);
				if (!f.isDone() && due.get(i) <= t)
				{
					clock.set(Math.max(clock.get(), due.get(i)));
					f.run();
				}
			}
			clock.set(Math.max(clock.get(), t));
		}

		/** Runs every waiting timer, moving the clock to each one's time. */
		void runDue()
		{
			for (int i = 0; i < tasks.size(); i++)
			{
				FutureTask<?> f = tasks.get(i);
				if (!f.isDone())
				{
					clock.set(Math.max(clock.get(), due.get(i)));
					f.run();
				}
			}
		}
	}

	private static final class Rig
	{
		final Wire wire = new Wire();
		final Timers timers = new Timers();
		final AtomicBoolean on = new AtomicBoolean(true);
		String url = "https://upload.example.test/api/ingest";
		Runnable duringKeyLoad = () -> { };
		int keyLoads;
		// runs at the sender's n-th clock read from now: post() reads the clock after its checks, before it serialises
		int clockReads = -1;
		Runnable atClockRead = () -> { };

		/** Runs {@code hook} at the sender's {@code n}-th clock read from now. */
		void atClockRead(int n, Runnable hook)
		{
			clockReads = n;
			atClockRead = hook;
		}

		private long clock()
		{
			if (clockReads > 0 && --clockReads == 0)
			{
				atClockRead.run();
			}
			return timers.clock.get();
		}
		DeviceKey loaded;
		final UploadSender sender;

		Rig() throws IOException
		{
			Filepath root = temp();
			sender = new UploadSender(wire, timers, () ->
				{
					keyLoads++;
					duringKeyLoad.run(); // as if the disk were slow and the player acted meanwhile
					loaded = DeviceKey.loadOrCreate(root, GSON, new SecureRandom());
					return loaded;
				},
				() -> url, on::get, this::clock, new Random(7), GSON);
		}
	}

	private static UploadEnvelope.Reading xp(int value)
	{
		return UploadEnvelope.skills(Collections.singletonMap("ATTACK", value), Instant.EPOCH);
	}

	private static UploadEnvelope.Reading pack(int id)
	{
		return UploadEnvelope.inventory(new int[]{id}, new int[]{1}, Instant.EPOCH);
	}

	private static int attack(JsonObject envelope)
	{
		return envelope.getAsJsonObject("categories").getAsJsonObject("skills").getAsJsonObject("data")
			.getAsJsonObject("xp").get("ATTACK").getAsInt();
	}

	private static int firstItem(JsonObject envelope)
	{
		JsonArray items = envelope.getAsJsonObject("categories").getAsJsonObject("inventory").getAsJsonObject("data")
			.getAsJsonArray("items");
		return items.get(0).getAsJsonArray().get(0).getAsInt();
	}

	@Test
	public void changesAreCoalescedIntoOneBatchWithTheLatestOfEach() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(10));
		r.sender.offer(ALPHA, xp(20));
		r.sender.offer(ALPHA, pack(995));
		assertEquals(0, r.timers.waiting()); // the first batch goes straight away
		r.timers.runDue();
		assertEquals(1, r.wire.bodies.size());
		assertEquals(20, attack(r.wire.last()));
		assertEquals(995, firstItem(r.wire.last()));
		assertTrue(r.wire.auth.get(0).startsWith("Bearer "));
		assertEquals(ALPHA.hashText(), r.wire.last().getAsJsonObject("account").get("hash").getAsString());
	}

	@Test
	public void oneRequestAtATimeAndAtLeastTheIntervalApart() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		r.sender.offer(ALPHA, xp(2));
		r.sender.offer(ALPHA, xp(3));
		assertEquals(-1, r.timers.waiting()); // nothing scheduled while the first is in flight
		r.wire.answer(200);
		assertEquals(UploadSender.DEFAULT_INTERVAL_MS, r.timers.waiting()); // the background gap
		r.timers.runDue();
		assertEquals(2, r.wire.bodies.size());
		assertEquals(3, attack(r.wire.last()));
		assertTrue(r.wire.bodies.get(1).get("seq").getAsLong() > r.wire.bodies.get(0).get("seq").getAsLong());
	}

	@Test
	public void theWebsiteSwitchesTheGapBetweenWatchedAndBackground() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		r.sender.offer(ALPHA, xp(2));
		r.wire.answer(200, null, "{\"ok\":true,\"minIntervalMs\":5000}"); // someone has RSBuddy open
		assertEquals(5_000, r.timers.waiting());
		r.timers.runDue();
		r.sender.offer(ALPHA, xp(3));
		r.wire.answer(200, null, "{\"ok\":true,\"minIntervalMs\":60000}"); // nobody is watching any more
		assertEquals(60_000, r.timers.waiting());
		r.timers.runDue();
		r.sender.offer(ALPHA, xp(4));
		r.wire.answer(200, null, "{\"ok\":true,\"minIntervalMs\":1}"); // never shorter than the floor
		assertEquals(UploadSender.MIN_INTERVAL_MS, r.timers.waiting());
		r.timers.runDue();
		r.sender.offer(ALPHA, xp(5));
		r.wire.answer(200, null, "not json"); // says nothing: the background gap
		assertEquals(UploadSender.DEFAULT_INTERVAL_MS, r.timers.waiting());
	}

	@Test
	public void nothingIsSentWhenNothingChanged() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.sender.offer(ALPHA, pack(995));
		r.timers.runDue();
		r.wire.answer(200);
		r.sender.offer(ALPHA, xp(1)); // the same readings again (a region load, a stat event with no change)
		r.sender.offer(ALPHA, pack(995));
		assertEquals(0, r.sender.pendingProfiles());
		assertEquals(-1, r.timers.waiting());
		r.sender.offer(ALPHA, xp(2));
		r.sender.offer(ALPHA, xp(1)); // changed and changed back before the gap was over
		assertEquals(0, r.sender.pendingProfiles());
		r.timers.runDue();
		assertEquals(1, r.wire.bodies.size());
	}

	@Test
	public void logoutSendsWithoutWaitingForTheGap() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		r.wire.answer(200);
		r.sender.offer(ALPHA, xp(2));
		assertEquals(UploadSender.DEFAULT_INTERVAL_MS, r.timers.waiting());
		r.sender.flushNow();
		assertTrue(r.timers.waiting() <= UploadSender.FLUSH_GAP_MS);
		r.timers.runDue();
		assertEquals(2, attack(r.wire.last()));
	}

	@Test
	public void stoppingThePluginSendsTheLastBatchWithoutWaiting() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue(); // loads the key
		r.wire.answer(200);
		r.sender.offer(ALPHA, xp(2));
		r.sender.close();
		assertEquals(2, r.wire.bodies.size()); // posted at once, not scheduled
		assertEquals(2, attack(r.wire.last()));
		r.wire.answer(200);
		r.sender.offer(ALPHA, xp(3));
		r.timers.runDue();
		assertEquals(2, r.wire.bodies.size()); // nothing after that

		Rig off = new Rig();
		off.sender.offer(ALPHA, xp(1));
		off.timers.runDue();
		off.wire.answer(200);
		off.sender.offer(ALPHA, xp(2));
		off.on.set(false);
		off.sender.close();
		assertEquals(1, off.wire.bodies.size()); // no consent, no last batch

		Rig cold = new Rig();
		cold.sender.offer(ALPHA, xp(1));
		cold.sender.close(); // the key was never loaded: no disk work at shutdown, nothing sent
		cold.timers.runDue();
		assertEquals(0, cold.wire.bodies.size());
	}

	@Test
	public void switchingOffWhileTheKeyLoadsSendsNothing() throws IOException
	{
		Rig r = new Rig();
		r.duringKeyLoad = () ->
		{
			r.on.set(false);
			r.sender.clear();
		};
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		assertEquals(0, r.wire.bodies.size());
		assertEquals(0, r.sender.pendingProfiles());

		Rig untold = new Rig(); // switched off, but the settings event has not arrived yet
		untold.duringKeyLoad = () -> untold.on.set(false);
		untold.sender.offer(ALPHA, xp(1));
		untold.timers.runDue();
		assertEquals(0, untold.wire.bodies.size());
		assertEquals(0, untold.sender.pendingProfiles());

		Rig stopping = new Rig();
		stopping.duringKeyLoad = stopping.sender::close;
		stopping.sender.offer(ALPHA, xp(1));
		stopping.timers.runDue();
		assertEquals(1, stopping.wire.bodies.size()); // not this batch, but the last batch at shutdown (consent on)
		stopping.wire.answer(200);
		stopping.sender.offer(ALPHA, xp(2));
		stopping.timers.runDue();
		assertEquals(1, stopping.wire.bodies.size());
	}

	@Test
	public void switchingOffCancelsAQueuedRequest() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		r.on.set(false);
		r.sender.clear();
		assertEquals(1, r.wire.cancelled);
		r.wire.answers.get(0).failed(new IOException("Canceled"));
		assertEquals(0, r.sender.pendingProfiles()); // not put back
	}

	@Test
	public void failuresBackOffAndKeepTheReadings() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.sender.offer(ALPHA, pack(995));
		r.timers.runDue();
		for (int i = 1; i <= 4; i++)
		{
			r.wire.answer(503);
			long wait = r.timers.waiting();
			long step = UploadSender.BACKOFF_BASE_MS << (i - 1);
			assertTrue("backoff " + i + " was " + wait, wait >= step / 2 && wait <= step);
			r.timers.runDue();
			assertEquals(i + 1, r.wire.bodies.size()); // retried
			assertEquals(1, attack(r.wire.last()));
			assertEquals(995, firstItem(r.wire.last()));
		}
		r.wire.answer(200);
		assertEquals(-1, r.timers.waiting()); // nothing left to send
		assertEquals(0, r.sender.pendingProfiles());
	}

	@Test
	public void backoffIsCapped() throws IOException
	{
		Rig r = new Rig();
		for (int i = 1; i < 40; i++)
		{
			assertTrue(r.sender.backoff(i) <= UploadSender.BACKOFF_MAX_MS);
		}
	}

	@Test
	public void retryAfterIsHonoured() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		r.wire.answer(429, "120", "");
		assertTrue(r.timers.waiting() >= 120_000);
		assertEquals(0, UploadSender.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
		assertEquals(UploadSender.RETRY_AFTER_MAX_MS, UploadSender.retryAfterMs("999999"));
	}

	@Test
	public void aRetryCarriesTheNewerReadingWhereThereIsOne() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.sender.offer(ALPHA, pack(995));
		r.timers.runDue();
		r.sender.offer(ALPHA, xp(2)); // arrives while the first batch is in flight
		r.wire.answer(500);
		r.timers.runDue();
		assertEquals(2, attack(r.wire.last()));
		assertEquals(995, firstItem(r.wire.last()));
	}

	@Test
	public void staleOrRejectedBatchesAreDroppedNotRetried() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		r.wire.answer(409, null, "{\"ok\":false,\"result\":\"stale\"}");
		assertEquals(0, r.sender.pendingProfiles());
		assertEquals(-1, r.timers.waiting());

		r.sender.offer(ALPHA, xp(2));
		r.timers.runDue();
		r.wire.answer(400, null, "{\"ok\":false}");
		assertEquals(0, r.sender.pendingProfiles());
		r.sender.offer(ALPHA, xp(3));
		assertTrue(r.timers.waiting() >= UploadSender.BACKOFF_BASE_MS / 2); // still paced by the failure
	}

	@Test
	public void aRefusedKeyPausesUntilTheSettingsChange() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		r.wire.answer(401, null, "");
		assertTrue(r.sender.refused());
		r.sender.offer(ALPHA, xp(2));
		assertEquals(-1, r.timers.waiting());
		assertEquals(0, r.sender.pendingProfiles());
		r.sender.settingsChanged();
		r.sender.offer(ALPHA, xp(3));
		r.timers.runDue();
		assertEquals(2, r.wire.bodies.size());
	}

	@Test
	public void theQueueIsBoundedToAFewAccountsAndModes() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue(); // one in flight
		for (int i = 0; i < 10; i++)
		{
			r.sender.offer(new ProfileKey(100 + i, "main", "Synth" + i), xp(i));
		}
		assertEquals(UploadSender.MAX_PROFILES, r.sender.pendingProfiles());
		r.wire.answer(503); // the failed batch comes back, still within the bound
		assertEquals(UploadSender.MAX_PROFILES, r.sender.pendingProfiles());
	}

	@Test
	public void switchingOffDropsWhatIsUnsentAndAnAnswerOnItsWayChangesNothing() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		r.sender.offer(ALPHA, xp(2));
		r.on.set(false);
		r.sender.clear();
		r.wire.answer(503);
		assertEquals(0, r.sender.pendingProfiles());
		r.sender.offer(ALPHA, xp(3)); // ignored while off
		assertEquals(0, r.sender.pendingProfiles());
	}

	@Test
	public void closingStopsEverything() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.sender.close();
		r.timers.runDue();
		assertEquals(0, r.wire.bodies.size());
		r.sender.offer(ALPHA, xp(2));
		assertEquals(0, r.sender.pendingProfiles());
	}

	@Test
	public void onlyHttpsOrThisPcIsAllowed() throws IOException
	{
		assertTrue(UploadSender.allowedEndpoint("https://rsbuddy.example.test/api/ingest"));
		assertTrue(UploadSender.allowedEndpoint("http://127.0.0.1:8787/api/ingest"));
		assertTrue(UploadSender.allowedEndpoint("http://localhost:8787/api/ingest"));
		assertFalse(UploadSender.allowedEndpoint("http://rsbuddy.example.test/api/ingest"));
		assertFalse(UploadSender.allowedEndpoint("http://127.0.0.1.example.test/api/ingest"));
		assertFalse(UploadSender.allowedEndpoint("https://user:pass@rsbuddy.example.test/"));
		assertFalse(UploadSender.allowedEndpoint("ftp://rsbuddy.example.test/"));
		assertFalse(UploadSender.allowedEndpoint(""));
		assertFalse(UploadSender.allowedEndpoint(null));

		Rig r = new Rig();
		r.url = "http://rsbuddy.example.test/api/ingest";
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		assertEquals(0, r.wire.bodies.size()); // never sent over plain HTTP to another machine
		assertTrue(r.sender.refused());
	}

	/**
	 * End to end through the real OkHttp transport, only when a local Worker is running
	 * ({@code python -I tools/upload_practice.py serve}, then
	 * {@code RSBUDDY_UPLOAD_URL=http://127.0.0.1:8787/api/ingest gradlew test --no-daemon}). Synthetic account.
	 */
	@Test
	public void sendsToALocalWorkerWhenOneIsRunning() throws Exception
	{
		String url = System.getenv("RSBUDDY_UPLOAD_URL");
		org.junit.Assume.assumeTrue(url != null && !url.isEmpty());
		Filepath root = temp();
		java.util.concurrent.ScheduledExecutorService executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
		java.util.concurrent.CompletableFuture<Integer> code = new java.util.concurrent.CompletableFuture<>();
		UploadSender.Transport okHttp = UploadCapture.okHttp(new okhttp3.OkHttpClient());
		UploadSender.Transport watched = (u, auth, json, answer) -> okHttp.post(u, auth, json, new UploadSender.Answer()
		{
			@Override
			public void status(int c, String retryAfter, String body)
			{
				answer.status(c, retryAfter, body);
				code.complete(c);
			}

			@Override
			public void failed(IOException e)
			{
				answer.failed(e);
				code.completeExceptionally(e);
			}
		});
		UploadSender sender = new UploadSender(watched, new UploadSender.Scheduler()
			{
				@Override
				public void execute(Runnable task)
				{
					executor.execute(task);
				}

				@Override
				public java.util.concurrent.Future<?> schedule(Runnable task, long ms)
				{
					return executor.schedule(task, ms, java.util.concurrent.TimeUnit.MILLISECONDS);
				}
			},
			() -> DeviceKey.loadOrCreate(root, GSON, new SecureRandom()), () -> url, () -> true, System::currentTimeMillis,
			new Random(), GSON);
		try
		{
			sender.offer(new ProfileKey(-1234567890123L, "main", "Synth Live"), xp(42));
			assertEquals(Integer.valueOf(200), code.get(20, java.util.concurrent.TimeUnit.SECONDS));
		}
		finally
		{
			sender.close();
			executor.shutdownNow();
		}
	}

	// ------------------------------------------------------------------ round 3: one owner for the sender's state

	@Test
	public void callersNeverRunTheSendersWorkAndShutdownNeverNeedsTheDisk() throws IOException
	{
		Rig r = new Rig();
		r.timers.hold = true; // the executor is busy: callers must return without doing the work themselves
		r.sender.offer(ALPHA, xp(1));
		r.sender.flushNow();
		r.sender.close();
		assertEquals(0, r.keyLoads); // no key IO, no post, on the caller's thread
		assertEquals(0, r.wire.bodies.size());
		r.timers.release();

		Rig used = new Rig();
		used.sender.offer(ALPHA, xp(1));
		used.timers.runDue();
		used.wire.answer(200);
		while (used.loaded.nextSeqInMemory() > 0)
		{
			// use up the reserved block: the next number needs the disk
		}
		used.sender.offer(ALPHA, xp(2));
		used.sender.close();
		assertEquals(1, used.wire.bodies.size()); // no last batch rather than disk IO at shutdown
	}

	@Test
	public void aChangeBackIsSentWhileADifferentValueIsInFlight() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, pack(1));
		r.timers.runDue();
		r.wire.answer(200); // the website has 1
		r.sender.offer(ALPHA, pack(2));
		r.timers.runDue(); // 2 is on its way
		r.sender.offer(ALPHA, pack(1)); // and the backpack goes back to 1
		assertEquals(1, r.sender.pendingProfiles());
		r.wire.answer(200);
		r.timers.runDue();
		assertEquals(3, r.wire.bodies.size());
		assertEquals(1, firstItem(r.wire.last()));

		Rig same = new Rig();
		same.sender.offer(ALPHA, pack(1));
		same.timers.runDue(); // 1 is on its way
		same.sender.offer(ALPHA, pack(2));
		same.sender.offer(ALPHA, pack(1)); // an unsent change undone: 1 is already being sent
		assertEquals(0, same.sender.pendingProfiles());
	}

	@Test
	public void theOldDestinationsAnswerCannotAcknowledgeTheNewOnesReadings() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue(); // in flight to the old address
		r.url = "https://new.example.test/api/ingest";
		r.sender.settingsChanged();
		assertEquals(1, r.wire.cancelled);
		r.sender.offer(ALPHA, xp(2));
		r.wire.answers.get(0).status(200, null, "{\"ok\":true}"); // the old one answers anyway
		assertEquals(1, r.sender.pendingProfiles()); // still queued for the new address
		r.timers.runDue();
		assertEquals(2, r.wire.bodies.size());
		assertEquals("https://new.example.test/api/ingest", r.wire.urls.get(1));
		assertEquals(2, attack(r.wire.last()));
		r.wire.answer(200);
		r.sender.offer(ALPHA, xp(1)); // the new destination never accepted 1 from this generation's point of view
		r.timers.runDue();
		assertEquals(3, r.wire.bodies.size());
	}

	@Test
	public void aLogoutFlushSurvivesTheAnswerInFlight() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		r.sender.offer(ALPHA, xp(2));
		r.sender.flushNow(); // logout while the first request is on its way
		r.wire.answer(200, null, "{\"ok\":true,\"minIntervalMs\":60000}");
		assertTrue(r.timers.waiting() <= UploadSender.FLUSH_GAP_MS);
		r.timers.runDue();
		assertEquals(2, attack(r.wire.last()));
		r.wire.answer(200);
		r.sender.offer(ALPHA, xp(3)); // all sent: the flag is gone, the usual gap again
		assertEquals(UploadSender.DEFAULT_INTERVAL_MS, r.timers.waiting());

		Rig failing = new Rig();
		failing.sender.offer(ALPHA, xp(1));
		failing.timers.runDue();
		failing.sender.flushNow();
		failing.wire.answer(503, "30", "");
		assertTrue(failing.timers.waiting() >= 30_000); // the failure's backoff still applies
	}

	@Test
	public void anOptOutDuringSerialisationPostsNothing() throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(1));
		// the timer's check reads the clock first; the second read is post()'s, past every earlier check
		r.atClockRead(2, () ->
		{
			r.on.set(false); // the player switches uploading off while the envelope is being built
			r.sender.clear();
		});
		r.timers.runDue();
		assertEquals("the opt-out came after the checks", 0, r.clockReads);
		assertEquals(0, r.wire.bodies.size());
		assertEquals(0, r.sender.pendingProfiles());
	}

	@Test
	public void anOptOutDuringTheLastBatchsSerialisationPostsNothing() throws IOException
	{
		Rig closing = new Rig(); // the shutdown path
		closing.sender.offer(ALPHA, xp(1));
		closing.timers.runDue();
		closing.wire.answer(200);
		closing.sender.offer(ALPHA, xp(2));
		closing.atClockRead(1, () -> // the last batch's post() reads the clock first
		{
			closing.on.set(false);
			closing.sender.clear();
		});
		closing.sender.close();
		assertEquals("the opt-out came after the checks", 0, closing.clockReads);
		assertEquals(1, closing.wire.bodies.size());
	}

	@Test
	public void anOptOutDuringTheEnqueueCancelsTheRequest() throws IOException
	{
		Rig r = new Rig();
		r.wire.duringPost = () ->
		{
			r.on.set(false);
			r.sender.clear();
		};
		r.sender.offer(ALPHA, xp(1));
		r.timers.runDue();
		assertEquals(1, r.wire.cancelled); // queued by the transport, cancelled at once
		r.wire.answers.get(0).status(200, null, "{}");
		assertEquals(0, r.sender.pendingProfiles());
	}

	// ------------------------------------------------------------------ capture timing

	private static final class Offered implements UploadSender.Transport
	{
		final List<JsonObject> bodies = new ArrayList<>();

		@Override
		public Runnable post(String url, String authorization, String json, UploadSender.Answer answer)
		{
			bodies.add(GSON.fromJson(json, JsonObject.class));
			answer.status(200, null, "{}");
			return null;
		}
	}

	private static final class CaptureRig
	{
		final GameFake g = new GameFake();
		final Offered wire = new Offered();
		final Timers timers = new Timers();
		final AtomicBoolean on = new AtomicBoolean(true);
		String url = "https://upload.example.test/api/ingest";
		final UploadCapture capture;

		CaptureRig() throws IOException
		{
			Filepath root = temp();
			UploadSender sender = new UploadSender(wire, timers, () -> DeviceKey.loadOrCreate(root, GSON, new SecureRandom()),
				() -> url, on::get, timers.clock::get, new Random(1), GSON);
			capture = new UploadCapture(g.ctx, sender, on::get);
			g.captures.add(capture);
		}

		/** Sends what is due, then lets the interval pass. */
		void send()
		{
			timers.runDue();
			timers.clock.addAndGet(UploadSender.MAX_INTERVAL_MS);
		}
	}

	@Test
	public void theCaptureSendsOnlyForASettledLoginAndUnderItsKey() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.g.xp = 500;
		c.g.containers.put(InventoryID.INV, new Item[]{new Item(995, 100), new Item(-1, 0)});
		c.g.hub.loggedIn(c.g.now);
		c.g.tick(); // not settled yet
		c.send();
		assertEquals(0, c.wire.bodies.size());
		c.g.settle();
		c.g.tick();
		c.send();
		assertEquals(1, c.wire.bodies.size());
		JsonObject e = c.wire.bodies.get(0);
		assertEquals("1", e.getAsJsonObject("account").get("hash").getAsString());
		assertEquals("main", e.getAsJsonObject("account").get("mode").getAsString());
		assertEquals(500, attack(e));
		assertEquals(995, firstItem(e));
		assertNull(e.getAsJsonObject("categories").getAsJsonObject("skills").getAsJsonObject("data").getAsJsonObject("xp")
			.get("OVERALL"));

		c.g.setContainer(InventoryID.INV, new Item(952, 1)); // only the backpack changed
		c.g.tick();
		c.send();
		assertEquals(2, c.wire.bodies.size());
		assertFalse(c.wire.bodies.get(1).getAsJsonObject("categories").has("skills"));
		assertEquals(952, firstItem(c.wire.bodies.get(1)));
	}

	@Test
	public void nothingIsReadOrSentWhileSwitchedOffOrOnAWorldTheGameWipes() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.on.set(false);
		c.g.logIn();
		c.g.ticks(3);
		c.send();
		assertEquals(0, c.wire.bodies.size());

		c.on.set(true);
		c.capture.onConfigChanged(UploadCapture.ENABLE_KEY);
		c.g.tick();
		c.send();
		assertEquals(1, c.wire.bodies.size()); // a full reading as soon as it is switched on

		c.g.logOut();
		c.g.worldTypes = java.util.EnumSet.of(WorldType.BETA_WORLD);
		c.g.logIn();
		c.g.setXp(900);
		c.g.ticks(5);
		c.send();
		assertEquals(1, c.wire.bodies.size());
	}

	@Test
	public void aNewAddressGetsAFullReadingWithNoChangeInTheGame() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.url = ""; // not set yet: the readings are dropped and sending pauses
		c.g.containers.put(InventoryID.INV, new Item[]{new Item(995, 100)});
		c.g.logIn();
		c.g.tick();
		c.send();
		assertEquals(0, c.wire.bodies.size());
		c.url = "https://upload.example.test/api/ingest";
		c.capture.onConfigChanged(UploadCapture.URL_KEY);
		c.g.tick(); // nothing changed in the game
		c.send();
		assertEquals(1, c.wire.bodies.size());
		assertTrue(c.wire.bodies.get(0).getAsJsonObject("categories").has("skills"));
		assertEquals(995, firstItem(c.wire.bodies.get(0)));
	}

	@Test
	public void anIdlePlayerSendsNothingAndLogoutSendsTheLastChange() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.g.logIn();
		c.g.tick();
		c.timers.runDue(); // no time passes on the sender clock
		assertEquals(1, c.wire.bodies.size());
		c.g.loadRegion(); // every reading is taken again, but nothing changed
		c.g.ticks(5);
		c.timers.runDue(); // no time passes on the sender clock
		assertEquals(1, c.wire.bodies.size());

		c.g.setXp(777);
		c.g.tick();
		assertTrue(c.timers.waiting() > UploadSender.FLUSH_GAP_MS); // waiting for the background gap
		c.g.logOut();
		assertTrue(c.timers.waiting() <= UploadSender.FLUSH_GAP_MS);
		c.timers.runDue();
		assertEquals(2, c.wire.bodies.size());
		assertEquals(777, attack(c.wire.bodies.get(1)));
	}

	@Test
	public void aDifferentModeIsADifferentStream() throws IOException
	{
		CaptureRig c = new CaptureRig();
		c.g.logIn();
		c.g.tick();
		c.send();
		c.g.logOut();
		c.g.world = 345; // a Deadman world, its season named by the world list
		c.g.worldTypes = java.util.EnumSet.of(WorldType.DEADMAN);
		c.g.activities.put(345, "Deadman: Armageddon");
		c.g.logIn();
		c.g.tick();
		c.send();
		c.g.logOut();
		c.g.activities.put(345, "Deadman: Annihilation"); // the next season: a fresh character
		c.g.refreshWorldList();
		c.g.logIn();
		c.g.tick();
		c.send();
		c.g.logOut();
		c.g.activities.remove(345); // a season the world list can't name: never settles, nothing is sent
		c.g.refreshWorldList();
		c.g.logIn();
		c.g.ticks(5);
		c.send();
		assertEquals(3, c.wire.bodies.size());
		String[] modes = new String[3];
		for (int i = 0; i < 3; i++)
		{
			modes[i] = c.wire.bodies.get(i).getAsJsonObject("account").get("mode").getAsString();
		}
		assertEquals("main", modes[0]);
		assertEquals("deadman-deadman_armageddon", modes[1]);
		assertEquals("deadman-deadman_annihilation", modes[2]);
		assertNotEquals(c.wire.bodies.get(0).get("seq"), c.wire.bodies.get(1).get("seq"));
	}

	// ------------------------------------------------------------------ request budget (Plugin Hub audit, card #123)

	private static final ProfileKey BETA = new ProfileKey(-4611686018427387903L, "main", "Synth Beta");

	/** A full login snapshot of a maxed-out synthetic account: every category, a full bank, a big log. */
	private static List<UploadEnvelope.Reading> fullSnapshot(Instant at)
	{
		List<UploadEnvelope.Reading> out = new ArrayList<>();
		Map<String, Integer> xp = new LinkedHashMap<>();
		for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
		{
			xp.put(skill.name(), 200_000_000);
		}
		out.add(UploadEnvelope.skills(xp, at));
		int[] inv = new int[28];
		int[] invQ = new int[28];
		Arrays.fill(inv, 12_695);
		Arrays.fill(invQ, 1);
		out.add(UploadEnvelope.inventory(inv, invQ, at));
		out.add(UploadEnvelope.equipment(Arrays.copyOf(inv, 14), Arrays.copyOf(invQ, 14), at));
		int[] ids = new int[1_410];
		int[] qtys = new int[1_410];
		for (int i = 0; i < ids.length; i++)
		{
			ids[i] = 10_000 + i;
			qtys[i] = 2_000_000_000 - i;
		}
		out.add(UploadEnvelope.bank(ids, qtys, new int[]{150, 150, 150, 150, 150, 150, 150, 150, 150}, at));
		List<Integer> rows = new ArrayList<>();
		for (int i = 0; i < 230; i++)
		{
			rows.add(i);
		}
		out.add(UploadEnvelope.quests(RsbuddyData.quests(at.toString(), rows, row -> "A Quest Name Of Some Length " + row,
			row -> 2, 330), at));
		out.add(UploadEnvelope.diaries(v -> 1, at));
		out.add(UploadEnvelope.combatAchievements(v -> -1, v -> 1, at));
		out.add(UploadEnvelope.caCatalogue(v -> -1, at));
		out.add(UploadEnvelope.music(v -> -1, UploadCategoriesTest.musicTable(900), at)); // all unlocked: the largest
		Map<Integer, Integer> log = new LinkedHashMap<>();
		for (int i = 0; i < 1_600; i++)
		{
			log.put(20_000 + i, 1 + i % 50);
		}
		out.add(UploadEnvelope.collectionLog(1_600, 1_700, log, at));
		Map<String, Integer> kc = new LinkedHashMap<>();
		for (int i = 0; i < 150; i++)
		{
			kc.put("A boss or creature name " + i, 10_000 + i);
		}
		out.add(UploadEnvelope.killcounts(kc, at));
		return out;
	}

	/** Sends what is due up to {@code until}, answering each request at once; returns how many were sent. */
	private static int drain(Rig r, int code, String answer, long until)
	{
		int before = r.wire.bodies.size();
		for (int guard = 0; guard < 500; guard++)
		{
			long wait = r.timers.waiting();
			if (wait < 0 || r.timers.clock.get() + wait > until)
			{
				break;
			}
			int sent = r.wire.bodies.size();
			r.timers.runBy(r.timers.clock.get() + wait);
			if (r.wire.bodies.size() > sent)
			{
				r.wire.answer(code, null, answer);
			}
		}
		return r.wire.bodies.size() - before;
	}

	/** A change every game tick for 10 minutes, answered with {@code answer}; returns {requests, bytes}. */
	private static long[] busyTenMinutes(String answer) throws IOException
	{
		Rig r = new Rig();
		r.sender.offer(ALPHA, xp(0));
		drain(r, 200, answer, r.timers.clock.get());
		long t0 = r.timers.clock.get();
		long bytes0 = r.wire.bytes;
		int sent0 = r.wire.bodies.size();
		for (int tick = 1; tick <= 1_000; tick++) // 600 s
		{
			r.sender.offer(ALPHA, xp(tick));
			r.sender.offer(ALPHA, pack(1_000 + tick));
			drain(r, 200, answer, t0 + tick * 600L);
			r.timers.runBy(t0 + tick * 600L);
		}
		return new long[]{r.wire.bodies.size() - sent0, r.wire.bytes - bytes0};
	}

	@Test
	public void worstCaseRequestBudget() throws IOException
	{
		StringBuilder report = new StringBuilder();
		Instant at = Instant.parse("2026-01-01T00:00:00Z");
		String ok = "{\"ok\":true}";

		// 1. a login snapshot of one maxed-out account (the bank goes in parts)
		Rig r = new Rig();
		long t0 = r.timers.clock.get();
		r.sender.snapshot(ALPHA);
		for (UploadEnvelope.Reading reading : fullSnapshot(at))
		{
			r.sender.offer(ALPHA, reading);
		}
		int n = drain(r, 200, ok, Long.MAX_VALUE);
		long biggest = 0;
		for (JsonObject body : r.wire.bodies)
		{
			biggest = Math.max(biggest, GSON.toJson(body).getBytes(StandardCharsets.UTF_8).length);
		}
		report.append(String.format("login snapshot, 1 account          requests %4d  bytes %7d  largest %6d  over %5.1f s%n",
			n, r.wire.bytes, biggest, (r.timers.clock.get() - t0) / 1000.0));
		assertTrue(n <= 3 + UploadSender.MAX_PARTS);
		assertTrue(biggest <= UploadSender.MAX_BODY);

		// 2. two accounts at once (a body carries one account)
		Rig two = new Rig();
		t0 = two.timers.clock.get();
		for (ProfileKey key : new ProfileKey[]{ALPHA, BETA})
		{
			two.sender.snapshot(key);
			for (UploadEnvelope.Reading reading : fullSnapshot(at))
			{
				two.sender.offer(key, reading);
			}
		}
		n = drain(two, 200, ok, Long.MAX_VALUE);
		report.append(String.format("login snapshot, 2 accounts         requests %4d  bytes %7d  over %5.1f s%n", n,
			two.wire.bytes, (two.timers.clock.get() - t0) / 1000.0));
		assertTrue(n <= 2 * (3 + UploadSender.MAX_PARTS));

		// 3. RSBuddy open on the website (5 s gap), then in the background (60 s gap): a change every tick, 10 minutes
		long[] watched = busyTenMinutes("{\"ok\":true,\"minIntervalMs\":5000}");
		report.append(String.format("watched, a change every tick, 10 min    requests %4d  bytes %7d%n", watched[0],
			watched[1]));
		assertTrue(watched[0] <= 600_000 / UploadSender.MIN_INTERVAL_MS + 1);
		long[] background = busyTenMinutes(ok);
		report.append(String.format("background, a change every tick, 10 min requests %4d  bytes %7d%n", background[0],
			background[1]));
		assertTrue(background[0] <= 600_000 / UploadSender.DEFAULT_INTERVAL_MS + 1);

		// 4. the website down (503) for an hour while the player keeps changing things
		Rig down = new Rig();
		t0 = down.timers.clock.get();
		for (int minute = 1; minute <= 60; minute++)
		{
			down.sender.offer(ALPHA, xp(minute));
			drain(down, 503, "", t0 + minute * 60_000L);
			down.timers.runBy(t0 + minute * 60_000L);
		}
		report.append(String.format("website down 1 hour (503 each)     requests %4d  bytes %7d%n",
			down.wire.bodies.size(), down.wire.bytes));
		assertTrue(down.wire.bodies.size() <= 30);

		// 5. switched off (the default): nothing at all, logout and shutdown included
		Rig off = new Rig();
		off.on.set(false);
		off.sender.snapshot(ALPHA);
		for (UploadEnvelope.Reading reading : fullSnapshot(at))
		{
			off.sender.offer(ALPHA, reading);
		}
		off.sender.flushNow();
		off.timers.runDue();
		off.sender.close();
		report.append(String.format("switched off                       requests %4d%n", off.wire.bodies.size()));
		assertEquals(0, off.wire.bodies.size());

		Path out = Paths.get("build", "reports", "hub-audit", "network.txt");
		Files.createDirectories(out.getParent());
		Files.write(out, report.toString().getBytes(StandardCharsets.UTF_8));
	}
}
