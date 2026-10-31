package com.hans;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldType;

/**
 * Where we are in a login: not logged in, logged in but the stats are still loading, or settled.
 * "Settled" starts a few game ticks after login, when the stats the game sends late have arrived; the profile key
 * (account hash, game mode and name) is fixed at that moment. A login on a world the game wipes (beta, tournament)
 * never settles, and neither does one whose account hash is still unknown (-1), nor one on a seasonal or Deadman world
 * whose season can't be named ({@link GameMode#UNIDENTIFIED}): what arrives there goes to quarantine.
 *
 * The mode is read at LOGGED_IN. A change of mode or account while logged in is a new login: the caller ends this one
 * first ({@link #keyChanged}). Values that events hand over are tagged with the {@link Token} current when they
 * arrived, and a token says once and for all which key they are credited to: the settled key, or none. A new token
 * starts at every boundary (login, logout, each world hop or region load, settle, and the tick that confirms the
 * world after a hop; the LOGGED_IN after a hop can still report the old world's types). A token carries a key only
 * while the login is settled, a tick has confirmed the world, and the client still reports that key's account and
 * mode. A value whose token has no key is never credited to any profile, never carried to the next login and never
 * guessed: the capture writes it to the plugin's quarantine file ({@link JsonWriter#appendQuarantine}).
 *
 * Two keys, never mixed up: {@link #reading} is the key a capture may take a new game reading for, valid only on the
 * same terms as a token's key; {@link #logoutKey} is the key this login settled with, kept until logout only so that
 * what was already read and tagged under it can still be written. After settle the client can report another
 * account (a hash of -1, then another player) before the next login starts: no reading is taken then.
 * Touched on the client thread only.
 */
final class LoginState
{
	static final int SETTLE_TICKS = 3;

	/**
	 * Which stretch of a login a value arrived in, and the key it is credited to: null when none (the value goes to
	 * quarantine, with {@link #why}). Immutable; two values belong to the same stretch only if their tokens are one.
	 */
	static final class Token
	{
		final int seq;
		final ProfileKey key;
		final String why; // null when key is set

		Token(int seq, ProfileKey key, String why)
		{
			this.seq = seq;
			this.key = key;
			this.why = why;
		}
	}

	static final String LOGGED_OUT = "logged out: no account or game mode to credit it to";
	static final String CHANGING_WORLD = "arrived while the game was changing world or loading, before a game tick"
		+ " confirmed the world";
	static final String NOT_SETTLED = "arrived before the login settled (its account and game mode were not fixed yet)";
	static final String NO_HASH = "the account hash was not known yet (-1)";
	static final String NO_SAVE = "a world the game wipes (beta or tournament), while changing world";
	static final String NO_SEASON = "a seasonal or Deadman world whose season could not be named (from a recent world"
		+ " list), so no season's folder could be chosen";
	static final String OTHER_ACCOUNT = "the client reported another account or game mode than this login's";

	private final Client client;
	private final WorldSeasons seasons;
	private int ticks = -1; // -1: not logged in
	private boolean settled;
	private String mode;
	private ProfileKey key;
	private ProfileKey lastKey; // the most recent settled key: the outgoing key of what arrives after it
	private long hash = -1; // the account hash last seen in this login
	private boolean confirmed; // whether a tick has confirmed the world since the last hop or region load
	private int seq;
	private Token token = new Token(0, null, LOGGED_OUT);

	/** Without a world list: a Deadman season is never named, so nothing is recorded there (flow tests of main). */
	LoginState(Client client)
	{
		this(client, new WorldSeasons(() -> null, () ->
		{
		}, () -> 0L));
	}

	LoginState(Client client, WorldSeasons seasons)
	{
		this.client = client;
		this.seasons = seasons;
	}

	/**
	 * The game reached LOGGED_IN: starts a login (reading the world's mode). After a hop or region load it changes
	 * nothing: the destination is confirmed by the next tick.
	 */
	void loggedIn()
	{
		if (ticks < 0)
		{
			ticks = 0;
			mode = GameMode.current(client, seasons);
			hash = client.getAccountHash();
			confirmed = false;
			next(null, CHANGING_WORLD);
		}
	}

	/** RuneLite fetched the world list (any thread). */
	void worldsFetched(net.runelite.http.api.worlds.WorldResult result)
	{
		seasons.fetched(result);
	}

	/** The login screen, a change of key, or the plugin stopping: nothing from now on is credited to this login. */
	void loggedOut()
	{
		ticks = -1;
		settled = false;
		confirmed = false;
		mode = null;
		key = null;
		hash = -1;
		seasons.forget();
		next(null, LOGGED_OUT);
	}

	/** HOPPING or LOADING: every hop gets a fresh token, and nothing is credited until a tick confirms the world. */
	void hopping()
	{
		if (ticks >= 0)
		{
			confirmed = false;
			next(null, GameMode.wiped(mode) ? NO_SAVE : CHANGING_WORLD);
		}
	}

