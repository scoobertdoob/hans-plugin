package com.hans;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import net.runelite.client.util.Filepath;

/**
 * This PC's upload key: a public key id and a secret, made the first time uploading is switched on and kept in the
 * plugin's own folder ({@code upload/device.json}, via {@code getPluginDirectory()}). Never in RuneLite's settings,
 * which can sync to a RuneLite account. The website stores only a hash of the secret; the secret travels only in the
 * Authorization header over HTTPS. One key per PC, for every account played on it; the website links it to each
 * account separately.
 *
 * Also hands out the PC's sequence numbers. They only go up, across restarts too: the file records the end of a block
 * of numbers reserved before any of it is used, so a crash skips numbers but never repeats one. A file that cannot be
 * read is moved aside (never deleted) and a new key is made: the website then sees a newly linked PC.
 * Disk IO: call off the client thread.
 */
final class DeviceKey
{
	static final String FOLDER = "upload";
	static final String FILE = "device.json";
	static final long BLOCK = 10_000;

	final String keyId;
	private final String secret;
	private final Filepath folder;
	private final Gson gson;
	private final String created;
	private long next;
	private long reservedEnd;

	private DeviceKey(Filepath folder, Gson gson, String keyId, String secret, String created, long next)
	{
		this.folder = folder;
		this.gson = gson;
		this.keyId = keyId;
		this.secret = secret;
		this.created = created;
		this.next = next;
		this.reservedEnd = next;
	}

	/** The Authorization header's value. Never logged. */
	String bearer()
	{
		return "Bearer " + keyId + "." + secret;
	}

	@Override
	public String toString()
	{
		return "DeviceKey(" + keyId + ")"; // never the secret
	}

	/** The next sequence number, reserving (and saving) a new block first when the current one is used up. */
	synchronized long nextSeq() throws IOException
	{
		if (next >= reservedEnd)
		{
			reserve();
		}
		return next++;
	}

	/** The next sequence number if the reserved block still has one (no disk IO); -1 when a new block is needed. */
	synchronized long nextSeqInMemory()
	{
		return next < reservedEnd ? next++ : -1;
	}

	private void reserve() throws IOException
	{
		save(next + BLOCK);
		reservedEnd = next + BLOCK;
	}

	/** Loads this PC's key, or makes one. Reserves a first block of sequence numbers either way. */
	static DeviceKey loadOrCreate(Filepath root, Gson gson, SecureRandom random) throws IOException
	{
		Filepath folder = root.joinSegment(FOLDER);
		folder.createDirectories();
		Filepath file = folder.joinSegment(FILE);
		if (file.exists())
		{
			DeviceKey k = parse(folder, gson, readText(file));
			if (k != null)
			{
				k.reserve();
				return k;
			}
			file.moveTo(folder.joinSegment(FILE + ".unreadable-" + System.currentTimeMillis()));
		}
		DeviceKey k = new DeviceKey(folder, gson, hex(random, 16), hex(random, 32), Instant.now().toString(), 1);
		k.reserve();
		return k;
	}

	/**
	 * This PC's key, loaded on first use and the same one after that. The upload and sign-in share it: two copies would
	 * reserve the same block of sequence numbers. Disk IO on first use: call off the client thread.
	 */
	static UploadSender.Keys shared(JsonWriter.Root root, Gson gson)
	{
		return new UploadSender.Keys()
		{
			private DeviceKey key;

			@Override
			public synchronized DeviceKey get() throws IOException
			{
				if (key == null)
				{
					key = loadOrCreate(root.get(), gson, new SecureRandom());
				}
				return key;
			}
		};
	}

	private static DeviceKey parse(Filepath folder, Gson gson, String text)
	{
		try
		{
			JsonObject o = gson.fromJson(text, JsonObject.class);
			String id = o.get("keyId").getAsString();
			String secret = o.get("secret").getAsString();
			long next = o.get("nextSeq").getAsLong();
			if (!id.matches("[0-9a-f]{32}") || !secret.matches("[0-9a-f]{64}") || next < 1)
			{
				return null;
			}
			JsonElement created = o.get("created");
			return new DeviceKey(folder, gson, id, secret, created == null ? null : created.getAsString(), next);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private void save(long nextSeq) throws IOException
	{
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		o.addProperty("keyId", keyId);
		o.addProperty("secret", secret);
		o.addProperty("created", created);
		o.addProperty("nextSeq", nextSeq);
		o.addProperty("note", "Hans upload key for this PC. Keep it private; deleting it links this PC again as a new one.");
		byte[] bytes = gson.newBuilder().setPrettyPrinting().create().toJson(o).getBytes(StandardCharsets.UTF_8);
		Filepath out = folder.joinSegment(FILE);
		Filepath tmp = folder.joinSegment(FILE + ".tmp");
		try (OutputStream os = tmp.openOutputStream())
		{
			os.write(bytes);
		}
		try
		{
			tmp.moveTo(out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException atomicFailed)
		{
			tmp.moveTo(out, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String readText(Filepath file) throws IOException
	{
		try (InputStream in = file.openInputStream())
		{
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] b = new byte[4096];
			int n;
			while ((n = in.read(b)) > 0)
			{
				buf.write(b, 0, n);
			}
			return new String(buf.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static String hex(SecureRandom random, int bytes)
	{
		byte[] b = new byte[bytes];
		random.nextBytes(b);
		StringBuilder s = new StringBuilder(bytes * 2);
		for (byte x : b)
		{
			s.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
		}
		return s.toString();
	}
}
