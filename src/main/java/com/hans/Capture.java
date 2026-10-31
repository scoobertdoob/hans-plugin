package com.hans;

import java.time.Instant;
import java.util.concurrent.Future;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;

/**
 * One category of captured data (one file, or a few that belong together).
 *
 * The plugin forwards the events to every registered capture; a capture overrides only the hooks it needs.
 * Events only mark the capture dirty. The "dirty, read, write" step happens in {@link #onGameTick}, so every
 * game read is on the client thread and the writes go through {@link JsonWriter} (on the executor).
 *
 * To add a category: write one class that implements this interface, and add one line to
 * {@code RsbuddyPlugin.startUp} that registers it.
 */
interface Capture
{
	/** The game reached LOGGED_IN (also after a world hop or a region load). Mark whatever must be re-read. */
	default void onLogin()
	{
	}

	/**
	 * The first tick of a login on which the character's stats are loaded (a few ticks after login).
	 * Called on the client thread, before {@link #onGameTick} of the same tick, in registration order.
	 */
	default void onSettled(Player local, Instant now)
	{
	}

	/** The dirty-to-write step, every game tick while logged in and the local player is known. Client thread. */
	default void onGameTick(Player local, Instant now)
	{
	}

	/**
	 * The player logged out, the plugin is being shut down, or the client is closing. May be called more than once
	 * in a row.
	 *
	 * @return a pending write the client should wait for on shutdown, or null
	 */
	default Future<?> onLogout(Instant now)
	{
		return null;
	}

	default void onStatChanged(StatChanged event)
	{
	}

	default void onVarbitChanged(VarbitChanged event)
	{
	}

	default void onItemContainerChanged(ItemContainerChanged event)
	{
	}

	default void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
	}

	default void onChatMessage(ChatMessage event)
	{
	}

	default void onPlayerChanged(PlayerChanged event)
	{
	}

	default void onServerNpcLoot(ServerNpcLoot event)
	{
	}

	default void onNpcLootReceived(NpcLootReceived event)
	{
	}

	/** The player chose a menu option (only read, never consumed). Client thread. */
	default void onMenuOptionClicked(MenuOptionClicked event)
	{
	}

	/** A client script is about to run (every script, so keep it cheap). Client thread. */
	default void onScriptPreFired(ScriptPreFired event)
	{
	}

	/** A client script has run. Client thread. */
	default void onScriptPostFired(ScriptPostFired event)
	{
	}

	/** An interface was opened. Client thread. */
	default void onWidgetLoaded(WidgetLoaded event)
	{
	}

	/** An interface was closed. Client thread. */
	default void onWidgetClosed(WidgetClosed event)
	{
	}

	/** One of this plugin's settings changed. */
	default void onConfigChanged(String key)
	{
	}
}
