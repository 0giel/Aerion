package pl.aerion.mod;

import pl.aerion.mod.platform.Gfx;

/**
 * Life behind the menus: two slow aurora ribbons in the theme's colours and a soft shade towards
 * the bottom, between the theme's gradient and its particles. Off with the particles ("none").
 */
public final class Backdrop {
	private Backdrop() {}

	/** Column width in GUI pixels; one keeps the edges free of steps. */
	private static final int STEP = 1;

	public static void draw(Gfx g, Theme t) {
		if (t.particles == null || t.particles.equals("none") || MenuPicture.active()) return;
		int w = g.width(), h = g.height();
		float time = (System.nanoTime() % 3_600_000_000_000L) / 1e9f;
		int mid = t.gradient[t.gradient.length / 2];
		ribbon(g, w, h, time, 0.24f, 0.11f, t.accent, t.light ? 0x26 : 0x48, 0f);
		ribbon(g, w, h, time * 0.8f, 0.34f, 0.08f, Colors.mix(t.accent, mid, 0.5f), t.light ? 0x1C : 0x34, 2.1f);
		if (!t.light) g.gradient(0, h * 3 / 5, w, h, 0x00000000, 0x55000000);
	}

	/** A band whose centre and thickness drift on slow waves, brightest along its middle. */
	private static void ribbon(Gfx g, int w, int h, float time, float height, float thickness, int color, int alpha, float seed) {
		int rgb = color & 0xFFFFFF;
		for (int x = 0; x < w; x += STEP) {
			float u = (float) x / Math.max(1, w);
			double wave = Math.sin(x * 0.011 + time * 0.23 + seed) * 0.06 + Math.sin(x * 0.027 - time * 0.15 + seed * 1.7) * 0.025;
			int centre = Math.round(h * (height + (float) wave));
			int half = Math.max(4, Math.round(h * thickness * (0.75f + 0.35f * (float) Math.sin(x * 0.019 + time * 0.31 + seed))));
			// Fade out at the screen's edges and shimmer a little along the band.
			float edge = Math.min(1f, Math.min(u, 1 - u) * 5f);
			float shimmer = 0.75f + 0.25f * (float) Math.sin(x * 0.07 + time * 0.9 + seed);
			int a = Math.round(alpha * edge * shimmer);
			if (a <= 0) continue;
			int solid = (a << 24) | rgb;
			g.gradient(x, centre - half, x + STEP, centre, rgb, solid);
			g.gradient(x, centre, x + STEP, centre + half, solid, rgb);
		}
	}
}
