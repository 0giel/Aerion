package pl.aerion.mod;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.Game;
import pl.aerion.mod.platform.Textures;

/**
 * Inventories, chests, furnaces, the crafting table and the rest in the theme's colours, with a
 * look of their own (hud.json "features.inventory"): the panel's and the slots' colour and
 * opacity (clear slots show the world through), and the style of the edges.
 *
 * <p>The game draws every container from a grey picture: panel #C6C6C6, slots #8B8B8B, a white
 * edge, two dark edges and black. The picture is read from the game's resources, every grey is
 * mapped to the chosen colours along that ramp (colours stay as they are), and the result
 * replaces the picture while the screen is drawn. Titles the game writes in dark grey follow the
 * panel's brightness.
 */
public final class Containers {
	private Containers() {}

	private static final int[] RAMP = {0x00, 0x37, 0x55, 0x8B, 0xC6, 0xFF};
	private static final int PANEL_GREY = 0xC6;
	private static final int VANILLA_TITLE = 0xFF404040;

	/** What the inventory looks like now; colours are ARGB. */
	private record Look(String key, int[] ramp, int tab, int text) {}

	private record Made(String key, Identifier id) {}

	private static final Map<String, Made> MADE = new HashMap<>();
	private static final Set<String> FAILED = new HashSet<>();
	private static int serial;
	/** Replaced pictures are released a moment later: the frame being drawn may still use them. */
	private record Retired(Identifier id, long at) {}

	private static final java.util.List<Retired> RETIRED = new java.util.ArrayList<>();

	private static Look look;
	private static long lookAt;
	private static Theme lookTheme;

	private static boolean inContainer() {
		return Game.screen() instanceof AbstractContainerScreen<?>;
	}

	/** The current look, or null for the game's own (theme colours off or the feature off). */
	private static Look look() {
		Theme t = AerionClient.colors();
		if (t == null) return null;
		long now = System.currentTimeMillis();
		// Settings are read a few times a second, not on every picture.
		if (t == lookTheme && now - lookAt < 250) return look;
		lookTheme = t;
		lookAt = now;
		HudConfig cfg = AerionClient.hud();
		JsonObject f = cfg == null ? null : cfg.feature("inventory");
		look = f != null && !HudConfig.bool(f, "enabled", true) ? null : make(t, f);
		return look;
	}

	private static Look make(Theme t, JsonObject f) {
		String panelOpt = f == null ? "theme" : HudConfig.opt(f, "panel", "theme");
		String slotOpt = f == null ? "theme" : HudConfig.opt(f, "slotColor", "theme");
		int panelA = f == null ? 100 : clamp(num(f, "panelOpacity", 100), 10, 100);
		int slotA = f == null ? 100 : clamp(num(f, "slotOpacity", 100), 0, 100);
		String edges = f == null ? "bevel" : HudConfig.opt(f, "edges", "bevel");

		int themed = themed(t);
		// Chroma: the hue goes round, in steps, so the pictures are made again a few times a second.
		float[] wheel = Colors.toHsv(Chroma.color(0f, Chroma.DEFAULT_SPEED));
		float hue = Math.round(wheel[0] * 36f) / 36f;
		int panel = switch (panelOpt) {
			case "theme" -> themed;
			case Chroma.VALUE -> Colors.hsv(hue, 0.62f, t.light ? 0.93f : 0.33f);
			case "accent" -> {
				float[] hsv = Colors.toHsv(t.accent);
				yield Colors.hsv(hsv[0], hsv[1], Math.min(hsv[2], t.light ? 0.9f : 0.42f));
			}
			default -> Theme.color(panelOpt, themed);
		} | 0xFF000000;
		boolean pale = luminance(panel) > 0.5f;
		int slot = switch (slotOpt) {
			case Chroma.VALUE -> Colors.hsv((hue + 0.08f) % 1f, 0.6f, pale ? 0.8f : 0.2f);
			case "theme" -> Colors.mix(panel, 0xFF000000, pale ? 0.14f : 0.32f);
			case "accent" -> {
				float[] hsv = Colors.toHsv(t.accent);
				yield Colors.hsv(hsv[0], hsv[1], Math.min(hsv[2], pale ? 0.8f : 0.3f));
			}
			default -> Theme.color(slotOpt, Colors.mix(panel, 0xFF000000, 0.3f));
		} | 0xFF000000;

		int hi, d1, d2, out;
		switch (edges) {
			case "flat" -> {
				hi = Colors.mix(panel, 0xFFFFFFFF, 0.08f);
				d1 = d2 = Colors.mix(panel, 0xFF000000, 0.30f);
				out = Colors.mix(panel, 0xFF000000, 0.55f);
			}
			case "glow" -> {
				hi = Colors.mix(panel, t.accent | 0xFF000000, 0.85f);
				d1 = Colors.mix(panel, t.accent | 0xFF000000, 0.6f);
				d2 = Colors.mix(panel, t.accent | 0xFF000000, 0.4f);
				out = Colors.mix(panel, t.accent | 0xFF000000, 0.22f);
			}
			default -> {
				hi = Colors.mix(panel, 0xFFFFFFFF, 0.30f);
				d1 = Colors.mix(panel, 0xFF000000, 0.42f);
				d2 = Colors.mix(panel, 0xFF000000, 0.60f);
				out = Colors.mix(panel, 0xFF000000, 0.85f);
			}
		}
		int[] ramp = {
			out | 0xFF000000, d2 | 0xFF000000, d1 | 0xFF000000,
			Colors.alpha(slot, slotA * 255 / 100), Colors.alpha(panel, panelA * 255 / 100), hi | 0xFF000000
		};
		int tab = 0xFF000000 | channel(panel, 16) << 16 | channel(panel, 8) << 8 | channel(panel, 0);
		int text = pale ? VANILLA_TITLE : t.light ? 0xFFF2F2F2 : t.text;
		String key = panelOpt + "|" + slotOpt + "|" + panelA + "|" + slotA + "|" + edges + "|" + Integer.toHexString(panel) + "|" + Integer.toHexString(slot) + "|" + Integer.toHexString(t.accent);
		return new Look(key, ramp, tab, text);
	}

