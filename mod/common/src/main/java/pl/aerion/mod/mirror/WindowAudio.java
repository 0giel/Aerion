package pl.aerion.mod.mirror;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import pl.aerion.mod.AerionClient;

/**
 * The sound of a shared window: on Linux with PipeWire, the program's own audio stream (found by
 * the window's process, its program or its child processes) is recorded with `pw-record` - only
 * that program, not the game or the rest of the computer - and sent in 0.1 s pieces. Other
 * systems send no sound yet (they still hear others).
 */
final class WindowAudio {
	private WindowAudio() {}

	static final int RATE = 24_000, CHUNK = RATE / 10;

	private static final Map<Mirrors.Panel, Capture> RUNNING = new IdentityHashMap<>();
	private static Boolean available;

	/** Whether this computer can send window sound (Linux with PipeWire's tools). */
	static boolean available() {
		if (available == null) {
			available = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux") && onPath("pw-record") && onPath("pw-dump");
		}
		return available;
	}

	private static boolean onPath(String tool) {
		for (String dir : System.getenv().getOrDefault("PATH", "/usr/bin:/bin").split(":")) {
			if (Files.isExecutable(Path.of(dir, tool))) return true;
		}
		return false;
	}

	/** Every tick: sound for each panel in `live`, stopped for the rest. */
	static synchronized void tick(List<Mirrors.Panel> live) {
		for (var it = RUNNING.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			if (!live.contains(e.getKey())) {
				e.getValue().finish();
				it.remove();
			}
		}
		if (!available()) return;
		for (Mirrors.Panel p : live) {
			if (RUNNING.containsKey(p) || p.win == 0) continue;
			Capture c = new Capture(p);
			RUNNING.put(p, c);
			c.start();
		}
	}

	static synchronized void stopAll() {
		for (Capture c : RUNNING.values()) c.finish();
		RUNNING.clear();
	}

	private static final class Capture extends Thread {
		final Mirrors.Panel panel;
		volatile boolean stop;
		volatile Process process;
		boolean told;
		final Adpcm.Encoder encoder = new Adpcm.Encoder();

		Capture(Mirrors.Panel panel) {
			super("Aerion window sound");
			this.panel = panel;
			setDaemon(true);
		}

		void finish() {
			stop = true;
			Process p = process;
			if (p != null) p.destroy();
			interrupt();
		}

		@Override
		public void run() {
			while (!stop) {
				try {
					WindowSource src = Mirrors.source();
					long pid = src == null || panel.win == 0 ? 0 : src.pid(panel.win);
					long node = findNode(pid, panel.cls);
					// No sound playing yet: look again in a while.
					if (node < 0) {
						if (!told) {
							AerionClient.LOG.info("Aerion: no sound found yet for window \"{}\" (program {}, process {}); looking again every few seconds", panel.title, panel.cls, pid);
							told = true;
						}
						Thread.sleep(3000);
						continue;
					}
					AerionClient.LOG.info("Aerion: sharing the sound of window \"{}\" (PipeWire stream {})", panel.title, node);
					told = false;
					record(node);
				} catch (InterruptedException e) {
					return;
				} catch (Exception e) {
					AerionClient.warnOnce("window-sound", e);
					try {
						Thread.sleep(5000);
					} catch (InterruptedException ie) {
						return;
					}
				}
			}
		}

		/** Records the stream until it ends (the program stops playing) or sharing stops. */
		private void record(long node) throws Exception {
			Process p = pipewire(new ProcessBuilder("pw-record", "--target", String.valueOf(node), "--rate", String.valueOf(RATE), "--channels", "1", "--format", "s16", "-"))
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			process = p;
			byte[] buf = new byte[CHUNK * 2];
			short[] pcm = new short[CHUNK];
			try (InputStream in = p.getInputStream()) {
				while (!stop) {
					int got = in.readNBytes(buf, 0, buf.length);
					if (got <= 0) break;
					int n = got / 2;
					for (int i = 0; i < n; i++) pcm[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
					ScreenShare.sendAudio(panel, encoder.encode(pcm, n));
					if (got < buf.length) break;
				}
			} finally {
				p.destroy();
				p.waitFor(1, TimeUnit.SECONDS);
				process = null;
			}
		}
	}

