package pl.aerion.mod.hud;

import net.minecraft.resources.Identifier;
import pl.aerion.mod.Txt;
import pl.aerion.mod.features.Waypoints;
import pl.aerion.mod.platform.Gfx;
import pl.aerion.mod.platform.Textures;

/** What the minimap and the map draw on the terrain: the player's arrow and waypoint pins. */
final class MapMarks {
	private MapMarks() {}

	/** The arrow: tip, right wing, notch, left wing, as fractions of its half size (tip up). */
	private static final float[][] ARROW = {{0, -0.92f}, {0.66f, 0.74f}, {0, 0.36f}, {-0.66f, 0.74f}};
	private static final float[][] TRIANGLE = {{0, -0.8f}, {0.72f, 0.56f}, {-0.72f, 0.56f}};
	private static final Identifier ARROW_TEX = Identifier.fromNamespaceAndPath("aerion", "map_arrow");
	private static final Identifier TRIANGLE_TEX = Identifier.fromNamespaceAndPath("aerion", "map_triangle");
	private static final Identifier RING_TEX = Identifier.fromNamespaceAndPath("aerion", "map_ring");
	private static final int TEX = 64, RING = 256;
	private static boolean ready;

	/**
	 * The player: an arrow pointing `angle` radians clockwise from screen-up. It is a smooth picture
	 * (white with a dark edge, tinted when drawn), so it stays clean at any size and turn.
	 */
	static void arrow(Gfx g, float cx, float cy, double angle, float size, int fill) {
		turned(g, ARROW_TEX, cx, cy, angle, size, fill);
	}

	/** A waypoint beyond the map's edge: a small triangle in its colour pointing at it. */
	static void edge(Gfx g, float x, float y, double angle, Waypoints.Point w) {
		turned(g, TRIANGLE_TEX, x, y, angle, 4.5f, Waypoints.color(w));
	}

	private static void turned(Gfx g, Identifier tex, float cx, float cy, double angle, float half, int argb) {
		if (!ensure()) return;
		int size = Math.max(2, Math.round(half * 2));
		g.push();
		g.translate(cx, cy);
		g.rotate((float) angle);
		g.texture(tex, -size / 2, -size / 2, size, size, TEX, TEX, argb);
		g.pop();
	}

	private static boolean ensure() {
		if (ready) return true;
		try {
			Textures.registerSmooth(ARROW_TEX, TEX, TEX, picture(ARROW));
			Textures.registerSmooth(TRIANGLE_TEX, TEX, TEX, picture(TRIANGLE));
			Textures.registerSmooth(RING_TEX, RING, RING, ring());
			ready = true;
		} catch (RuntimeException e) {
			pl.aerion.mod.AerionClient.warnOnce("map-marks", e);
		}
		return ready;
	}

	/** The round map's edge: a dark ring `radius` from the centre, a little over half inside. */
	static void ring(Gfx g, float cx, float cy, float radius) {
		if (!ensure()) return;
		int size = Math.round(radius * 2 * RING_OUTER);
		g.push();
		g.translate(cx, cy);
		g.texture(RING_TEX, -size / 2, -size / 2, size, size, RING, RING, 0xFFFFFFFF);
		g.pop();
	}

	/** The ring picture reaches this far out, as a part of the map's radius. */
	private static final float RING_OUTER = 1.03f, RING_INNER = 0.955f;

	private static int[] ring() {
		int[] out = new int[RING * RING];
		float half = RING / 2f, outer = half, inner = half * RING_INNER / RING_OUTER;
		for (int y = 0; y < RING; y++) {
			for (int x = 0; x < RING; x++) {
				int hits = 0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						float d = (float) Math.hypot(x + (sx + 0.5f) / 4 - half, y + (sy + 0.5f) / 4 - half);
						if (d <= outer && d >= inner) hits++;
					}
				}
				if (hits > 0) out[y * RING + x] = (Math.round(0xE8 * hits / 16f) << 24) | 0x141414;
			}
		}
		return out;
	}

	/** A shape drawn white with a dark edge, smoothed by sampling each pixel 4 × 4 times. */
	private static int[] picture(float[][] shape) {
		float[] xs = new float[shape.length], ys = new float[shape.length];
		float[] ox = new float[shape.length], oy = new float[shape.length];
		float half = TEX / 2f, edge = 1.18f;
		for (int i = 0; i < shape.length; i++) {
			xs[i] = half + shape[i][0] * half * 0.82f;
			ys[i] = half + shape[i][1] * half * 0.82f;
			ox[i] = half + shape[i][0] * half * 0.82f * edge;
			oy[i] = half + shape[i][1] * half * 0.82f * edge;
		}
		int[] out = new int[TEX * TEX];
		for (int y = 0; y < TEX; y++) {
			for (int x = 0; x < TEX; x++) {
				int fill = 0, all = 0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						float px = x + (sx + 0.5f) / 4, py = y + (sy + 0.5f) / 4;
						if (inside(xs, ys, px, py)) fill++;
						if (inside(ox, oy, px, py)) all++;
					}
				}
				if (all == 0) continue;
				int v = Math.round(255f * fill / all), a = Math.round(255f * all / 16);
				out[y * TEX + x] = (a << 24) | (v << 16) | (v << 8) | v;
			}
		}
		return out;
	}

	private static boolean inside(float[] xs, float[] ys, float px, float py) {
		boolean in = false;
		for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
			if ((ys[i] > py) != (ys[j] > py) && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) in = !in;
		}
		return in;
	}

	/** A waypoint: a small block in its colour with the first letter of its name (× for a death). */
	static void pin(Gfx g, int x, int y, Waypoints.Point w, int half, boolean dim) {
		int c = Waypoints.color(w);
		if (dim) c = (c & 0x00FFFFFF) | 0x70000000;
		g.fill(x - half - 1, y - half - 1, x + half + 1, y + half + 1, 0xE0000000);
		g.fill(x - half, y - half, x + half, y + half, c);
		g.fill(x - half, y + half - 1, x + half, y + half, 0x40000000);
		String letter = "death".equals(w.kind) ? "×" : initial(w.name);
		if (!letter.isEmpty()) Txt.center(g, letter, x, y - 0.5f, half * 1.55f, light(c) ? 0xFF1A1A1A : 0xFFFFFFFF);
	}

	private static String initial(String name) {
		if (name == null) return "";
		String s = name.strip();
		if (s.isEmpty()) return "";
		String first = new String(Character.toChars(s.codePointAt(0))).toUpperCase();
		return Txt.supports(first) ? first : "";
	}

	private static boolean light(int argb) {
		int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		return r * 0.299 + g * 0.587 + b * 0.114 > 150;
	}
}
