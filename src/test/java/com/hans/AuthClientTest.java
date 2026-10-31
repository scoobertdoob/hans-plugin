package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.WorldType;
import net.runelite.client.util.Filepath;
import org.junit.Test;

/** Sign-in through the plugin (card #76): the challenge request, its answer and its threading. Synthetic only. */
public class AuthClientTest
{
	private static final Gson GSON = new Gson();
	private static final ProfileKey ALPHA = new ProfileKey(-4611686018427387904L, "seasonal", "Synth Alpha");
	private static final long NOW = 1_800_000_000_000L;
	private static final String UPLOAD = "https://ingest.example:8443/api/ingest/upload?x=1";

	/** Records each POST; answers only when told. */
	private static final class Wire implements UploadSender.Transport
	{
		final List<String[]> posts = Collections.synchronizedList(new ArrayList<>());
		final List<UploadSender.Answer> answers = Collections.synchronizedList(new ArrayList<>());
		final AtomicInteger cancelled = new AtomicInteger();

		@Override
		public Runnable post(String url, String authorization, String json, UploadSender.Answer answer)
		{
			posts.add(new String[]{url, authorization, json});
			answers.add(answer);
			return cancelled::incrementAndGet;
		}
	}

	/** An executor that runs only when told, so the caller's thread provably does no work. */
	private static final class Queue implements java.util.concurrent.Executor
	{
		final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable task)
		{
			tasks.add(task);
		}

