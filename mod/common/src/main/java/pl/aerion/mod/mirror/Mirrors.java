package pl.aerion.mod.mirror;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.platform.Textures;

/**
 * Windows of the computer shown on panels in the world, placed and moved by the player like in
 * VR: each panel is a live picture of one window, flat or bent around where the player stood.
 * Looking at a panel, clicks and the wheel go to the window; a key moves it, another types into
 * it. Panels are kept per world or server in the game folder (aerion-mirrors.json). Pictures are
 * read on a thread of their own, about twelve times a second, only while panels exist.
 */
public final class Mirrors {
	private Mirrors() {}

	/** One panel. Position is the centre; it faces the way the player looked when placing it. */
	public static final class Panel {
		public String title = "", cls = "";
		public double x, y, z;
		public float yaw, pitch;
		/** Width in blocks; the height follows the window's shape. */
		public float width = 2.4f;
		/** Bent around the player (radius = distance when placed), or flat. */
		public boolean curved = true;
		public float radius = 2.2f;
		/** Set once the first picture gave the panel a size that suits the window's shape. */
		public boolean sized;
		transient long win;
		transient Identifier tex;
		transient int texW, texH;
		transient volatile WindowSource.Frame pending;
		transient float aspect = 0.6f;

		public float height() {
			return width * aspect;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int FPS = 12, MAX_WIDTH = 1280;
	private static WindowSource source;
	private static boolean sourceTried;
	private static List<Panel> panels = new ArrayList<>();
	private static String loadedKey;
	private static JsonObject file;
	private static ScheduledExecutorService worker;
	private static int serial;
	private static long lastResolve;

	public static synchronized WindowSource source() {
		if (!sourceTried) {
			sourceTried = true;
			source = WindowSource.forThisComputer();
		}
		return source;
	}

	// --- Storage ------------------------------------------------------------------------------

	private static Path path() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("aerion-mirrors.json");
	}

