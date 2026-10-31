package com.hans;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Quest requirements bundled with the plugin (quest_requirements.json, generated from RSBuddy's quest catalog by
 * tools/build_plugin_reference.py), so the panel can pick "Nearly there" quests without fetching anything.
 */
@Slf4j
final class QuestRequirements
{
	static final class Quest
	{
		String name;
		int reqQp;
		Map<String, Integer> requires;
		List<Level> levels;
		List<JsonObject> rules;
	}

	static final class Level
	{
		String skill;
		int level;
	}

	private static final class File
	{
		List<Quest> quests;
	}

	private QuestRequirements()
	{
	}

	static List<Quest> load(Gson gson)
	{
		try (InputStream in = QuestRequirements.class.getResourceAsStream("quest_requirements.json");
			Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			File f = gson.fromJson(r, File.class);
			return f == null || f.quests == null ? Collections.emptyList() : f.quests;
		}
		catch (Exception e)
		{
			log.warn("Hans: could not load the bundled quest requirements", e);
			return Collections.emptyList();
		}
	}
}
