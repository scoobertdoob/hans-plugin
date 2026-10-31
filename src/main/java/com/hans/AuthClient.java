package com.hans;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks RSBuddy's website for a one-time sign-in link and short code for the character being played (card #76,
 * docs/plans/plan-76.md): {@code POST /api/auth/challenge} at the upload address's site, with this PC's key
 * ({@link DeviceKey#bearer}) and the account hash, mode and name. Only while sign-in and the upload are both switched
 * on: otherwise nothing is sent.
 *
 * <p>Every public method returns at once. The key is loaded (disk IO) and the request made on the plugin's executor,
 * never on the client or Swing thread; the key is the one the upload uses ({@link DeviceKey#shared}). The only state is
 * which request is the latest: a newer request, a switch-off or {@link #cancel} drops the older one's answer and
 * cancels it if it is still in flight. The answer reaches the listener on any thread.
 *
 * <p>Registered as a {@link Capture} for the game's lifecycle: every logout, change of character or game mode
 * ({@link #onLogout}) and every change to the sign-in or upload settings cancels, and tells the panel ({@link #onReset})
 * so nothing is left waiting for an answer that will never come.
 */
@Slf4j
final class AuthClient implements Capture
{
	static final String ENABLE_KEY = "signIn";
	static final String PATH = "/api/auth/challenge";
	static final long MAX_TTL_MS = 600_000; // the website's codes last 10 minutes
	static final int MAX_ANSWER = 4096;

	/** Why no link came back. */
	enum Problem
	{
		OFF, // sign-in or the upload is switched off
		NO_ADDRESS, // no valid upload address in the settings
		NOT_LOGGED_IN, // no settled login to sign in as
		KEY, // this PC's key could not be read or made
		REFUSED, // 401 or 403: the website does not accept this PC (unlinked)
		BUSY, // 429: too many tries
		UNREACHABLE, // no answer, or 408 or 5xx
		BAD_ANSWER // any other answer, or one without a usable link and code
	}

	/** What came back: a link and code, or the problem. Immutable. */
	static final class Result
	{
		final String url; // opens the browser, signed in
		final String code; // typed on a phone at loginUrl
		final String loginUrl;
		final long expiresAt; // this PC's clock, epoch ms
		final Problem problem;
		final long retryAfterMs; // BUSY: how long the website asks to wait (0 if it did not say)

		private Result(String url, String code, String loginUrl, long expiresAt, Problem problem, long retryAfterMs)
		{
			this.url = url;
			this.code = code;
			this.loginUrl = loginUrl;
			this.expiresAt = expiresAt;
			this.problem = problem;
			this.retryAfterMs = retryAfterMs;
		}

		static Result failed(Problem problem)
		{
			return new Result(null, null, null, 0, problem, 0);
		}

		boolean ok()
		{
			return problem == null;
		}

		@Override
		public String toString()
		{
			return ok() ? "Result(code, expires " + expiresAt + ")" : "Result(" + problem + ")"; // never the link
		}
	}

	interface Listener
	{
		void done(Result result);
	}

	private final UploadSender.Transport transport;
	private final Executor executor;
	private final UploadSender.Keys keys;
	private final Supplier<String> uploadUrl;
	private final BooleanSupplier enabled;
	private final LongSupplier clock;
	private final Gson gson;

	private final AtomicInteger latest = new AtomicInteger();
	private final AtomicReference<Runnable> inFlight = new AtomicReference<>();
	private volatile Runnable onReset = () -> { };

	/**
	 * @param enabled sign-in and the upload both switched on
	 */
	AuthClient(UploadSender.Transport transport, Executor executor, UploadSender.Keys keys, Supplier<String> uploadUrl,
		BooleanSupplier enabled, LongSupplier clock, Gson gson)
	{
		this.transport = transport;
		this.executor = executor;
		this.keys = keys;
		this.uploadUrl = uploadUrl;
		this.enabled = enabled;
		this.clock = clock;
		this.gson = gson;
	}

	/**
	 * Asks for a link and code for {@code profile} (read on the client thread by the caller; null when no reading may
	 * be taken). Replaces any request still waiting. Returns at once.
	 */
	void request(ProfileKey profile, Listener listener)
	{
		int ticket = latest.incrementAndGet();
		cancelInFlight();
		executor.execute(() -> send(ticket, profile, listener));
	}

	/** Run on every {@link #cancel}, on the caller's thread. */
	void onReset(Runnable r)
	{
		onReset = r;
	}

	/**
	 * Switched off, logged out, another character or mode, or stopping: the request in flight is cancelled, its answer
	 * dropped, and {@link #onReset} told.
	 */
	void cancel()
	{
		latest.incrementAndGet();
		cancelInFlight();
		onReset.run();
	}

	/** Every end of a login under a key: a logout, or a change of character or game mode (client thread). */
	@Override
	public Future<?> onLogout(Instant now)
	{
		cancel();
		return null;
	}

	@Override
	public void onConfigChanged(String key)
	{
		if (ENABLE_KEY.equals(key) || UploadCapture.ENABLE_KEY.equals(key) || UploadCapture.URL_KEY.equals(key))
		{
			cancel();
		}
	}

	private void cancelInFlight()
	{
		Runnable c = inFlight.getAndSet(null);
		if (c != null)
		{
			c.run();
		}
	}

	// ------------------------------------------------------------------ on the executor

	private void send(int ticket, ProfileKey profile, Listener listener)
	{
		if (!current(ticket))
		{
			return;
		}
		if (!enabled.getAsBoolean())
		{
			deliver(ticket, listener, Result.failed(Problem.OFF));
			return;
		}
		if (profile == null)
		{
			deliver(ticket, listener, Result.failed(Problem.NOT_LOGGED_IN));
			return;
		}
		String url = challengeUrl(uploadUrl.get());
		if (url == null)
		{
			deliver(ticket, listener, Result.failed(Problem.NO_ADDRESS));
			return;
		}
		DeviceKey key;
		try
		{
			key = keys.get(); // disk IO, here on the executor only
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Hans sign-in: could not prepare this PC's key", ex);
			deliver(ticket, listener, Result.failed(Problem.KEY));
			return;
		}
		if (!current(ticket) || !enabled.getAsBoolean())
		{
			return; // replaced or switched off while the key was read: not sent
		}
		String body = gson.toJson(body(profile));
		Runnable cancel;
		try
		{
			cancel = transport.post(url, key.bearer(), body, new UploadSender.Answer()
			{
				@Override
				public void status(int code, String retryAfter, String answer)
				{
					deliver(ticket, listener, answer(code, retryAfter, answer, url, clock.getAsLong(), gson));
				}

				@Override
				public void failed(IOException ex)
				{
					log.debug("Hans sign-in: no answer", ex);
					deliver(ticket, listener, Result.failed(Problem.UNREACHABLE));
				}
			});
		}
		catch (RuntimeException ex)
		{
			log.warn("Hans sign-in: could not send", ex);
			deliver(ticket, listener, Result.failed(Problem.UNREACHABLE));
			return;
		}
		if (cancel != null)
		{
			inFlight.set(cancel);
			if (!current(ticket) || !enabled.getAsBoolean())
			{
				cancelInFlight(); // replaced or switched off during the post itself: cancelled at once, its answer dropped
			}
		}
	}

	private boolean current(int ticket)
	{
		return latest.get() == ticket;
	}

	/** Only the latest request's answer, and only while still switched on (a problem saying so is the exception). */
	private void deliver(int ticket, Listener listener, Result result)
	{
		if (!current(ticket) || result.problem != Problem.OFF && !enabled.getAsBoolean())
		{
			return;
		}
		try
		{
			listener.done(result);
		}
		catch (RuntimeException e)
		{
			log.warn("Hans sign-in: unexpected error", e);
		}
	}

	// ------------------------------------------------------------------ the request and the answer (pure)

	/** The request: whose sign-in it is. The PC is named by the Authorization header. */
	static JsonObject body(ProfileKey profile)
	{
		JsonObject account = new JsonObject();
		account.addProperty("hash", profile.hashText());
		account.addProperty("mode", profile.mode);
		account.addProperty("name", profile.name.replace(' ', ' ')); // the client's names use non-breaking spaces
		JsonObject o = new JsonObject();
		o.add("account", account);
		return o;
	}

	/** The challenge address at the upload address's site (scheme, host and port), or null if that is not valid. */
	static String challengeUrl(String upload)
	{
		String origin = origin(upload);
		return origin == null ? null : origin + PATH;
	}

	/**
	 * What an answer means. 200 with {@code {"url", "code", "expiresAt" (ISO-8601) or "expiresIn" (seconds),
	 * "loginUrl" (optional)}}; the link must be https (or http to this PC, as for the upload); the code is shown as
	 * sent. The expiry is counted on this PC's clock and never beyond 10 minutes.
	 */
	static Result answer(int status, String retryAfter, String text, String challengeUrl, long now, Gson gson)
	{
		if (status == 401 || status == 403)
		{
			return Result.failed(Problem.REFUSED);
		}
		if (status == 429)
		{
			return new Result(null, null, null, 0, Problem.BUSY, UploadSender.retryAfterMs(retryAfter));
		}
		if (status == 408 || status >= 500)
		{
			return Result.failed(Problem.UNREACHABLE);
		}
		if (status < 200 || status >= 300)
		{
			return Result.failed(Problem.BAD_ANSWER);
		}
		try
		{
			JsonObject o = text == null ? null : gson.fromJson(text, JsonObject.class);
			String url = string(o, "url");
			String code = string(o, "code");
			if (url == null || !UploadSender.allowedEndpoint(url) || code == null)
			{
				return Result.failed(Problem.BAD_ANSWER);
			}
			code = code.trim().toUpperCase(Locale.ROOT);
			if (!code.matches("(?=.{4,16}$)[0-9A-Z]+(-[0-9A-Z]+)?"))
			{
				return Result.failed(Problem.BAD_ANSWER);
			}
			String login = string(o, "loginUrl");
			if (login == null || !UploadSender.allowedEndpoint(login))
			{
				String site = origin(url);
				login = (site == null ? origin(challengeUrl) : site) + "/login";
			}
			return new Result(url.trim(), code, login.trim(), now + ttl(o, now), null, 0);
		}
		catch (RuntimeException e)
		{
			return Result.failed(Problem.BAD_ANSWER);
		}
	}

	private static long ttl(JsonObject o, long now)
	{
		long ttl = MAX_TTL_MS;
		JsonElement in = o.get("expiresIn");
		JsonElement at = o.get("expiresAt");
		if (in != null && in.isJsonPrimitive() && in.getAsJsonPrimitive().isNumber())
		{
			ttl = (long) (in.getAsDouble() * 1000);
		}
		else if (at != null && at.isJsonPrimitive() && at.getAsJsonPrimitive().isString())
		{
			ttl = Instant.parse(at.getAsString()).toEpochMilli() - now;
		}
		return Math.max(0, Math.min(MAX_TTL_MS, ttl));
	}

	private static String string(JsonObject o, String name)
	{
		JsonElement e = o == null ? null : o.get(name);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	/** {@code scheme://host[:port]} of an address allowed for the upload, or null. */
	static String origin(String url)
	{
		if (!UploadSender.allowedEndpoint(url))
		{
			return null;
		}
		try
		{
			URI u = new URI(url.trim());
			String scheme = u.getScheme().toLowerCase(Locale.ROOT);
			String host = u.getHost().toLowerCase(Locale.ROOT);
			return scheme + "://" + host + (u.getPort() >= 0 ? ":" + u.getPort() : "");
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
