package pl.aerion.mod;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import pl.aerion.mod.platform.Gfx;
import pl.aerion.mod.platform.Textures;

/**
 * Smooth text for menus and buttons: Inter glyphs baked into one image (scripts/build-mod-assets.py)
 * and drawn in screen pixels with linear filtering, so it stays sharp at every GUI scale. Text with
 * characters the image doesn't have falls back to the game's font.
 */
public final class Txt {
	private Txt() {}

	private record Glyph(int u, int v, int w, int h, int xo, int yo, int adv) {}

	private static final Identifier ATLAS = Identifier.fromNamespaceAndPath("aerion", "font");
	private static final Map<Integer, Glyph> GLYPHS = new HashMap<>();
	private static int em = 32, cap = 24, texW, texH;
	private static boolean loaded, failed;

	private static boolean ready() {
		if (loaded || failed) return loaded;
		try (InputStream png = Txt.class.getResourceAsStream("/assets/aerion/textures/gui/font.png");
				InputStream txt = Txt.class.getResourceAsStream("/assets/aerion/font.txt");
				BufferedReader r = new BufferedReader(new InputStreamReader(txt, StandardCharsets.UTF_8))) {
			String[] head = r.readLine().split(" ");
			em = Integer.parseInt(head[0]);
			cap = Integer.parseInt(head[1]);
			texW = Integer.parseInt(head[2]);
			texH = Integer.parseInt(head[3]);
			for (String line = r.readLine(); line != null; line = r.readLine()) {
				String[] p = line.split(" ");
				GLYPHS.put(Integer.parseInt(p[0]), new Glyph(
						Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
						Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7])));
			}
			Textures.registerSmooth(ATLAS, png);
			loaded = true;
		} catch (Exception e) {
			failed = true;
			AerionClient.LOG.warn("Aerion UI: can't load the text image, using the game's font: {}", e.toString());
		}
		return loaded;
	}

	/** True when every character can be drawn smoothly. */
	public static boolean supports(String s) {
		if (!ready()) return false;
		return s.codePoints().allMatch(GLYPHS::containsKey);
	}

	public static float width(String s, float size) {
		if (!supports(s)) return Gfx.textWidth(s);
		int pen = 0;
		for (int cp : s.codePoints().toArray()) pen += GLYPHS.get(cp).adv;
		return pen * size / em;
	}

	/** Draw with the capital letters vertically centred on `cy`. */
	public static void draw(Gfx g, String s, float x, float cy, float size, int argb) {
		if (!supports(s)) {
			g.text(s, Math.round(x), Math.round(cy - 4), argb, false);
			return;
		}
		int k = Gfx.guiScale();
		float scale = size * k / em;
		g.push();
		g.scale(1f / k);
		g.translate(Math.round(x * k), Math.round(cy * k - cap * scale / 2));
		g.scale(scale);
		int pen = 0;
		for (int cp : s.codePoints().toArray()) {
			Glyph c = GLYPHS.get(cp);
			if (c.w > 0) g.region(ATLAS, pen + c.xo, c.yo + cap, c.w, c.h, c.u, c.v, texW, texH, argb);
			pen += c.adv;
		}
		g.pop();
	}

	public static void center(Gfx g, String s, float cx, float cy, float size, int argb) {
		draw(g, s, cx - width(s, size) / 2, cy, size, argb);
	}
}
