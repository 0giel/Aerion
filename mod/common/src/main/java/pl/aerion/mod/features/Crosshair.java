package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Chroma;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.Gfx;

/** A crosshair of the player's own shape and colour (hud.json "features.crosshair"). */
public final class Crosshair {
	private Crosshair() {}

	private static int number(JsonObject f, String key, int fallback) {
		try {
			return Integer.parseInt(HudConfig.opt(f, key, String.valueOf(fallback)));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean flag(JsonObject f, String key, boolean fallback) {
		return "1".equals(HudConfig.opt(f, key, fallback ? "1" : "0"));
	}

	/** Draws in the game's place; false leaves the game's own crosshair. */
	public static boolean draw(Gfx g) {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return false;
		JsonObject f = cfg.feature("crosshair");
		if (!HudConfig.bool(f, "enabled", false)) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.options.getCameraType().isFirstPerson() || mc.player.isSpectator()) return true;
		boolean target = flag(f, "target", false) && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY;
		int color = Chroma.parse(HudConfig.opt(f, target ? "targetColor" : "color", target ? "#ff5555" : "#ffffff"), 0xFFFFFFFF);
		int k = Gfx.guiScale(), cx = g.width() * k / 2, cy = g.height() * k / 2;
		g.push();
		g.scale(1f / k);
		paint(g, f, cx, cy, 0xFF000000 | color);
		if (flag(f, "attack", true)) {
			float strength = mc.player.getAttackStrengthScale(0f);
			if (strength < 1f) {
				int w = 8 * k, top = cy + 7 * k;
				g.fill(cx - w, top, cx + w, top + k, 0x80000000);
				g.fill(cx - w, top, cx - w + Math.round(2 * w * strength), top + k, 0xFF000000 | color);
			}
		}
		g.pop();
		return true;
	}

	/** Coordinates are screen pixels; also used by the preview in the menu. */
	public static void paint(Gfx g, JsonObject f, int cx, int cy, int color) {
		int k = Gfx.guiScale();
		int size = Math.round(Math.max(1, Math.min(30, number(f, "size", 8))) * k / 2f);
		int gap = Math.round(Math.max(0, Math.min(20, number(f, "gap", 3))) * k / 2f);
		int t = Math.max(1, Math.round(Math.max(1, Math.min(8, number(f, "thickness", 2))) * k / 2f));
		boolean outline = flag(f, "outline", true);
		switch (HudConfig.opt(f, "shape", "cross")) {
			case "dot" -> box(g, cx - t, cy - t, cx + t, cy + t, color, outline);
			case "circle" -> {
				int r = gap + size, steps = Math.max(24, r * 6);
				for (int pass = outline ? 0 : 1; pass < 2; pass++) {
					int grow = pass == 0 ? 1 : 0, c = pass == 0 ? 0xC0000000 : color;
					for (int i = 0; i < steps; i++) {
						double a = 2 * Math.PI * i / steps;
						int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
						g.fill(x - t / 2 - grow, y - t / 2 - grow, x + (t + 1) / 2 + grow, y + (t + 1) / 2 + grow, c);
					}
				}
			}
			case "x" -> {
				for (int pass = outline ? 0 : 1; pass < 2; pass++) {
					int grow = pass == 0 ? 1 : 0, c = pass == 0 ? 0xC0000000 : color;
					for (int i = gap; i <= gap + size * 7 / 10; i++) {
						for (int[] s : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
							int x = cx + s[0] * i, y = cy + s[1] * i;
							g.fill(x - t / 2 - grow, y - t / 2 - grow, x + (t + 1) / 2 + grow, y + (t + 1) / 2 + grow, c);
						}
					}
				}
			}
			default -> {
				int a = t / 2, b = t - a;
				box(g, cx - gap - size, cy - a, cx - gap, cy + b, color, outline);
				box(g, cx + gap, cy - a, cx + gap + size, cy + b, color, outline);
				box(g, cx - a, cy - gap - size, cx + b, cy - gap, color, outline);
				box(g, cx - a, cy + gap, cx + b, cy + gap + size, color, outline);
			}
		}
		if (flag(f, "dot", false) && !"dot".equals(HudConfig.opt(f, "shape", "cross"))) {
			int a = t / 2, b = t - a;
			box(g, cx - a, cy - a, cx + b, cy + b, color, outline);
		}
	}

	private static void box(Gfx g, int x1, int y1, int x2, int y2, int color, boolean outline) {
		if (outline) g.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xC0000000);
		g.fill(x1, y1, x2, y2, color);
	}
}
