package pl.aerion.mod.hud;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Cost;
import pl.aerion.mod.features.Waypoints;

/**
 * The map remembers what the player has seen: the terrain's colours of the chunks around them,
 * one pixel per block, kept in tiles of 256 × 256 blocks per world and dimension
 * (`<game>/aerion-map/<world>/<dimension>/<x>.<z>.png`; a server is a hash of its address). Only
 * a few chunks are looked at each tick, files are read and written on a thread of their own and
 * only some tiles stay in memory.
 */
public final class MapMemory {
	private MapMemory() {}

	private static final int TILE = 256, KEEP_TILES = 64, CHUNKS_PER_TICK = 3, RADIUS = 8;
	/** A chunk is looked at again after this long (blocks change). */
	private static final long AGAIN_MS = 45_000;

	private static final class Tile {
		final int[] px = new int[TILE * TILE];
		boolean dirty;
	}

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Aerion map memory");
		t.setDaemon(true);
		return t;
	});

	/** Tiles in memory, least recently used first; null values are tiles being read. */
	private static final Map<String, Tile> TILES = new LinkedHashMap<>(16, 0.75f, true);
	private static final Set<String> MISSING = new HashSet<>();
	private static final Map<Long, Long> SEEN = new HashMap<>();
	private static String world, dimension;
	private static long lastSave;

	public static boolean on() {
		HudConfig cfg = AerionClient.hud();
		return cfg == null || HudConfig.bool(cfg.feature("mapMemory"), "enabled", true);
	}

	private static String worldKey(Minecraft mc) {
		String raw;
		if (mc.getCurrentServer() != null && !mc.isLocalServer()) raw = "server:" + mc.getCurrentServer().ip.toLowerCase(java.util.Locale.ROOT);
		else if (mc.getSingleplayerServer() != null) raw = "world:" + mc.getSingleplayerServer().getWorldData().getLevelName();
		else return null;
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
			StringBuilder s = new StringBuilder();
			for (int i = 0; i < 8; i++) s.append(String.format("%02x", d[i]));
			return s.toString();
		} catch (Exception e) {
			return null;
		}
	}

	private static Path dir(String world, String dimension) {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("aerion-map").resolve(world).resolve(dimension.replace(':', '_').replaceAll("[^a-z0-9_.-]", "_"));
	}

	/** Every tick: follows world and dimension changes and looks at a few chunks around the player. */
	public static void tick(Minecraft mc) {
		String w = mc.level == null ? null : worldKey(mc);
		String d = mc.player == null ? null : Waypoints.dimension(mc.player);
		if (w == null || d == null || !on()) {
			if (world != null) flush(true);
			world = null;
			dimension = null;
			return;
		}
		if (!w.equals(world) || !d.equals(dimension)) {
			if (world != null) flush(true);
			world = w;
			dimension = d;
		}
		long started = System.nanoTime();
		try {
			record(mc.level, mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4);
		} finally {
			Cost.part("mapMemory").add(started);
		}
		if (System.currentTimeMillis() - lastSave > 30_000) flush(false);
	}

	/** The nearest chunks not looked at for a while (a few per tick). */
	private static void record(ClientLevel level, int pcx, int pcz) {
		long now = System.currentTimeMillis();
		int done = 0;
		for (int r = 0; r <= RADIUS && done < CHUNKS_PER_TICK; r++) {
			for (int dz = -r; dz <= r && done < CHUNKS_PER_TICK; dz++) {
				for (int dx = -r; dx <= r && done < CHUNKS_PER_TICK; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					int cx = pcx + dx, cz = pcz + dz;
					long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
					Long seen = SEEN.get(key);
					if (seen != null && now - seen < AGAIN_MS) continue;
					if (!level.hasChunk(cx, cz)) continue;
					Tile t = tile(cx >> 4, cz >> 4, true);
					if (t == null) return; // still being read: next tick
					int[] px = MapColors.sample(level, cx << 4, cz << 4, 16, 16, 1);
					int ox = (cx & 15) << 4, oz = (cz & 15) << 4;
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							int c = px[z * 16 + x];
							if (c != 0) t.px[(oz + z) * TILE + ox + x] = c;
						}
					}
					t.dirty = true;
					SEEN.put(key, now);
					done++;
				}
			}
		}
		if (SEEN.size() > 20_000) SEEN.clear();
	}

	private static String name(int tx, int tz) {
		return world + "/" + dimension + "/" + tx + "." + tz;
	}

	/** A tile, asked to be read from disk when it isn't in memory (null until it is there). */
	private static synchronized Tile tile(int tx, int tz, boolean create) {
		String key = name(tx, tz);
		if (TILES.containsKey(key)) return TILES.get(key);
		if (MISSING.contains(key)) {
			if (!create) return null;
			MISSING.remove(key);
			Tile t = new Tile();
			TILES.put(key, t);
			trim();
			return t;
		}
		TILES.put(key, null);
		Path file = dir(world, dimension).resolve(tx + "." + tz + ".png");
		IO.execute(() -> {
			Tile t = null;
			try {
				if (Files.exists(file)) {
					BufferedImage img = ImageIO.read(file.toFile());
					if (img != null && img.getWidth() == TILE && img.getHeight() == TILE) {
						t = new Tile();
						img.getRGB(0, 0, TILE, TILE, t.px, 0, TILE);
					}
				}
			} catch (Exception e) {
				AerionClient.warnOnce("map-memory-read", e);
			}
			synchronized (MapMemory.class) {
				if (t != null) TILES.put(key, t);
				else {
					TILES.remove(key);
					MISSING.add(key);
				}
				trim();
			}
		});
		return null;
	}

	/** Keeps memory bounded: the least recently used tiles are written out and let go. */
	private static void trim() {
		while (TILES.size() > KEEP_TILES) {
			var it = TILES.entrySet().iterator();
			var e = it.next();
			if (e.getValue() != null && e.getValue().dirty) write(e.getKey(), e.getValue().px.clone());
			it.remove();
		}
	}

	/** Writes changed tiles (on the file thread); `all` also forgets them (leaving a world). */
	private static synchronized void flush(boolean all) {
		lastSave = System.currentTimeMillis();
		for (var e : TILES.entrySet()) {
			Tile t = e.getValue();
			if (t != null && t.dirty) {
				t.dirty = false;
				write(e.getKey(), t.px.clone());
			}
		}
		if (all) {
			TILES.clear();
			MISSING.clear();
			SEEN.clear();
		}
	}

	private static void write(String key, int[] px) {
		Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("aerion-map").resolve(fileOf(key));
		IO.execute(() -> {
			try {
				Files.createDirectories(file.getParent());
				BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
				img.setRGB(0, 0, TILE, TILE, px, 0, TILE);
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				ImageIO.write(img, "png", tmp.toFile());
				Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				AerionClient.warnOnce("map-memory-write", e);
			}
		});
	}

	/** `world/dimension/x.z` → `world/<dimension folder>/x.z.png`. */
	private static String fileOf(String key) {
		String[] p = key.split("/", 3);
		return p[0] + "/" + p[1].replace(':', '_').replaceAll("[^a-z0-9_.-]", "_") + "/" + p[2] + ".png";
	}

	/**
	 * Fills the pixels a live sample has no colour for (0) with remembered ones, a little darker so
	 * the present stands out: `nx` × `nz` pixels, one per `step` blocks from (`x0`, `z0`).
	 */
	public static void fill(int[] px, int x0, int z0, int nx, int nz, int step) {
		if (world == null || dimension == null) return;
		long started = System.nanoTime();
		try {
			for (int dz = 0; dz < nz; dz++) {
				int z = z0 + dz * step, tz = Math.floorDiv(z, TILE);
				Tile t = null;
				int lastTx = Integer.MIN_VALUE;
				for (int dx = 0; dx < nx; dx++) {
					int i = dz * nx + dx;
					if (px[i] != 0) continue;
					int x = x0 + dx * step, tx = Math.floorDiv(x, TILE);
					if (tx != lastTx) {
						t = tile(tx, tz, false);
						lastTx = tx;
					}
					if (t == null) continue;
					int c = t.px[Math.floorMod(z, TILE) * TILE + Math.floorMod(x, TILE)];
					if (c != 0) px[i] = 0xFF000000 | MapColors.scale(c, 0.78f);
				}
			}
		} finally {
			Cost.part("mapMemory").add(started);
		}
	}
}