		void runAll()
		{
			Runnable r;
			while ((r = tasks.poll()) != null)
			{
				r.run();
			}
		}
	}

	private static final class Rig
	{
		final Wire wire = new Wire();
		final Queue queue = new Queue();
		final AtomicBoolean on = new AtomicBoolean(true);
		final AtomicInteger keyLoads = new AtomicInteger();
		String upload = UPLOAD;
		DeviceKey key;
		final AuthClient client;
		final List<AuthClient.Result> results = Collections.synchronizedList(new ArrayList<>());

		Rig() throws IOException
		{
			key = DeviceKey.loadOrCreate(temp(), GSON, new SecureRandom());
			client = new AuthClient(wire, queue, () ->
			{
				keyLoads.incrementAndGet();
				return key;
			}, () -> upload, on::get, () -> NOW, GSON);
		}

		void ask(ProfileKey p)
		{
			client.request(p, results::add);
		}

		AuthClient.Result only()
		{
			assertEquals(1, results.size());
			return results.get(0);
		}
	}

	private static Filepath temp() throws IOException
	{
		return Filepath.Unchecked.getRooted(Files.createTempDirectory("rsbuddy-auth"));
	}

	private static String ok(String extra)
	{
		return "{\"url\":\"https://site.example/auth?token=00ff\",\"code\":\"k7m4xp\"" + extra + "}";
	}

	// ------------------------------------------------------------------ the request

	@Test
	public void theRequestGoesOffTheCallersThreadWithThisPcsKeyAndTheCharacter() throws IOException
	{
		Rig r = new Rig();
		r.ask(new ProfileKey(ALPHA.hash, ALPHA.mode, "Synth Alpha"));
		assertTrue("nothing on the caller's thread", r.wire.posts.isEmpty());
		assertEquals(0, r.keyLoads.get());
		r.queue.runAll();

		assertEquals(1, r.wire.posts.size());
		String[] p = r.wire.posts.get(0);
		assertEquals("https://ingest.example:8443/api/auth/challenge", p[0]);
		assertEquals(r.key.bearer(), p[1]);
		JsonObject body = GSON.fromJson(p[2], JsonObject.class);
		JsonObject account = body.getAsJsonObject("account");
		assertEquals("-4611686018427387904", account.get("hash").getAsString());
		assertEquals("seasonal", account.get("mode").getAsString());
		assertEquals("Synth Alpha", account.get("name").getAsString());
		assertFalse("the secret only travels in the header", p[2].contains(r.key.bearer().substring(40)));
		assertTrue(r.results.isEmpty());
	}

	@Test
	public void switchedOffSendsNothing() throws IOException
	{
		Rig r = new Rig();
		r.on.set(false);
		r.ask(ALPHA);
		r.queue.runAll();
		assertTrue(r.wire.posts.isEmpty());
		assertEquals(0, r.keyLoads.get());
		assertEquals(AuthClient.Problem.OFF, r.only().problem);
	}

	@Test
	public void noCharacterOrNoValidAddressSendsNothing() throws IOException
	{
		Rig r = new Rig();
		r.ask(null);
		r.queue.runAll();
		assertEquals(AuthClient.Problem.NOT_LOGGED_IN, r.only().problem);

		for (String bad : new String[]{"", "http://ingest.example/api", "ftp://x.example", "https://user@x.example/"})
		{
			Rig s = new Rig();
			s.upload = bad;
			s.ask(ALPHA);
			s.queue.runAll();
			assertEquals(bad, AuthClient.Problem.NO_ADDRESS, s.only().problem);
			assertTrue(s.wire.posts.isEmpty());
		}
		assertEquals("http://localhost:8787/api/auth/challenge", AuthClient.challengeUrl("http://localhost:8787/api/ingest"));
	}

	@Test
	public void aKeyThatCannotBeReadIsReported() throws IOException
	{
		Wire wire = new Wire();
		Queue queue = new Queue();
		List<AuthClient.Result> results = new ArrayList<>();
		AuthClient c = new AuthClient(wire, queue, () ->
		{
			throw new IOException("disk");
		}, () -> UPLOAD, () -> true, () -> NOW, GSON);
		c.request(ALPHA, results::add);
		queue.runAll();
		assertTrue(wire.posts.isEmpty());
		assertEquals(AuthClient.Problem.KEY, results.get(0).problem);
	}

	@Test
	public void theUploadAndSignInShareOneKey() throws IOException
	{
		Filepath root = temp();
		AtomicInteger roots = new AtomicInteger();
		UploadSender.Keys keys = DeviceKey.shared(() ->
		{
			roots.incrementAndGet();
			return root;
		}, GSON);
		DeviceKey a = keys.get();
		long seq = a.nextSeq();
		assertSame(a, keys.get());
		assertEquals(1, roots.get());
		assertTrue(keys.get().nextSeq() > seq);
	}

	// ------------------------------------------------------------------ the answer

	@Test
	public void aGoodAnswerGivesTheLinkTheCodeAndTenMinutesAtMost() throws IOException
	{
		Rig r = new Rig();
		r.ask(ALPHA);
		r.queue.runAll();
		r.wire.answers.get(0).status(200, null, ok(",\"expiresIn\":600"));
		AuthClient.Result res = r.only();
		assertTrue(res.ok());
		assertEquals("https://site.example/auth?token=00ff", res.url);
		assertEquals("K7M4XP", res.code);
		assertEquals("https://site.example/login", res.loginUrl);
		assertEquals(NOW + 600_000, res.expiresAt);
		assertFalse("never the link in logs", res.toString().contains("token"));

		String url = "https://ingest.example/api/auth/challenge";
		assertEquals(NOW + 90_000, AuthClient.answer(200, null, ok(",\"expiresAt\":\""
			+ Instant.ofEpochMilli(NOW + 90_000) + "\""), url, NOW, GSON).expiresAt);
		assertEquals(NOW + AuthClient.MAX_TTL_MS, AuthClient.answer(200, null, ok(""), url, NOW, GSON).expiresAt);
		assertEquals(NOW + AuthClient.MAX_TTL_MS,
			AuthClient.answer(200, null, ok(",\"expiresIn\":86400"), url, NOW, GSON).expiresAt);
		assertEquals("https://other.example/login", AuthClient.answer(200, null,
			ok(",\"loginUrl\":\"https://other.example/login\""), url, NOW, GSON).loginUrl);
		assertEquals("K7M-4XP9", AuthClient.answer(200, null,
			"{\"url\":\"https://site.example/auth?token=1\",\"code\":\"K7M-4XP9\"}", url, NOW, GSON).code);
	}

	@Test
	public void unsafeOrBrokenAnswersAreRefused()
	{
		String url = "https://ingest.example/api/auth/challenge";
		for (String body : new String[]{
			"{\"url\":\"http://site.example/auth?token=1\",\"code\":\"K7M4XP\"}", // plain http off this PC
			"{\"url\":\"javascript:alert(1)\",\"code\":\"K7M4XP\"}",
			"{\"url\":\"https://site.example/auth?token=1\"}",
			"{\"url\":\"https://site.example/auth?token=1\",\"code\":\"<b>hi</b>\"}",
			"{\"url\":\"https://site.example/auth?token=1\",\"code\":\"K7M4XP\",\"expiresAt\":\"soon\"}",
			"not json",
			"[]",
			"",
		})
		{
			assertEquals(body, AuthClient.Problem.BAD_ANSWER, AuthClient.answer(200, null, body, url, NOW, GSON).problem);
		}
		assertEquals(AuthClient.Problem.BAD_ANSWER, AuthClient.answer(200, null, null, url, NOW, GSON).problem);
	}

	@Test
	public void statusCodesMeanWhatTheySay() throws IOException
	{
		String url = "https://ingest.example/api/auth/challenge";
		assertEquals(AuthClient.Problem.REFUSED, AuthClient.answer(401, null, "", url, NOW, GSON).problem);
		assertEquals(AuthClient.Problem.REFUSED, AuthClient.answer(403, null, "", url, NOW, GSON).problem);
		AuthClient.Result busy = AuthClient.answer(429, "120", "", url, NOW, GSON);
		assertEquals(AuthClient.Problem.BUSY, busy.problem);
		assertEquals(120_000, busy.retryAfterMs);
		assertEquals("Too many tries. Wait 2 min.", SignInFlow.problemText(busy));
		assertEquals(AuthClient.Problem.UNREACHABLE, AuthClient.answer(503, null, "", url, NOW, GSON).problem);
		assertEquals(AuthClient.Problem.UNREACHABLE, AuthClient.answer(408, null, "", url, NOW, GSON).problem);
		assertEquals(AuthClient.Problem.BAD_ANSWER, AuthClient.answer(404, null, ok(""), url, NOW, GSON).problem);
		assertEquals(AuthClient.Problem.BAD_ANSWER, AuthClient.answer(302, null, ok(""), url, NOW, GSON).problem);

		Rig r = new Rig();
		r.ask(ALPHA);
		r.queue.runAll();
		r.wire.answers.get(0).failed(new IOException("offline"));
		assertEquals(AuthClient.Problem.UNREACHABLE, r.only().problem);
	}

	@Test
	public void theWordsForEachProblemArePlain()
	{
		for (AuthClient.Problem p : AuthClient.Problem.values())
		{
			assertFalse(SignInFlow.problemText(AuthClient.Result.failed(p)).isEmpty());
		}
		assertEquals("9:41", SignInFlow.timeLeft(580_001));
		assertEquals("0:01", SignInFlow.timeLeft(1));
		assertEquals("site.example/login", SignInFlow.shortAddress("https://site.example/login"));
	}

	// ------------------------------------------------------------------ only the latest request counts

	@Test
	public void aNewerRequestCancelsTheOlderAndDropsItsAnswer() throws IOException
	{
		Rig r = new Rig();
		r.ask(ALPHA);
		r.queue.runAll();
		r.ask(ALPHA);
		assertEquals("the older request is cancelled at once", 1, r.wire.cancelled.get());
		r.queue.runAll();
		r.wire.answers.get(0).status(200, null, ok("")); // the older answer, late
		assertTrue(r.results.isEmpty());
		r.wire.answers.get(1).status(200, null, ok(""));
		assertTrue(r.only().ok());
	}

	@Test
	public void cancellingOrSwitchingOffDropsTheAnswer() throws IOException
	{
		Rig r = new Rig();
		r.ask(ALPHA);
		r.queue.runAll();
		r.client.cancel();
		assertEquals(1, r.wire.cancelled.get());
		r.wire.answers.get(0).status(200, null, ok(""));
		assertTrue(r.results.isEmpty());

		Rig s = new Rig();
		s.ask(ALPHA);
		s.queue.runAll();
		s.on.set(false); // switched off, before RuneLite tells the plugin
		s.wire.answers.get(0).status(200, null, ok(""));
		assertTrue(s.results.isEmpty());

		Rig t = new Rig();
		t.ask(ALPHA);
		t.client.cancel(); // before the executor ran it
		t.queue.runAll();
		assertTrue(t.wire.posts.isEmpty());
		assertTrue(t.results.isEmpty());
	}

	@Test
	public void manyThreadsAskingAtOnceGetOneAnswerForTheLatest() throws Exception
	{
		Wire wire = new Wire();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		DeviceKey key = DeviceKey.loadOrCreate(temp(), GSON, new SecureRandom());
		List<AuthClient.Result> results = Collections.synchronizedList(new ArrayList<>());
		AuthClient c = new AuthClient(wire, pool, () -> key, () -> UPLOAD, () -> true, () -> NOW, GSON);
		int threads = 8;
		CountDownLatch go = new CountDownLatch(1);
		CountDownLatch asked = new CountDownLatch(threads);
		for (int i = 0; i < threads; i++)
		{
			new Thread(() ->
			{
				try
				{
					go.await();
					c.request(ALPHA, results::add);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
				asked.countDown();
			}).start();
		}
		go.countDown();
		assertTrue(asked.await(10, TimeUnit.SECONDS));
		pool.shutdown();
		assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
		List<UploadSender.Answer> answers;
		synchronized (wire.answers)
		{
			answers = new ArrayList<>(wire.answers);
		}
		assertTrue(answers.size() >= 1 && answers.size() <= threads);
		for (UploadSender.Answer a : answers)
		{
			a.status(200, null, ok(""));
		}
		assertEquals("only the latest request's answer arrives", 1, results.size());
		assertNull(results.get(0).problem);
	}

	// ------------------------------------------------------------------ the panel's two actions (SignInFlow)

	/** What the panel would show, recorded. */
	private static final class Shown implements SignInFlow.View
	{
		final List<String> browsed = new ArrayList<>();
		final List<String> codes = new ArrayList<>();
		final List<String> messages = new ArrayList<>();
		boolean busy;
		int clears;

		@Override
		public void busy(SignInFlow.Action action)
		{
			busy = true;
		}

		@Override
		public void idle()
		{
			busy = false;
		}

		@Override
		public void browse(String url)
		{
			browsed.add(url);
		}

		@Override
		public void showCode(AuthClient.Result code, String timeLeft)
		{
			codes.add(code.code);
		}

		@Override
		public void countdown(String timeLeft)
		{
		}

		@Override
		public void message(String text)
		{
			messages.add(text);
		}

		@Override
		public void clear()
		{
			clears++;
		}
	}

	/**
	 * A website that pairs each link with a code, as task 1 does: redeeming either burns both. Answers each challenge
	 * with a new pair.
	 */
	private static final class Site
	{
		final java.util.Map<String, String> codeOfToken = new java.util.HashMap<>();
		final java.util.Set<String> burned = new java.util.HashSet<>();
		int issued;

		void answer(UploadSender.Answer a)
		{
			issued++;
			String token = "t" + issued;
			String code = "CODE" + issued;
			codeOfToken.put(token, code);
			a.status(200, null, "{\"url\":\"https://site.example/auth?token=" + token + "\",\"code\":\"" + code + "\"}");
		}

		boolean redeemLink(String url)
		{
			String token = url.substring(url.indexOf("token=") + 6);
			String code = codeOfToken.get(token);
			return code != null && burned.add(token) && burned.add(code);
		}

		boolean redeemCode(String code)
		{
			return codeOfToken.containsValue(code) && burned.add(code);
		}
	}

	private static SignInFlow flow(Rig r, Shown shown, java.util.function.Supplier<ProfileKey> playing)
	{
		return new SignInFlow(new SignInFlow.Requests()
		{
			@Override
			public boolean on()
			{
				return r.on.get();
			}

			@Override
			public void request(AuthClient.Listener done)
			{
				r.client.request(playing.get(), done);
			}
		}, shown, Runnable::run, () -> "https://site.example", () -> NOW);
	}

	@Test
	public void openingTheBrowserThenAskingForAPhoneCodeUsesTwoChallengesAndBothWork() throws IOException
	{
		Rig r = new Rig();
		Shown shown = new Shown();
		Site site = new Site();
		SignInFlow f = flow(r, shown, () -> ALPHA);

		f.click(SignInFlow.Action.BROWSER, true);
		r.queue.runAll();
		site.answer(r.wire.answers.get(0));
		assertEquals(1, shown.browsed.size());
		assertTrue("no code with the browser", shown.codes.isEmpty());
		assertTrue(site.redeemLink(shown.browsed.get(0))); // the browser signs in, burning its pair

		f.click(SignInFlow.Action.PHONE, true);
		r.queue.runAll();
		assertEquals("a fresh challenge for the phone", 2, r.wire.posts.size());
		site.answer(r.wire.answers.get(1));
		assertEquals("no second browser", 1, shown.browsed.size());
		assertEquals(Collections.singletonList("CODE2"), shown.codes);
		assertTrue("the phone code still works", site.redeemCode(shown.codes.get(0)));
		assertFalse(shown.busy);
	}

	@Test
	public void signInOffOpensJustTheWebsiteAndSendsNothing() throws IOException
	{
		Rig r = new Rig();
		r.on.set(false);
		Shown shown = new Shown();
		SignInFlow f = flow(r, shown, () -> ALPHA);
		f.click(SignInFlow.Action.BROWSER, true);
		f.click(SignInFlow.Action.PHONE, true);
		f.click(SignInFlow.Action.PHONE, false);
		r.queue.runAll();
		assertTrue(r.wire.posts.isEmpty());
		assertEquals(Collections.singletonList("https://site.example"), shown.browsed);
		assertEquals(2, shown.messages.size());
	}

	@Test
	public void switchingOffAndOnWhileWaitingFreesTheButtons() throws IOException
	{
		Rig r = new Rig();
		Shown shown = new Shown();
		SignInFlow f = flow(r, shown, () -> ALPHA);
		r.client.onReset(f::reset); // the plugin hops to the Swing thread; here it is all one thread

		f.click(SignInFlow.Action.PHONE, true);
		r.queue.runAll();
		assertTrue(f.waiting());
		assertTrue(shown.busy);

		r.on.set(false);
		r.client.onConfigChanged(AuthClient.ENABLE_KEY);
		assertFalse("not stuck waiting", f.waiting());
		assertFalse(shown.busy);
		assertEquals(1, r.wire.cancelled.get());
		r.wire.answers.get(0).status(200, null, ok("")); // late: ignored
		assertTrue(shown.codes.isEmpty());

		r.on.set(true);
		r.client.onConfigChanged(AuthClient.ENABLE_KEY);
		f.click(SignInFlow.Action.PHONE, true);
		r.queue.runAll();
		assertEquals(2, r.wire.posts.size());
		r.wire.answers.get(1).status(200, null, ok(""));
		assertEquals(Collections.singletonList("K7M4XP"), shown.codes);
		assertFalse(f.waiting());

		f.click(SignInFlow.Action.BROWSER, true); // the upload switched off while waiting frees them too
		r.on.set(false);
		r.client.onConfigChanged(UploadCapture.ENABLE_KEY);
		assertFalse(f.waiting());
		assertTrue("the code shown is cleared", shown.clears > 0);
	}

	// ------------------------------------------------------------------ the game's lifecycle

	@Test
	public void aLogoutCancelsARequestNotYetSentAndTellsThePanel() throws IOException
	{
		Rig r = new Rig();
		AtomicInteger resets = new AtomicInteger();
		r.client.onReset(resets::incrementAndGet);
		GameFake g = new GameFake();
		g.captures.add(r.client);
		g.logIn();
		ProfileKey playing = g.login.reading();
		assertTrue(playing != null);

		r.ask(playing); // queued on the executor, not yet sent
		g.logOut();
		r.queue.runAll();
		assertTrue("the old character is never sent", r.wire.posts.isEmpty());
		assertTrue(r.results.isEmpty());
		assertEquals(1, resets.get());

		g.logIn();
		r.ask(g.login.reading());
		r.queue.runAll();
		assertEquals(1, r.wire.posts.size());
		g.logOut(); // in flight: cancelled, its answer dropped
		assertEquals(1, r.wire.cancelled.get());
		r.wire.answers.get(0).status(200, null, ok(""));
		assertTrue(r.results.isEmpty());
	}

	@Test
	public void aHopIntoAnotherGameModeCancelsLikeALogout() throws IOException
	{
		Rig r = new Rig();
		AtomicInteger resets = new AtomicInteger();
		r.client.onReset(resets::incrementAndGet);
		GameFake g = new GameFake();
		g.captures.add(r.client);
		g.logIn();
		ProfileKey main = g.login.reading();
		r.ask(main);
		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL);
		r.queue.runAll();
		assertTrue(r.wire.posts.isEmpty());
		assertEquals(1, resets.get());

		g.hop(501, WorldType.MEMBERS, WorldType.SEASONAL); // the same mode: nothing cancelled
		assertEquals(1, resets.get());
	}
}
