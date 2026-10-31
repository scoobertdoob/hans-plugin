package com.hans;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Settings saved under an old group ("gnomechild", else "rsbuddy") carry over to "hans" once, and only once. */
public class ConfigMigrationTest
{
	/** RuneLite's stored settings, as "group.key" to value. */
	private final Map<String, String> stored = new LinkedHashMap<>();

	private final ConfigMigration.Settings settings = new ConfigMigration.Settings()
	{
		@Override
		public List<String> keys(String prefix)
		{
			List<String> out = new ArrayList<>();
			for (String k : stored.keySet())
			{
				if (k.startsWith(prefix))
				{
					out.add(k);
				}
			}
			return out;
		}

		@Override
		public String get(String group, String key)
		{
			return stored.get(group + "." + key);
		}

		@Override
		public void set(String group, String key, String value)
		{
			String full = group + "." + key;
			if (full.equals(failOn))
			{
				failOn = null; // fails once, like a full disk or a settings write that throws
				throw new IllegalStateException("synthetic failure writing " + full);
			}
			stored.put(full, value);
		}
	};

	/** The next write of this "group.key" throws. */
	private String failOn;

	private int run()
	{
		return ConfigMigration.run(settings, RsbuddyConfig.GROUP, RsbuddyConfig.OLD_GROUPS);
	}

	/** What RuneLite writes into the new group before startUp: every default that isn't empty. */
	private void runeLiteFillsDefaults()
	{
		runeLiteFillsDefaults("hans");
	}

	/** The same for a group, as the Gnome Child build did for "gnomechild" when it ran (its address default aside). */
	private void runeLiteFillsDefaults(String group)
	{
		for (Map.Entry<String, String> e : ConfigMigration.defaults().entrySet())
		{
			if (!e.getValue().isEmpty())
			{
				stored.putIfAbsent(group + "." + e.getKey(), e.getValue());
			}
		}
	}

	@Test
	public void theOldValuesReplaceTheDefaultsRuneLiteFilledIn()
	{
		stored.put("rsbuddy.exportBank", "false");
		stored.put("rsbuddy.uploadLive", "true");
		stored.put("rsbuddy.uploadUrl", "https://upload.example/ingest");
		runeLiteFillsDefaults();

		assertEquals(3, run());

		assertEquals("false", stored.get("hans.exportBank"));
		assertEquals("true", stored.get("hans.uploadLive")); // consent already given carries over
		assertEquals("https://upload.example/ingest", stored.get("hans.uploadUrl"));
		assertEquals("true", stored.get("hans.writeFiles")); // never changed: still the default
		assertEquals("true", stored.get("hans." + ConfigMigration.DONE_KEY));
		// the old group is left as it was
		assertEquals("false", stored.get("rsbuddy.exportBank"));
		assertEquals("true", stored.get("rsbuddy.uploadLive"));
	}

	@Test
	public void itRunsOnceSoALaterChangeIsKept()
	{
		stored.put("rsbuddy.uploadLive", "true");
		runeLiteFillsDefaults();
		run();

		stored.put("hans.uploadLive", "false"); // the player switches the upload off afterwards
		runeLiteFillsDefaults();
		assertEquals(0, run());
		assertEquals("false", stored.get("hans.uploadLive"));

		stored.remove("hans.uploadUrl"); // or resets an item to its default
		assertEquals(0, run());
		assertNull(stored.get("hans.uploadUrl"));
	}

	@Test
	public void aNewPlayerWithNoOldSettingsGetsOnlyTheMarker()
	{
		runeLiteFillsDefaults();
		Map<String, String> before = new LinkedHashMap<>(stored);

		assertEquals(0, run());

		before.put("hans." + ConfigMigration.STARTED_KEY, "none");
		before.put("hans." + ConfigMigration.DONE_KEY, "true");
		assertEquals(before, stored);
	}

	@Test
	public void onlyTheOldGroupIsCopied()
	{
		stored.put("rsbuddyx.exportBank", "false"); // another plugin whose group starts the same way
		stored.put("rsbuddy.exportBank", "false");
		runeLiteFillsDefaults();

		assertEquals(1, run());
		assertNull(stored.get("hans.x.exportBank"));
		assertEquals("false", stored.get("hans.exportBank"));
	}

