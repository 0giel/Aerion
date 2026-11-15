package pl.aerion.mod.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Txt;
import pl.aerion.mod.features.Waypoints;
import pl.aerion.mod.platform.Gfx;
import pl.aerion.mod.platform.Textures;

/**
 * A map of the terrain around the player: square or round, north up or turning with the player.
 * It shows blocks only — never other players or mobs (a radar is banned on many servers). The
 * terrain is sampled once a second or when the player walks off the sample; the picture is rebuilt
 * from that only when the player moves a block or turns, and drawn as one texture.
 */
final class Minimap implements Hud.Module {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("aerion", "minimap");
	private static final int EMPTY = 0xFF1A1A1A;

	/** The sampled terrain: a square of `grid` blocks with its north-west corner at (gx, gz). */
	private int[] colors = new int[0];
	private int grid, gx, gz;
	private long sampled;
	private boolean resampled;

	/** What the texture holds now, so it is rebuilt only when something changed. */
	private int[] pixels = new int[0];
	private int builtN = -1, builtX, builtZ;
	private float builtYaw = Float.NaN;
	private boolean builtRound, builtRotate, ready;

	private int side(Hud.Ctx c) {
		return Math.max(48, Math.min(160, parse(c.opt("size", "80"), 80)));
	}

	private int zoom(Hud.Ctx c) {
		return Math.max(1, Math.min(4, parse(c.opt("zoom", "1"), 1)));
	}

	private static int parse(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (Exception e) {
			return fallback;
		}
	}

	/** A round map has no box around it, only its own edge. */
	@Override
	public boolean boxed(Hud.Ctx c) {
		return !"round".equals(c.opt("shape", "square"));
	}

	@Override
	public int[] size(Hud.Ctx c) {
		int p = c.style().padding();
		int s = side(c) / zoom(c) * zoom(c);
		return new int[] {s + 2 * p, s + 2 * p + (c.flag("coords", true) ? Hud.LINE + 1 : 0)};
	}

	private void sample(Minecraft mc, LocalPlayer player, int n) {
		ClientLevel level = mc.level;
		if (level == null) return;
		// Enough around the player for any turn of the map (half the diagonal), plus a margin to walk
		// into before the next sample.
		int r = (int) Math.ceil(n * 0.71 + n / 8.0) + 3;
		grid = 2 * r + 1;
		gx = player.getBlockX() - r;
		gz = player.getBlockZ() - r;
		colors = MapColors.sample(level, gx, gz, grid, grid, 1);
		resampled = true;
	}

