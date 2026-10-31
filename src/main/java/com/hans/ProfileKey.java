package com.hans;

import java.util.Objects;

/**
 * Whose data a write is: the account hash and the game mode, plus the name the account had at that login.
 * Files go to {@code accounts/<hash>/<mode>/}; the name is data, so a name change keeps the folder.
 * Immutable, and compared as a whole: a value tagged with one key is never credited to another.
 */
final class ProfileKey
{
	final long hash;
	final String mode;
	final String name;

	ProfileKey(long hash, String mode, String name)
	{
		this.hash = hash;
		this.mode = Objects.requireNonNull(mode);
		this.name = Objects.requireNonNull(name);
	}

	/** The hash as account.json writes it: a signed decimal string (it can exceed 2^53). */
	String hashText()
	{
		return Long.toString(hash);
	}

	boolean isMain()
	{
		return GameMode.MAIN.equals(mode);
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof ProfileKey))
		{
			return false;
		}
		ProfileKey k = (ProfileKey) o;
		return hash == k.hash && mode.equals(k.mode) && name.equals(k.name);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(hash, mode, name);
	}

	@Override
	public String toString()
	{
		return hash + "/" + mode + " (" + name + ")";
	}
}
