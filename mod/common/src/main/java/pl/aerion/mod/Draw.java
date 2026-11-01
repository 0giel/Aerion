package pl.aerion.mod;

import pl.aerion.mod.platform.Gfx;

/** Flat, rounded shapes made of fills (GUI pixels are large, so a few rows make a corner). */
public final class Draw {
	private Draw() {}

	public static void rounded(Gfx g, int x, int y, int w, int h, int r, int color) {
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r == 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = r - (int) Math.round(Math.sqrt((double) r * r - dy * dy));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
		}
	}

	/** A control: 1-pixel border in `edge`, body in `fill`. */
	public static void control(Gfx g, int x, int y, int w, int h, int r, int edge, int fill) {
		rounded(g, x, y, w, h, r, edge);
		rounded(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
	}

	/** Minecraft writes titles and hints in white on the background, so light themes are dimmed. */
	public static int behindText(Theme t, int c) {
		return t.light ? Colors.forWhiteText(c) : c;
	}

	public static void background(Gfx g, Theme t, int alpha) {
		int top = Colors.alpha(behindText(t, t.gradient[0]), alpha);
		int bottom = Colors.alpha(behindText(t, t.gradient[t.gradient.length - 1]), alpha);
		g.gradient(0, 0, g.width(), g.height(), top, bottom);
	}
}
