package pl.aerion.mod.hud;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Terrain colours seen from above, shaded like a vanilla map; shared by the minimap and the map. */
final class MapColors {
	private MapColors() {}

	/**
	 * `nx` × `nz` pixels (ARGB, row by row; 0 where the chunk isn't loaded), one per `step` blocks,
	 * starting at block (`x0`, `z0`) in the north-west corner.
	 */
	static int[] sample(ClientLevel level, int x0, int z0, int nx, int nz, int step) {
		int[] out = new int[nx * nz];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int[] prevHeight = new int[nx];
		for (int dz = 0; dz < nz; dz++) {
			for (int dx = 0; dx < nx; dx++) {
				int x = x0 + dx * step, z = z0 + dz * step;
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
				out[dz * nx + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
		return out;
	}

	/** The height to stand on at a column, or `fallback` where the chunk isn't loaded. */
	static int surface(ClientLevel level, int x, int z, int fallback) {
		return level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : fallback;
	}
}
