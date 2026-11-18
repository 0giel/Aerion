package pl.aerion.screens.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windows of a Wayland desktop. Programs running on Wayland can't be read or clicked by another
 * program; the desktop's own screen-share dialog (xdg-desktop-portal) lets the player choose one,
 * and a small helper (portal-capture.py, Python with GStreamer) passes its picture on, made small
 * already. These panels are pictures only. The choice is remembered (a restore token saved with the
 * panel), so a saved panel comes back without the dialog. X11 windows (XWayland) still come from
 * the X11 source, with clicks and typing.
 *
 * <p>The helper is its own process, so a crash in it never takes the game down; it ends when the
 * game lets go of its window or ends.
 */
public final class PortalSource implements WindowSource {
	/** The picker's entry that opens the desktop's dialog. */
	static final long PICK = -1;
	static final String NEW = "portal:new", PREFIX = "portal:", PENDING = "portal:pending:";
	/** A stream nobody looked at for this long is let go (a panel in another world). */
	private static final long IDLE_MS = 20_000;

	/** Why the last window from Wayland didn't work (shown in the picker), or null. */
	public static volatile String problem;

	private final WindowSource x11;
	private final Map<Long, Stream> streams = new ConcurrentHashMap<>();
	/** Choices the player stopped sharing this session: never reopened behind their back. */
	private final Set<String> stopped = ConcurrentHashMap.newKeySet();
	private long nextId = -2;

	private PortalSource(WindowSource x11) {
		this.x11 = x11;
	}

	/** Whether to offer windows from Wayland: on a Wayland desktop, or when asked for (tests). */
	public static boolean wanted() {
		String w = System.getenv("WAYLAND_DISPLAY");
		return (w != null && !w.isEmpty()) || "1".equals(System.getenv("AERION_PORTAL"));
	}

	/** The source for this Linux desktop: X11 windows, plus windows from Wayland when there. */
	static WindowSource wrap(WindowSource x11) {
		return wanted() ? new PortalSource(x11) : x11;
	}

	private static final class Stream {
		final long id;
		final Process process;
		volatile Frame last;
		volatile String token;
		volatile boolean done;
		volatile long used = System.currentTimeMillis();

		Stream(long id, Process process, String token) {
			this.id = id;
			this.process = process;
			this.token = token;
		}

		void close() {
			done = true;
			try {
				process.getOutputStream().close();
			} catch (IOException ignored) {
				// already gone
			}
			process.destroy();
		}
	}

	private static boolean ours(long id) {
		return id < PICK;
	}

