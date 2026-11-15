package pl.aerion.mod;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.Gfx;
import pl.aerion.mod.platform.Textures;

/**
 * What the menus show behind them (Game look → Menu background): the aurora in the theme's
 * colours (the default), the game's own turning panorama tinted with the theme, or the player's
 * own world - a picture taken now and then while they play, blurred and drifting slowly.
 */
public final class MenuScene {
	private MenuScene() {}

	private static JsonObject menus() {
		pl.aerion.mod.hud.HudConfig cfg = AerionClient.hud();
		return cfg == null ? null : cfg.feature("menus");
	}

	private static String opt(String key, String fallback) {
		JsonObject f = menus();
		return f == null ? fallback : HudConfig.opt(f, key, fallback);
	}

	private static int number(String key, int fallback, int min, int max) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(opt(key, String.valueOf(fallback)))));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** "aurora", "panorama" or "world". */
	public static String mode() {
		return opt("scene", "aurora");
	}

	/** The game's panorama stays, with the theme laid over it. */
	public static boolean vanillaPanorama() {
		return "panorama".equals(mode());
	}

	/** The theme's colour over the panorama, stronger towards the bottom. */
	public static void tint(Gfx g, Theme t) {
		int dim = number("dim", 55, 0, 90) * 255 / 100;
		if (dim == 0) return;
		if (!white) {
			Textures.registerPixels(WHITE, 1, 1, new int[] {0xFFFFFFFF});
			white = true;
		}
		int w = g.width(), h = g.height(), bands = 24;
		int top = t.gradient[0], bottom = t.gradient[t.gradient.length - 1];
		for (int i = 0; i < bands; i++) {
			float f = (i + 0.5f) / bands;
			int y0 = h * i / bands, y1 = h * (i + 1) / bands;
			int a = Math.round(dim * (0.66f + 0.34f * f));
			g.texture(WHITE, 0, y0, w, y1 - y0, 1, 1, (a << 24) | (Colors.mix(top, bottom, f) & 0xFFFFFF));
		}
	}

	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("aerion", "menu/white");
	private static boolean white;

	// --- The player's world ---------------------------------------------------------------------

	private static final Identifier WORLD = Identifier.fromNamespaceAndPath("aerion", "menu/world");
	private static final int WIDTH = 640;
	private static int texW, texH, madeBlur = -1;
	private static long loadedStamp = -1, lastShot;
	private static boolean loading;
	private static int[] source;

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("aerion-menu-world.png");
	}

	/**
	 * Every tick in a world: about once a minute, while nothing covers the view, a picture of it
	 * is kept for the menus. Only with this background chosen.
	 */
	public static void tick(Minecraft mc) {
		if (!"world".equals(mode()) || mc.level == null || mc.player == null || pl.aerion.mod.platform.Game.screen() != null) return;
		long now = System.currentTimeMillis();
		if (lastShot == 0) lastShot = now - 50_000; // the first one ten seconds after joining
		if (now - lastShot < 60_000) return;
		lastShot = now;
		wantShot = true;
	}

	private static volatile boolean wantShot;

	/** Right after the world is drawn (before the HUD): takes the picture asked for in the tick. */
	public static void afterLevel() {
		if (!wantShot) return;
		wantShot = false;
		try {
			pl.aerion.mod.platform.Game.grabFrame(MenuScene::save);
		} catch (RuntimeException e) {
			AerionClient.warnOnce("menu-world-shot", e);
		}
	}

	/** Keeps the frame small, without its bottom edge (the hand), on a thread of its own. */
	private static void save(int[] argb, int w, int h) {
		Thread t = new Thread(() -> {
			try {
				int cw = w, ch = Math.round(h * 0.85f);
				float k = Math.min(1f, (float) WIDTH / cw);
				int ow = Math.max(1, Math.round(cw * k)), oh = Math.max(1, Math.round(ch * k));
				BufferedImage out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
				for (int y = 0; y < oh; y++) {
					int sy = Math.min(ch - 1, Math.round(y / k));
					for (int x = 0; x < ow; x++) out.setRGB(x, y, argb[sy * w + Math.min(cw - 1, Math.round(x / k))] & 0xFFFFFF);
				}
				Path tmp = file().resolveSibling("aerion-menu-world.png.tmp");
				ImageIO.write(out, "png", tmp.toFile());
				Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				AerionClient.warnOnce("menu-world-save", e);
			}
		}, "Aerion menu picture");
		t.setDaemon(true);
		t.start();
	}

	private static void save(pl.aerion.mod.platform.Game.Frame f) {
		save(f.argb(), f.width(), f.height());
	}

	/** Draws the world behind a menu; false when there is no picture yet (the aurora then shows). */
	public static boolean drawWorld(Gfx g, Theme t) {
		if (!"world".equals(mode())) return false;
		Path p = file();
		long stamp;
		try {
			stamp = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : -1;
		} catch (Exception e) {
			stamp = -1;
		}
		if (stamp < 0) return false;
		if (stamp != loadedStamp && !loading) load(p, stamp);
		int blur = number("blur", 6, 0, 20);
		if (source != null && blur != madeBlur) upload(blur);
		if (madeBlur < 0) return false;
		// Slowly drifting and breathing, a little bigger than the screen so no edge shows.
		double time = System.currentTimeMillis() / 1000.0;
		float zoom = 1.12f + 0.03f * (float) Math.sin(time / 23);
		int sw = g.width(), sh = g.height();
		float scale = Math.max((float) sw / texW, (float) sh / texH) * zoom;
		int dw = Math.round(texW * scale), dh = Math.round(texH * scale);
		int ox = Math.round((sw - dw) / 2f + (float) Math.sin(time / 31) * (dw - sw) / 2.2f);
		int oy = Math.round((sh - dh) / 2f + (float) Math.cos(time / 37) * (dh - sh) / 2.2f);
		g.texture(WORLD, ox, oy, dw, dh, texW, texH, 0xFFFFFFFF);
		tint(g, t);
		return true;
	}

	private static void load(Path p, long stamp) {
		loading = true;
		Thread th = new Thread(() -> {
			try {
				BufferedImage img = ImageIO.read(p.toFile());
				if (img != null) {
					int w = img.getWidth(), h = img.getHeight();
					int[] px = img.getRGB(0, 0, w, h, null, 0, w);
					synchronized (MenuScene.class) {
						texW = w;
						texH = h;
						source = px;
						madeBlur = -2; // makes the next frame upload it
					}
				}
				loadedStamp = stamp;
			} catch (Exception e) {
				AerionClient.warnOnce("menu-world-load", e);
				loadedStamp = stamp;
			} finally {
				loading = false;
			}
		}, "Aerion menu picture");
		th.setDaemon(true);
		th.start();
	}

	private static synchronized void upload(int blur) {
		int[] px = blur > 0 ? blur(source, texW, texH, blur) : source.clone();
		for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
		Textures.update(WORLD, texW, texH, px);
		madeBlur = blur;
	}

	/** Three box blurs (close to a Gaussian), across and then down. */
	private static int[] blur(int[] src, int w, int h, int r) {
		int[] a = src.clone(), b = new int[a.length];
		for (int pass = 0; pass < 3; pass++) {
			box(a, b, w, h, r, true);
			box(b, a, w, h, r, false);
		}
		return a;
	}

	private static void box(int[] in, int[] out, int w, int h, int r, boolean across) {
		int lines = across ? h : w, len = across ? w : h;
		for (int l = 0; l < lines; l++) {
			int sr = 0, sg = 0, sb = 0, n = 0;
			for (int i = -r; i <= r; i++) {
				int c = in[idx(l, Math.max(0, Math.min(len - 1, i)), w, across)];
				sr += (c >> 16) & 0xFF;
				sg += (c >> 8) & 0xFF;
				sb += c & 0xFF;
				n++;
			}
			for (int i = 0; i < len; i++) {
				out[idx(l, i, w, across)] = 0xFF000000 | (sr / n) << 16 | (sg / n) << 8 | (sb / n);
				int add = in[idx(l, Math.min(len - 1, i + r + 1), w, across)], drop = in[idx(l, Math.max(0, i - r), w, across)];
				sr += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
				sg += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
				sb += (add & 0xFF) - (drop & 0xFF);
			}
		}
	}

	private static int idx(int line, int i, int w, boolean across) {
		return across ? line * w + i : i * w + line;
	}
}
