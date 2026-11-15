package pl.aerion.mod.hud;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Terrain colours seen from above; shared by the minimap and the map. Grass, leaves and water take
 * their biome's colour, slopes are lit from the north-west so hills read in relief, and water gets
 * lighter where it is shallow, so coasts and rivers show their shape.
 */
final class MapColors {
	private MapColors() {}

	/** How deep water is looked into, in blocks. */
	private static final int DEPTH = 12;

	/**
	 * `nx` × `nz` pixels (ARGB, row by row; 0 where the chunk isn't loaded), one per `step` blocks,
	 * starting at block (`x0`, `z0`) in the north-west corner.
	 */
	static int[] sample(ClientLevel level, int x0, int z0, int nx, int nz, int step) {
		int[] out = new int[nx * nz];
		// Heights with one extra column on the west and one extra row on the north, for the light.
		int w = nx + 1;
		int[] height = new int[w * (nz + 1)];
		for (int dz = -1; dz < nz; dz++) {
			for (int dx = -1; dx < nx; dx++) {
				int x = x0 + dx * step, z = z0 + dz * step;
				height[(dz + 1) * w + dx + 1] = level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1 : Integer.MIN_VALUE;
			}
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dz = 0; dz < nz; dz++) {
			for (int dx = 0; dx < nx; dx++) {
				int h = height[(dz + 1) * w + dx + 1];
				if (h == Integer.MIN_VALUE) continue;
				int x = x0 + dx * step, z = z0 + dz * step;
				pos.set(x, h, z);
				BlockState state = level.getBlockState(pos);
				MapColor mc = state.getMapColor(level, pos);
				if (mc.col == 0) continue;
				int col;
				if (mc == MapColor.WATER) {
					col = water(level, pos, x, h, z);
				} else {
					col = mc.col;
					if (mc == MapColor.GRASS) col = biome(level, pos).getGrassColor(x, z);
					else if (mc == MapColor.PLANT) col = scale(biome(level, pos).getFoliageColor(), 0.86f);
					// Lit from the north-west: ground rising towards the light is brighter.
					int west = height[(dz + 1) * w + dx], north = height[dz * w + dx + 1];
					float rise = 0;
					if (west != Integer.MIN_VALUE) rise += h - west;
					if (north != Integer.MIN_VALUE) rise += h - north;
					col = scale(col, Math.max(0.68f, Math.min(1.24f, 1f + rise * 0.07f / step)));
				}
				out[dz * nx + dx] = 0xFF000000 | (col & 0xFFFFFF);
			}
		}
		return out;
	}

	private static Biome biome(ClientLevel level, BlockPos pos) {
		return level.getBiome(pos).value();
	}

	/** Water: the biome's water over what lies below, darker the deeper it is. */
	private static int water(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int top, int z) {
		int tint = biome(level, pos).getWaterColor();
		int depth = 1, floor = 0;
		for (; depth <= DEPTH; depth++) {
			pos.set(x, top - depth, z);
			BlockState below = level.getBlockState(pos);
			MapColor mc = below.getMapColor(level, pos);
			if (mc != MapColor.WATER) {
				floor = mc.col;
				break;
			}
		}
		// Shallow water shows a little of the bottom; deep water is a dark blue.
		float see = Math.max(0f, 0.42f - depth * 0.05f);
		int col = mix(scale(tint, 1.0f - Math.min(depth, DEPTH) * 0.035f), floor == 0 ? tint : floor, see);
		pos.set(x, top, z);
		return col;
	}

	static int scale(int rgb, float f) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
		int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
		int b = Math.min(255, Math.round((rgb & 0xFF) * f));
		return (r << 16) | (g << 8) | b;
	}

	private static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return (r << 16) | (g << 8) | bl;
	}

	/** The height to stand on at a column, or `fallback` where the chunk isn't loaded. */
	static int surface(ClientLevel level, int x, int z, int fallback) {
		return level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : fallback;
	}
}
