package com.hans;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the latest readings to RSBuddy's website in batches, off the client thread.
 *
 * <p><b>One owner.</b> All of the sender's state (unsent readings, the request in flight, what the website accepted,
 * the destination's generation, the next send time, the logout flag, this PC's key and its sequence numbers) is touched
 * only by its mailbox, which runs one message at a time on the plugin's executor. Every public method only posts a
 * message and returns: none takes a lock, waits, or touches the disk. Timers and HTTP answers post messages too.
 *
 * <ul>
 * <li>A reading replaces the category's unsent one for the same account and mode (at most {@link #MAX_PROFILES}
 * accounts and modes; the oldest is dropped beyond that; the files on disk still have everything). It is not sent if
 * it equals the latest value already on its way (unsent, in flight, or else accepted): an idle player sends
 * nothing.</li>
 * <li>One request in flight. The website sets the gap in each answer (owner, 30 Oct): {@link #DEFAULT_INTERVAL_MS} in
 * the background, {@link #MIN_INTERVAL_MS} while someone has RSBuddy open. After a logout, the unsent readings go at
 * the earliest moment allowed ({@link #FLUSH_GAP_MS} after the last request) until none is left; a failure's backoff
 * still applies.</li>
 * <li>A failed request (no answer, 408, 429 or 5xx) puts its readings back unless newer ones arrived, and backs off
 * exponentially with jitter, or longer if the website says so (Retry-After). 401 or 403 stops sending until the
 * settings change; other answers drop the batch (the next one carries the latest state anyway).</li>
 * <li>Switching off, or a new address, starts a new destination generation: the request in flight is cancelled and
 * any answer from an older generation is ignored. A batch being prepared when that happens is not posted.</li>
 * <li>A batch is packed under {@link #BATCH_BYTES}, progress first and the bank last; what does not fit follows at the
 * short gap, so a full snapshot goes as 2 or 3 requests (the website allows a short burst, then one per 2 seconds). A
 * bank past that size goes in parts ({chunk, of, items}, one per request, same capturedAt), started again whole if
 * one fails. Nothing over {@link #MAX_BODY} is ever posted.</li>
 * <li>A settled login ({@link #snapshot}) and a new destination send every category again: the latest reading of
 * each one offered while switched on is kept for that (switching off forgets them).</li>
 * <li>Stopping the plugin sends one last batch only if this PC's key and a sequence number are already in memory.</li>
 * </ul>
 */
@Slf4j
final class UploadSender
{
	static final long MIN_INTERVAL_MS = 5_000;
	static final long DEFAULT_INTERVAL_MS = 60_000;
	static final long MAX_INTERVAL_MS = 600_000;
	static final long FLUSH_GAP_MS = 2_500; // just over the website's 2-second minimum between two requests
	static final long BACKOFF_BASE_MS = 5_000;
	static final long BACKOFF_MAX_MS = 300_000;
	static final long RETRY_AFTER_MAX_MS = 3_600_000;
	static final int MAX_PROFILES = 4;
	/** A batch is packed to stay under this (owner, 31 Oct: the login snapshot goes as 2 or 3 batches of ~24 KiB). */
	static final int BATCH_BYTES = 24 * 1024;
	/** The website's hard limit on one request's body: nothing larger is ever posted. */
	static final int MAX_BODY = 32 * 1024;
	/** A bank past {@link #BATCH_BYTES} goes in 2 to this many parts, one per request, joined by the website. */
	static final int MAX_PARTS = 4;
	private static final String OVERHEAD_KEY_ID = "00000000000000000000000000000000"; // sizes the envelope's own fields

	/**
	 * One POST of a JSON body. Must not block: the answer arrives through the callback, on any thread.
	 *
	 * @return cancels the request if it has not completed (may be null)
	 */
	interface Transport
	{
		Runnable post(String url, String authorization, String json, Answer answer);
	}

	interface Answer
	{
		/** The server answered. {@code retryAfter}: the Retry-After header, or null. */
		void status(int code, String retryAfter, String body);

		/** No answer (no connection, timeout, cancelled). */
		void failed(IOException e);
	}

	/** The plugin's executor: runs the mailbox, now or later. */
	interface Scheduler
	{
		void execute(Runnable task);

		Future<?> schedule(Runnable task, long delayMs);
	}

	/** This PC's key, loaded on first use (disk IO, on the executor). */
	interface Keys
	{
		DeviceKey get() throws IOException;
	}

	private final Transport transport;
	private final Scheduler scheduler;
	private final Keys keys;
	private final Supplier<String> endpoint;
	private final BooleanSupplier enabled;
	private final LongSupplier clock;
	private final Random random;
	private final Gson gson;

	private final ConcurrentLinkedQueue<Runnable> mailbox = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean draining = new AtomicBoolean();
	// raised by every switch-off, address change and close, before its message: a batch being prepared checks it
	private final AtomicInteger epoch = new AtomicInteger();
	// raised only by a switch-off, synchronously by the caller: checked again just before every post, on every path
	private final AtomicInteger optOuts = new AtomicInteger();

	// ---- owned by the mailbox: touched only inside a message
	private final LinkedHashMap<ProfileKey, Map<String, UploadEnvelope.Reading>> pending = new LinkedHashMap<>();
	private final LinkedHashMap<ProfileKey, Map<String, JsonObject>> accepted = new LinkedHashMap<>(); // this generation
	// the latest reading of each category offered while switched on, to send whole again (a login, a new address)
	private final LinkedHashMap<ProfileKey, Map<String, UploadEnvelope.Reading>> known = new LinkedHashMap<>();
	private Parts parts; // a bank being sent in parts, or null
	private boolean followUp; // the last batch was split: the rest goes at the short gap
	private Flight flight;
	private int generation; // the destination's: a new address or a switch-off starts the next one
	private Future<?> timer;
	private long timerAt;
	private long lastAttempt = Long.MIN_VALUE / 2;
	private long backoffUntil;
	private long intervalMs = DEFAULT_INTERVAL_MS;
	private int failures;
	private boolean flushWanted;
	private boolean refused;
	private boolean closed;
	private DeviceKey key;
	private String warned; // the last problem logged, so a repeating one is logged once

	/** One request's worth: what the website will have once it is accepted, and what goes on the wire for it. */
	private static final class Send
	{
		final ProfileKey profile;
		final Map<String, UploadEnvelope.Reading> batch; // remembered as accepted (a bank in parts: with its last part)
		final List<UploadEnvelope.Reading> wire;
		final Parts parts; // the bank this request carries a part of, or null
		final boolean lastPart;

		Send(ProfileKey profile, Map<String, UploadEnvelope.Reading> batch, List<UploadEnvelope.Reading> wire, Parts parts,
			boolean lastPart)
		{
			this.profile = profile;
			this.batch = batch;
			this.wire = wire;
			this.parts = parts;
			this.lastPart = lastPart;
		}
	}

	/** A bank too big for one batch: its parts still to send, one per request, all with the whole bank's capturedAt. */
	private static final class Parts
	{
		final int generation;
		final ProfileKey profile;
		final UploadEnvelope.Reading whole;
		final ArrayDeque<UploadEnvelope.Reading> left;

		Parts(int generation, ProfileKey profile, UploadEnvelope.Reading whole, List<UploadEnvelope.Reading> parts)
		{
			this.generation = generation;
			this.profile = profile;
			this.whole = whole;
			this.left = new ArrayDeque<>(parts);
		}
	}

	/** The request in flight. */
	private static final class Flight
	{
		final int generation;
		final Send send;
		final ProfileKey profile;
		final Map<String, UploadEnvelope.Reading> batch;
		Runnable cancel;

		Flight(int generation, Send send)
		{
			this.generation = generation;
			this.send = send;
			this.profile = send.profile;
			this.batch = send.batch;
		}
	}

	UploadSender(Transport transport, Scheduler scheduler, Keys keys, Supplier<String> endpoint, BooleanSupplier enabled,
		LongSupplier clock, Random random, Gson gson)
	{
		this.transport = transport;
		this.scheduler = scheduler;
		this.keys = keys;
		this.endpoint = endpoint;
		this.enabled = enabled;
		this.clock = clock;
		this.random = random;
		this.gson = gson;
	}

	// ------------------------------------------------------------------ messages (any thread; never wait)

	/** A category's latest reading (client thread). */
	void offer(ProfileKey profile, UploadEnvelope.Reading reading)
	{
		tell(() -> onOffer(profile, reading));
	}

	/** Uploading was switched off: drop everything unsent and cancel the request in flight. */
	void clear()
	{
		optOuts.incrementAndGet();
		epoch.incrementAndGet();
		tell(this::onClear);
	}

	/**
	 * The settings changed (switched on, or a new address): a new destination generation. A refusal may be tried
	 * again, and the unsent readings, with the one in flight, go to the destination now set.
	 */
	void settingsChanged()
	{
		epoch.incrementAndGet();
		tell(this::onSettings);
	}

	/**
	 * A login settled for this account and mode: the website gets every category again (a full snapshot), not only
	 * what changed: the latest reading of each one offered since the plugin started, then the fresh readings.
	 */
	void snapshot(ProfileKey profile)
	{
		tell(() -> onSnapshot(profile));
	}

	/** Logout: the unsent readings go at the earliest moment allowed, until none is left. */
	void flushNow()
	{
		tell(this::onLogout);
	}

	/** The plugin is stopping. Returns at once; see {@link #onClose}. */
	void close()
	{
		epoch.incrementAndGet();
		tell(this::onClose);
	}

	/** For tests, once the mailbox has run: how many accounts and modes have unsent readings. */
	int pendingProfiles()
	{
		return pending.size();
	}

	/** For tests, once the mailbox has run. */
	boolean refused()
	{
		return refused;
	}

	private void tell(Runnable message)
	{
		mailbox.add(message);
		kick();
	}

	private void kick()
	{
		if (draining.compareAndSet(false, true))
		{
			scheduler.execute(this::drain);
		}
	}

	/** Runs the messages one at a time; only one drain runs at once, whatever the executor. */
	private void drain()
	{
		try
		{
			Runnable m;
			while ((m = mailbox.poll()) != null)
			{
				try
				{
					m.run();
				}
				catch (RuntimeException e)
				{
					log.warn("Hans upload: unexpected error", e);
				}
			}
		}
		finally
		{
			draining.set(false);
		}
		if (!mailbox.isEmpty())
		{
			kick();
		}
	}

	// ------------------------------------------------------------------ the mailbox's own work

	private void onOffer(ProfileKey profile, UploadEnvelope.Reading reading)
	{
		if (closed || refused || !enabled.getAsBoolean())
		{
			return;
		}
		String category = reading.category;
		Map<String, UploadEnvelope.Reading> latestKnown = known.remove(profile);
		if (latestKnown == null)
		{
			latestKnown = new LinkedHashMap<>();
		}
		latestKnown.put(category, reading);
		known.put(profile, latestKnown);
		trim(known, null);
		JsonObject flying = inFlight(profile, category);
		JsonObject base = flying != null ? flying : acceptedValue(profile, category); // what the website will have
		JsonObject latest = valueIn(pending, profile, category);
		if (latest == null)
		{
			latest = base;
		}
		if (reading.data.equals(latest))
		{
			return; // the latest value on its way already
		}
		Map<String, UploadEnvelope.Reading> batch = pending.remove(profile);
		if (batch == null)
		{
			batch = new LinkedHashMap<>();
		}
		batch.put(category, reading);
		if (reading.data.equals(base))
		{
			batch.remove(category); // back to what the website has or is being sent: an unsent change undone
		}
		if (!batch.isEmpty())
		{
			pending.put(profile, batch); // the most recently changed account and mode last
			trim(pending, null);
		}
		arm();
	}

	private void onClear()
	{
		generation++;
		pending.clear();
		accepted.clear();
		known.clear(); // switched off: nothing read before is sent later
		parts = null;
		followUp = false;
		flushWanted = false;
		cancelFlight();
		cancelTimer();
	}

	private void onSettings()
	{
		generation++;
		accepted.clear();
		refused = false;
		warned = null;
		if (flight != null)
		{
			Flight f = flight;
			cancelFlight();
			putBack(f.profile, f.batch); // for the destination now set
		}
		dropParts(true);
		for (ProfileKey profile : new ArrayList<>(known.keySet()))
		{
			requeueKnown(profile); // the new destination gets every category, not only what changes next
		}
		arm();
	}

	private void onSnapshot(ProfileKey profile)
	{
		if (closed || refused || !enabled.getAsBoolean())
		{
			return;
		}
		accepted.remove(profile); // this login's first readings go even if the website had them
		requeueKnown(profile);
		arm();
	}

	/** Queues the latest known reading of each category, except one already unsent or on its way. */
	private void requeueKnown(ProfileKey profile)
	{
		Map<String, UploadEnvelope.Reading> all = known.get(profile);
		if (all == null)
		{
			return;
		}
		Map<String, UploadEnvelope.Reading> batch = pending.remove(profile);
		if (batch == null)
		{
			batch = new LinkedHashMap<>();
		}
		for (UploadEnvelope.Reading r : all.values())
		{
			if (!batch.containsKey(r.category) && !r.data.equals(inFlight(profile, r.category)))
			{
				batch.put(r.category, r);
			}
		}
		if (!batch.isEmpty())
		{
			pending.put(profile, batch);
			trim(pending, profile);
		}
	}

	/** Stops a bank being sent in parts; {@code resend}: it goes again whole, from its first part. */
	private void dropParts(boolean resend)
	{
		Parts p = parts;
		parts = null;
		if (p != null && resend)
		{
			putBack(p.profile, Collections.singletonMap(p.whole.category, p.whole));
		}
	}

	private void onLogout()
	{
		flushWanted = true;
		arm();
	}

	/**
	 * Sends one last batch if consent is on, nothing is in flight, and this PC's key and a sequence number are already
	 * in memory (no disk work; the post is only queued). Nothing is sent after that.
	 */
	private void onClose()
	{
		if (closed)
		{
			return;
		}
		String url = endpoint.get();
		int consent = optOuts.get();
		if (key != null && flight == null && !refused && hasWork() && enabled.getAsBoolean() && allowedEndpoint(url))
		{
			long seq = key.nextSeqInMemory();
			Send last = seq > 0 ? next() : null;
			if (last != null)
			{
				post(url, seq, last, consent, -1);
			}
		}
		closed = true;
		pending.clear();
		accepted.clear();
		known.clear();
		parts = null;
		cancelTimer();
	}

	/** The timer fired: send one account and mode's batch if it is time. */
	private void onTimer()
	{
		timer = null;
		if (closed || refused || flight != null || !hasWork())
		{
			return;
		}
		if (!enabled.getAsBoolean())
		{
			pending.clear(); // switched off, not yet told
			parts = null;
			return;
		}
		if (clock.getAsLong() < due())
		{
			arm();
			return;
		}
		String url = endpoint.get();
		if (!allowedEndpoint(url))
		{
			pending.clear();
			parts = null;
			refused = true; // until the address is changed
			warnOnce("Hans upload: no valid upload address is set (https://..., or http:// to this PC only);"
				+ " uploads are paused until the settings change");
			return;
		}
		int seen = epoch.get();
		int consent = optOuts.get();
		Send s = next();
		if (s == null)
		{
			arm();
			return; // nothing that may be sent was left
		}
		long seq;
		try
		{
			if (key == null)
			{
				key = keys.get(); // disk IO, here on the executor only
			}
			seq = key.nextSeq(); // may reserve a new block (disk IO)
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Hans upload: could not prepare the upload key", ex);
			giveBack(s);
			failed(null);
			arm();
			return;
		}
		if (epoch.get() != seen || !enabled.getAsBoolean())
		{
			// switched off, readdressed or stopping while the key was read: its message, next in the mailbox, decides
			giveBack(s);
			if (!enabled.getAsBoolean())
			{
				pending.clear();
				parts = null;
			}
			return;
		}
		post(url, seq, s, consent, seen);
	}

	private boolean hasWork()
	{
		return !pending.isEmpty() || parts != null;
	}

	/** The next request: the next part of a bank being sent in parts, else the oldest account and mode's batch. */
	private Send next()
	{
		if (parts != null && parts.generation != generation)
		{
			parts = null;
		}
		if (parts != null)
		{
			return nextPart();
		}
		if (pending.isEmpty())
		{
			return null;
		}
		Map.Entry<ProfileKey, Map<String, UploadEnvelope.Reading>> e = take();
		return pack(e.getKey(), e.getValue());
	}

	private Send nextPart()
	{
		Parts p = parts;
		UploadEnvelope.Reading part = p.left.poll();
		boolean last = p.left.isEmpty();
		Map<String, UploadEnvelope.Reading> whole = last ? Collections.singletonMap(p.whole.category, p.whole)
			: Collections.emptyMap();
		return new Send(p.profile, whole, Collections.singletonList(part), p, last);
	}

	/**
	 * Packs one account and mode's readings into a request under {@link #BATCH_BYTES}, in the order of
	 * {@link UploadEnvelope#PRIVACY} (progress first, the bank last); what does not fit goes back in front, to follow
	 * at the short gap. A reading bigger than a whole batch goes alone (under {@link #MAX_BODY}), except the bank,
	 * which is split into parts. One that cannot be sent at all is dropped and logged.
	 */
	private Send pack(ProfileKey profile, Map<String, UploadEnvelope.Reading> batch)
	{
		int overhead = bytes(UploadEnvelope.build(profile, OVERHEAD_KEY_ID, Long.MAX_VALUE,
			Instant.ofEpochMilli(clock.getAsLong()), Collections.emptyList()));
		Map<String, UploadEnvelope.Reading> send = new LinkedHashMap<>();
		Map<String, UploadEnvelope.Reading> rest = new LinkedHashMap<>();
		int used = overhead;
		followUp = false;
		for (UploadEnvelope.Reading r : packingOrder(batch))
		{
			int size = bytes(r.json()) + r.category.length() + 4; // "name":{...},
			if (UploadEnvelope.BANK.equals(r.category) && overhead + size > BATCH_BYTES)
			{
				if (parts == null && !startParts(profile, r, overhead, size))
				{
					continue; // too big even in parts: dropped
				}
				if (parts.whole != r)
				{
					rest.put(r.category, r); // another bank's parts are still going
				}
				continue;
			}
			Integer cap = UploadEnvelope.MAX_BYTES.get(r.category);
			if (overhead + size > MAX_BODY || cap != null && bytes(r.data) > cap)
			{
				// the website would refuse it: not sent, and not remembered, so its next change is tried again
				warnOnce("Hans upload: " + r.category + " is too large to send; it is kept in the files only");
				continue;
			}
			if (send.isEmpty() || used + size <= BATCH_BYTES)
			{
				send.put(r.category, r);
				used += size;
			}
			else
			{
				rest.put(r.category, r);
			}
		}
		if (!rest.isEmpty())
		{
			putBack(profile, rest);
			followUp = true;
		}
		if (send.isEmpty())
		{
			return parts != null ? nextPart() : null;
		}
		return new Send(profile, send, new ArrayList<>(send.values()), null, false);
	}

	/** Splits a bank into the fewest parts that each fit a batch; false (and dropped) if it needs more than allowed. */
	private boolean startParts(ProfileKey profile, UploadEnvelope.Reading bank, int overhead, int size)
	{
		int room = BATCH_BYTES - overhead - 64; // a part's own fields and the uneven split
		int of = Math.max(2, (size + room - 1) / room);
		if (of > MAX_PARTS)
		{
			warnOnce("Hans upload: the bank is too large to send; it is kept in the files only");
			return false;
		}
		parts = new Parts(generation, profile, bank, UploadEnvelope.parts(bank, of));
		return true;
	}

	/** The batch's readings, the known categories in their packing order first, then any others as they came. */
	private static List<UploadEnvelope.Reading> packingOrder(Map<String, UploadEnvelope.Reading> batch)
	{
		List<UploadEnvelope.Reading> out = new ArrayList<>();
		for (String category : UploadEnvelope.PRIVACY.keySet())
		{
			UploadEnvelope.Reading r = batch.get(category);
			if (r != null)
			{
				out.add(r);
			}
		}
		for (UploadEnvelope.Reading r : batch.values())
		{
			if (!UploadEnvelope.PRIVACY.containsKey(r.category))
			{
				out.add(r);
			}
		}
		return out;
	}

	private int bytes(JsonObject o)
	{
		return gson.toJson(o).getBytes(StandardCharsets.UTF_8).length;
	}

	/** A request that was not posted goes back: its readings in front, and a bank in parts again whole. */
	private void giveBack(Send s)
	{
		putBack(s.profile, s.batch);
		if (s.parts != null && s.parts == parts)
		{
			dropParts(true);
		}
	}

	/**
	 * Whether the batch may still go: no switch-off since {@code consent} was read (the caller raises the count before
	 * its message, so this sees it even while this message runs), uploads still on, and, unless closing
	 * ({@code seen} -1), no address change or close since {@code seen}.
	 */
	private boolean stillWanted(int consent, int seen)
	{
		return optOuts.get() == consent && enabled.getAsBoolean() && (seen < 0 || epoch.get() == seen);
	}

	private void post(String url, long seq, Send s, int consent, int seen)
	{
		long now = clock.getAsLong();
		String body = gson.toJson(UploadEnvelope.build(s.profile, key.keyId, seq, Instant.ofEpochMilli(now), s.wire));
		if (!stillWanted(consent, seen))
		{
			// switched off (or readdressed) while the envelope was built: not posted; the message behind this decides
			if (seen >= 0)
			{
				giveBack(s);
			}
			if (!enabled.getAsBoolean() || optOuts.get() != consent)
			{
				pending.clear();
				parts = null;
			}
			return;
		}
		if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY)
		{
			// never reached by a packed batch; a guard so the website's limit is never sent past
			warnOnce("Hans upload: a batch was too large to send and was dropped");
			if (s.parts != null && s.parts == parts)
			{
				parts = null;
			}
			arm();
			return;
		}
		lastAttempt = now;
		Flight f = new Flight(generation, s);
		flight = f;
		try
		{
			f.cancel = transport.post(url, key.bearer(), body, new Answer()
			{
				@Override
				public void status(int code, String retryAfter, String answer)
				{
					tell(() -> onAnswer(f, outcome(code), retryAfter, answer));
				}

				@Override
				public void failed(IOException ex)
				{
					log.debug("Hans upload: no answer", ex);
					tell(() -> onAnswer(f, Outcome.RETRY, null, null));
				}
			});
		}
		catch (RuntimeException ex)
		{
			log.warn("Hans upload: could not send", ex);
			tell(() -> onAnswer(f, Outcome.RETRY, null, null));
			return;
		}
		if (optOuts.get() != consent || !enabled.getAsBoolean())
		{
			cancelFlight(); // switched off during the enqueue itself: cancelled at once, its answer ignored
		}
	}

	enum Outcome
	{
		SENT, RETRY, REFUSED, DROPPED
	}

	static Outcome outcome(int code)
	{
		if (code >= 200 && code < 300 || code == 409) // 409: stale or already seen; the next batch is newer
		{
			return Outcome.SENT;
		}
		if (code == 408 || code == 429 || code >= 500)
		{
			return Outcome.RETRY;
		}
		if (code == 401 || code == 403)
		{
			return Outcome.REFUSED;
		}
		return Outcome.DROPPED;
	}

	private void onAnswer(Flight f, Outcome outcome, String retryAfter, String body)
	{
		if (f != flight)
		{
			return; // cancelled, from an older destination generation, or after closing
		}
		flight = null;
		if (closed || f.generation != generation)
		{
			arm();
			return;
		}
		switch (outcome)
		{
			case SENT:
				failures = 0;
				warned = null;
				intervalMs = serverInterval(body);
				Reply reply = reply(body);
				Map<String, UploadEnvelope.Reading> stored = new LinkedHashMap<>(f.batch);
				if (reply.stored != null)
				{
					stored.keySet().retainAll(reply.stored); // a bank's parts count once the whole bank is stored
				}
				remember(f.profile, stored);
				if (!reply.refused.isEmpty())
				{
					// not remembered: the next change of each is sent again
					log.debug("Hans upload: the website refused {}", reply.refused);
					warnOnce("Hans upload: the website refused " + reply.refused);
				}
				if (f.send.parts != null && f.send.parts == parts
					&& (f.send.lastPart || reply.refused.containsKey(f.send.parts.whole.category)))
				{
					parts = null; // the website has joined the bank's parts, or will not take them
				}
				if (!hasWork())
				{
					flushWanted = false; // the logout's readings are all sent
				}
				break;
			case REFUSED:
				refused = true;
				pending.clear();
				accepted.clear();
				parts = null;
				warnOnce("Hans upload: the website did not accept this PC's key; uploads are paused until the"
					+ " settings change");
				break;
			case RETRY:
				giveBack(f.send); // a bank in parts starts again from its first part
				failed(retryAfter);
				break;
			case DROPPED:
				Reply rejected = reply(body); // which categories, and why: names and rules only, never values
				log.debug("Hans upload: the website rejected a batch of {}: refused {}, errors {}",
					f.batch.keySet(), rejected.refused, rejected.errors);
				if (f.send.parts != null && f.send.parts == parts)
				{
					parts = null;
				}
				failed(retryAfter);
				warnOnce("Hans upload: the website rejected a batch; the next one carries the latest state");
				break;
		}
		arm();
	}

	private void failed(String retryAfter)
	{
		failures++;
		backoffUntil = clock.getAsLong() + Math.max(backoff(failures), retryAfterMs(retryAfter));
	}

	/** The earliest the next request may go: the backoff after a failure, else the gap (short after a logout). */
	private long due()
	{
		if (failures > 0)
		{
			return backoffUntil;
		}
		// a logout, or the rest of a split batch (the login snapshot's 2 or 3 requests): the short gap
		return lastAttempt + (flushWanted || followUp || parts != null ? FLUSH_GAP_MS : intervalMs);
	}

	/** Sets the timer for the next send when there is something to send and nothing in flight. */
	private void arm()
	{
		if (closed || refused || flight != null || !hasWork())
		{
			return;
		}
		long at = due();
		if (timer != null && timerAt == at)
		{
			return;
		}
		cancelTimer();
		timerAt = at;
		timer = scheduler.schedule(() -> tell(this::onTimer), Math.max(0, at - clock.getAsLong()));
	}

	private void cancelTimer()
	{
		if (timer != null)
		{
			timer.cancel(false);
			timer = null;
		}
	}

	private void cancelFlight()
	{
		if (flight != null)
		{
			Runnable cancel = flight.cancel;
			flight = null; // its answer, whenever it comes, is ignored
			if (cancel != null)
			{
				cancel.run();
			}
		}
	}

	/** The oldest account and mode's batch, removed from the queue. */
	private Map.Entry<ProfileKey, Map<String, UploadEnvelope.Reading>> take()
	{
		Iterator<Map.Entry<ProfileKey, Map<String, UploadEnvelope.Reading>>> first = pending.entrySet().iterator();
		Map.Entry<ProfileKey, Map<String, UploadEnvelope.Reading>> e = first.next();
		first.remove();
		return e;
	}

	private JsonObject inFlight(ProfileKey profile, String category)
	{
		if (parts != null && parts.generation == generation && parts.profile.equals(profile)
			&& parts.whole.category.equals(category))
		{
			return parts.whole.data; // a bank still being sent in parts
		}
		if (flight == null || flight.generation != generation || !flight.profile.equals(profile))
		{
			return null;
		}
		UploadEnvelope.Reading r = flight.batch.get(category);
		return r == null ? null : r.data;
	}

	private static JsonObject valueIn(Map<ProfileKey, Map<String, UploadEnvelope.Reading>> m, ProfileKey profile,
		String category)
	{
		Map<String, UploadEnvelope.Reading> batch = m.get(profile);
		UploadEnvelope.Reading r = batch == null ? null : batch.get(category);
		return r == null ? null : r.data;
	}

	private JsonObject acceptedValue(ProfileKey profile, String category)
	{
		Map<String, JsonObject> sent = accepted.get(profile);
		return sent == null ? null : sent.get(category);
	}

	/** What the website now has (this generation); drops unsent readings equal to it. */
	private void remember(ProfileKey profile, Map<String, UploadEnvelope.Reading> batch)
	{
		Map<String, JsonObject> sent = accepted.remove(profile);
		if (sent == null)
		{
			sent = new LinkedHashMap<>();
		}
		for (UploadEnvelope.Reading r : batch.values())
		{
			sent.put(r.category, r.data);
		}
		accepted.put(profile, sent);
		trim(accepted, null);
		Map<String, JsonObject> now = sent;
		Map<String, UploadEnvelope.Reading> unsent = pending.get(profile);
		if (unsent != null)
		{
			unsent.values().removeIf(r -> r.data.equals(now.get(r.category)));
			if (unsent.isEmpty())
			{
				pending.remove(profile);
			}
		}
	}

	/** A batch goes back in front, except for the categories that have a newer reading by now. */
	private void putBack(ProfileKey profile, Map<String, UploadEnvelope.Reading> batch)
	{
		Map<String, UploadEnvelope.Reading> newer = pending.remove(profile);
		Map<String, UploadEnvelope.Reading> merged = new LinkedHashMap<>(batch);
		if (newer != null)
		{
			merged.putAll(newer);
		}
		if (merged.isEmpty())
		{
			return;
		}
		LinkedHashMap<ProfileKey, Map<String, UploadEnvelope.Reading>> rest = new LinkedHashMap<>(pending);
		pending.clear();
		pending.put(profile, merged);
		pending.putAll(rest);
		trim(pending, profile);
	}

	/** Keeps at most {@link #MAX_PROFILES} entries, dropping the oldest (never {@code keep}). */
	private static <V> void trim(LinkedHashMap<ProfileKey, V> map, ProfileKey keep)
	{
		Iterator<ProfileKey> it = map.keySet().iterator();
		while (map.size() > MAX_PROFILES && it.hasNext())
		{
			if (!it.next().equals(keep))
			{
				it.remove();
			}
		}
	}

	/** Exponential backoff with jitter: half the step plus a random part of the other half. */
	long backoff(int failures)
	{
		long step = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << Math.min(failures - 1, 16));
		return step / 2 + (long) (random.nextDouble() * (step / 2));
	}

	/** Retry-After in seconds (the HTTP-date form is ignored); 0 when absent. */
	static long retryAfterMs(String header)
	{
		if (header == null)
		{
			return 0;
		}
		try
		{
			return Math.min(RETRY_AFTER_MAX_MS, Math.max(0, Long.parseLong(header.trim()) * 1000));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/** The longest Worker error message kept for the log. */
	static final int MAX_ERROR = 200;

	/** What the website says it did with a request's categories, and a rejected one's reasons. */
	static final class Reply
	{
		Set<String> stored; // null: the answer does not say (an older website, a repeat): all of them
		final Map<String, String> refused = new TreeMap<>();
		final List<String> errors = new ArrayList<>(); // a rejected request's reasons
	}

	/**
	 * Reads {"categories": [stored], "waiting": [parts held], "refused": {name: why}} from an answer. Only stored
	 * categories count as accepted; waiting and refused ones do not.
	 */
	Reply reply(String body)
	{
		Reply r = new Reply();
		try
		{
			JsonObject o = body == null ? null : gson.fromJson(body, JsonObject.class);
			if (o != null && o.has("categories") && o.get("categories").isJsonArray())
			{
				r.stored = new HashSet<>();
				for (JsonElement e : o.getAsJsonArray("categories"))
				{
					if (e.isJsonPrimitive())
					{
						r.stored.add(e.getAsString());
					}
				}
			}
			if (o != null && o.has("errors") && o.get("errors").isJsonArray())
			{
				for (JsonElement e : o.getAsJsonArray("errors"))
				{
					if (e.isJsonPrimitive() && r.errors.size() < 20)
					{
						String m = e.getAsString();
						r.errors.add(m.length() > MAX_ERROR ? m.substring(0, MAX_ERROR) + "..." : m);
					}
				}
			}
			if (o != null && o.has("refused") && o.get("refused").isJsonObject())
			{
				for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("refused").entrySet())
				{
					r.refused.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "?");
				}
			}
		}
		catch (RuntimeException e)
		{
			// not JSON: all of them, as before
		}
		return r;
	}

	/** The gap the website asks for ({"minIntervalMs": n}), within our bounds; the background gap if it says none. */
	private long serverInterval(String body)
	{
		try
		{
			JsonObject o = body == null ? null : gson.fromJson(body, JsonObject.class);
			if (o != null && o.has("minIntervalMs") && o.get("minIntervalMs").isJsonPrimitive())
			{
				return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, o.get("minIntervalMs").getAsLong()));
			}
		}
		catch (RuntimeException e)
		{
			// not JSON: the background gap
		}
		return DEFAULT_INTERVAL_MS;
	}

	/** HTTPS anywhere; plain HTTP only to this PC (the practice run's local website). */
	static boolean allowedEndpoint(String url)
	{
		if (url == null || url.trim().isEmpty())
		{
			return false;
		}
		try
		{
			URI u = new URI(url.trim());
			String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
			String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
			if (host.isEmpty() || u.getUserInfo() != null)
			{
				return false;
			}
			return "https".equals(scheme)
				|| "http".equals(scheme) && ("127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host));
		}
		catch (Exception e)
		{
			return false;
		}
	}

	private void warnOnce(String message)
	{
		if (!message.equals(warned))
		{
			warned = message;
			log.warn(message);
		}
	}
}
