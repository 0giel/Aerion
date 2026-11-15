package pl.aerion.mod;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import pl.aerion.mod.platform.Gfx;
import pl.aerion.mod.platform.Textures;

/**
 * The launcher theme outside the menus (the "Game look" page, each part off until switched on):
 * the hotbar, item tooltips, the chat's background and the player list (Tab). Only colours change;
 * sizes and places stay the game's own.
 */
public final class GameLook {
	private GameLook() {}

	/** The theme when that part should follow it, else null. */
	private static Theme on(String part) {
		Theme t = AerionClient.colors();
		return t != null && MenuLook.part(part) ? t : null;
	}

	// --- Hotbar --------------------------------------------------------------------------------

	private record Made(String key, Identifier id) {}

	private static final Map<String, Made> MADE = new HashMap<>();
	private static final Map<String, Boolean> FAILED = new HashMap<>();
	private static int serial;

	/** The hotbar pictures recoloured to the theme, or null to draw the game's own. */
	public static Identifier sprite(Identifier sprite) {
		if (sprite == null || !"minecraft".equals(sprite.getNamespace())) return null;
		String path = sprite.getPath();
		boolean frame = path.equals("hud/hotbar") || path.startsWith("hud/hotbar_offhand");
		boolean selection = path.equals("hud/hotbar_selection");
		if (!frame && !selection) return null;
		Theme t = on("hotbar");
		if (t == null) return null;
		String key = path + "|" + Integer.toHexString(t.surface) + Integer.toHexString(t.accent) + Integer.toHexString(t.border);
		Made m = MADE.get(path);
		if (m != null && m.key.equals(key)) return m.id;
		if (FAILED.containsKey(path)) return null;
		try {
			Identifier png = Identifier.withDefaultNamespace("textures/gui/sprites/" + path + ".png");
			Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(png);
			if (res.isEmpty()) throw new IllegalStateException("missing");
			BufferedImage img;
			try (InputStream in = res.get().open()) {
				img = ImageIO.read(in);
			}
			int w = img.getWidth(), h = img.getHeight();
			int[] px = img.getRGB(0, 0, w, h, null, 0, w);
			// Dark greys become the theme's panel, light ones its border; the selection its accent.
			int dark = frame ? t.bg : Colors.mix(t.accent, 0xFF000000, 0.45f);
			int light = frame ? Colors.mix(t.border, t.text, 0.25f) : t.accent;
			for (int i = 0; i < px.length; i++) {
				int a = px[i] >>> 24;
				if (a == 0) continue;
				int r = (px[i] >> 16) & 0xFF, g = (px[i] >> 8) & 0xFF, b = px[i] & 0xFF;
				float v = (r + g + b) / (3f * 255f);
				px[i] = Colors.alpha(Colors.mix(dark, light, v), a);
			}
			Identifier made = Identifier.fromNamespaceAndPath("aerion", "look" + (++serial) + "/" + path);
			Textures.registerPixels(made, w, h, px);
			if (m != null) Textures.release(m.id);
			MADE.put(path, new Made(key, made));
			return made;
		} catch (Exception | LinkageError e) {
			FAILED.put(path, true);
			AerionClient.LOG.warn("Aerion UI: can't recolour {}: {}", path, e.toString());
			return null;
		}
	}

	// --- Tooltips ------------------------------------------------------------------------------

	/**
	 * Draws a tooltip's box in the theme (the panel colour with a thin accent edge) around the text
	 * area `x, y, w, h`; false when the game should draw its own.
	 */
	public static boolean tooltip(Gfx g, int x, int y, int w, int h) {
		Theme t = on("tooltips");
		if (t == null) return false;
		int x1 = x - 3, y1 = y - 3, x2 = x + w + 3, y2 = y + h + 3;
		g.fill(x1, y1 - 1, x2, y2 + 1, Colors.alpha(t.surface, 0xF2));
		g.fill(x1 - 1, y1, x2 + 1, y2, Colors.alpha(t.surface, 0xF2));
		int edge = Colors.alpha(Colors.mix(t.border, t.accent, 0.55f), 0xFF);
		g.fill(x1, y1, x2, y1 + 1, edge);
		g.fill(x1, y2 - 1, x2, y2, Colors.alpha(edge, 0xA0));
		g.fill(x1, y1 + 1, x1 + 1, y2 - 1, Colors.alpha(edge, 0xD0));
		g.fill(x2 - 1, y1 + 1, x2, y2 - 1, Colors.alpha(edge, 0xD0));
		return true;
	}

	// --- Chat and the player list ---------------------------------------------------------------

	/** A background colour of the chat: the game's black, as the theme's background. */
	public static int chat(int argb) {
		return recolour("chat", argb);
	}

	/** A background colour of the player list (Tab): the game's black and white, in the theme. */
	public static int tabList(int argb) {
		return recolour("tabList", argb);
	}

	/** Black keeps its see-through but takes the theme's background; white takes the accent. */
	private static int recolour(String part, int argb) {
		Theme t = on(part);
		if (t == null) return argb;
		int a = argb >>> 24, rgb = argb & 0xFFFFFF;
		if (rgb == 0) return Colors.alpha(t.bg, a);
		if (rgb == 0xFFFFFF) return Colors.alpha(t.accent, a);
		return argb;
	}
}
