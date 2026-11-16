package pl.aerion.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * The messages of shared windows. A player with Aerion says hello; the server mod answers, then
 * passes the panels a player shares (where they hang, how big, what the window is called) and
 * their pictures (JPEG, sent in parts because messages to the server are limited to 32 KiB) on
 * to the other Aerion players near them. Everything read is checked: it comes from the network.
 */
public final class Proto {
	private Proto() {}

	public static final int VERSION = 1;
	/** A part of a picture sent to the server (the server takes at most 32767 bytes per message). */
	public static final int PART = 30_000;
	public static final int MAX_PARTS = 40;
	public static final int MAX_TITLE = 64;

	// To the server.
	public static final byte HELLO = 1, PANEL = 2, PART_OF_FRAME = 3, REMOVE = 4, ALLOW = 5;
	public static final int MAX_ALLOWED = 64;
	// To the players.
	public static final byte WELCOME = 10, THEIR_PANEL = 11, THEIR_FRAME = 12, THEIR_REMOVE = 13;

	/** Where a shared panel hangs and how it looks (sizes in blocks). */
	public record Panel(int id, double x, double y, double z, float yaw, float pitch, float width, float aspect, float radius, boolean curved, String title) {
		/** Values a well-behaved mod would send; anything else is pulled into range. */
		public Panel clamped() {
			return new Panel(id, finite(x), finite(y), finite(z), clamp(yaw, -720, 720), clamp(pitch, -90, 90), clamp(width, 0.2f, 8),
					clamp(aspect, 0.1f, 4), clamp(radius, 0.3f, 16), curved, title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) : title);
		}

		void write(DataOutputStream o) throws IOException {
			o.writeInt(id);
			o.writeDouble(x);
			o.writeDouble(y);
			o.writeDouble(z);
			o.writeFloat(yaw);
			o.writeFloat(pitch);
			o.writeFloat(width);
			o.writeFloat(aspect);
			o.writeFloat(radius);
			o.writeBoolean(curved);
			writeText(o, title);
		}