	private static final String MARKER = "hans." + ConfigMigration.DONE_KEY;

	@Test
	public void aCopyThatFailsPartWayLeavesNoMarkerAndTheRetryFinishesIt()
	{
		stored.put("rsbuddy.exportBank", "false");
		stored.put("rsbuddy.uploadUrl", "https://upload.example/ingest");
		stored.put("rsbuddy.exportLoot", "false");
		runeLiteFillsDefaults();
		failOn = "hans.uploadUrl"; // the second of three

		assertEquals(-1, run());
		assertNull(stored.get(MARKER));
		assertEquals("false", stored.get("hans.exportBank")); // the part that was copied
		assertEquals(ConfigMigration.UPLOAD_URL, stored.get("hans.uploadUrl")); // still RuneLite's default

		assertEquals(2, run()); // next start-up: the two not copied yet, still at their defaults
		assertEquals("https://upload.example/ingest", stored.get("hans.uploadUrl"));
		assertEquals("false", stored.get("hans.exportLoot"));
		assertEquals("true", stored.get(MARKER));
		assertEquals(0, run());
	}

	@Test
	public void aMarkerWriteThatFailsIsRetriedAndCopiesTheSameValues()
	{
		stored.put("rsbuddy.exportBank", "false");
		runeLiteFillsDefaults();
		failOn = MARKER;

		assertEquals(-1, run());
		assertNull(stored.get(MARKER));
		assertEquals("false", stored.get("hans.exportBank"));

		assertEquals(0, run()); // already copied: no longer its default, so left as it is
		assertEquals("true", stored.get(MARKER));
		assertEquals("false", stored.get("hans.exportBank"));
	}

	@Test
	public void unknownAndInternalKeysAreHandledExactly()
	{
		stored.put("rsbuddy.someRetiredSetting", "kept as it was"); // not an item today: copied, nothing is discarded
		stored.put("rsbuddy.a.dotted.key", "x");
		stored.put("rsbuddy." + ConfigMigration.DONE_KEY, "true"); // never taken for the new group's markers
		stored.put("rsbuddy." + ConfigMigration.STARTED_KEY, "true");
		stored.put("rsbuddy.", "nameless");
		runeLiteFillsDefaults();

		assertEquals(2, run());
		assertEquals("kept as it was", stored.get("hans.someRetiredSetting"));
		assertEquals("x", stored.get("hans.a.dotted.key"));
		assertNull(stored.get("hans."));
		assertEquals("true", stored.get(MARKER));
	}

	@Test
	public void storedTextIsCopiedExactly()
	{
		String[] values = {"https://upload.example/ingest?x=1&y=%20", "  spaced  ", "5", "-0.50", "{\"a\":[1,2]}",
			"Ünïcödé ✓", "line1\nline2", ""};
		for (int i = 0; i < values.length; i++)
		{
			stored.put("rsbuddy.item" + i, values[i]);
		}
		runeLiteFillsDefaults();

		run();
		for (int i = 0; i < values.length; i++)
		{
			assertEquals(values[i], stored.get("hans.item" + i));
		}
	}

	@Test
	public void missingOrFalseConsentNeverBecomesTrue()
	{
		// switched off before, or never set: RuneLite's filled-in default stays off
		stored.put("rsbuddy.uploadLive", "false");
		runeLiteFillsDefaults();
		stored.putIfAbsent("hans.signIn", "false");
		run();
		assertEquals("false", stored.get("hans.uploadLive"));
		assertEquals("false", stored.get("hans.signIn"));

		// and through a failed first try and its retry
		stored.clear();
		stored.put("rsbuddy.signIn", "false");
		stored.put("rsbuddy.exportBank", "false");
		runeLiteFillsDefaults();
		stored.put("hans.signIn", "false");
		failOn = "hans.exportBank";
		run();
		run();
		assertEquals("false", stored.get("hans.uploadLive"));
		assertEquals("false", stored.get("hans.signIn"));
		for (Map.Entry<String, String> e : stored.entrySet())
		{
			if (e.getKey().startsWith("hans.uploadLive") || e.getKey().startsWith("hans.signIn"))
			{
				assertEquals(e.getKey(), "false", e.getValue());
			}
		}
	}

