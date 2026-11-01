package pl.aerion.mod;

import pl.aerion.mod.platform.Gfx;

/**
 * Flat shapes made of fills, drawn in screen pixels rather than the game's large GUI pixels, so
 * rounded corners are smooth and borders thin at every GUI scale. Coordinates are GUI pixels.
 */
public final class Draw {
	private Draw() {}

	/** The drawing target of the widget being rendered, for hooks that aren't handed one. */
	public static Gfx current;

	/** Left and right inset of a rounded row: `i` rows from the top or bottom edge. */
	private static float inset(int i, int r) {
		if (i >= r) return 0;
		double dy = r - i - 0.5;
		return (float) (r - Math.sqrt((double) r * r - dy * dy));
	}

	private static int faded(int color, float coverage) {
		return Colors.alpha(color, Math.round(((color >>> 24) & 0xFF) * coverage));
	}

	/** One row of a rounded shape; the partly covered end pixels keep the curve smooth. */
	private static void row(Gfx g, int x, int y, int w, float inset, int color) {
		int solid = (int) Math.ceil(inset);
		g.fill(x + solid, y, x + w - solid, y + 1, color);
		float edge = solid - inset;
		if (solid > 0 && edge > 0.02f) {
			int soft = faded(color, edge);
			g.fill(x + solid - 1, y, x + solid, y + 1, soft);
			g.fill(x + w - solid, y, x + w - solid + 1, y + 1, soft);
		}
	}

	public static void rounded(Gfx g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r == 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		int k = Gfx.guiScale();
		int X = x * k, Y = y * k, W = w * k, H = h * k, R = r * k;
		g.push();
		g.scale(1f / k);
		g.fill(X, Y + R, X + W, Y + H - R, color);
		for (int i = 0; i < R; i++) {
			float in = inset(i, R);
			row(g, X, Y + i, W, in, color);
			row(g, X, Y + H - 1 - i, W, in, color);
		}
		g.pop();
	}

	/** A control: a thin border in `edge` around a body in `fill` (which stays see-through). */
	public static void control(Gfx g, int x, int y, int w, int h, int r, int edge, int fill) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		int k = Gfx.guiScale();
		int X = x * k, Y = y * k, W = w * k, H = h * k, R = r * k;
		int line = Math.max(1, k / 3);
		g.push();
		g.scale(1f / k);
		// Middle part, between the rounded corners.
		g.fill(X, Y + R, X + line, Y + H - R, edge);
		g.fill(X + W - line, Y + R, X + W, Y + H - R, edge);
		int bodyTop = Math.max(R, line);
		g.fill(X + line, Y + bodyTop, X + W - line, Y + H - bodyTop, fill);
		if (R == 0) {
			g.fill(X, Y, X + W, Y + line, edge);
			g.fill(X, Y + H - line, X + W, Y + H, edge);
		}
		int innerR = Math.max(0, R - line);
		for (int i = 0; i < Math.max(R, line); i++) {
			for (int rowY : new int[] {Y + i, Y + H - 1 - i}) {
				if (R == 0) {
					continue;
				}
				float outer = inset(i, R);
				if (i < line) {
					row(g, X, rowY, W, outer, edge);
					continue;
				}
				int inner = line + Math.round(inset(i - line, innerR));
				int solid = (int) Math.ceil(outer);
				// Border on both sides of the body, with the outer end softened as in row().
				g.fill(X + solid, rowY, X + inner, rowY + 1, edge);
				g.fill(X + W - inner, rowY, X + W - solid, rowY + 1, edge);
				float soft = solid - outer;
				if (solid > 0 && soft > 0.02f) {
					int c = faded(edge, soft);
					g.fill(X + solid - 1, rowY, X + solid, rowY + 1, c);
					g.fill(X + W - solid, rowY, X + W - solid + 1, rowY + 1, c);
				}
				g.fill(X + inner, rowY, X + W - inner, rowY + 1, fill);
			}
		}
		g.pop();
	}

	/** A button or slider label in smooth text; false when the game's own text must draw it. */
	public static boolean label(Gfx g, String text, int x, int y, int w, int h, boolean active, int alpha) {
		float size = 8.5f;
		if (!Txt.supports(text) || Txt.width(text, size) > w - 8) return false;
		int color = Colors.alpha(active ? 0xFFFFFFFF : 0xFFA0A0A0, alpha);
		Txt.center(g, text, x + w / 2f, y + h / 2f, size, color);
		return true;
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
