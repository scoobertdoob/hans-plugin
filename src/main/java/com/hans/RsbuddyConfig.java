package com.hans;

import java.util.List;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * The settings. The per-category switches mirror the OSRS Toolkit Exporter's (same names and meaning); all are on
 * by default. "Write files" is the master switch above them.
 */
@ConfigGroup(RsbuddyConfig.GROUP)
public interface RsbuddyConfig extends Config
{
	String GROUP = "hans";
	/**
	 * The groups before the public renames, newest first: "gnomechild" (briefly, 31 Oct) and "rsbuddy" (before card #123).
	 * {@link ConfigMigration} copies one of them into {@link #GROUP} once.
	 */
	List<String> OLD_GROUPS = List.of("gnomechild", "rsbuddy");

	@ConfigItem(
		keyName = "writeFiles",
		name = "Write files",
		description = "Save your progress, items and play sessions to local JSON files in RuneLite's data folder. The files stay on this PC unless you switch on \"Send to Hans online\" or \"Sign in with this plugin\" below.",
		position = 0
	)
	default boolean writeFiles()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportPosition",
		name = "Export position",
		description = "Write your live world position to position.json",
		position = 1
	)
	default boolean exportPosition()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportGeOffers",
		name = "Export GE offers",
		description = "Write your Grand Exchange offers to ge_offers.json",
		position = 2
	)
	default boolean exportGeOffers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportGameQuests",
		name = "Export game quest data",
		description = "Save the game's own quest list (requirements, start locations, experience rewards) to game_quests.json. It's the same for every player, so it's never uploaded.",
		position = 3
	)
	default boolean exportGameQuests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportCharacter",
		name = "Export character progress",
		description = "Write your levels and experience (character.json), quest states (quests.json), achievement diary tiers (diaries.json), combat achievement totals (combat_achievements.json) and the game's combat achievement task list (ca_tasks.json)",
		position = 4
	)
	default boolean exportCharacter()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportItems",
		name = "Export inventory and equipment",
		description = "Write your inventory (inventory.json), worn equipment (equipment.json) and the items you gain each session (item_gains.json)",
		position = 5
	)
	default boolean exportItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportBank",
		name = "Export bank",
		description = "Write your bank (bank.json). It updates when you open your bank",
		position = 6
	)
	default boolean exportBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportVars",
		name = "Account and progress variables",
		description = "Write your account type and raw progress variables (diaries, combat achievements, music, slayer, dailies, collection log counts) to account.json and vars.json, and your unlocked music tracks to music.json",
		position = 7
	)
	default boolean exportVars()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportFarming",
		name = "Farming and bird houses",
		description = "Write the raw farming patch and bird house values you see to farming.json and birdhouses.json",
		position = 8
	)
	default boolean exportFarming()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportLoot",
		name = "Loot and kill counts",
		description = "Write the loot you get from NPCs (loot.jsonl) and your boss kill and clue counts (killcounts.json)",
		position = 9
	)
	default boolean exportLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportStorage",
		name = "Storage containers",
		description = "Write the seed vault, looting bag, player-owned house storage and group storage when you open them (seed_vault.json, looting_bag.json, poh_storage.json, group_storage.json), your rune pouch and Dizana's quiver while you carry them (rune_pouch.json, quiver.json), and your potion storage while your bank is open (potion_storage.json)",
		position = 10
	)
	default boolean exportStorage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportAppearance",
		name = "Appearance",
		description = "Write your own character's gender, colours, kits and worn models to appearance.json",
		position = 11
	)
	default boolean exportAppearance()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exportCollectionLog",
		name = "Collection log",
		description = "Write the collection log items you have obtained to collection_log.json. When you open your own collection log, Hans presses its Search button once so the game lists every item at once (as other collection log plugins do)",
		position = 12
	)
	default boolean exportCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "appUrl",
		name = "Hans address",
		description = "Where the panel's Open Hans button goes. Normally the Hans website; developers can point it at a copy running on their own PC.",
		position = 13
	)
	default String appUrl()
	{
		return ConfigMigration.APP_URL;
	}

	// Opt-in upload (card #115). Off by default; the warning is RuneLite's confirmation when the switch is changed.
	@ConfigItem(
		keyName = UploadCapture.ENABLE_KEY,
		name = "Send to Hans online",
		description = "Off unless you switch it on. Sends your progress, and private data only you can see (backpack, worn items, bank, other storages, item gains, Slayer task and location), to the Hans website at the upload address below. It sends what changed while you play, at most about once a minute (every few seconds while the website is open), and when you log out. Chat and login details are never sent.",
		warning = UploadCapture.WARNING,
		position = 30
	)
	default boolean uploadLive()
	{
		return false;
	}

	@ConfigItem(
		keyName = UploadCapture.URL_KEY,
		name = "Hans upload address",
		description = "Where uploads go when \"Send to Hans online\" is on. Normally the Hans website; must start with https://, or http:// for a copy running on this PC.",
		position = 31
	)
	default String uploadUrl()
	{
		return ConfigMigration.UPLOAD_URL;
	}

	// Sign-in through the plugin (card #76). Off by default; also needs the upload on. Draft wording for the owner.
	@ConfigItem(
		keyName = AuthClient.ENABLE_KEY,
		name = "Sign in with this plugin",
		description = "Off unless you switch it on. Open Hans then signs you in to the Hans website as the character you're playing, and shows a code for signing in on another device. Needs \"Send to Hans online\" on too.",
		warning = "Each time you click Open Hans, this sends this PC's Hans ID and your account's ID, game mode and name to the Hans website (the upload address in the settings), so the website can sign you in. Continue?",
		position = 32
	)
	default boolean signIn()
	{
		return false;
	}
}
