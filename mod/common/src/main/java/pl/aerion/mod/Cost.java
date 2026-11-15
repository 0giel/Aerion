package pl.aerion.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the mod itself costs while playing: the processor time its parts take while the game builds
 * each frame (HUD modules, shapes in the world, window panels, the hunger bar), averaged per frame
 * every second and reported to the launcher with the FPS. Work on threads of its own (reading
 * windows for the panels) is counted apart, per second, as it doesn't hold the frame up.
 */
public final class Cost {
	private Cost() {}

	/** One measured part; `add` is called with a start time from {@link System#nanoTime()}. */
	public static final class Part {
		final String id;
		long ns;

		Part(String id) {
			this.id = id;
		}

		public void add(long start) {
			ns += System.nanoTime() - start;
		}
	}

	private static final Map<String, Part> BY_ID = new ConcurrentHashMap<>();
	private static final List<Part> PARTS = new ArrayList<>();
	private static final AtomicLong BACKGROUND = new AtomicLong();
	private static long frames, since = System.nanoTime();

	/** The part with this id (made once, then reused every frame). */
	public static Part part(String id) {
		Part p = BY_ID.get(id);
		if (p != null) return p;
		synchronized (PARTS) {
			return BY_ID.computeIfAbsent(id, k -> {
				Part n = new Part(k);
				PARTS.add(n);
				return n;
			});
		}
	}

	/** Called once per frame drawn in a world. */
	public static void frame() {
		frames++;
	}

	/** Time spent on a thread of the mod's own (not holding the frame up). */
	public static void background(long ns) {
		BACKGROUND.addAndGet(ns);
	}

	/**
	 * The last second as JSON fields for the status file: `costMs` (per frame), the biggest parts,
	 * and `backgroundMs` (per second); empty outside a world. Starts the next second.
	 */
	public static String report() {
		long now = System.nanoTime();
		double seconds = Math.max(0.001, (now - since) / 1e9);
		since = now;
		long n = frames;
		frames = 0;
		long bg = BACKGROUND.getAndSet(0);
		List<Part> parts;
		synchronized (PARTS) {
			parts = new ArrayList<>(PARTS);
		}
		long total = 0;
		List<long[]> sizes = new ArrayList<>();
		for (int i = 0; i < parts.size(); i++) {
			Part p = parts.get(i);
			total += p.ns;
			if (p.ns > 0) sizes.add(new long[] {p.ns, i});
			p.ns = 0;
		}
		if (n == 0) return "";
		sizes.sort((a, b) -> Long.compare(b[0], a[0]));
		StringBuilder top = new StringBuilder();
		for (int i = 0; i < Math.min(6, sizes.size()); i++) {
			if (i > 0) top.append(',');
			top.append('"').append(parts.get((int) sizes.get(i)[1]).id).append("\":").append(ms(sizes.get(i)[0] / (double) n));
		}
		return ",\"costMs\":" + ms(total / (double) n) + ",\"costParts\":{" + top + "},\"backgroundMs\":" + ms(bg / seconds);
	}

	private static String ms(double ns) {
		return String.format(java.util.Locale.ROOT, "%.3f", ns / 1e6);
	}
}
