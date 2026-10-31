package com.hans;

import com.hans.RsbuddyData.Session;
import com.hans.RsbuddyData.SessionsFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Every session of one account, as held in memory between writes. Past sessions are only ever appended to:
 * the live session is replaced by its newer version, nothing else is changed.
 * Not thread safe; the caller serialises access.
 */
final class SessionLog
{
	private final List<Session> sessions = new ArrayList<>();

	/**
	 * Adopts the sessions already on disk. A session still open (end is null) that is not the live one was cut
	 * short by a crash or a kill: it is closed at the time it was last written.
	 */
	void load(SessionsFile existing, String liveStart)
	{
		sessions.clear();
		if (existing == null || existing.sessions == null)
		{
			return;
		}
		for (Session s : existing.sessions)
		{
			if (s == null || s.start == null)
			{
				continue;
			}
			if (s.end == null && !s.start.equals(liveStart))
			{
				s.end = s.last_update != null ? s.last_update : s.start;
				if (s.xp_end == null || s.xp_end.isEmpty())
				{
					s.xp_end = s.xp_start;
					s.levels_end = s.levels_start;
				}
			}
			sessions.add(s);
		}
	}

	SessionsFile compose(String accountName, long accountHash, Session live)
	{
		boolean replaced = false;
		for (int i = 0; i < sessions.size(); i++)
		{
			if (sessions.get(i).start.equals(live.start))
			{
				sessions.set(i, live);
				replaced = true;
				break;
			}
		}
		if (!replaced)
		{
			sessions.add(live);
		}
		SessionsFile out = new SessionsFile();
		out.format_version = RsbuddyData.FORMAT_VERSION;
		out.account_name = accountName;
		out.account_hash = accountHash;
		out.sessions = new ArrayList<>(sessions);
		return out;
	}
}
