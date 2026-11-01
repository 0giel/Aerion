package pl.aerion.mod.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import pl.aerion.mod.Colors;
import pl.aerion.mod.platform.Gfx;

/**
 * A north-up map of the terrain around the player, shaded like a vanilla map. It shows blocks
 * only — never other players or mobs (a radar is banned on many servers). Colours are sampled a
 * few times a second, not every frame.
 */
final class Minimap implements Hud.Module {
	private int[] colors = new int[0];
	private int blocks;
	private long sampled;

	private int side(Hud.Ctx c) {
		return Math.max(48, Math.min(160, parse(c.opt("size", "80"), 80)));
	}

	private int zoom(Hud.Ctx c) {
		return Math.max(1, Math.min(4, parse(c.opt("zoom", "2"), 2)));
	}

	private static int parse(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (Exception e) {
			return fallback;
		}
	}

	@Override
	public int[] size(Hud.Ctx c) {
		int p = c.style().padding();
		int s = side(c);
		return new int[] {s + 2 * p, s + 2 * p + (c.flag("coords", true) ? Hud.LINE + 1 : 0)};
	}

	private void sample(Minecraft mc, LocalPlayer player, int n) {
		ClientLevel level = mc.level;
		if (level == null) return;
		int[] out = new int[n * n];
		int px = player.getBlockX(), pz = player.getBlockZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int[] prevHeight = new int[n];
		for (int dz = 0; dz < n; dz++) {
			for (int dx = 0; dx < n; dx++) {
				int x = px - n / 2 + dx, z = pz - n / 2 + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				pos.set(x, h, z);
				BlockState state = level.getBlockState(pos);
				int col = state.getMapColor(level, pos).col;
				if (col == 0) continue;
				// Brighter towards rising ground, darker towards falling (north to south), like maps.
				float shade = dz == 0 ? 1f : h > prevHeight[dx] ? 1.1f : h < prevHeight[dx] ? 0.82f : 1f;
				prevHeight[dx] = h;
				int r = Math.min(255, Math.round(((col >> 16) & 0xFF) * shade));
				int g = Math.min(255, Math.round(((col >> 8) & 0xFF) * shade));
				int b = Math.min(255, Math.round((col & 0xFF) * shade));
				out[dz * n + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
		colors = out;
		blocks = n;
	}

	@Override
	public void draw(Hud.Ctx c, Gfx g) {
		int p = c.style().padding();
		int s = side(c);
		int z = zoom(c);
		int n = s / z;
		LocalPlayer player = c.player();
		long now = System.currentTimeMillis();
		if (player != null && (now - sampled > 250 || blocks != n)) {
			sample(c.mc(), player, n);
			sampled = now;
		}
		g.fill(p, p, p + s, p + s, 0xFF1A1A1A);
		if (blocks == n) {
			for (int dz = 0; dz < n; dz++) {
				for (int dx = 0; dx < n; dx++) {
					int col = colors[dz * n + dx];
					if (col != 0) g.fill(p + dx * z, p + dz * z, p + dx * z + z, p + dz * z + z, col);
				}
			}
		}
		// The player: a dot with a tick towards where they look.
		int cx = p + s / 2, cy = p + s / 2;
		int accent = c.theme() != null ? c.theme().accent : 0xFFFFFFFF;
		g.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFF000000);
		g.fill(cx - 1, cy - 1, cx + 1, cy + 1, accent);
		if (player != null) {
			double a = Math.toRadians(player.getYRot());
			for (int k = 2; k <= 5; k++) {
				int tx = cx + (int) Math.round(-Math.sin(a) * k), ty = cy + (int) Math.round(Math.cos(a) * k);
				g.fill(tx, ty, tx + 1, ty + 1, Colors.alpha(accent, 255));
			}
		}
		if (c.flag("north", true)) Hud.text(c, g, "N", p + s / 2 - 2, p + 1, 0xFFFFFFFF, true);
		if (c.flag("coords", true) && player != null) {
			String t = player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ();
			Hud.text(c, g, t, p + (s - Hud.width(c, t)) / 2, p + s + 2, c.style().text(), c.style().shadow());
		}
	}
}