	@Test
	public void anOptOutBetweenRetriesSticks()
	{
		stored.put("rsbuddy.uploadLive", "true");
		stored.put("rsbuddy.signIn", "true");
		runeLiteFillsDefaults();
		failOn = MARKER;
		assertEquals(-1, run());
		assertEquals("true", stored.get("hans.uploadLive")); // the first try carried the consent over

		stored.put("hans.uploadLive", "false"); // the player switches both off before the next start
		stored.put("hans.signIn", "false");
		run();
		assertEquals("true", stored.get(MARKER));
		assertEquals("false", stored.get("hans.uploadLive"));
		assertEquals("false", stored.get("hans.signIn"));
	}

	@Test
	public void aRetryNeverCopiesConsent()
	{
		stored.put("rsbuddy.exportBank", "false");
		stored.put("rsbuddy.uploadLive", "true");
		stored.put("rsbuddy.signIn", "true");
		runeLiteFillsDefaults();
		failOn = "hans.exportBank"; // fails before the consent keys are reached
		assertEquals(-1, run());

		run();
		assertEquals("false", stored.get("hans.exportBank"));
		assertEquals("false", stored.get("hans.uploadLive")); // whatever is there now stands
		assertEquals("false", stored.get("hans.signIn"));
	}

	@Test
	public void aSettingThePlayerChangedBetweenRetriesIsKept()
	{
		stored.put("rsbuddy.appUrl", "https://old.example");
		stored.put("rsbuddy.exportLoot", "false");
		runeLiteFillsDefaults();
		failOn = "hans.appUrl";
		assertEquals(-1, run());

		stored.put("hans.appUrl", "https://mine.example");
		assertEquals(1, run());
		assertEquals("https://mine.example", stored.get("hans.appUrl"));
		assertEquals("false", stored.get("hans.exportLoot")); // still at its default, so copied
	}

	/** A PC that ran the Gnome Child build: its group, filled in and copied from "rsbuddy" then, as that build left it. */
	private void gnomeChildRan()
	{
		runeLiteFillsDefaults("gnomechild");
		stored.put("gnomechild.appUrl", "https://app.gnomechild.workers.dev");
		stored.put("gnomechild.uploadUrl", "https://app.gnomechild.workers.dev/api/ingest");
		stored.put("gnomechild.copyingFromRsbuddy", "true");
		stored.put("gnomechild.copiedFromRsbuddy", "true");
		stored.put("gnomechild.movedToGnomechildAddress", "true");
	}

	@Test
	public void aGnomeChildPcCopiesFromGnomeChildNotTheOlderGroup()
	{
		stored.put("rsbuddy.exportBank", "false"); // the oldest group: Gnome Child already carried it over, then changed
		stored.put("rsbuddy.uploadLive", "true");
		gnomeChildRan();
		stored.put("gnomechild.exportBank", "true");
		stored.put("gnomechild.uploadLive", "true");
		stored.put("gnomechild.exportLoot", "false");
		runeLiteFillsDefaults();

		assertTrue(run() > 0);
		assertEquals("gnomechild", stored.get("hans." + ConfigMigration.STARTED_KEY));
		assertEquals("true", stored.get("hans.exportBank")); // Gnome Child's value, not the older one
		assertEquals("false", stored.get("hans.exportLoot"));
		assertEquals("true", stored.get("hans.uploadLive")); // consent given in Gnome Child carries over
		assertEquals("https://app.gnomechild.workers.dev", stored.get("hans.appUrl")); // exact; the address move is next
		for (String marker : new String[]{"copyingFromRsbuddy", "copiedFromRsbuddy", "movedToGnomechildAddress"})
		{
			assertNull(marker, stored.get("hans." + marker));
		}
		assertEquals("true", stored.get(MARKER));
		assertEquals("true", stored.get("gnomechild.exportBank")); // the old groups are left as they were
		assertEquals("false", stored.get("rsbuddy.exportBank"));

		Map<String, String> after = new LinkedHashMap<>(stored);
		assertEquals(0, run()); // idempotent
		assertEquals(after, stored);
	}

