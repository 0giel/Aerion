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

/** The menu's icons, one image made by scripts/build-mod-assets.py; white, tinted when drawn. */
public final class Icons {
	private Icons() {}

	private static final Identifier ATLAS = Identifier.fromNamespaceAndPath("aerion", "icons");
	private static final Map<String, Integer> INDEX = new HashMap<>();
	private static int cell = 64, cols = 6, texW, texH;
	private static boolean loaded, failed;

	private static boolean ready() {
		if (loaded || failed) return loaded;
		try (InputStream png = Icons.class.getResourceAsStream("/assets/aerion/textures/gui/icons.png");
				InputStream txt = Icons.class.getResourceAsStream("/assets/aerion/icons.txt");
				BufferedReader r = new BufferedReader(new InputStreamReader(txt, StandardCharsets.UTF_8))) {
			String[] head = r.readLine().split(" ");
			cell = Integer.parseInt(head[0]);
			cols = Integer.parseInt(head[1]);
			texW = Integer.parseInt(head[2]);
			texH = Integer.parseInt(head[3]);
			int i = 0;
			for (String line = r.readLine(); line != null; line = r.readLine()) INDEX.put(line.trim(), i++);
			Textures.registerSmooth(ATLAS, png);
			loaded = true;
		} catch (Exception e) {
			failed = true;
			AerionClient.LOG.warn("Aerion UI: can't load the icons: {}", e.toString());
		}
		return loaded;
	}

	public static void draw(Gfx g, String name, float x, float y, float size, int argb) {
		if (!ready()) return;
		Integer i = INDEX.get(name);
		if (i == null) return;
		int k = Gfx.guiScale();
		float scale = size * k / cell;
		g.push();
		g.scale(1f / k);
		g.translate(Math.round(x * k), Math.round(y * k));
		g.scale(scale);
		g.region(ATLAS, 0, 0, cell, cell, (i % cols) * cell, (i / cols) * cell, texW, texH, argb);
		g.pop();
	}
}
