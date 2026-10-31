package com.hans;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * The login, tick and logout path of every capture, kept out of the plugin class so that the flow tests run the very
 * same code. One rule (docs/plugin-mode-separation.md section 4): a change of profile key, through the login screen
 * or a world hop into another mode, is a logout under the old key followed by a login under the new one.
 * Client thread only: the plugin hands the shutdown, the client's closing and settings changes over to it.
 * A capture that throws (a game update that changed what it reads, say) is logged and skipped; the others still run.
 */
@Slf4j
final class CaptureHub
{
	final List<Capture> captures = new CopyOnWriteArrayList<>();
	private final Set<Class<?>> failed = ConcurrentHashMap.newKeySet(); // logged in full once each, then quietly
	private final Client client;
	private final LoginState login;
	private final JsonWriter json;

	CaptureHub(CaptureContext ctx)
	{
		this.client = ctx.client;
		this.login = ctx.login;
		this.json = ctx.json;
	}

	/** The game reached LOGGED_IN: also after a world hop or a region load. */
	void loggedIn(Instant now)
	{
		if (login.keyChanged())
		{
			switchKey(now); // a hop into another mode: everything pending goes to the old key's folder
		}
		startLogin();
	}

	/** HOPPING or LOADING: the session goes on, but nothing that arrives now is credited to the current key. */
	void hopping()
	{
		login.hopping();
	}

	/** The login screen: the player logged out (or switched account). */
	void loggedOut(Instant now)
	{
		endLogin(now);
	}

	/** One game tick while logged in and the local player is known. */
	void tick(Player local, Instant now)
	{
		if (login.keyChanged())
		{
			// the same check as at LOGGED_IN, in case the world's types changed only after it: what arrived during
			// the hop was never credited to the old key (it went to quarantine)
			switchKey(now);
			startLogin();
		}
		boolean settledNow = login.tick(local);
		ProfileKey reading = login.reading();
		if (reading != null)
		{
			json.identify(reading, observed(), now, settledNow);
		}
		if (settledNow)
		{
			each(c -> c.onSettled(local, now));
		}
		each(c -> c.onGameTick(local, now));
	}

	/** Runs one hook on every capture, in registration order; one that throws does not stop the rest. */
	void each(Consumer<Capture> hook)
	{
		for (Capture c : captures)
		{
			try
			{
				hook.accept(c);
			}
			catch (RuntimeException e)
			{
				if (failed.add(c.getClass()))
				{
					log.warn("Hans: {} failed; skipped, the other captures go on", c.getClass().getSimpleName(), e);
				}
				else
				{
					log.debug("Hans: {} failed again", c.getClass().getSimpleName(), e);
				}
			}
		}
	}

	/**
	 * The client is closing ({@code ClientShutdown} is posted on Swing's thread). The logout runs on the client
	 * thread like every other, and the client waits for that hand-off, then for the writes it queued: they are added
	 * to {@code waitFor} before the hand-off completes, and the client's queue is read until it is empty.
	 */
	void closing(Instant now, Consumer<Runnable> clientThread, Consumer<Future<?>> waitFor)
	{
		CompletableFuture<Void> handed = new CompletableFuture<>();
		waitFor.accept(handed);
		clientThread.accept(() ->
		{
			try
			{
				logoutAll(now).forEach(waitFor);
				waitFor.accept(json.barrier()); // and every write the logout queued without returning it
			}
			finally
			{
				handed.complete(null);
			}
		});
	}

	/** Tells every capture the player is gone (under the key still current); returns the writes still pending. */
	List<Future<?>> logoutAll(Instant now)
	{
		List<Future<?>> pending = new ArrayList<>();
		each(c ->
		{
			Future<?> done = c.onLogout(now);
			if (done != null)
			{
				pending.add(done);
			}
		});
		return pending;
	}

	private void endLogin(Instant now)
	{
		logoutAll(now);
		login.loggedOut();
	}

	/** A change of key while logged in: the logout of the old key. Nothing pending is carried to the new login. */
	private void switchKey(Instant now)
	{
		endLogin(now);
	}

	private void startLogin()
	{
		login.loggedIn();
		each(Capture::onLogin);
	}

	/** What the client reports about the world now, recorded in mode.json so a first login confirms the mode. */
	private Map<String, Object> observed()
	{
		List<String> types = new ArrayList<>();
		for (WorldType t : GameMode.worldTypes(client))
		{
			types.add(t.name());
		}
		Collections.sort(types);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("world_types", types);
		out.put("profile_type", RuneScapeProfileType.getCurrent(client).name());
		String activity = login.worldActivity();
		if (activity != null)
		{
			out.put("world_activity", activity); // the world list's name for the Deadman season, as it read
		}
		return out;
	}
}
