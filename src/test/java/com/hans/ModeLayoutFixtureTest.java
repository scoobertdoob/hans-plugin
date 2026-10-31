package com.hans;

import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import net.runelite.api.Item;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;

/**
 * Writes the synthetic importer fixture {@code tests/fixtures/synthetic/mode_layout_writer/rsbuddy/} from the real
 * capture flow: one synthetic account plays main, hops straight into a league world and logs out. Skipped unless
 * the environment variable RSBUDDY_FIXTURE_OUT names the folder to write into, for example
 * {@code RSBUDDY_FIXTURE_OUT=../tests/fixtures/synthetic/mode_layout_writer/rsbuddy gradlew test --no-daemon}.
 */
public class ModeLayoutFixtureTest
{
	static final long HASH = -5151515151515151515L;

	@Test
	public void writeTheImporterFixture() throws IOException
	{
		String out = System.getenv("RSBUDDY_FIXTURE_OUT");
		assumeTrue(out != null && !out.isEmpty());

		GameFake g = new GameFake();
		g.name = "Synthetic Writer";
		g.accountHash = HASH;
		CharacterCapture character = new CharacterCapture(g.ctx);
		SessionCapture sessions = new SessionCapture(g.ctx, character, (a, s, x) -> { });
		g.captures.add(character);
		g.captures.add(sessions);
		g.captures.add(new ItemGainsCapture(g.ctx, sessions));
		g.captures.add(new LootCapture(g.ctx));
		g.captures.add(new AccountCapture(g.ctx));
		g.containers.put(InventoryID.WORN, new Item[0]);
		g.containers.put(InventoryID.INV, new Item[]{new Item(440, 1)});

		g.logIn();
		g.ticks(5);
		g.setContainer(InventoryID.INV, new Item(440, 4));
		g.ticks(2);
		g.chat("Your Zulrah kill count is: <col=ff0000>12</col>.");
		g.hop(500, WorldType.MEMBERS, WorldType.SEASONAL);
		g.settle();
		g.executor.runAll();
		g.tick();
		g.chat("Your Zulrah kill count is: <col=ff0000>1</col>.");
		g.tick();
		g.logOut();
		g.executor.runAll();

		Path target = Paths.get(out);
		try (Stream<Path> files = Files.walk(g.dir))
		{
			for (Path p : (Iterable<Path>) files::iterator)
			{
				Path to = target.resolve(g.dir.relativize(p).toString());
				if (Files.isDirectory(p))
				{
					Files.createDirectories(to);
				}
				else
				{
					Files.copy(p, to);
				}
			}
		}
	}
}