	private static String key() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getCurrentServer() != null && !mc.isLocalServer()) return "server:" + mc.getCurrentServer().ip;
		return mc.getSingleplayerServer() != null ? "world:" + mc.getSingleplayerServer().getWorldData().getLevelName() : null;
	}

	/** This world's panels; loaded when the world changes. */
	public static synchronized List<Panel> all() {
		String key = key();
		if (key == null) return List.of();
		if (!key.equals(loadedKey)) {
			loadedKey = key;
			panels = new ArrayList<>();
			try {
				file = Files.exists(path()) ? JsonParser.parseString(Files.readString(path(), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
				if (file.get(key) != null) for (var e : file.getAsJsonArray(key)) panels.add(GSON.fromJson(e, Panel.class));
			} catch (Exception e) {
				AerionClient.warnOnce("mirrors", e);
				file = new JsonObject();
			}
		}
		return panels;
	}

	public static synchronized void save() {
		if (loadedKey == null || file == null) return;
		try {
			file.add(loadedKey, GSON.toJsonTree(panels));
			Files.writeString(path(), GSON.toJson(file), StandardCharsets.UTF_8);
		} catch (Exception e) {
			AerionClient.warnOnce("mirrors", e);
		}
	}

	// --- Placing ------------------------------------------------------------------------------

	/** A new panel for `w`, a little in front of the player and facing them. */
	public static void place(WindowSource.Win w) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return;
		Panel panel = new Panel();
		panel.title = w.title();
		panel.cls = w.cls();
		panel.win = w.id();
		putInFront(panel, p, 2.2f);
		synchronized (Mirrors.class) {
			all().add(panel);
		}
		save();
		start();
	}

	private static void putInFront(Panel panel, LocalPlayer p, float distance) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1f);
		panel.x = eye.x + look.x * distance;
		panel.y = eye.y + look.y * distance;
		panel.z = eye.z + look.z * distance;
		panel.yaw = p.getYRot();
		panel.pitch = p.getXRot();
		panel.radius = distance;
	}

	public static void remove(Panel panel) {
		synchronized (Mirrors.class) {
			all().remove(panel);
		}
		if (panel.tex != null) Textures.release(panel.tex);
		save();
	}

	// --- Pictures -----------------------------------------------------------------------------

	private static synchronized void start() {
		if (worker != null) return;
		worker = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Aerion mirrors");
			t.setDaemon(true);
			return t;
		});
		worker.scheduleWithFixedDelay(Mirrors::captureAll, 0, 1000 / FPS, TimeUnit.MILLISECONDS);
	}

	/** On the capture thread: a new picture for every panel whose window is found. */
	private static void captureAll() {
		try {
			Minecraft mc = Minecraft.getInstance();
			List<Panel> list;
			synchronized (Mirrors.class) {
				list = loadedKey != null && mc.level != null ? new ArrayList<>(panels) : List.of();
			}
			if (list.isEmpty() || mc.isPaused()) return;
			WindowSource src = source();
			if (src == null) return;
			long now = System.currentTimeMillis();
			boolean resolve = now - lastResolve > 2000 && list.stream().anyMatch(p -> p.win == 0);
			List<WindowSource.Win> wins = resolve ? src.list() : null;
			if (resolve) lastResolve = now;
			for (Panel p : list) {
				if (p.win == 0 && wins != null) p.win = find(wins, p);
				if (p.win == 0 || p.pending != null) continue;
				WindowSource.Frame f = src.capture(p.win, MAX_WIDTH);
				if (f == null) {
					// The window is gone or hidden: look for it again later.
					if (!src.list().stream().anyMatch(w -> w.id() == p.win)) p.win = 0;
					continue;
				}
				p.pending = f;
			}
		} catch (Throwable e) {
			AerionClient.warnOnce("mirror-capture", e);
		}
	}

	private static long find(List<WindowSource.Win> wins, Panel p) {
		for (WindowSource.Win w : wins) if (w.title().equals(p.title)) return w.id();
		for (WindowSource.Win w : wins) if (!p.cls.isEmpty() && w.cls().equals(p.cls)) return w.id();
		return 0;
	}

	/** On the render thread: new pictures become the panels' textures. */
	private static void upload(Panel p) {
		WindowSource.Frame f = p.pending;
		if (f == null) return;
		p.pending = null;
		if (p.tex == null || p.texW != f.width() || p.texH != f.height()) {
			if (p.tex != null) Textures.release(p.tex);
			p.tex = Identifier.fromNamespaceAndPath("aerion", "mirror/" + (++serial));
			p.texW = f.width();
			p.texH = f.height();
		}
		Textures.update(p.tex, f.width(), f.height(), f.argb());
		p.aspect = f.height() / (float) f.width();
		if (!p.sized) {
			// A tall window would reach the ground: at most about 1.6 blocks high at first.
			p.width = Math.min(p.width, 1.6f / p.aspect);
			p.sized = true;
			save();
		}
	}

	// --- Drawing ------------------------------------------------------------------------------

	private static final int BRIGHT = 0xF000F0;

	/** Called while the world's entities are drawn; the pose is relative to the camera. */
	public static void submit(PoseStack pose, SubmitNodeCollector out) {
		List<Panel> list;
		synchronized (Mirrors.class) {
			if (panels.isEmpty() || loadedKey == null) return;
			list = new ArrayList<>(all());
		}
		Vec3 cam = pl.aerion.mod.platform.Game.cameraPosition();
		Hit aim = aim();
		for (Panel p : list) {
			upload(p);
			if (p.tex == null) continue;
			pose.pushPose();
			pose.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
			pose.mulPose(new Matrix4f().rotateY((float) Math.toRadians(-p.yaw)).rotateX((float) Math.toRadians(p.pitch)));
			float w = p.width, h = p.height();
			out.submitCustomGeometry(pose, RenderTypes.text(p.tex), (pp, vc) -> quads(pp, vc, p, w, h, 0xFFFFFFFF));
			if (aim != null && aim.panel == p) {
				Panel dot = p;
				out.submitCustomGeometry(pose, RenderTypes.text(DOT), (pp, vc) -> cursor(pp, vc, dot, aim.u, aim.v));
			}
			pose.popPose();
		}
	}

	/** Point (u, v) of the panel (0–1 each, from the top left) in its own space. */
	private static float[] at(Panel p, float u, float v, float lift) {
		float w = p.width, h = p.height();
		float y = (0.5f - v) * h;
		if (!p.curved || p.radius <= 0.3f) return new float[] {(0.5f - u) * w, y, -lift};
		float a = (u - 0.5f) * w / p.radius;
		float r = p.radius - lift;
		return new float[] {(float) (-r * Math.sin(a)), y, (float) (-p.radius + r * Math.cos(a))};
	}

	private static void quads(PoseStack.Pose pose, VertexConsumer vc, Panel p, float w, float h, int color) {
		int n = p.curved ? Math.max(2, Math.round(w * 8)) : 1;
		for (int i = 0; i < n; i++) {
			float u0 = i / (float) n, u1 = (i + 1) / (float) n;
			float[] a = at(p, u0, 0, 0), b = at(p, u1, 0, 0), c = at(p, u1, 1, 0), d = at(p, u0, 1, 0);
			// Both sides, so the panel is seen from behind too.
			vertex(vc, pose, a, u0, 0, color);
			vertex(vc, pose, d, u0, 1, color);
			vertex(vc, pose, c, u1, 1, color);
			vertex(vc, pose, b, u1, 0, color);
			vertex(vc, pose, b, u1, 0, color);
			vertex(vc, pose, c, u1, 1, color);
			vertex(vc, pose, d, u0, 1, color);
			vertex(vc, pose, a, u0, 0, color);
		}
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float[] q, float u, float v, int color) {
		vc.addVertex(pose, q[0], q[1], q[2]).setColor(color).setUv(u, v).setLight(BRIGHT);
	}

	private static final Identifier DOT = Identifier.fromNamespaceAndPath("aerion", "mirror/dot");
	private static boolean dotReady;

	/** The pointer on the panel: a small round dot just in front of it. */
	private static void cursor(PoseStack.Pose pose, VertexConsumer vc, Panel p, float u, float v) {
		if (!dotReady) return;
		float s = 0.035f / Math.max(0.01f, p.width), sv = 0.035f / Math.max(0.01f, p.height());
		float[] a = at(p, u - s, v - sv, 0.01f), b = at(p, u + s, v - sv, 0.01f), c = at(p, u + s, v + sv, 0.01f), d = at(p, u - s, v + sv, 0.01f);
		for (float[][] q : new float[][][] {{a, d, c, b}, {b, c, d, a}}) {
			vertex(vc, pose, q[0], 0, 0, 0xFFFFFFFF);
			vertex(vc, pose, q[1], 0, 1, 0xFFFFFFFF);
			vertex(vc, pose, q[2], 1, 1, 0xFFFFFFFF);
			vertex(vc, pose, q[3], 1, 0, 0xFFFFFFFF);
		}
	}

	private static void ensureDot() {
		if (dotReady) return;
		int n = 32;
		int[] px = new int[n * n];
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				double d = Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0);
				if (d < n / 2.0 - 1) px[y * n + x] = d < n / 2.0 - 5 ? 0xFFFFFFFF : 0xFF202020;
			}
		}
		Textures.registerPixels(DOT, n, n, px);
		dotReady = true;
	}

	// --- Aiming -------------------------------------------------------------------------------

	/** Where the player looks on a panel. */
	public record Hit(Panel panel, float u, float v, double distance) {}

	/** The panel under the crosshair, nearest first; null when none. */
	public static Hit aim() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer pl = mc.player;
		if (pl == null) return null;
		List<Panel> list;
		synchronized (Mirrors.class) {
			if (panels.isEmpty() || loadedKey == null) return null;
			list = new ArrayList<>(panels);
		}
		Vec3 eye = pl.getEyePosition(), look = pl.getViewVector(1f);
		Hit best = null;
		for (Panel p : list) {
			Hit h = hit(p, eye, look);
			if (h != null && h.distance < 32 && (best == null || h.distance < best.distance)) best = h;
		}
		return best;
	}

	/** The look ray in the panel's space (the inverse of its turn), then the panel's shape. */
	private static Hit hit(Panel p, Vec3 eye, Vec3 look) {
		double[] o = local(p, eye.x - p.x, eye.y - p.y, eye.z - p.z), d = local(p, look.x, look.y, look.z);
		float w = p.width, h = p.height();
		double t;
		float u, v;
		if (!p.curved || p.radius <= 0.3f) {
			if (Math.abs(d[2]) < 1e-6) return null;
			t = -o[2] / d[2];
			if (t <= 0) return null;
			double hx = o[0] + d[0] * t, hy = o[1] + d[1] * t;
			u = (float) (0.5 - hx / w);
			v = (float) (0.5 - hy / h);
		} else {
			// The cylinder around (0, y, -r): the far side, where the panel is.
			double cz = -p.radius;
			double ox = o[0], oz = o[2] - cz;
			double A = d[0] * d[0] + d[2] * d[2], B = 2 * (ox * d[0] + oz * d[2]), C = ox * ox + oz * oz - p.radius * p.radius;
			double disc = B * B - 4 * A * C;
			if (A < 1e-9 || disc < 0) return null;
			t = (-B + Math.sqrt(disc)) / (2 * A);
			if (t <= 0) return null;
			double hx = ox + d[0] * t, hz = oz + d[2] * t, hy = o[1] + d[1] * t;
			double a = Math.atan2(-hx, hz);
			u = (float) (a * p.radius / w + 0.5);
			v = (float) (0.5 - hy / h);
		}
		if (u < 0 || u > 1 || v < 0 || v > 1) return null;
		ensureDot();
		return new Hit(p, u, v, t);
	}

	/** A world direction in the panel's space. */
	private static double[] local(Panel p, double x, double y, double z) {
		double yaw = Math.toRadians(p.yaw), pitch = Math.toRadians(p.pitch);
		// Undo the turn about Y (by -yaw), then the tilt about X (by pitch).
		double cy = Math.cos(yaw), sy = Math.sin(yaw);
		double x1 = x * cy + z * sy, z1 = -x * sy + z * cy;
		double cp = Math.cos(pitch), sp = Math.sin(pitch);
		double y2 = y * cp + z1 * sp, z2 = -y * sp + z1 * cp;
		return new double[] {x1, y2, z2};
	}

	// --- Input --------------------------------------------------------------------------------

	private static Panel grabbed;
	private static double grabDistance;

	/** A click while looking at a panel goes to its window; true when it was taken. */
	public static boolean click(int button) {
		Hit h = aim();
		if (h == null) return false;
		WindowSource src = source();
		if (src != null && h.panel.win != 0) src.click(h.panel.win, h.u, h.v, button);
		return true;
	}

	/** The wheel over a panel scrolls its window, or resizes it while it is held. */
	public static boolean scroll(double dy) {
		if (grabbed != null) {
			grabbed.width = (float) Math.max(0.6, Math.min(12, grabbed.width * (dy > 0 ? 1.1 : 1 / 1.1)));
			return true;
		}
		Hit h = aim();
		if (h == null) return false;
		WindowSource src = source();
		if (src != null && h.panel.win != 0) src.scroll(h.panel.win, h.u, h.v, (int) Math.signum(dy) * Math.max(1, (int) Math.round(Math.abs(dy))));
		return true;
	}

	/** Called every tick with the state of the grab key: a held panel follows the look. */
	public static void tick(Minecraft mc, boolean grabKey) {
		if (mc.player == null) {
			grabbed = null;
			return;
		}
		if (grabKey && grabbed == null) {
			Hit h = aim();
			if (h != null) {
				grabbed = h.panel;
				Vec3 eye = mc.player.getEyePosition();
				grabDistance = eye.distanceTo(new Vec3(grabbed.x, grabbed.y, grabbed.z));
			}
		} else if (!grabKey && grabbed != null) {
			grabbed = null;
			save();
		}
		if (grabbed != null) putInFront(grabbed, mc.player, (float) grabDistance);
	}

	public static boolean holding() {
		return grabbed != null;
	}

	/** The window the player looks at, for typing into it. */
	public static long aimedWindow() {
		Hit h = aim();
		return h == null ? 0 : h.panel.win;
	}
}
