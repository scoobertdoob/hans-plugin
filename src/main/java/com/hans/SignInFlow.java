package com.hans;

import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The side panel's two sign-in actions (card #76), kept apart from Swing so they can be tested. Each click asks for its
 * own new one-time challenge, because opening a link burns its paired code:
 * <ul>
 * <li>{@link Action#BROWSER} ("Open Hans") opens the browser with the link only;</li>
 * <li>{@link Action#PHONE} ("Sign in on phone") shows the code and its time left only.</li>
 * </ul>
 * With sign-in off or no character, "Open Hans" just opens the website (nothing sent) and "Sign in on phone" says
 * why it can't. Runs on the Swing thread; answers come back to it through {@code swing}. {@link #reset} (a logout, a
 * change of character or mode, or a sign-in setting changed) drops the request waiting and the code shown.
 */
final class SignInFlow
{
	enum Action
	{
		BROWSER, PHONE
	}

	/** Sign-in through the plugin. */
	interface Requests
	{
		/** Sign-in and the upload are both switched on. */
		boolean on();

		/** Asks for a link and code for the character being played (read on the client thread); answers on any thread. */
		void request(AuthClient.Listener done);
	}

	/** What the panel shows. Swing thread. */
	interface View
	{
		/** Both buttons off while {@code action}'s request waits. */
		void busy(Action action);

		/** Both buttons back on. */
		void idle();

		void browse(String url);

		/** The code, where to type it, and the time left ({@link #timeLeft}). */
		void showCode(AuthClient.Result code, String timeLeft);

		void countdown(String timeLeft);

		void message(String text);

		/** Nothing about sign-in shown. */
		void clear();
	}

	private final Requests requests;
	private final View view;
	private final Executor swing;
	private final Supplier<String> appUrl;
	private final LongSupplier clock;
	private int ask; // raised by each click and each reset: an older answer is dropped
	private Action pending;
	private AuthClient.Result code; // the code shown, until it runs out

	SignInFlow(Requests requests, View view, Executor swing, Supplier<String> appUrl, LongSupplier clock)
	{
		this.requests = requests;
		this.view = view;
		this.swing = swing;
		this.appUrl = appUrl;
		this.clock = clock;
	}

	void click(Action action, boolean loggedIn)
	{
		if (pending != null)
		{
			return;
		}
		if (!requests.on() || !loggedIn)
		{
			if (action == Action.BROWSER)
			{
				view.browse(appUrl.get()); // just the website, nothing sent
			}
			else
			{
				code = null;
				view.message(problemText(AuthClient.Result.failed(requests.on() ? AuthClient.Problem.NOT_LOGGED_IN
					: AuthClient.Problem.OFF)));
			}
			return;
		}
		int mine = ++ask;
		pending = action;
		view.busy(action);
		requests.request(r -> swing.execute(() -> answered(mine, action, r)));
	}

	private void answered(int mine, Action action, AuthClient.Result r)
	{
		if (mine != ask)
		{
			return; // reset since
		}
		pending = null;
		view.idle();
		if (!r.ok())
		{
			code = null;
			view.message(problemText(r));
		}
		else if (action == Action.BROWSER)
		{
			if (code == null)
			{
				view.clear(); // an earlier message is out of date; a phone code shown is a different challenge and stays
			}
			view.browse(r.url);
		}
		else
		{
			code = r;
			view.showCode(r, timeLeft(r.expiresAt - clock.getAsLong()));
		}
	}

	/** Drops the request waiting (its answer is ignored) and the code shown; both buttons back on. */
	void reset()
	{
		ask++;
		pending = null;
		code = null;
		view.idle();
		view.clear();
	}

	/** Once a second: the code's time left, or that it has run out. */
	void tick()
	{
		if (code == null)
		{
			return;
		}
		long left = code.expiresAt - clock.getAsLong();
		if (left <= 0)
		{
			code = null;
			view.message("Code expired. Click Manual link for a new one.");
			return;
		}
		view.countdown(timeLeft(left));
	}

	boolean waiting()
	{
		return pending != null;
	}

	/** Minutes and seconds, rounded up: 9:41. */
	static String timeLeft(long ms)
	{
		long s = (Math.max(0, ms) + 999) / 1000;
		return s / 60 + ":" + String.format("%02d", s % 60);
	}

	/** What went wrong, in plain words. Draft wording for the owner. */
	static String problemText(AuthClient.Result r)
	{
		switch (r.problem)
		{
			case OFF:
				return "Sign-in is off in the plugin settings.";
			case NO_ADDRESS:
				return "Set the upload address in the plugin settings.";
			case NOT_LOGGED_IN:
				return "Log in to the game first.";
			case KEY:
				return "Couldn't read this PC's key. Try again.";
			case REFUSED:
				return "The Hans website didn't accept this PC. If you unlinked it, sign in again from a browser that's already signed in.";
			case BUSY:
				if (r.retryAfterMs > 0)
				{
					long min = Math.max(1, (r.retryAfterMs + 59_999) / 60_000);
					return "Too many tries. Wait " + min + " min.";
				}
				return "Too many tries. Wait a few minutes.";
			case UNREACHABLE:
				return "Can't reach the Hans website. Check your connection.";
			default:
				return "Sign-in failed. Try again later.";
		}
	}

	/** An address without its https:// for reading out: rsbuddy.example/login. */
	static String shortAddress(String url)
	{
		return url.replaceFirst("^https?://", "");
	}
}
