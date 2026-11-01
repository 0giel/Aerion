package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Theme;
import pl.aerion.mod.hud.HudConfig;

/**
 * Entity hitboxes in the player's colours (hud.json "features.hitboxes"), separate from the game's
 * F3+B. An entity close enough to be hit can change colour; the range is the player's own.
 */
public final class Hitboxes {
	private Hitboxes() {}

	private static int color(String value, int fallback) {
		if ("accent".equals(value)) {
			Theme t = AerionClient.theme();
			return t != null ? t.accent : fallback;
		}
		return Theme.color(value, fallback);
	}

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

	/** Called while the game collects the frame's debug shapes. */
	public static void emit(float partial) {
		Minecraft mc = Minecraft.getInstance();
		HudConfig cfg = AerionClient.hud();
		if (cfg == null || mc.level == null || mc.player == null) return;
		JsonObject f = cfg.feature("hitboxes");
		if (!HudConfig.bool(f, "enabled", false)) return;
		int normal = color(HudConfig.opt(f, "color", "#ffffff"), 0xFFFFFFFF);
		int inReach = color(HudConfig.opt(f, "reachColor", "#ff5555"), 0xFFFF5555);
		boolean reach = flag(f, "reach", true);
		boolean players = flag(f, "players", true), mobs = flag(f, "mobs", true), others = flag(f, "others", false);
		boolean look = flag(f, "look", false);
		float width = Math.max(1, Math.min(6, number(f, "width", 2)));
		int fill = Math.max(0, Math.min(100, number(f, "fill", 0)));
		int range = Math.max(4, Math.min(128, number(f, "range", 48)));
		Entity camera = mc.getCameraEntity();
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e.isInvisible() || (e == camera && firstPerson)) continue;
			boolean shown = e instanceof Player ? players : e instanceof LivingEntity ? mobs : others;
			if (!shown || e.distanceToSqr(mc.player) > (double) range * range) continue;
			boolean hittable = e != mc.player && e instanceof LivingEntity && mc.player.isWithinEntityInteractionRange(e, 0);
			int stroke = 0xFF000000 | (reach && hittable ? inReach : normal);
			// The box follows the smooth position the entity is drawn at.
			Vec3 drawn = e.getPosition(partial);
			AABB box = e.getBoundingBox().move(drawn.subtract(e.position()));
			Gizmos.cuboid(box, GizmoStyle.strokeAndFill(stroke, width, fill == 0 ? 0 : Colors.alpha(stroke, fill * 255 / 100)));
			if (look && e instanceof LivingEntity living) {
				Vec3 eye = drawn.add(0, living.getEyeHeight(), 0);
				Gizmos.line(eye, eye.add(living.getViewVector(partial).scale(2)), 0xFF5577FF, width);
			}
		}
	}
}
