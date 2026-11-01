package pl.aerion.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Aerion logo in Minecraft's own style: the launcher's cube glyph rasterised into GUI pixels
 * and "Aerion" in the game font, enlarged. Drawn where the Minecraft logo would be.
 */
public final class Logo {
	private Logo() {}

	/** Faces of the glyph (src/assets/glyph.svg, viewBox 150 140 760 760) and their shade. */
	private static final float[][] FACES = {
		{512.0f, 262.0f, 757.8f, 398.0f, 512.0f, 534.0f, 266.2f, 398.0f},
		{259.2f, 410.1f, 499.9f, 555.0f, 505.0f, 835.9f, 264.3f, 691.0f},
		{524.1f, 555.0f, 764.8f, 410.1f, 759.7f, 691.0f, 519.0f, 835.9f},
	};
	private static final float[] SHADES = {1.0f, 0.78f, 0.56f};
	private static final float[] SPARKLE = {
		792.0f, 172.0f, 804.1f, 237.9f, 870.0f, 250.0f, 804.1f, 262.1f, 792.0f, 328.0f, 779.9f, 262.1f, 714.0f, 250.0f, 779.9f, 237.9f
	};

	/** Fill a polygon (glyph coordinates) scaled into a `size`-pixel box at (x, y), row by row. */
	private static void polygon(GuiGraphicsExtractor g, float[] pts, int x, int y, int size, int color) {
		float k = size / 760f;
		int n = pts.length / 2;
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			minY = Math.min(minY, (pts[2 * i + 1] - 140) * k);
			maxY = Math.max(maxY, (pts[2 * i + 1] - 140) * k);
		}
		for (int row = (int) Math.floor(minY); row <= (int) Math.ceil(maxY); row++) {
			float sy = row + 0.5f;
			float lo = Float.MAX_VALUE;
			float hi = -Float.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				float x1 = (pts[2 * i] - 150) * k;
				float y1 = (pts[2 * i + 1] - 140) * k;
				float x2 = (pts[(2 * i + 2) % pts.length] - 150) * k;
				float y2 = (pts[(2 * i + 3) % pts.length] - 140) * k;
				if ((y1 <= sy && y2 > sy) || (y2 <= sy && y1 > sy)) {
					float ix = x1 + (sy - y1) / (y2 - y1) * (x2 - x1);
					lo = Math.min(lo, ix);
					hi = Math.max(hi, ix);
				}
			}
			if (hi > lo) g.fill(x + Math.round(lo), y + row, x + Math.round(hi), y + row + 1, color);
		}
	}

	/** Draw the logo centred in the Minecraft logo's 256×44 box at `top`. */
	public static void draw(GuiGraphicsExtractor g, Theme t, int screenWidth, int top, float alpha) {
		Font font = Minecraft.getInstance().font;
		int a = Math.round(alpha * 255) << 24;
		int ink = Colors.mix(0xFFFFFFFF, t.accent, 0.15f) & 0xFFFFFF;
		int cube = 40;
		int scale = 4;
		String word = "Aerion";
		int textW = font.width(word) * scale;
		int total = cube + 8 + textW;
		int x = (screenWidth - total) / 2;
		int y = top + 2;
		for (int i = 0; i < FACES.length; i++) {
			polygon(g, FACES[i], x, y, cube, a | (Colors.mix(0xFF000000, 0xFF000000 | ink, SHADES[i]) & 0xFFFFFF));
		}
		polygon(g, SPARKLE, x, y, cube, a | (t.accent & 0xFFFFFF));
		g.pose().pushMatrix();
		g.pose().translate(x + cube + 8, y + (cube - 8 * scale) / 2f + 2);
		g.pose().scale(scale, scale);
		g.text(font, word, 0, 0, a | ink, true);
		g.pose().popMatrix();
	}
}
