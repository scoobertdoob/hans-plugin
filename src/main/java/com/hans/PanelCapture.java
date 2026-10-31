package com.hans;

import com.google.gson.Gson;
import com.hans.RsbuddyData.Session;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import net.runelite.api.Player;

/**
 * Feeds the side panel. Writes nothing: every few ticks, if the character reading, the quests or the sessions
 * changed, it builds a {@link PanelModel} on the client thread and hands it to the panel.
 */
final class PanelCapture implements Capture
{
	private static final int EVERY_TICKS = 5;

	private final CaptureContext ctx;
	private final CharacterCapture character;
	private final SessionCapture sessions;
	private final QuestsCapture quests;
	private final Consumer<PanelModel> show;
	private final List<QuestRequirements.Quest> reqs;
	private Instant shownAt;
	private Object shownQuests;
	private Object shownPrevious;
	private int ticks;

	PanelCapture(CaptureContext ctx, Gson gson, CharacterCapture character, SessionCapture sessions,
		QuestsCapture quests, Consumer<PanelModel> show)
	{
		this.ctx = ctx;
		this.character = character;
		this.sessions = sessions;
		this.quests = quests;
		this.show = show;
		this.reqs = QuestRequirements.load(gson);
	}

	@Override
	public void onSettled(Player local, Instant now)
	{
		shownAt = null; // force a refresh
		ticks = EVERY_TICKS - 1;
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		ProfileKey key = ctx.login.reading();
		if (key == null || ++ticks < EVERY_TICKS || character.latest() == null)
		{
			return;
		}
		ticks = 0;
		Instant at = character.latestAt();
		RsbuddyData.Quests q = quests.latest();
		Session previous = sessions.previous();
		if (at.equals(shownAt) && q == shownQuests && previous == shownPrevious)
		{
			return;
		}
		shownAt = at;
		shownQuests = q;
		shownPrevious = previous;
		String label = key.isMain() ? key.name : key.name + " (" + key.mode + ")";
		show.accept(PanelModel.build(label, character.latest(), sessions.live(), previous, q, reqs));
	}

	@Override
	public Future<?> onLogout(Instant now)
	{
		shownAt = null;
		show.accept(PanelModel.loggedOut(sessions.previous()));
		return null;
	}
}
