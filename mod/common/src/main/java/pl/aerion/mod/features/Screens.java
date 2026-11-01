package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Theme;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.Gfx;

/**
 * What screens over a running world draw behind themselves (hud.json "features.screens"): the
 * theme-coloured veil, a neutral one or nothing, instead of always the theme's colour.
 */
public final class Screens {
	private Screens() {}

	private static JsonObject feature() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return null;
		JsonObject f = cfg.feature("screens");
		return HudConfig.bool(f, "enabled", false) ? f : null;
	}

	/** True when this feature drew (or left out) the veil, so the theme's isn't drawn. */
	public static boolean drawVeil(Gfx g) {
		JsonObject f = feature();
		if (f == null) return false;
		int alpha;
		try {
			alpha = Math.max(0, Math.min(100, Integer.parseInt(HudConfig.opt(f, "opacity", "50")))) * 255 / 100;
		} catch (NumberFormatException e) {
			alpha = 127;
		}
		switch (HudConfig.opt(f, "veil", "none")) {
			case "dark" -> g.fill(0, 0, g.width(), g.height(), Colors.alpha(0xFF000000, alpha));
			case "theme" -> {
				Theme t = AerionClient.theme();
				if (t != null) Draw.background(g, t, alpha);
			}
			default -> {}
		}
		return true;
	}

	/** List panels in menus: true when the feature replaced the theme's tint. */
	public static boolean drawPanel(Gfx g, int x, int y, int w, int h) {
		JsonObject f = feature();
		if (f == null || "theme".equals(HudConfig.opt(f, "veil", "none"))) return false;
		if ("dark".equals(HudConfig.opt(f, "veil", "none"))) g.fill(x, y, x + w, y + h, 0x8C000000);
		return true;
	}

	/** The game's blur behind menus. */
	public static boolean noBlur() {
		JsonObject f = feature();
		return f != null && "0".equals(HudConfig.opt(f, "blur", "1"));
	}
}
