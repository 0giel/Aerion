package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Theme;
import pl.aerion.mod.hud.HudConfig;

/**
 * Chunk edges around the player: the corners of the nearby chunks as tall lines, and the edges of
 * the chunk the player stands in at every few blocks of height, in the theme's accent.
 */
public final class ChunkBorders {
	private ChunkBorders() {}

	/** Called while the game collects the frame's debug shapes. */
	public static void emit() {
		HudConfig cfg = AerionClient.hud();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (cfg == null || p == null || mc.level == null) return;
		JsonObject f = cfg.feature("chunkBorders");
		if (!HudConfig.bool(f, "enabled", false)) return;
		Theme t = AerionClient.theme();
		int accent = 0xFF000000 | (t != null ? t.accent : 0xFFFFE25A);
		int dim = (accent & 0x00FFFFFF) | 0x70000000;
		int cx = p.getBlockX() >> 4, cz = p.getBlockZ() >> 4;
		double y0 = Math.max(mc.level.getMinY(), p.getY() - 24), y1 = Math.min(mc.level.getMaxY() + 1, p.getY() + 24);
		// Corners of the chunks around (a 5 × 5 grid of corners on the 4 × 4 chunks nearby).
		for (int dx = -2; dx <= 3; dx++) {
			for (int dz = -2; dz <= 3; dz++) {
				double x = (cx + dx) << 4, z = (cz + dz) << 4;
				boolean own = (dx == 0 || dx == 1) && (dz == 0 || dz == 1);
				Gizmos.line(new Vec3(x, y0, z), new Vec3(x, y1, z), own ? accent : dim, own ? 2.5f : 1.5f);
			}
		}
		// The player's own chunk: its edges every 4 blocks of height, and every 4 blocks along.
		double x0 = cx << 4, z0 = cz << 4;
		int from = (int) Math.ceil(y0 / 4) * 4;
		for (int y = from; y <= y1; y += 4) {
			Gizmos.line(new Vec3(x0, y, z0), new Vec3(x0 + 16, y, z0), dim, 1f);
			Gizmos.line(new Vec3(x0, y, z0 + 16), new Vec3(x0 + 16, y, z0 + 16), dim, 1f);
			Gizmos.line(new Vec3(x0, y, z0), new Vec3(x0, y, z0 + 16), dim, 1f);
			Gizmos.line(new Vec3(x0 + 16, y, z0), new Vec3(x0 + 16, y, z0 + 16), dim, 1f);
		}
		for (int k = 4; k < 16; k += 4) {
			Gizmos.line(new Vec3(x0 + k, y0, z0), new Vec3(x0 + k, y1, z0), dim, 1f);
			Gizmos.line(new Vec3(x0 + k, y0, z0 + 16), new Vec3(x0 + k, y1, z0 + 16), dim, 1f);
			Gizmos.line(new Vec3(x0, y0, z0 + k), new Vec3(x0, y1, z0 + k), dim, 1f);
			Gizmos.line(new Vec3(x0 + 16, y0, z0 + k), new Vec3(x0 + 16, y1, z0 + k), dim, 1f);
		}
	}
}