	@Test
	public void consentOnlyInTheOlderGroupIsNeverInvented()
	{
		stored.put("rsbuddy.uploadLive", "true"); // agreed once, under the oldest name
		stored.put("rsbuddy.signIn", "true");
		gnomeChildRan(); // then switched off in Gnome Child (its defaults are off)
		stored.put("gnomechild.uploadLive", "false");
		stored.put("gnomechild.signIn", "false");
		runeLiteFillsDefaults();
		stored.putIfAbsent("hans.signIn", "false");

		run();
		assertEquals("false", stored.get("hans.uploadLive"));
		assertEquals("false", stored.get("hans.signIn"));
	}

	@Test
	public void anyGnomeChildSettingMakesItTheSource()
	{
		stored.put("rsbuddy.exportBank", "false");
		stored.put("gnomechild.copyingFromRsbuddy", "true"); // a Gnome Child copy that only got as far as its marker
		runeLiteFillsDefaults();

		assertEquals(0, run());
		assertEquals("gnomechild", stored.get("hans." + ConfigMigration.STARTED_KEY));
		assertEquals("true", stored.get("hans.exportBank"));
	}

	@Test
	public void aPcThatNeverRanGnomeChildCopiesFromRsbuddy()
	{
		stored.put("rsbuddy.exportBank", "false");
		stored.put("rsbuddy.uploadLive", "true");
		stored.put("rsbuddy.appUrl", "https://rsbuddy.rsbuddy-app.workers.dev");
		runeLiteFillsDefaults();

		assertEquals(3, run());
		assertEquals("rsbuddy", stored.get("hans." + ConfigMigration.STARTED_KEY));
		assertEquals("false", stored.get("hans.exportBank"));
		assertEquals("true", stored.get("hans.uploadLive"));
		move();
		assertEquals("https://app.hans-osrs.workers.dev", stored.get("hans.appUrl"));
		assertEquals(0, run());
	}

	@Test
	public void aRetryCopiesFromTheGroupTheFirstTryChose()
	{
		stored.put("rsbuddy.exportBank", "false");
		stored.put("rsbuddy.exportLoot", "false");
		runeLiteFillsDefaults();
		failOn = "hans.exportLoot";
		assertEquals(-1, run());

		stored.put("gnomechild.exportLoot", "true"); // a Gnome Child group appearing in between changes nothing
		assertEquals(1, run());
		assertEquals("false", stored.get("hans.exportLoot"));
		assertEquals("rsbuddy", stored.get("hans." + ConfigMigration.STARTED_KEY));
	}

	@Test
	public void theStartUpOrderEndsOnTheNewAddressEvenAfterARetry()
	{
		gnomeChildRan();
		runeLiteFillsDefaults();
		failOn = "hans.appUrl"; // the address is not copied
		startUp();
		assertEquals("https://app.hans-osrs.workers.dev", stored.get("hans.appUrl")); // RuneLite's new default
		assertNull(stored.get("hans." + ConfigMigration.ADDRESS_DONE_KEY)); // no move while the copy is unfinished

		startUp(); // the retry copies the old default address (still at its default here), then the move fixes it
		assertEquals("https://app.hans-osrs.workers.dev", stored.get("hans.appUrl"));
		assertEquals("https://app.hans-osrs.workers.dev/api/ingest", stored.get("hans.uploadUrl"));
		assertEquals("true", stored.get(MARKER));
		assertEquals("true", stored.get("hans." + ConfigMigration.ADDRESS_DONE_KEY));

		stored.put("hans.appUrl", "http://localhost:8810"); // later custom addresses are the player's
		startUp();
		assertEquals("http://localhost:8810", stored.get("hans.appUrl"));
	}

	/** As RsbuddyPlugin.startUp: the copy, then the address move only once the copy has finished. */
	private void startUp()
	{
		if (run() >= 0)
		{
			move();
		}
	}