	/**
	 * The PipeWire node of the program's sound: its own process, else a stream of the same program,
	 * else one of its child processes (browsers play sound from helper processes).
	 */
	static long findNode(long pid, String cls) throws Exception {
		Process p = pipewire(new ProcessBuilder("pw-dump")).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		String json;
		try (InputStream in = p.getInputStream()) {
			json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} finally {
			p.waitFor(3, TimeUnit.SECONDS);
		}
		long self = ProcessHandle.current().pid();
		if (pid == self) return -1;
		// Programs in a sandbox (Flatpak) give their own process numbers: then only the name counts.
		JsonElement all = json.isBlank() ? null : JsonParser.parseString(json);
		if (all == null || !all.isJsonArray()) return -1;
		String binary = pid > 0 && related(binary(pid), cls) ? binary(pid) : null;
		Set<Long> family = binary != null ? children(pid) : Set.of();
		long byBinary = -1, byChild = -1, byName = -1;
		for (JsonElement e : all.getAsJsonArray()) {
			if (!(e instanceof JsonObject o) || !(o.get("info") instanceof JsonObject info) || !(info.get("props") instanceof JsonObject props)) continue;
			if (!props.has("media.class") || !props.get("media.class").getAsString().equals("Stream/Output/Audio")) continue;
			long id = o.get("id").getAsLong();
			long streamPid = props.has("application.process.id") ? parse(props.get("application.process.id").getAsString()) : -1;
			// Never the game's own sound.
			if (streamPid == self) continue;
			if (streamPid == pid && binary != null) return id;
			if (byName < 0 && cls != null) {
				for (String k : new String[] {"application.process.binary", "pipewire.access.portal.app_id", "application.id", "application.name"}) {
					if (props.has(k) && related(props.get(k).getAsString(), cls)) {
						byName = id;
						break;
					}
				}
			}
			if (byBinary < 0 && binary != null && props.has("application.process.binary") && binary.equals(props.get("application.process.binary").getAsString())) byBinary = id;
			// A helper process of the same program (browsers), not anything started from it.
			if (byChild < 0 && family.contains(streamPid) && binary != null && binary.equals(binary(streamPid))) byChild = id;
		}
		return byChild >= 0 ? byChild : byBinary >= 0 ? byBinary : byName;
	}

	/**
	 * Whether a program name and the window's class (WM_CLASS, "instance class") belong together:
	 * "brave" and "brave-browser", "com.discordapp.Discord" and "discord".
	 */
	static boolean related(String name, String cls) {
		if (name == null || cls == null) return false;
		String n = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
		if (n.length() < 3) return false;
		for (String part : cls.toLowerCase(java.util.Locale.ROOT).split("[\\s,\"]+")) {
			String c = part.replaceAll("[^a-z0-9]", "");
			if (c.length() >= 3 && (n.contains(c) || c.contains(n))) return true;
		}
		return false;
	}

	/**
	 * PipeWire's tools find the sound server through XDG_RUNTIME_DIR; a game started with another
	 * one (a launcher in a sandbox) gets the user's own folder that has the server's socket.
	 */
	private static ProcessBuilder pipewire(ProcessBuilder b) {
		var env = b.environment();
		if (env.containsKey("PIPEWIRE_RUNTIME_DIR")) return b;
		String xdg = env.get("XDG_RUNTIME_DIR");
		if (xdg != null && Files.exists(Path.of(xdg, "pipewire-0"))) return b;
		try (var dirs = Files.list(Path.of("/run/user"))) {
			String me = System.getProperty("user.name");
			for (Path d : (Iterable<Path>) dirs::iterator) {
				if (Files.exists(d.resolve("pipewire-0")) && Files.getOwner(d).getName().equals(me)) {
					env.put("PIPEWIRE_RUNTIME_DIR", d.toString());
					break;
				}
			}
		} catch (Exception ignored) {
			// no /run/user: leave it as it is
		}
		return b;
	}

	private static long parse(String s) {
		try {
			return Long.parseLong(s.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String binary(long pid) {
		try {
			return Path.of("/proc", String.valueOf(pid), "exe").toRealPath().getFileName().toString();
		} catch (Exception e) {
			return null;
		}
	}

	/** The process and everything started from it. */
	private static Set<Long> children(long root) {
		Set<Long> family = new HashSet<>();
		family.add(root);
		try (var dirs = Files.list(Path.of("/proc"))) {
			Map<Long, Long> parent = new java.util.HashMap<>();
			for (Path d : (Iterable<Path>) dirs::iterator) {
				long id = parse(d.getFileName().toString());
				if (id < 0) continue;
				try {
					String stat = Files.readString(d.resolve("stat"));
					String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
					parent.put(id, parse(f[1]));
				} catch (Exception ignored) {
					// gone meanwhile
				}
			}
			boolean grew = true;
			while (grew) {
				grew = false;
				for (var e : parent.entrySet()) if (!family.contains(e.getKey()) && family.contains(e.getValue())) grew |= family.add(e.getKey());
			}
		} catch (Exception ignored) {
			// no /proc: only the process itself
		}
		return family;
	}
}
