# Hans

Hans is your OSRS progress companion. The plugin saves your progress while you play, and the Hans website turns it into a record of your account: levels, quests, diaries, collection log, bank, plans and history.

Hans is a fan-made plugin and is not affiliated with Jagex or RuneLite.

## What it records on your PC

By default the plugin only writes files. Nothing is sent anywhere. The files go in RuneLite's data folder for this plugin (`.runelite/plugin-data/hans/`), one folder per account and game mode, so your main, ironman, seasonal and other accounts stay separate.

It records, for the character you are logged in as:

- levels and experience, quest states, achievement diaries, combat achievements
- inventory, worn equipment, bank, and other storages such as the seed vault and looting bag, updated when you open or carry them
- collection log (the plugin presses the log's Search button once so the game lists every item, as other collection log plugins do)
- loot, kill counts, clue counts, items gained each session, play sessions
- Grand Exchange offers, world position, farming and bird house values, Slayer, music tracks and other progress variables
- your character's appearance

Each group can be switched off in the settings, or all at once with "Write files".

## What the optional upload sends

Two settings are off until you switch them on. Each one asks you to confirm first.

**Send to Hans online** sends your progress to the Hans website. It also sends private data that only you can see once signed in: backpack, worn items, bank, other storages, item gains, Slayer task and location. It sends what changed while you play, at most about once a minute (every few seconds while the website is open), and when you log out. Each upload includes an ID for this PC and your account, and the server sees your IP address. Chat messages and login details are never sent.

**Sign in with this plugin** lets the Open Hans button sign you in to the website as the character you are playing. **Manual link** gives you a code to sign in on any other device. Each time, the plugin sends this PC's Hans ID and your account's ID, game mode and name to the website. It needs the upload switched on.

Switching either off stops sending at once.

## Privacy

- Private data (bank, storages, Slayer task, location) is shown only to you.
- The plugin does not read your password or any RuneLite login files.
- The website keeps your latest uploaded data, and the milestones it records along the way, so your journey picks up right where you left off every time you come back, from any device. It's kept until you delete it: "Delete my data" on the website removes everything about you from the website straight away. The files on your PC are never touched.
- Prices come from RuneLite's built-in prices.

## Settings

Open RuneLite's configuration panel and choose Hans.

- **Write files**: master switch for saving files on this PC.
- **Export ...** switches: one for each group of data listed above.
- **Hans address**: where the Open Hans button goes.
- **Send to Hans online** and **Hans upload address**: the optional upload.
- **Sign in with this plugin**: the optional sign-in.

## Getting help

Report a problem or ask a question on the [GitHub issues page](https://github.com/scoobertdoob/hans-plugin/issues).

## Licence

Hans is released under the BSD 2-Clause licence; see [LICENSE](LICENSE). Copyright (c) 2026, scoobertdoob.

Parts are adapted from OSRS Toolkit Exporter by Gorsokk, also BSD 2-Clause; see [NOTICE.md](NOTICE.md).

The plugin icon is the Hans chat-head image from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki/w/File:Hans_chathead.png). The image is copyright Jagex Ltd. and is not covered by this licence.