	private void move()
	{
		ConfigMigration.moveAddress(settings, RsbuddyConfig.GROUP);
	}

	@Test
	public void theOldDefaultAddressMovesToTheNewOne()
	{
		stored.put("hans.appUrl", "https://rsbuddy.rsbuddy-app.workers.dev");
		stored.put("hans.uploadUrl", "");
		move();
		assertEquals("https://app.hans-osrs.workers.dev", stored.get("hans.appUrl"));
		assertEquals("https://app.hans-osrs.workers.dev/api/ingest", stored.get("hans.uploadUrl"));
	}

	@Test
	public void theGnomeChildDefaultAddressMovesToo()
	{
		stored.put("hans.appUrl", "https://app.gnomechild.workers.dev/");
		stored.put("hans.uploadUrl", "https://app.gnomechild.workers.dev/api/ingest");
		move();
		assertEquals("https://app.hans-osrs.workers.dev", stored.get("hans.appUrl"));
		assertEquals("https://app.hans-osrs.workers.dev/api/ingest", stored.get("hans.uploadUrl"));

		stored.clear(); // the first site's upload address, stored in full
		stored.put("hans.uploadUrl", "https://rsbuddy.rsbuddy-app.workers.dev/api/ingest");
		move();
		assertEquals("https://app.hans-osrs.workers.dev/api/ingest", stored.get("hans.uploadUrl"));
	}

	@Test
	public void aLookalikeAddressIsKept()
	{
		stored.put("hans.appUrl", "https://app.gnomechild.workers.dev.example");
		stored.put("hans.uploadUrl", "https://app.gnomechild.workers.dev/api/ingest/extra");
		move();
		assertEquals("https://app.gnomechild.workers.dev.example", stored.get("hans.appUrl"));
		assertEquals("https://app.gnomechild.workers.dev/api/ingest/extra", stored.get("hans.uploadUrl"));
	}

	@Test
	public void aCustomAddressIsKept()
	{
		stored.put("hans.appUrl", "http://localhost:8810");
		stored.put("hans.uploadUrl", "http://localhost:8787/api/ingest");
		move();
		assertEquals("http://localhost:8810", stored.get("hans.appUrl"));
		assertEquals("http://localhost:8787/api/ingest", stored.get("hans.uploadUrl"));
	}

	@Test
	public void aFreshInstallGetsTheNewDefaults()
	{
		RsbuddyConfig d = new RsbuddyConfig()
		{
		};
		assertEquals("https://app.hans-osrs.workers.dev", d.appUrl());
		assertEquals("https://app.hans-osrs.workers.dev/api/ingest", d.uploadUrl());
		move(); // nothing stored: nothing rewritten
		assertNull(stored.get("hans.appUrl"));
		assertNull(stored.get("hans.uploadUrl"));
	}

	@Test
	public void theAddressMoveRunsOnlyOnce()
	{
		stored.put("hans.appUrl", "https://rsbuddy.rsbuddy-app.workers.dev");
		move();
		stored.put("hans.appUrl", "https://rsbuddy.rsbuddy-app.workers.dev"); // set back by hand
		move();
		assertEquals("https://rsbuddy.rsbuddy-app.workers.dev", stored.get("hans.appUrl"));
	}

	@Test
	public void theDefaultsListEveryItem() throws Exception
	{
		String source = new String(Files.readAllBytes(Paths.get("src", "main", "java", "com", "hans",
			"RsbuddyConfig.java")), StandardCharsets.UTF_8);
		Map<String, String> defaults = ConfigMigration.defaults();
		assertEquals(source.split(Pattern.quote("@ConfigItem("), -1).length - 1, defaults.size());
		Matcher m = Pattern.compile("keyName = \"([^\"]+)\"").matcher(source);
		while (m.find())
		{
			assertTrue(m.group(1), defaults.containsKey(m.group(1)));
		}
		assertEquals("false", defaults.get(UploadCapture.ENABLE_KEY));
		assertEquals("false", defaults.get(AuthClient.ENABLE_KEY));
	}
}
