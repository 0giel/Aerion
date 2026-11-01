package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Chroma;
import pl.aerion.mod.Colors;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.Gfx;

/** Which of the game's full-screen effects are drawn (hud.json "features.overlays"). */
public final class Overlays {
	private Overlays() {}

	private static JsonObject feature() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return null;
		JsonObject f = cfg.feature("overlays");
		return HudConfig.bool(f, "enabled", false) ? f : null;
	}

	/** True when the switched-on feature hides this effect. */
	public static boolean hidden(String key) {
		JsonObject f = feature();
		return f != null && "1".equals(HudConfig.opt(f, key, "0"));
	}

	/** The texture overlays share one method: pumpkin, powder snow and modded helmets. */
	public static boolean textureHidden(String texturePath) {
		if (texturePath.contains("pumpkin")) return hidden("pumpkin");
		if (texturePath.contains("powder_snow")) return hidden("snow");
		return false;
	}

	/** How much lower the fire on screen sits: 0 as in the game, 100 entirely below the screen. */
	public static float fireDrop() {
		JsonObject f = feature();
		if (f == null) return 0;
		try {
			return Math.max(0, Math.min(100, Integer.parseInt(HudConfig.opt(f, "fireDrop", "0")))) / 100f;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Opacity of the fire on screen, 0 to 100; the game's own is 90. */
	public static int fireOpacity() {
		JsonObject f = feature();
		return f == null ? 90 : number(f, "fireOpacity", 90, 0, 100);
	}

	private static int number(JsonObject f, String key, int fallback, int min, int max) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(HudConfig.opt(f, key, String.valueOf(fallback)))));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Multiplier of the hurt cam tilt (its own feature, so it works without the other overlays). */
	public static double hurtCam() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return 1;
		JsonObject f = cfg.feature("hurtCam");
		if (!HudConfig.bool(f, "enabled", false)) return 1;
		return number(f, "strength", 50, 0, 200) / 100.0;
	}

	/** A colour tint and a red edge at low health, drawn under the HUD modules. */
	public static void draw(Gfx g) {
		JsonObject f = feature();
		if (f == null) return;
		int tint = number(f, "tintOpacity", 0, 0, 80);
		if (tint > 0) {
			int c = Chroma.parse(HudConfig.opt(f, "tint", "#ff0000"), 0xFFFF0000);
			g.fill(0, 0, g.width(), g.height(), Colors.alpha(c, tint * 255 / 100));
		}
		Minecraft mc = Minecraft.getInstance();
		if ("1".equals(HudConfig.opt(f, "lowHealth", "0")) && mc.player != null && mc.player.getHealth() <= number(f, "lowHealthAt", 6, 1, 19)) {
			double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0);
			int peak = (int) (40 + 60 * pulse), w = g.width(), h = g.height();
			for (int i = 0; i < 14; i++) {
				int c = Colors.alpha(0xFFFF2020, peak * (14 - i) / 14);
				g.fill(i, i, w - i, i + 1, c);
				g.fill(i, h - i - 1, w - i, h - i, c);
				g.fill(i, i + 1, i + 1, h - i - 1, c);
				g.fill(w - i - 1, i + 1, w - i, h - i - 1, c);
			}
		}
	}
}