	@Override
	public void draw(Hud.Ctx c, Gfx g) {
		int p = c.style().padding();
		int z = zoom(c);
		int n = side(c) / z, s = n * z;
		boolean round = "round".equals(c.opt("shape", "square")), rotate = c.flag("rotate", false);
		LocalPlayer player = c.player();
		long now = System.currentTimeMillis();
		if (player != null) {
			int r = (grid - 1) / 2;
			boolean walkedOff = Math.abs(player.getBlockX() - (gx + r)) > n / 8 || Math.abs(player.getBlockZ() - (gz + r)) > n / 8;
			// The terrain itself rarely changes: once a second, or sooner when walking off the sample.
			if (now - sampled > 1000 || walkedOff || grid < n) {
				sample(c.mc(), player, n);
				sampled = now;
			}
		}
		float yaw = player != null ? player.getYRot() : 0;
		double a = Math.toRadians(yaw);
		// The player's forward and right on the ground (x east, z south).
		double fx = -Math.sin(a), fz = Math.cos(a), rx = -Math.cos(a), rz = -Math.sin(a);
		if (player != null) build(player, n, round, rotate, yaw, fx, fz, rx, rz);

		if (!round) g.fill(p - 1, p - 1, p + s + 1, p + s + 1, 0xC0000000);
		if (ready) g.texture(TEXTURE, p, p, s, s, n, n, 0xFFFFFFFF);
		// A smooth edge over the stepped one of the round picture.
		if (round) MapMarks.ring(g, p + s / 2f, p + s / 2f, s / 2f);
		float cx = p + s / 2f, cy = p + s / 2f;
		float half = s / 2f;
		int accent = c.theme() != null ? c.theme().accent : 0xFFFFFFFF;

		if (player != null && Waypoints.showOnMinimap()) {
			String here = Waypoints.dimension(player);
			for (Waypoints.Point w : Waypoints.all()) {
				double[] at = w.enabled ? Waypoints.position(w, here) : null;
				if (at == null) continue;
				double dx = at[0] + 0.5 - player.getX(), dz = at[2] + 0.5 - player.getZ();
				double ox = rotate ? dx * rx + dz * rz : dx, oy = rotate ? -(dx * fx + dz * fz) : dz;
				ox *= z;
				oy *= z;
				double limit = half - 5;
				double far = round ? Math.hypot(ox, oy) / limit : Math.max(Math.abs(ox), Math.abs(oy)) / limit;
				if (far <= 1) {
					MapMarks.pin(g, Math.round(cx + (float) ox), Math.round(cy + (float) oy), w, 3, false);
				} else {
					MapMarks.edge(g, cx + (float) (ox / far), cy + (float) (oy / far), Math.atan2(ox, -oy), w);
				}
			}
		}
		// The player: up when the map turns with them, otherwise towards where they look.
		MapMarks.arrow(g, cx, cy, rotate ? 0 : Math.atan2(fx, -fz), 5.5f, Colors.alpha(accent, 255));

		if (c.flag("north", true)) {
			String[] names = {"N", "E", "S", "W"};
			double[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
			for (int i = 0; i < 4; i++) {
				double ox = rotate ? dirs[i][0] * rx + dirs[i][1] * rz : dirs[i][0];
				double oy = rotate ? -(dirs[i][0] * fx + dirs[i][1] * fz) : dirs[i][1];
				float lx = cx + (float) (ox * (half - 5)), ly = cy + (float) (oy * (half - 5));
				float size = i == 0 ? 7f : 5.5f;
				Txt.center(g, names[i], lx + 0.6f, ly + 0.6f, size, 0xC0000000);
				Txt.center(g, names[i], lx, ly, size, i == 0 ? 0xFFFFFFFF : 0xD0E0E0E0);
			}
		}
		if (c.flag("coords", true) && player != null && !pl.aerion.mod.features.Streamer.active()) {
			String t = player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ();
			Hud.text(c, g, t, p + (s - Hud.width(c, t)) / 2, p + s + 2, c.style().text(), c.style().shadow());
		}
	}

	/** Rebuilds the texture from the sampled terrain when the player moved a block or turned. */
	private void build(LocalPlayer player, int n, boolean round, boolean rotate, float yaw, double fx, double fz, double rx, double rz) {
		int bx = player.getBlockX(), bz = player.getBlockZ();
		boolean same = !resampled && n == builtN && bx == builtX && bz == builtZ && round == builtRound && rotate == builtRotate
				&& (!rotate || Math.abs(yaw - builtYaw) < 1f);
		if (same) return;
		if (pixels.length != n * n) pixels = new int[n * n];
		double px = bx + 0.5, pz = bz + 0.5, mid = n / 2.0, radius = n / 2.0;
		for (int oy = 0; oy < n; oy++) {
			for (int ox = 0; ox < n; ox++) {
				double dx = ox + 0.5 - mid, dy = oy + 0.5 - mid;
				int i = oy * n + ox;
				if (round) {
					double d = Math.hypot(dx, dy);
					if (d > radius - 0.5) {
						pixels[i] = 0;
						continue;
					}
				}
				double wx = rotate ? dx * rx - dy * fx : dx, wz = rotate ? dx * rz - dy * fz : dy;
				int cx = (int) Math.floor(px + wx) - gx, cz = (int) Math.floor(pz + wz) - gz;
				int col = cx >= 0 && cz >= 0 && cx < grid && cz < grid ? colors[cz * grid + cx] : 0;
				pixels[i] = col != 0 ? col : EMPTY;
			}
		}
		Textures.update(TEXTURE, n, n, pixels);
		ready = true;
		resampled = false;
		builtN = n;
		builtX = bx;
		builtZ = bz;
		builtYaw = yaw;
		builtRound = round;
		builtRotate = rotate;
	}
}