		static Panel read(DataInputStream in) throws IOException {
			return new Panel(in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readFloat(),
					in.readFloat(), in.readFloat(), in.readBoolean(), readText(in)).clamped();
		}
	}

	private static double finite(double v) {
		return Double.isFinite(v) ? Math.max(-3.0e7, Math.min(3.0e7, v)) : 0;
	}

	private static float clamp(float v, float min, float max) {
		return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : min;
	}

	static void writeText(DataOutputStream o, String s) throws IOException {
		byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		o.writeShort(Math.min(b.length, 1024));
		o.write(b, 0, Math.min(b.length, 1024));
	}

	static String readText(DataInputStream in) throws IOException {
		int n = in.readUnsignedShort();
		if (n > 1024) throw new IOException("text too long");
		byte[] b = in.readNBytes(n);
		if (b.length != n) throw new IOException("cut short");
		return new String(b, java.nio.charset.StandardCharsets.UTF_8);
	}

	private interface Body {
		void write(DataOutputStream o) throws IOException;
	}

	private static byte[] message(byte kind, Body body) {
		try {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			DataOutputStream o = new DataOutputStream(b);
			o.writeByte(kind);
			body.write(o);
			o.flush();
			return b.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	// --- Building messages ---------------------------------------------------------------------

	/** `view`: whether this player wants to see the windows others share. */
	public static byte[] hello(boolean view) {
		return message(HELLO, o -> {
			o.writeInt(VERSION);
			o.writeBoolean(view);
		});
	}

	public static byte[] panel(Panel p) {
		return message(PANEL, p::write);
	}

	public static byte[] part(int id, int frame, int index, int count, byte[] jpeg, int from, int len) {
		return message(PART_OF_FRAME, o -> {
			o.writeInt(id);
			o.writeInt(frame);
			o.writeShort(index);
			o.writeShort(count);
			o.write(jpeg, from, len);
		});
	}

	public static byte[] remove(int id) {
		return message(REMOVE, o -> o.writeInt(id));
	}

	/** Who may see this player's windows: everyone, or only the players named. */
	public static byte[] allow(boolean everyone, java.util.Collection<String> names) {
		return message(ALLOW, o -> {
			o.writeBoolean(everyone);
			int n = Math.min(MAX_ALLOWED, names.size());
			o.writeShort(n);
			int i = 0;
			for (String name : names) {
				if (i++ >= n) break;
				writeText(o, name);
			}
		});
	}

	/** The server's answer: it passes windows on, at most `fps` pictures a second of `maxFrameKb` each. */
	public static byte[] welcome(int fps, int maxPanels, int maxFrameKb) {
		return message(WELCOME, o -> {
			o.writeInt(VERSION);
			o.writeInt(fps);
			o.writeInt(maxPanels);
			o.writeInt(maxFrameKb);
		});
	}

	public static byte[] theirPanel(UUID owner, String name, Panel p) {
		return message(THEIR_PANEL, o -> {
			writeUuid(o, owner);
			writeText(o, name);
			p.write(o);
		});
	}

	public static byte[] theirFrame(UUID owner, int id, byte[] jpeg) {
		return message(THEIR_FRAME, o -> {
			writeUuid(o, owner);
			o.writeInt(id);
			o.write(jpeg);
		});
	}

	public static byte[] theirRemove(UUID owner, int id) {
		return message(THEIR_REMOVE, o -> {
			writeUuid(o, owner);
			o.writeInt(id);
		});
	}

	private static void writeUuid(DataOutputStream o, UUID u) throws IOException {
		o.writeLong(u.getMostSignificantBits());
		o.writeLong(u.getLeastSignificantBits());
	}

	private static UUID readUuid(DataInputStream in) throws IOException {
		return new UUID(in.readLong(), in.readLong());
	}

	// --- Reading messages ----------------------------------------------------------------------

	public sealed interface Message {}

	public record Hello(int version, boolean view) implements Message {}

	public record PanelMsg(Panel panel) implements Message {}

	public record Part(int id, int frame, int index, int count, byte[] bytes) implements Message {}

	public record Remove(int id) implements Message {}

	public record Allow(boolean everyone, java.util.List<String> names) implements Message {}

	public record Welcome(int version, int fps, int maxPanels, int maxFrameKb) implements Message {}

	public record TheirPanel(UUID owner, String name, Panel panel) implements Message {}

	public record TheirFrame(UUID owner, int id, byte[] jpeg) implements Message {}

	public record TheirRemove(UUID owner, int id) implements Message {}

	/** The message in these bytes, or null when they aren't a valid one. */
	public static Message read(byte[] data) {
		if (data == null || data.length == 0) return null;
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			byte kind = in.readByte();
			return switch (kind) {
				case HELLO -> new Hello(in.readInt(), in.readBoolean());
				case PANEL -> new PanelMsg(Panel.read(in));
				case PART_OF_FRAME -> {
					int id = in.readInt(), frame = in.readInt(), index = in.readUnsignedShort(), count = in.readUnsignedShort();
					if (count < 1 || count > MAX_PARTS || index >= count) yield null;
					byte[] rest = in.readAllBytes();
					yield rest.length > PART ? null : new Part(id, frame, index, count, rest);
				}
				case REMOVE -> new Remove(in.readInt());
				case ALLOW -> {
					boolean everyone = in.readBoolean();
					int n = in.readUnsignedShort();
					if (n > MAX_ALLOWED) yield null;
					java.util.List<String> names = new java.util.ArrayList<>();
					for (int i = 0; i < n; i++) {
						String name = readText(in);
						if (name.length() <= 32) names.add(name);
					}
					yield new Allow(everyone, names);
				}
				case WELCOME -> {
					int version = in.readInt(), fps = in.readInt(), panels = in.readInt();
					// Servers of the first version didn't say the picture size.
					yield new Welcome(version, fps, panels, in.available() >= 4 ? in.readInt() : 200);
				}
				case THEIR_PANEL -> new TheirPanel(readUuid(in), readText(in), Panel.read(in));
				case THEIR_FRAME -> new TheirFrame(readUuid(in), in.readInt(), in.readAllBytes());
				case THEIR_REMOVE -> new TheirRemove(readUuid(in), in.readInt());
				default -> null;
			};
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