	@Override
	public List<Win> list() {
		List<Win> out = x11 != null ? new ArrayList<>(x11.list()) : new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Stream s : streams.values()) {
			if (!s.done && now - s.used > IDLE_MS) {
				s.close();
				streams.remove(s.id);
			}
			if (!s.done) out.add(new Win(s.id, Text.tr("aerion_screens.waylandPanel"), clsOf(s)));
		}
		return out;
	}

	@Override
	public List<Win> pickable() {
		List<Win> out = x11 != null ? new ArrayList<>(x11.list()) : new ArrayList<>();
		out.add(new Win(PICK, Text.tr("aerion_screens.wayland"), NEW));
		return out;
	}

	@Override
	public Win open(Win w) {
		if (w.id() != PICK) return w;
		long id = start(null);
		return id == 0 ? null : new Win(id, Text.tr("aerion_screens.waylandPanel"), PENDING + id);
	}

	@Override
	public String clsOf(long id) {
		Stream s = ours(id) ? streams.get(id) : null;
		return s == null ? null : clsOf(s);
	}

	private static String clsOf(Stream s) {
		return s.token != null ? PREFIX + s.token : PENDING + s.id;
	}

	@Override
	public long reopen(String cls) {
		if (cls == null || !cls.startsWith(PREFIX) || cls.startsWith(PENDING) || cls.equals(NEW)) return 0;
		String token = cls.substring(PREFIX.length());
		if (token.isEmpty() || stopped.contains(token)) return 0;
		for (Stream s : streams.values()) if (!s.done && token.equals(s.token)) return s.id;
		return start(token);
	}

	@Override
	public void closed(long id) {
		Stream s = ours(id) ? streams.remove(id) : null;
		if (s != null) s.close();
		else if (x11 != null && id > 0) x11.closed(id);
	}

	@Override
	public Frame capture(long id, int maxWidth) {
		if (id == PICK) return null;
		if (!ours(id)) return x11 != null ? x11.capture(id, maxWidth) : null;
		Stream s = streams.get(id);
		if (s == null || s.done) return null;
		s.used = System.currentTimeMillis();
		Frame f = s.last;
		return f == null ? null : WindowSource.fit(f.width(), f.height(), f.argb(), maxWidth);
	}

	/** Starts the helper: with a token it restores that choice, without one the dialog opens. */
	private synchronized long start(String token) {
		Path script;
		try {
			script = helper();
		} catch (IOException e) {
			problem = "missing:helper";
			Screens.LOG.warn("Aerion: the Wayland window helper couldn't be written: {}", e.toString());
			return 0;
		}
		long id = nextId--;
		int width = Math.min(1920, Math.max(320, Mirrors.captureWidth()));
		ProcessBuilder pb = new ProcessBuilder("python3", "-I", script.toString(), String.valueOf(width), String.valueOf(Mirrors.captureFps()), token == null ? "-" : token);
		pb.redirectError(ProcessBuilder.Redirect.DISCARD);
		Process process;
		try {
			process = pb.start();
		} catch (IOException e) {
			problem = "missing:python";
			Screens.LOG.warn("Aerion: python3 isn't there for windows from Wayland: {}", e.toString());
			return 0;
		}
		Stream s = new Stream(id, process, token);
		streams.put(id, s);
		problem = null;
		Thread t = new Thread(() -> read(s), "Aerion Wayland window");
		t.setDaemon(true);
		t.start();
		Screens.LOG.info("Aerion: asking the desktop for a window{}", token == null ? "" : " (the one chosen before)");
		return id;
	}

	/** Reads the helper's messages until it ends. */
	private void read(Stream s) {
		try (DataInputStream in = new DataInputStream(s.process.getInputStream())) {
			while (true) {
				int kind = in.read();
				if (kind < 0) break;
				int len = in.readInt();
				if (len < 0 || len > 1920 * 1200 * 4 + 8) break;
				byte[] body = in.readNBytes(len);
				if (body.length != len) break;
				switch (kind) {
					case 'F' -> s.last = frame(body);
					case 'T' -> s.token = new String(body, StandardCharsets.UTF_8);
					case 'S' -> {
						if (s.token != null) stopped.add(s.token);
						Screens.LOG.info("Aerion: the desktop stopped sharing a window");
					}
					case 'E' -> {
						String why = new String(body, StandardCharsets.UTF_8);
						// A restored choice that the desktop refused is not asked for again.
						if (s.token != null) stopped.add(s.token);
						if (!why.equals("cancelled")) problem = why;
						Screens.LOG.info("Aerion: no window from Wayland: {}", why);
					}
					default -> {}
				}
			}
		} catch (IOException ignored) {
			// the helper ended
		} finally {
			s.done = true;
			s.process.destroy();
		}
	}

	/** B, G, R, x bytes to ARGB pixels. */
	private static Frame frame(byte[] b) {
		int w = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
		int h = ((b[4] & 0xFF) << 24) | ((b[5] & 0xFF) << 16) | ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
		if (w <= 0 || h <= 0 || (long) w * h * 4 != b.length - 8L) return null;
		int[] px = new int[w * h];
		for (int i = 0, o = 8; i < px.length; i++, o += 4) px[i] = 0xFF000000 | (b[o + 2] & 0xFF) << 16 | (b[o + 1] & 0xFF) << 8 | (b[o] & 0xFF);
		return new Frame(w, h, px);
	}

	/** The helper, written next to the session's other private files (only this user can change it). */
	private static Path helper() throws IOException {
		String runtime = System.getenv("XDG_RUNTIME_DIR");
		Path dir = runtime != null && !runtime.isEmpty() && Files.isDirectory(Path.of(runtime)) ? Path.of(runtime, "aerion") : Path.of(System.getProperty("user.home"), ".cache", "aerion");
		Files.createDirectories(dir);
		Path file = dir.resolve("portal-capture.py");
		try (InputStream in = PortalSource.class.getResourceAsStream("/assets/aerion_screens/portal-capture.py")) {
			if (in == null) throw new IOException("not in the mod");
			Path tmp = Files.createTempFile(dir, "portal-capture", ".py");
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		return file;
	}

	// --- X11 windows keep everything they had ---------------------------------------------------

	@Override
	public long pid(long id) {
		return x11 != null && !ours(id) ? x11.pid(id) : 0;
	}

	@Override
	public Frame icon(long id) {
		return x11 != null && id >= 0 ? x11.icon(id) : null;
	}

	@Override
	public void press(long id, float x, float y, int button) {
		if (x11 != null && id >= 0) x11.press(id, x, y, button);
	}

	@Override
	public void release(long id, float x, float y, int button) {
		if (x11 != null && id >= 0) x11.release(id, x, y, button);
	}

	@Override
	public void move(long id, float x, float y, boolean held) {
		if (x11 != null && id >= 0) x11.move(id, x, y, held);
	}

	@Override
	public void scroll(long id, float x, float y, int notches) {
		if (x11 != null && id >= 0) x11.scroll(id, x, y, notches);
	}

	@Override
	public void type(long id, String text) {
		if (x11 != null && id >= 0) x11.type(id, text);
	}

	@Override
	public void key(long id, String name) {
		if (x11 != null && id >= 0) x11.key(id, name);
	}

	@Override
	public void focusGame() {
		if (x11 != null) x11.focusGame();
	}
}
