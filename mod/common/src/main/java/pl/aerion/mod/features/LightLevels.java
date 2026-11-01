package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Chroma;
import pl.aerion.mod.hud.HudConfig;

/**
 * Light level numbers just above the floor around the player (hud.json "features.lightLevels"):
 * red where it is dark enough for monsters, yellow in dim light, green in good light. The blocks
 * are scanned a few times a second and the found numbers drawn every frame.
 */
public final class LightLevels {
	private LightLevels() {}

	private static final int ABOVE = 3, BELOW = 5, MAX_LABELS = 600;
	private static final long SCAN_MILLIS = 300;

	private record Label(Vec3 pos, int level) {}

	private static List<Label> labels = List.of();
	private static long scanned;

	private static int number(JsonObject f, String key, int fallback, int min, int max) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(HudConfig.opt(f, key, String.valueOf(fallback)))));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static List<Label> scan(ClientLevel level, BlockPos center, int radius, int maxLevel, boolean withSky) {
		List<Label> out = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), below = new BlockPos.MutableBlockPos();
		int skyDarken = level.getSkyDarken();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = ABOVE; dy >= -BELOW; dy--) {
					pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					below.set(pos.getX(), pos.getY() - 1, pos.getZ());
					BlockState state = level.getBlockState(pos);
					if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) continue;
					if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
					int light = level.getBrightness(LightLayer.BLOCK, pos);
					if (withSky) light = Math.max(light, level.getBrightness(LightLayer.SKY, pos) - skyDarken);
					if (light > maxLevel) continue;
					out.add(new Label(new Vec3(pos.getX() + 0.5, pos.getY() + 0.12, pos.getZ() + 0.5), light));
					if (out.size() >= MAX_LABELS) return out;
				}
			}
		}
		return out;
	}

	/** Called while the game collects the frame's debug shapes. */
	public static void emit() {
		HudConfig cfg = AerionClient.hud();
		Minecraft mc = Minecraft.getInstance();
		if (cfg == null || mc.level == null || mc.player == null) return;
		JsonObject f = cfg.feature("lightLevels");
		if (!HudConfig.bool(f, "enabled", false)) return;
		long now = System.currentTimeMillis();
		if (now - scanned > SCAN_MILLIS) {
			scanned = now;
			labels = scan(mc.level, mc.player.blockPosition(), number(f, "radius", 8, 2, 16), number(f, "maxLevel", 15, 0, 15),
					"combined".equals(HudConfig.opt(f, "mode", "block")));
		}
		int low = 0xFF000000 | Chroma.parse(HudConfig.opt(f, "lowColor", "#ff5555"), 0xFFFF5555);
		int mid = 0xFF000000 | Chroma.parse(HudConfig.opt(f, "midColor", "#ffe25a"), 0xFFFFE25A);
		int high = 0xFF000000 | Chroma.parse(HudConfig.opt(f, "highColor", "#55dd77"), 0xFF55DD77);
		float scale = TextGizmo.Style.DEFAULT_SCALE * number(f, "scale", 100, 40, 300) / 100f;
		for (Label l : labels) {
			int color = l.level == 0 ? low : l.level < 8 ? mid : high;
			Gizmos.billboardText(String.valueOf(l.level), l.pos, TextGizmo.Style.forColorAndCentered(color).withScale(scale));
		}
	}
}
