package com.hans;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Carries the settings over when the settings group was renamed with the plugin's public name: "rsbuddy" became
 * "gnomechild" (card #123), which became "hans". Every switch the player set, including an upload they already agreed to,
 * stays as it was. The copy comes from one old group only: the newest one this PC has any setting in ("gnomechild" if
 * that plugin ever ran, as it had already carried "rsbuddy" over), otherwise the oldest.
 *
 * <p>RuneLite fills in a default for every unset item of the new group when the plugin loads, before
 * {@code startUp}, so "only where the new group has no value" would copy nothing. Instead:
 * <ol>
 * <li>A first try sets {@link #STARTED_KEY} (to the old group it copies from) before anything is copied, then copies
 * every old value, exactly as stored, over the new group, and sets {@link #DONE_KEY} last. After that nothing is ever
 * copied again.</li>
 * <li>If a try fails part-way, the plugin still starts and the next start-up retries. A retry may follow the player
 * changing settings, so it copies from the same old group, never copies the upload or sign-in consent (whatever is
 * there now stands), and copies any other key only where the new value still equals its default.</li>
 * </ol>
 * The old groups are never changed, and their own copy markers are never copied.
 */
@Slf4j
final class ConfigMigration
{
	/** Set in the new group once the copy has finished; not a ConfigItem, so it isn't shown in the settings. */
	static final String DONE_KEY = "copiedFromOldGroup";
	/**
	 * Set in the new group, to the old group being copied, before anything is copied: a later start-up that finds it
	 * without {@link #DONE_KEY} is a retry, and copies from that same group.
	 */
	static final String STARTED_KEY = "copyingFromOldGroup";
	/** Never copied on a retry: a consent can only be carried over by the first try. */
	static final Set<String> CONSENT = Set.of(UploadCapture.ENABLE_KEY, AuthClient.ENABLE_KEY);

	/** The public site and its upload address (the defaults), and the earlier default sites they replace. */
	static final String APP_URL = "https://app.hans-osrs.workers.dev";
	static final String UPLOAD_URL = APP_URL + "/api/ingest";
	static final List<String> OLD_APP_URLS = List.of("https://rsbuddy.rsbuddy-app.workers.dev",
		"https://app.gnomechild.workers.dev");
	/** Set in the new group once the old default addresses have been moved; not a ConfigItem. */
	static final String ADDRESS_DONE_KEY = "movedToHansAddress";
	/** Bookkeeping keys, this version's and the "gnomechild" group's: never copied from an old group. */
	static final Set<String> MARKERS = Set.of(DONE_KEY, STARTED_KEY, ADDRESS_DONE_KEY,
		"copiedFromRsbuddy", "copyingFromRsbuddy", "movedToGnomechildAddress");

	/** The parts of RuneLite's ConfigManager this uses. */
	interface Settings
	{
		/** Every stored key starting with the prefix, as "group.key". */
		List<String> keys(String prefix);

		String get(String group, String key);

		void set(String group, String key, String value);
	}

	private ConfigMigration()
	{
	}

	static Settings of(ConfigManager manager)
	{
		return new Settings()
		{
			@Override
			public List<String> keys(String prefix)
			{
				return manager.getConfigurationKeys(prefix);
			}

			@Override
			public String get(String group, String key)
			{
				return manager.getConfiguration(group, key);
			}

			@Override
			public void set(String group, String key, String value)
			{
				manager.setConfiguration(group, key, value);
			}
		};
	}

	/**
	 * Every item's default, as RuneLite stores it, from the settings interface's own default methods (an instance that
	 * overrides nothing). ConfigMigrationTest checks that every item in RsbuddyConfig is listed.
	 */
	static Map<String, String> defaults()
	{
		RsbuddyConfig d = new RsbuddyConfig()
		{
		};
		Map<String, String> m = new HashMap<>();
		m.put("writeFiles", String.valueOf(d.writeFiles()));
		m.put("exportPosition", String.valueOf(d.exportPosition()));
		m.put("exportGeOffers", String.valueOf(d.exportGeOffers()));
		m.put("exportGameQuests", String.valueOf(d.exportGameQuests()));
		m.put("exportCharacter", String.valueOf(d.exportCharacter()));
		m.put("exportItems", String.valueOf(d.exportItems()));
		m.put("exportBank", String.valueOf(d.exportBank()));
		m.put("exportVars", String.valueOf(d.exportVars()));
		m.put("exportFarming", String.valueOf(d.exportFarming()));
		m.put("exportLoot", String.valueOf(d.exportLoot()));
		m.put("exportStorage", String.valueOf(d.exportStorage()));
		m.put("exportAppearance", String.valueOf(d.exportAppearance()));
		m.put("exportCollectionLog", String.valueOf(d.exportCollectionLog()));
		m.put("appUrl", d.appUrl());
		m.put(UploadCapture.ENABLE_KEY, String.valueOf(d.uploadLive()));
		m.put(UploadCapture.URL_KEY, d.uploadUrl());
		m.put(AuthClient.ENABLE_KEY, String.valueOf(d.signIn()));
		return m;
	}

	/** Whether a new-group value is still the default (RuneLite stores an empty default as no value). */
	private static boolean untouched(String current, String key, Map<String, String> defaults)
	{
		if (!defaults.containsKey(key))
		{
			return current == null; // not an item: RuneLite filled in nothing
		}
		String d = defaults.get(key);
		return Objects.equals(current, d) || (empty(current) && empty(d));
	}

	private static boolean empty(String s)
	{
		return s == null || s.isEmpty();
	}

	/**
	 * The old group to copy from: on a retry the one the first try chose, otherwise the first (newest) of the old groups
	 * with any stored setting; null if there are none.
	 */
	static String source(Settings settings, String to, List<String> from)
	{
		String started = settings.get(to, STARTED_KEY);
		if (started != null && from.contains(started))
		{
			return started;
		}
		for (String group : from)
		{
			if (!settings.keys(group + ".").isEmpty())
			{
				return group;
			}
		}
		return null;
	}

	/**
	 * Copies an old group into the new one: in full on the first try, carefully on a retry (see the class comment).
	 *
	 * @param from the old groups, newest first
	 * @return how many settings were copied; 0 once the copy has finished; -1 if it failed and will be tried again
	 */
	static int run(Settings settings, String to, List<String> from)
	{
		try
		{
			if (settings.get(to, DONE_KEY) != null)
			{
				return 0;
			}
			String source = source(settings, to, from);
			boolean retry = settings.get(to, STARTED_KEY) != null;
			if (!retry)
			{
				settings.set(to, STARTED_KEY, source == null ? "none" : source);
			}
			int copied = source == null ? 0 : copy(settings, source, to, retry);
			settings.set(to, DONE_KEY, "true");
			if (copied > 0)
			{
				log.info("Hans: copied {} settings from the old \"{}\" group", copied, source);
			}
			return copied;
		}
		catch (RuntimeException e)
		{
			log.warn("Hans: could not copy the settings from an old group; trying again next start", e);
			return -1;
		}
	}

	private static int copy(Settings settings, String from, String to, boolean retry)
	{
		Map<String, String> defaults = defaults();
		String prefix = from + ".";
		int copied = 0;
		for (String full : settings.keys(prefix))
		{
			String key = full.substring(prefix.length());
			if (key.isEmpty() || MARKERS.contains(key))
			{
				continue;
			}
			String value = settings.get(from, key);
			if (value == null
				|| retry && (CONSENT.contains(key) || !untouched(settings.get(to, key), key, defaults)))
			{
				continue;
			}
			settings.set(to, key, value);
			copied++;
		}
		return copied;
	}

	/**
	 * Once, after the settings copy has finished: a stored old default address (either earlier public site, and the empty
	 * upload address that went with the first) becomes the new default. A custom address, such as a copy on this PC, is
	 * kept.
	 */
	static void moveAddress(Settings settings, String group)
	{
		try
		{
			if (settings.get(group, ADDRESS_DONE_KEY) != null)
			{
				return;
			}
			String app = settings.get(group, "appUrl");
			if (app != null && OLD_APP_URLS.contains(trimSlash(app)))
			{
				settings.set(group, "appUrl", APP_URL);
			}
			String up = settings.get(group, UploadCapture.URL_KEY);
			if (up != null && (up.isEmpty() || OLD_APP_URLS.contains(trimSlash(up))
				|| OLD_APP_URLS.contains(trimSlash(up).replaceFirst("/api/ingest$", ""))))
			{
				settings.set(group, UploadCapture.URL_KEY, UPLOAD_URL);
			}
			settings.set(group, ADDRESS_DONE_KEY, "true");
		}
		catch (RuntimeException e)
		{
			log.warn("Hans: could not move the old default address; trying again next start", e);
		}
	}

	private static String trimSlash(String s)
	{
		String t = s.trim();
		return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
	}
}