	/** The panel colour the theme gives: its accent's hue, lively but dark (or pale on light themes). */
	private static int themed(Theme t) {
		float[] hsv = Colors.toHsv(t.accent);
		return t.light
				? Colors.hsv(hsv[0], Math.min(0.18f, hsv[1] * 0.25f), 0.93f)
				: Colors.hsv(hsv[0], Math.min(0.62f, hsv[1] * 0.7f), 0.33f);
	}

	private static int channel(int argb, int shift) {
		return Math.min(255, ((argb >> shift) & 0xFF) * 255 / PANEL_GREY);
	}

	private static float luminance(int c) {
		return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static int num(JsonObject f, String key, int fallback) {
		try {
			return Integer.parseInt(HudConfig.opt(f, key, String.valueOf(fallback)));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** True for the pictures of container panels (inventory, chest, furnace, creative tabs...). */
	private static boolean isPanel(Identifier id) {
		return id != null && id.getPath().startsWith("textures/gui/container/");
	}

	/** The picture to draw instead of the game's `id`: the same one recoloured, or `id` itself. */
	public static Identifier texture(Identifier id) {
		if (!isPanel(id)) return id;
		Look l = look();
		if (l == null || !inContainer()) return id;
		String path = id.toString();
		if (FAILED.contains(path)) return id;
		if (!RETIRED.isEmpty()) {
			long now = System.currentTimeMillis();
			RETIRED.removeIf(r -> {
				if (now - r.at < 2000) return false;
				Textures.release(r.id);
				return true;
			});
		}
		Made m = MADE.get(path);
		if (m != null && m.key.equals(l.key)) return m.id;
		try {
			Identifier made = Identifier.fromNamespaceAndPath("aerion", "inv" + (++serial) + "/" + id.getPath());
			Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(id);
			if (res.isEmpty()) throw new IllegalStateException("missing");
			BufferedImage img;
			try (InputStream in = res.get().open()) {
				img = ImageIO.read(in);
			}
			if (img == null) throw new IllegalStateException("not an image");
			int w = img.getWidth(), h = img.getHeight();
			int[] px = img.getRGB(0, 0, w, h, null, 0, w);
			for (int i = 0; i < px.length; i++) px[i] = remap(px[i], l.ramp);
			Textures.registerPixels(made, w, h, px);
			if (m != null) RETIRED.add(new Retired(m.id, System.currentTimeMillis()));
			MADE.put(path, new Made(l.key, made));
			return made;
		} catch (Exception | LinkageError e) {
			FAILED.add(path);
			AerionClient.LOG.warn("Aerion UI: can't recolour {}: {}", id, e.toString());
			return id;
		}
	}

	/** Greys go along the ramp (vanilla anchor → new colour); anything with a colour is left alone. */
	private static int remap(int argb, int[] ramp) {
		int a = argb >>> 24;
		if (a == 0) return argb;
		int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		if (Math.abs(r - g) > 2 || Math.abs(g - b) > 2) return argb;
		int v = (r + g + b) / 3;
		int i = 0;
		while (i < RAMP.length - 2 && v > RAMP[i + 1]) i++;
		float t = (v - RAMP[i]) / (float) (RAMP[i + 1] - RAMP[i]);
		int c = Colors.mix(ramp[i], ramp[i + 1], Math.max(0f, Math.min(1f, t)));
		return Colors.alpha(c, ((c >>> 24) * a) / 255);
	}

	/** Sprites that are part of a container's frame (the creative inventory's tabs): the panel's tint. */
	public static int tintSprite(Identifier name, int color) {
		if (color != -1 || name == null || !name.getPath().startsWith("container/creative_inventory/tab_")) return color;
		Look l = look();
		return l == null || !inContainer() ? color : l.tab;
	}

	/** The colour for text the game asks to be dark grey (container titles). */
	public static int text(int color) {
		if (color != VANILLA_TITLE) return color;
		Look l = look();
		return l == null || !inContainer() ? color : l.text;
	}
}