	private void next(ProfileKey k, String why)
	{
		token = new Token(++seq, k, k == null ? why : null);
	}

	/**
	 * True when the world's mode, or the account, is no longer this login's: the caller ends this login under its
	 * old key and starts a new one.
	 */
	boolean keyChanged()
	{
		if (ticks < 0)
		{
			return false;
		}
		if (!Objects.equals(GameMode.current(client, seasons), mode))
		{
			return true;
		}
		if (!settled)
		{
			return false;
		}
		long h = client.getAccountHash();
		return h != -1 && h != key.hash;
	}

	/**
	 * Counts one game tick of a login. The caller has checked {@link #keyChanged} first, so the world this tick sees
	 * is the login's own: the world is confirmed.
	 *
	 * @return true on the tick the login becomes settled
	 */
	boolean tick(Player local)
	{
		if (ticks < 0)
		{
			return false;
		}
		long h = client.getAccountHash();
		if (h != -1)
		{
			hash = h;
		}
		boolean settledNow = false;
		if (!settled && ++ticks >= SETTLE_TICKS && GameMode.saves(mode) && h != -1 && local.getName() != null)
		{
			key = new ProfileKey(h, mode, local.getName());
			lastKey = key;
			settled = true;
			settledNow = true;
		}
		ProfileKey credit = settled && h == key.hash ? key : null;
		if (!confirmed || !Objects.equals(credit, token.key))
		{
			confirmed = true;
			next(credit, h == -1 ? NO_HASH : settled ? OTHER_ACCOUNT
				: GameMode.UNIDENTIFIED.equals(mode) ? NO_SEASON : NOT_SETTLED);
		}
		return settledNow;
	}

	/** Whether this login has settled. Not a licence to read the game: use {@link #reading} for that. */
	boolean settled()
	{
		return settled;
	}

	/**
	 * The key this login settled with, kept until logout; null otherwise. Only for flushing what was already read
	 * and tagged under it (a pending file, the end of the session): never a reason to take a new reading, because the
	 * client may be showing another account by now.
	 */
	ProfileKey logoutKey()
	{
		return key;
	}

	/**
	 * The key a new game reading (position, containers, variables, stats, anything a capture samples) may be taken
	 * and written for now, or null when none may be taken. Valid only while the login is settled, a tick has
	 * confirmed the world since the last hop or region load, and the client still reports this key's account hash
	 * and mode: the same terms as a token's key. When null, take no reading; what was already tagged keeps its key.
	 */
	ProfileKey reading()
	{
		return token().key;
	}

	/**
	 * The world's activity in the world list, the evidence that named a Deadman season; null on any other world or
	 * when it isn't known. Recorded in mode.json.
	 */
	String worldActivity()
	{
		return GameMode.worldTypes(client).contains(WorldType.DEADMAN) ? seasons.activity(client.getWorld()) : null;
	}

	/** The mode read at LOGGED_IN (null when logged out). */
	String mode()
	{
		return mode;
	}

	/**
	 * The token for a value an event hands over now. If the client no longer reports the account and mode of the
	 * token's key, crediting stops at once (a fresh token without a key) until the next tick sorts the login out.
	 */
	Token token()
	{
		ProfileKey k = token.key;
		if (k != null && (client.getAccountHash() != k.hash || !k.mode.equals(GameMode.current(client, seasons))))
		{
			confirmed = false;
			next(null, OTHER_ACCOUNT);
		}
		return token;
	}

	/**
	 * True when the client reports, right now, a world the game wipes (beta, tournament): a value arriving now is
	 * dropped at once, never held or written (owner, 28 Oct). Decided from the world the client reports when the
	 * value arrives, not from the login's mode or a tick: so also before the first tick of a beta login, and not once
	 * the client reports a saved world while leaving one (the value then follows its token, usually to quarantine).
	 */
	boolean dropsEvidence()
	{
		return GameMode.wiped(GameMode.current(client, seasons));
	}

	/**
	 * Where a value without a key could belong, for its quarantine line: the outgoing key (the last settled one, if
	 * any) and, while logged in, the candidate destination the client reports now. Never used to credit anything.
	 */
	Map<String, Object> whereabouts()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("outgoing_key", describe(lastKey));
		if (ticks >= 0)
		{
			long h = client.getAccountHash();
			Map<String, Object> candidate = new LinkedHashMap<>();
			candidate.put("account_hash", h == -1 ? null : Long.toString(h));
			candidate.put("mode", GameMode.current(client, seasons));
			candidate.put("login_mode", mode);
			candidate.put("world", client.getWorld());
			m.put("candidate", candidate);
		}
		return m;
	}

	private static Map<String, Object> describe(ProfileKey k)
	{
		if (k == null)
		{
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("account_hash", k.hashText());
		m.put("mode", k.mode);
		m.put("name", k.name);
		return m;
	}
}
