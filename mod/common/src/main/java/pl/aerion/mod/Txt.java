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
 * Smooth text for menus and buttons: glyphs baked into images (scripts/build-mod-assets.py) and
 * drawn in screen pixels with linear filtering, so they stay sharp at every GUI scale. Body text is
 * Inter; {@link Display} is Unbounded, the launcher's heading font, for titles and section labels.
 * Text with characters an image doesn't have falls back to Inter, then to the game's font.
 */
public final class Txt {
	private Txt() {}

	private record Glyph(int u, int v, int w, int h, int xo, int yo, int adv) {}

	/** One baked font: its image and the place of every glyph in it. */
	private static final class Face {
		private final String name;
		private final Identifier atlas;
		private final Map<Integer, Glyph> glyphs = new HashMap<>();
		private int em = 32, cap = 24, texW, texH;
		private boolean loaded, failed;

		Face(String name) {
			this.name = name;
			this.atlas = Identifier.fromNamespaceAndPath("aerion", name);
		}

		boolean ready() {
			if (loaded || failed) return loaded;
			try (InputStream png = Txt.class.getResourceAsStream("/assets/aerion/textures/gui/" + name + ".png");
					InputStream txt = Txt.class.getResourceAsStream("/assets/aerion/" + name + ".txt");
					BufferedReader r = new BufferedReader(new InputStreamReader(txt, StandardCharsets.UTF_8))) {
				String[] head = r.readLine().split(" ");
				em = Integer.parseInt(head[0]);
				cap = Integer.parseInt(head[1]);
				texW = Integer.parseInt(head[2]);
				texH = Integer.parseInt(head[3]);
				for (String line = r.readLine(); line != null; line = r.readLine()) {
					String[] p = line.split(" ");
					glyphs.put(Integer.parseInt(p[0]), new Glyph(
							Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
							Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7])));
				}
				Textures.registerSmooth(atlas, png);
				loaded = true;
			} catch (Exception e) {
				failed = true;
				AerionClient.LOG.warn("Aerion UI: can't load the {} text image: {}", name, e.toString());
			}
			return loaded;
		}

		boolean supports(String s) {
			return ready() && s.codePoints().allMatch(glyphs::containsKey);
		}

		float width(String s, float size) {
			int pen = 0;
			for (int cp : s.codePoints().toArray()) pen += glyphs.get(cp).adv;
			return pen * size / em;
		}

		void draw(Gfx g, String s, float x, float cy, float size, int argb) {
			int k = Gfx.guiScale();
			float scale = size * k / em;
			g.push();
			g.scale(1f / k);
			g.translate(Math.round(x * k), Math.round(cy * k - cap * scale / 2));
			g.scale(scale);
			int pen = 0;
			for (int cp : s.codePoints().toArray()) {
				Glyph c = glyphs.get(cp);
				if (c.w > 0) g.region(atlas, pen + c.xo, c.yo + cap, c.w, c.h, c.u, c.v, texW, texH, argb);
				pen += c.adv;
			}
			g.pop();
		}
	}

	private static final Face BODY = new Face("font"), HEADING = new Face("display");

	/** True when every character can be drawn smoothly. */
	public static boolean supports(String s) {
		return BODY.supports(s);
	}

	public static float width(String s, float size) {
		return BODY.supports(s) ? BODY.width(s, size) : Gfx.textWidth(s);
	}

	/** Draw with the capital letters vertically centred on `cy`. */
	public static void draw(Gfx g, String s, float x, float cy, float size, int argb) {
		if (BODY.supports(s)) BODY.draw(g, s, x, cy, size, argb);
		else g.text(s, Math.round(x), Math.round(cy - 4), argb, false);
	}

	public static void center(Gfx g, String s, float cx, float cy, float size, int argb) {
		draw(g, s, cx - width(s, size) / 2, cy, size, argb);
	}

	/** Headings in the launcher's display font (Unbounded); other text falls back to the body font. */
	public static final class Display {
		private Display() {}

		public static float width(String s, float size) {
			return HEADING.supports(s) ? HEADING.width(s, size) : Txt.width(s, size);
		}

		public static void draw(Gfx g, String s, float x, float cy, float size, int argb) {
			if (HEADING.supports(s)) HEADING.draw(g, s, x, cy, size, argb);
			else Txt.draw(g, s, x, cy, size, argb);
		}

		public static void center(Gfx g, String s, float cx, float cy, float size, int argb) {
			draw(g, s, cx - width(s, size) / 2, cy, size, argb);
		}
	}
}
