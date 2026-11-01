package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.hud.HudConfig;

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
}
