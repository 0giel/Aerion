package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Chroma;
import pl.aerion.mod.Colors;
import pl.aerion.mod.hud.HudConfig;

/** The outline of the block being looked at, in the player's colours (hud.json "features.blockOutline"). */
public final class BlockOutline {
	private BlockOutline() {}

	private static JsonObject feature() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return null;
		JsonObject f = cfg.feature("blockOutline");
		return HudConfig.bool(f, "enabled", false) ? f : null;
	}

	/** True when this replaces the game's outline. */
	public static boolean active() {
		return feature() != null;
	}

	private static int number(JsonObject f, String key, int fallback, int min, int max) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(HudConfig.opt(f, key, String.valueOf(fallback)))));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Called while the game collects the frame's debug shapes. */
	public static void emit() {
		JsonObject f = feature();
		Minecraft mc = Minecraft.getInstance();
		if (f == null || mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir()) return;
		int color = Chroma.parse(HudConfig.opt(f, "color", "#ffffff"), 0xFFFFFFFF);
		int fillColor = Chroma.parse(HudConfig.opt(f, "fillColor", "accent"), 0xFFFFFFFF);
		int stroke = Colors.alpha(color, number(f, "opacity", 100, 0, 100) * 255 / 100);
		int fill = number(f, "fill", 0, 0, 100) * 255 / 100;
		float width = number(f, "width", 2, 1, 8);
		GizmoStyle style = GizmoStyle.strokeAndFill(stroke, width, fill == 0 ? 0 : Colors.alpha(fillColor, fill));
		state.getShape(mc.level, pos).forAllBoxes((x1, y1, z1, x2, y2, z2) ->
				Gizmos.cuboid(new AABB(x1, y1, z1, x2, y2, z2).move(pos.getX(), pos.getY(), pos.getZ()).inflate(0.002), style));
	}
}
