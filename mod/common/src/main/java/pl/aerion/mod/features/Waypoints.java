package pl.aerion.mod.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Chroma;
import pl.aerion.mod.hud.HudConfig;

/**
 * The player's own waypoints, per world or server, kept in the game folder (aerion-waypoints.json).
 * Labels stay readable at any distance: far points are drawn closer, along the same line of sight.
 */
public final class Waypoints {
	private Waypoints() {}

	public static final class Point {
		public String name, dimension, color = "#55aaff";
		public int x, y, z;
		public boolean enabled = true;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final double LABEL_DISTANCE = 32;
	private static JsonObject file;
	private static String loadedKey;
	private static List<Point> points = new ArrayList<>();

	private static Path path() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("aerion-waypoints.json");
	}

	private static String key() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getCurrentServer() != null && !mc.isLocalServer()) return "server:" + mc.getCurrentServer().ip;
		return mc.getSingleplayerServer() != null ? "world:" + mc.getSingleplayerServer().getWorldData().getLevelName() : null;
	}

	/** This world's points; loaded when the world changes. */
	public static List<Point> all() {
		String key = key();
		if (key == null) return List.of();
		if (!key.equals(loadedKey)) {
			loadedKey = key;
			points = new ArrayList<>();
			try {
				file = Files.exists(path()) ? JsonParser.parseString(Files.readString(path(), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
				if (file.get(key) != null) {
					for (var e : file.getAsJsonArray(key)) points.add(GSON.fromJson(e, Point.class));
				}
			} catch (Exception e) {
				AerionClient.warnOnce("waypoints", e);
				file = new JsonObject();
			}
		}
		return points;
	}

	public static void save() {
		if (loadedKey == null || file == null) return;
		try {
			file.add(loadedKey, GSON.toJsonTree(points));
			Files.writeString(path(), GSON.toJson(file), StandardCharsets.UTF_8);
		} catch (Exception e) {
			AerionClient.warnOnce("waypoints", e);
		}
	}

	/** A new point where the player stands. */
	public static Point addHere(String name) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return null;
		all();
		Point w = new Point();
		w.name = name + " " + (points.size() + 1);
		w.x = p.getBlockX();
		w.y = p.getBlockY();
		w.z = p.getBlockZ();
		w.dimension = dimension(p);
		points.add(w);
		save();
		return w;
	}

	public static String dimension(LocalPlayer p) {
		return p.level().dimension().identifier().toString();
	}

	public static void remove(Point w) {
		points.remove(w);
		save();
	}

	public static int color(Point w) {
		return 0xFF000000 | Chroma.parse(w.color, 0xFF55AAFF);
	}

	private static boolean flag(JsonObject f, String key, boolean fallback) {
		return "1".equals(HudConfig.opt(f, key, fallback ? "1" : "0"));
	}

	public static boolean showOnMinimap() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return false;
		JsonObject f = cfg.feature("waypoints");
		return HudConfig.bool(f, "enabled", false) && flag(f, "minimap", true);
	}

	/** Called while the game collects the frame's debug shapes. */
	public static void emit(float partial) {
		HudConfig cfg = AerionClient.hud();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (cfg == null || p == null) return;
		JsonObject f = cfg.feature("waypoints");
		if (!HudConfig.bool(f, "enabled", false)) return;
		boolean showDistance = flag(f, "distance", true), beam = flag(f, "beam", true);
		int scalePercent;
		try {
			scalePercent = Math.max(40, Math.min(300, Integer.parseInt(HudConfig.opt(f, "scale", "100"))));
		} catch (NumberFormatException e) {
			scalePercent = 100;
		}
		Vec3 eye = p.getEyePosition(partial);
		String here = dimension(p);
		for (Point w : all()) {
			if (!w.enabled || !here.equals(w.dimension)) continue;
			Vec3 target = new Vec3(w.x + 0.5, w.y + 1.5, w.z + 0.5);
			Vec3 toward = target.subtract(eye);
			double distance = toward.length();
			if (distance < 1.5) continue;
			int color = color(w);
			double shownAt = Math.min(distance, LABEL_DISTANCE);
			Vec3 at = eye.add(toward.normalize().scale(shownAt));
			float scale = (float) (TextGizmo.Style.DEFAULT_SCALE * scalePercent / 100.0 * Math.max(1, shownAt / 5));
			String text = showDistance ? w.name + "  " + Math.round(distance) + " m" : w.name;
			Gizmos.billboardText(text, at, TextGizmo.Style.forColorAndCentered(color).withScale(scale)).setAlwaysOnTop();
			if (beam && distance < LABEL_DISTANCE) {
				Gizmos.line(new Vec3(w.x + 0.5, w.y, w.z + 0.5), new Vec3(w.x + 0.5, w.y + 24, w.z + 0.5), color, 2f);
			}
		}
	}
}
