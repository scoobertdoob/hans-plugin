package com.hans;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/** What every capture needs, handed to it once. */
final class CaptureContext
{
	final Client client;
	final RsbuddyConfig config;
	final JsonWriter json;
	final ItemManager itemManager;
	final LoginState login;
	final ClientThread clientThread; // for the few calls that must not run inside a game script; null in most tests

	CaptureContext(Client client, RsbuddyConfig config, JsonWriter json, ItemManager itemManager, LoginState login)
	{
		this(client, config, json, itemManager, login, null);
	}

	CaptureContext(Client client, RsbuddyConfig config, JsonWriter json, ItemManager itemManager, LoginState login,
		ClientThread clientThread)
	{
		this.client = client;
		this.config = config;
		this.json = json;
		this.itemManager = itemManager;
		this.login = login;
		this.clientThread = clientThread;
	}

	/** The opt-in upload is on: it needs the captures' readings in memory, whatever the file settings say. */
	boolean uploading()
	{
		return config.uploadLive();
	}

	/** A capture's file is wanted: its own switch and "Write files" are both on. */
	boolean writes(boolean fileSwitch)
	{
		return fileSwitch && json.enabled();
	}

	/** A capture keeps its readings: for its file, or for the upload. The file settings only decide the disk writes. */
	boolean keeps(boolean fileSwitch)
	{
		return writes(fileSwitch) || uploading();
	}
}
