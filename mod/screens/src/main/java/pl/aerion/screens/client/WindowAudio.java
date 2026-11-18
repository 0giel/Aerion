package pl.aerion.screens.client;

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

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

	/** Whether this computer can send window sound (Linux with PipeWire's tools, or Windows). */
	static boolean available() {
		if (available == null) {
			available = WINDOWS || (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux") && onPath("pw-record") && onPath("pw-dump"));
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
			if (WINDOWS) {
				windows();
				return;
			}
			while (!stop) {
				try {
					List<Long> nodes = nodes();
					// No sound playing yet: look again in a while.
					if (nodes.isEmpty()) {
						if (!told) {
							Screens.LOG.info("Aerion: no sound found yet for window \"{}\" (program {}); looking again every few seconds", panel.title, panel.cls);
							told = true;
						}
						Thread.sleep(3000);
						continue;
					}
					Screens.LOG.info("Aerion: sharing the sound of window \"{}\" (PipeWire streams {})", panel.title, nodes);
					told = false;
					record(nodes);
				} catch (InterruptedException e) {
					return;
				} catch (Exception e) {
					Screens.warnOnce("window-sound", e);
					try {
						Thread.sleep(5000);
					} catch (InterruptedException ie) {
						return;
					}
				}
			}
		}

		/** Windows: the window's process (and the ones it started), recorded by the system itself. */
		private void windows() {
			long self = ProcessHandle.current().pid();
			while (!stop) {
				WindowSource src = Mirrors.source();
				long pid = src == null || panel.win == 0 ? 0 : src.pid(panel.win);
				if (pid > 0 && gameOrAncestor(pid)) {
					// The launcher (or a terminal) the game runs in: its tree holds the game's own sound.
					if (!told) Screens.LOG.info("Aerion: window \"{}\" belongs to the game's own launcher; its sound isn't shared", panel.title);
					told = true;
				} else if (pid > 0 && pid != self) {
					Screens.LOG.info("Aerion: sharing the sound of window \"{}\" (process {})", panel.title, pid);
					boolean ok = WinLoopback.record(pid, RATE, CHUNK, () -> stop, pcm -> ScreenShare.sendAudio(panel, encoder.encode(pcm, pcm.length)));
					// Not possible on this Windows: no use asking again and again.
					if (!ok) return;
				}
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					return;
				}
			}
		}

		private static boolean gameOrAncestor(long pid) {
			for (var p = java.util.Optional.of(ProcessHandle.current()); p.isPresent(); p = p.get().parent()) {
				if (p.get().pid() == pid) return true;
			}
			return false;
		}

		private List<Long> nodes() throws Exception {
			WindowSource src = Mirrors.source();
			long pid = src == null || panel.win == 0 ? 0 : src.pid(panel.win);
			return findNodes(pid, panel.cls);
		}

		/**
		 * Records all the program's streams at once, mixed (a browser plays each tab and video as a
		 * stream of its own), in 0.1 s pieces. Every few seconds the streams are looked up again:
		 * programs end streams and start new ones all the time, and then recording starts over.
		 */
		private void record(List<Long> nodes) throws Exception {
			List<Process> procs = new java.util.ArrayList<>();
			List<java.util.concurrent.ArrayBlockingQueue<short[]>> queues = new java.util.ArrayList<>();
			try {
				for (long node : nodes) {
					Process p = pipewire(new ProcessBuilder("pw-record", "--target", String.valueOf(node), "--rate", String.valueOf(RATE), "--channels", "1", "--format", "s16", "-"))
							.redirectError(ProcessBuilder.Redirect.DISCARD).start();
					procs.add(p);
					var q = new java.util.concurrent.ArrayBlockingQueue<short[]>(10);
					queues.add(q);
					Thread reader = new Thread(() -> read(p, q), "Aerion window sound reader");
					reader.setDaemon(true);
					reader.start();
				}
				process = procs.get(0);
				long nextCheck = System.currentTimeMillis() + 3000, tick = System.nanoTime();
				while (!stop) {
					tick += 100_000_000L;
					long wait = tick - System.nanoTime();
					if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
					int[] mix = null;
					for (var q : queues) {
						// Behind by more than a few pieces: skip ahead so the sound stays live.
						while (q.size() > 3) q.poll();
						short[] c = q.poll();
						if (c == null) continue;
						if (mix == null) mix = new int[CHUNK];
						for (int i = 0; i < c.length && i < CHUNK; i++) mix[i] += c[i];
					}
					if (mix != null) {
						short[] pcm = new short[CHUNK];
						for (int i = 0; i < CHUNK; i++) pcm[i] = (short) Math.max(-32768, Math.min(32767, mix[i]));
						ScreenShare.sendAudio(panel, encoder.encode(pcm, CHUNK));
					}
					if (System.currentTimeMillis() > nextCheck) {
						nextCheck = System.currentTimeMillis() + 3000;
						if (!nodes().equals(nodes)) return;
					}
					if (procs.stream().noneMatch(Process::isAlive)) return;
				}
			} finally {
				for (Process p : procs) {
					p.destroy();
					p.waitFor(1, TimeUnit.SECONDS);
				}
				process = null;
			}
		}

		/** A stream's sound into its queue, 0.1 s at a time (the oldest piece goes when it is full). */
		private static void read(Process p, java.util.concurrent.ArrayBlockingQueue<short[]> q) {
			byte[] buf = new byte[CHUNK * 2];
			try (InputStream in = p.getInputStream()) {
				while (true) {
					int got = in.readNBytes(buf, 0, buf.length);
					if (got < buf.length) return;
					short[] pcm = new short[CHUNK];
					for (int i = 0; i < CHUNK; i++) pcm[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
					if (!q.offer(pcm)) {
						q.poll();
						q.offer(pcm);
					}
				}
			} catch (Exception ignored) {
				// the stream ended
			}
		}
	}

	/** The first of the program's streams (see {@link #findNodes}), or -1. */
	static long findNode(long pid, String cls) throws Exception {
		List<Long> all = findNodes(pid, cls);
		return all.isEmpty() ? -1 : all.get(0);
	}

	/**
	 * The PipeWire node of the program's sound: its own process, else a stream of the same program,
	 * else one of its child processes (browsers play sound from helper processes).
	 */
	static List<Long> findNodes(long pid, String cls) throws Exception {
		Process p = pipewire(new ProcessBuilder("pw-dump")).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		String json;
		try (InputStream in = p.getInputStream()) {
			json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} finally {
			p.waitFor(3, TimeUnit.SECONDS);
		}
		long self = ProcessHandle.current().pid();
		if (pid == self) return List.of();
		// Programs in a sandbox (Flatpak) give their own process numbers: then only the name counts.
		JsonElement all = json.isBlank() ? null : JsonParser.parseString(json);
		if (all == null || !all.isJsonArray()) return List.of();
		String binary = pid > 0 && related(binary(pid), cls) ? binary(pid) : null;
		Set<Long> family = binary != null ? children(pid) : Set.of();
		// Its own streams and its helpers' first; by the program's name only when there are none.
		List<Long> own = new java.util.ArrayList<>(), named = new java.util.ArrayList<>();
		for (JsonElement e : all.getAsJsonArray()) {
			if (!(e instanceof JsonObject o) || !(o.get("info") instanceof JsonObject info) || !(info.get("props") instanceof JsonObject props)) continue;
			if (!props.has("media.class") || !props.get("media.class").getAsString().equals("Stream/Output/Audio")) continue;
			long id = o.get("id").getAsLong();
			long streamPid = props.has("application.process.id") ? parse(props.get("application.process.id").getAsString()) : -1;
			// Never the game's own sound.
			if (streamPid == self) continue;
			boolean mine = binary != null && (streamPid == pid
					|| (props.has("application.process.binary") && binary.equals(props.get("application.process.binary").getAsString()))
					// A helper process of the same program (browsers), not anything started from it.
					|| (family.contains(streamPid) && binary.equals(binary(streamPid))));
			if (mine) {
				own.add(id);
				continue;
			}
			if (cls != null) {
				for (String k : new String[] {"application.process.binary", "pipewire.access.portal.app_id", "application.id", "application.name"}) {
					if (props.has(k) && related(props.get(k).getAsString(), cls)) {
						named.add(id);
						break;
					}
				}
			}
		}
		List<Long> out = own.isEmpty() ? named : own;
		java.util.Collections.sort(out);
		return out.size() > 6 ? out.subList(0, 6) : out;
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
