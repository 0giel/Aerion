package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import net.minecraft.client.renderer.texture.DynamicTexture;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Chroma;
import pl.aerion.mod.Colors;
import pl.aerion.mod.hud.HudConfig;

/** The tint of entities that were just hit (hud.json "features.hitColor"); the game's is red. */
public final class HitColor {
	private HitColor() {}

	private static final int GAME_COLOR = 0xB2FF0000;
	private static DynamicTexture texture;
	private static int shown = GAME_COLOR;

	public static void attach(DynamicTexture overlay) {
		texture = overlay;
		shown = GAME_COLOR;
	}

	/** Called every tick; the small texture is written only when the colour changes. */
	public static void tick() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null || texture == null) return;
		JsonObject f = cfg.feature("hitColor");
		int wanted = GAME_COLOR;
		if (HudConfig.bool(f, "enabled", false)) {
			int alpha;
			try {
				alpha = Math.max(0, Math.min(100, Integer.parseInt(HudConfig.opt(f, "opacity", "70")))) * 255 / 100;
			} catch (NumberFormatException e) {
				alpha = 178;
			}
			wanted = Colors.alpha(Chroma.parse(HudConfig.opt(f, "color", "#55aaff"), 0xFF55AAFF), alpha);
		}
		if (wanted == shown) return;
		shown = wanted;
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 16; x++) texture.getPixels().setPixel(x, y, wanted);
		}
		texture.upload();
	}
}
