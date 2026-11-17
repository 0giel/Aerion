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
		/** Goes along with the player: kept at the same turn and distance from them. */
		public boolean pinned;
		public float pinYaw, pinDistance, pinHeight;
		/** Seen by the other Aerion players nearby (on a server with the Aerion Screens mod). */
		public boolean shared;
		transient long win;
		/** Someone else's panel (read-only) and their name. */
		transient boolean remote;
		transient String owner;
		transient volatile boolean decoding;
		// Sending: its number on the server, what was last said about it, pictures.
		transient int netId, frameNo, audioSeq;
		transient long lastSent;
		transient pl.aerion.net.Proto.Panel sentAs;
		transient Identifier tex;
		transient int texW, texH;
		transient volatile WindowSource.Frame pending;
		transient float aspect = 0.6f;

		public float height() {
			return width * aspect;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** The player's settings (Aerion menu → Features → Windows in the world). */
	private static com.google.gson.JsonObject options() {
		pl.aerion.mod.hud.HudConfig cfg = AerionClient.hud();
		return cfg == null ? null : cfg.feature("mirrors");
	}

	private static String opt(String key, String fallback) {
		com.google.gson.JsonObject f = options();
		return f == null ? fallback : pl.aerion.mod.hud.HudConfig.opt(f, key, fallback);
	}

	/** The picture rate and width asked of sources that scale pictures themselves. */
	static int captureFps() {
		return fps();
	}

	static int captureWidth() {
		return maxWidth();
	}

	private static int fps() {
		try {
			return Math.max(2, Math.min(30, Integer.parseInt(opt("fps", "12"))));
		} catch (NumberFormatException e) {
			return 12;
		}
	}

	private static int maxWidth() {
		int local = switch (opt("quality", "medium")) {
			case "low" -> 640;
			case "high" -> 1920;
			default -> 1280;
		};
		// Shared pictures may ask for more than the panel itself.
		return Math.max(local, ScreenShare.captureWidth());
	}

	/** How solid the pictures are, 0.2–1. */
	private static float opacity() {
		try {
			return Math.max(20, Math.min(100, Integer.parseInt(opt("opacity", "100")))) / 100f;
		} catch (NumberFormatException e) {
			return 1f;
		}
	}

	private static boolean showTitle() {
		return !"0".equals(opt("title", "1"));
	}

	private static boolean showBar() {
		return !"0".equals(opt("handle", "1"));
	}
	private static WindowSource source;
	private static boolean sourceTried;
	private static List<Panel> panels = new ArrayList<>();
	private static String loadedKey;
	private static JsonObject file;
	private static ScheduledExecutorService worker;
	private static int serial;
	private static long lastResolve, lastCapture;

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

	/** Who may see this player's shared windows on this server: everyone, or the names listed. */
	public record Allow(boolean everyone, List<String> names) {}

	public static synchronized Allow allow() {
		all();
		JsonObject a = file != null && loadedKey != null && file.get("share:" + loadedKey) instanceof JsonObject o ? o : null;
		if (a == null) return new Allow(true, List.of());
		List<String> names = new ArrayList<>();
		if (a.get("names") instanceof com.google.gson.JsonArray arr) for (var e : arr) names.add(e.getAsString());
		return new Allow(!a.has("everyone") || a.get("everyone").getAsBoolean(), names);
	}

	public static synchronized void setAllow(boolean everyone, List<String> names) {
		all();
		if (file == null || loadedKey == null) return;
		JsonObject a = new JsonObject();
		a.addProperty("everyone", everyone);
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (String n : names) arr.add(n);
		a.add("names", arr);
		file.add("share:" + loadedKey, a);
		save();
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
		WindowSource src = source();
		if (src != null) w = src.open(w);
		if (w == null) return;
		Panel panel = new Panel();
		panel.title = w.title();
		panel.cls = w.cls();
		panel.win = w.id();
		panel.curved = !"straight".equals(opt("shape", "curve"));
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

	/** Moves a panel along its own up direction by `d` blocks. */
	private static void lift(Panel p, float d) {
		double yaw = Math.toRadians(p.yaw), pitch = Math.toRadians(p.pitch);
		p.x += -Math.sin(pitch) * Math.sin(yaw) * d;
		p.y += Math.cos(pitch) * d;
		p.z += Math.sin(pitch) * Math.cos(yaw) * d;
	}

	public static void remove(Panel panel) {
		synchronized (Mirrors.class) {
			all().remove(panel);
		}
		WindowSource src = source;
		if (src != null && panel.win != 0) src.closed(panel.win);
		ScreenShare.removed(panel);
		release(panel);
		save();
	}

	/** Lets go of a panel's picture. */
	static void release(Panel panel) {
		if (panel.tex != null) Textures.release(panel.tex);
		panel.tex = null;
	}

	/** Shares a panel with the players nearby, or stops. */
	public static void toggleShare(Panel panel) {
		panel.shared = !panel.shared;
		if (!panel.shared) ScreenShare.removed(panel);
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
		// Ticks often; each panel is read at the chosen rate.
		worker.scheduleWithFixedDelay(Mirrors::captureAll, 0, 30, TimeUnit.MILLISECONDS);
	}

	/** On the capture thread: a new picture for every panel whose window is found. */
	private static void captureAll() {
		long started = System.nanoTime();
		try {
			captureEach();
		} finally {
			pl.aerion.mod.Cost.background(System.nanoTime() - started);
		}
	}

	private static void captureEach() {
		try {
			Minecraft mc = Minecraft.getInstance();
			List<Panel> list;
			synchronized (Mirrors.class) {
				list = loadedKey != null && mc.level != null ? new ArrayList<>(panels) : List.of();
			}
			if (list.isEmpty() || mc.isPaused() || hidden) return;
			long due = 1000L / fps();
			if (System.currentTimeMillis() - lastCapture < due) return;
			lastCapture = System.currentTimeMillis();
			WindowSource src = source();
			if (src == null) return;
			long now = System.currentTimeMillis();
			boolean resolve = now - lastResolve > 2000 && list.stream().anyMatch(p -> p.win == 0);
			List<WindowSource.Win> wins = resolve ? src.list() : null;
			if (resolve) lastResolve = now;
			for (Panel p : list) {
				if (p.win == 0 && wins != null) p.win = find(wins, p);
				if (p.win == 0 || p.pending != null) continue;
				// A window that learned how to be found again (a choice the desktop remembers).
				String cls = src.clsOf(p.win);
				if (cls != null && !cls.equals(p.cls)) {
					p.cls = cls;
					save();
				}
				WindowSource.Frame f = src.capture(p.win, maxWidth());
				if (f == null) {
					// The window is gone or hidden: look for it again later.
					if (!src.list().stream().anyMatch(w -> w.id() == p.win)) p.win = 0;
					continue;
				}
				p.pending = f;
				ScreenShare.offer(p, f);
			}
		} catch (Throwable e) {
			AerionClient.warnOnce("mirror-capture", e);
		}
	}

	private static long find(List<WindowSource.Win> wins, Panel p) {
		// Windows from the desktop's dialog come back only through their remembered choice.
		if (p.cls.startsWith(PortalSource.PREFIX)) {
			WindowSource src = source();
			return src == null ? 0 : src.reopen(p.cls);
		}
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
			// The chosen height (Aerion menu, in tenths of a block), at most 2.4 blocks wide.
			float target = 1.6f;
			try {
				target = Math.max(0.5f, Math.min(6f, Integer.parseInt(opt("size", "16")) / 10f));
			} catch (NumberFormatException ignored) {
				// keep the default
			}
			p.width = Math.min(target * 1.5f, target / p.aspect);
			p.sized = true;
			save();
		}
	}

	// --- Drawing ------------------------------------------------------------------------------

	private static final int BRIGHT = 0xF000F0;

	/** Called while the world's entities are drawn; the pose is relative to the camera. */
	public static void submit(PoseStack pose, SubmitNodeCollector out) {
		long started = System.nanoTime();
		try {
			draw(pose, out);
		} finally {
			pl.aerion.mod.Cost.part("mirrors").add(started);
		}
	}

	private static void draw(PoseStack pose, SubmitNodeCollector out) {
		List<Panel> list;
		synchronized (Mirrors.class) {
			list = panels.isEmpty() || loadedKey == null ? new ArrayList<>() : new ArrayList<>(all());
		}
		List<Panel> theirs = ScreenShare.remote();
		if (hidden || (list.isEmpty() && theirs.isEmpty())) return;
		Vec3 cam = pl.aerion.mod.platform.Game.cameraPosition();
		LocalPlayer me = Minecraft.getInstance().player;
		for (Panel p : list) if (p.pinned && me != null && grabbed != p) follow(p, me, 1f);
		Hit aim = aim();
		int alpha = Math.round(opacity() * 255);
		for (Panel p : list) {
			upload(p);
			if (p.tex == null) continue;
			pose.pushPose();
			pose.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
			pose.mulPose(new Matrix4f().rotateY((float) Math.toRadians(-p.yaw)).rotateX((float) Math.toRadians(p.pitch)));
			float w = p.width, h = p.height();
			int tint = (alpha << 24) | 0xFFFFFF;
			out.submitCustomGeometry(pose, RenderTypes.text(p.tex), (pp, vc) -> quads(pp, vc, p, w, h, tint));
			if (showTitle()) title(pose, out, p);
			if (showBar() || grabbed == p) {
				Panel bar = p;
				boolean overBar = grabbed == p || (aim != null && aim.panel == p && aim.part == Part.BAR);
				boolean overClose = aim != null && aim.panel == p && aim.part == Part.CLOSE;
				int accent = AerionClient.theme() != null ? AerionClient.theme().accent : 0xFF7AA2FF;
				out.submitCustomGeometry(pose, RenderTypes.text(BAR), (pp, vc) -> handle(pp, vc, bar, overBar ? accent : 0xD0E8E8E8));
				out.submitCustomGeometry(pose, RenderTypes.text(CLOSE), (pp, vc) -> closer(pp, vc, bar, overClose ? 0xFFFF5A5A : 0xD0E8E8E8));
				boolean overPin = aim != null && aim.panel == p && aim.part == Part.PIN;
				out.submitCustomGeometry(pose, RenderTypes.text(PIN), (pp, vc) -> box(pp, vc, bar, pinBox(bar), overPin || bar.pinned ? accent : 0xD0E8E8E8));
				boolean overShape = aim != null && aim.panel == p && aim.part == Part.SHAPE;
				out.submitCustomGeometry(pose, RenderTypes.text(p.curved ? SHAPE_FLAT : SHAPE_CURVE), (pp, vc) -> box(pp, vc, bar, shapeBox(bar), overShape ? accent : 0xD0E8E8E8));
				boolean overSmaller = aim != null && aim.panel == p && aim.part == Part.SMALLER;
				boolean overBigger = aim != null && aim.panel == p && aim.part == Part.BIGGER;
				out.submitCustomGeometry(pose, RenderTypes.text(MINUS), (pp, vc) -> box(pp, vc, bar, sizeBox(bar, false), overSmaller ? accent : 0xD0E8E8E8));
				out.submitCustomGeometry(pose, RenderTypes.text(PLUS), (pp, vc) -> box(pp, vc, bar, sizeBox(bar, true), overBigger ? accent : 0xD0E8E8E8));
			}
			if (aim != null && aim.panel == p && aim.part == Part.PANEL) {
				Panel dot = p;
				out.submitCustomGeometry(pose, RenderTypes.text(POINTER), (pp, vc) -> cursor(pp, vc, dot, aim.u, aim.v));
			}
			pose.popPose();
		}
		// Others' panels: the picture and their name, nothing to take hold of.
		for (Panel p : theirs) {
			upload(p);
			if (p.tex == null) continue;
			pose.pushPose();
			pose.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
			pose.mulPose(new Matrix4f().rotateY((float) Math.toRadians(-p.yaw)).rotateX((float) Math.toRadians(p.pitch)));
			float w = p.width, h = p.height();
			out.submitCustomGeometry(pose, RenderTypes.text(p.tex), (pp, vc) -> quads(pp, vc, p, w, h, 0xFFFFFFFF));
			title(pose, out, p);
			pose.popPose();
		}
	}

	/** The window's name above the panel, small, on a dark strip. */
	private static void title(PoseStack pose, SubmitNodeCollector out, Panel p) {
		Minecraft mc = Minecraft.getInstance();
		String name = p.title.length() > 60 ? p.title.substring(0, 59) + "…" : p.title;
		if (p.owner != null) name = p.owner + ": " + name;
		float[] top = at(p, 0.5f, 0, 0);
		float scale = 0.0075f;
		pose.pushPose();
		pose.translate(top[0], top[1] + 0.09f, top[2]);
		// Text faces the other way and runs downwards: turn it to the player and stand it up.
		pose.mulPose(new Matrix4f().rotateY((float) Math.PI));
		pose.scale(scale, -scale, scale);
		var line = net.minecraft.network.chat.Component.empty();
		// Shared and seen by others now: a red mark in front.
		if (ScreenShare.isLive(p)) line.append(net.minecraft.network.chat.Component.literal("● " + pl.aerion.mod.Lang.tr("aerion.share.live") + "  ").withStyle(net.minecraft.ChatFormatting.RED));
		line.append(name);
		var text = line.getVisualOrderText();
		float x = -mc.font.width(text) / 2f;
		out.submitText(pose, x, 0, text, false, net.minecraft.client.gui.Font.DisplayMode.NORMAL, BRIGHT, 0xFFFFFFFF, 0xA0000000, 0);
		pose.popPose();
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

	/** The bar under a panel (to take it and move it) and the close button beside it, in blocks. */
	private static final float BAR_GAP = 0.07f, BAR_H = 0.075f, CLOSE_D = 0.13f;

	private static float barWidth(Panel p) {
		return Math.max(0.35f, Math.min(0.9f, p.width * 0.3f));
	}

	/** Where the bar and the close button are, as (u0, v0, u1, v1) of the panel. */
	private static float[] barBox(Panel p) {
		float w = p.width, h = p.height(), bw = barWidth(p);
		float v0 = 1 + BAR_GAP / h;
		return new float[] {0.5f - bw / 2 / w, v0, 0.5f + bw / 2 / w, v0 + BAR_H / h};
	}

	/** Right of the bar: the pin button, the shape button (rounded ↔ straight), the close button. */
	private static float[] pinBox(Panel p) {
		float w = p.width, h = p.height(), bw = barWidth(p);
		float u0 = 0.5f + (bw / 2 + 0.06f) / w;
		float v0 = 1 + (BAR_GAP + BAR_H / 2 - CLOSE_D / 2) / h;
		return new float[] {u0, v0, u0 + CLOSE_D / w, v0 + CLOSE_D / h};
	}

	private static float[] shapeBox(Panel p) {
		float w = p.width, h = p.height(), bw = barWidth(p);
		float u0 = 0.5f + (bw / 2 + 0.06f + CLOSE_D + 0.04f) / w;
		float v0 = 1 + (BAR_GAP + BAR_H / 2 - CLOSE_D / 2) / h;
		return new float[] {u0, v0, u0 + CLOSE_D / w, v0 + CLOSE_D / h};
	}

	private static float[] closeBox(Panel p) {
		float w = p.width, h = p.height(), bw = barWidth(p);
		float u0 = 0.5f + (bw / 2 + 0.06f + 2 * (CLOSE_D + 0.04f)) / w;
		float v0 = 1 + (BAR_GAP + BAR_H / 2 - CLOSE_D / 2) / h;
		return new float[] {u0, v0, u0 + CLOSE_D / w, v0 + CLOSE_D / h};
	}

	/** The − and + buttons left of the bar: + nearer the bar, − further out (so they read − +). */
	private static float[] sizeBox(Panel p, boolean bigger) {
		float w = p.width, h = p.height(), bw = barWidth(p);
		float u1 = 0.5f - (bw / 2 + 0.06f + (bigger ? 0 : CLOSE_D + 0.04f)) / w;
		float v0 = 1 + (BAR_GAP + BAR_H / 2 - CLOSE_D / 2) / h;
		return new float[] {u1 - CLOSE_D / w, v0, u1, v0 + CLOSE_D / h};
	}

	private static void handle(PoseStack.Pose pose, VertexConsumer vc, Panel p, int color) {
		box(pose, vc, p, barBox(p), color);
	}

	private static void closer(PoseStack.Pose pose, VertexConsumer vc, Panel p, int color) {
		box(pose, vc, p, closeBox(p), color);
	}

	/** A picture over the box (u0, v0, u1, v1) of the panel, both sides. */
	private static void box(PoseStack.Pose pose, VertexConsumer vc, Panel p, float[] b, int color) {
		if (!dotReady) return;
		float[] a = at(p, b[0], b[1], 0), c = at(p, b[2], b[1], 0), d = at(p, b[2], b[3], 0), e = at(p, b[0], b[3], 0);
		vertex(vc, pose, a, 0, 0, color);
		vertex(vc, pose, e, 0, 1, color);
		vertex(vc, pose, d, 1, 1, color);
		vertex(vc, pose, c, 1, 0, color);
		vertex(vc, pose, c, 1, 0, color);
		vertex(vc, pose, d, 1, 1, color);
		vertex(vc, pose, e, 0, 1, color);
		vertex(vc, pose, a, 0, 0, color);
	}

	private static final Identifier BAR = Identifier.fromNamespaceAndPath("aerion", "mirror/bar");
	private static final Identifier CLOSE = Identifier.fromNamespaceAndPath("aerion", "mirror/close");
	private static final Identifier MINUS = Identifier.fromNamespaceAndPath("aerion", "mirror/minus");
	/** The shape button shows what a click makes: a straight line, or an arc. */
	private static final Identifier SHAPE_FLAT = Identifier.fromNamespaceAndPath("aerion", "mirror/shape_flat");
	private static final Identifier SHAPE_CURVE = Identifier.fromNamespaceAndPath("aerion", "mirror/shape_curve");
	private static final Identifier POINTER = Identifier.fromNamespaceAndPath("aerion", "mirror/pointer");
	private static final Identifier PIN = Identifier.fromNamespaceAndPath("aerion", "mirror/pin");
	private static final Identifier PLUS = Identifier.fromNamespaceAndPath("aerion", "mirror/plus");
	private static final Identifier DOT = Identifier.fromNamespaceAndPath("aerion", "mirror/dot");
	private static boolean dotReady;

	/** The pointer's size in blocks (its picture is square; the arrow's tip is its top left corner). */
	private static final float POINTER_SIZE = 0.05f;
	private static final int PT = 64;

	/** The pointer on the panel: a small arrow, its tip exactly where the player looks. */
	private static void cursor(PoseStack.Pose pose, VertexConsumer vc, Panel p, float u, float v) {
		if (!dotReady) return;
		float s = POINTER_SIZE / Math.max(0.01f, p.width), sv = POINTER_SIZE / Math.max(0.01f, p.height());
		float[] a = at(p, u, v, 0.01f), b = at(p, u + s, v, 0.01f), c = at(p, u + s, v + sv, 0.01f), d = at(p, u, v + sv, 0.01f);
		for (float[][] q : new float[][][] {{a, d, c, b}, {b, c, d, a}}) {
			vertex(vc, pose, q[0], 0, 0, 0xFFFFFFFF);
			vertex(vc, pose, q[1], 0, 1, 0xFFFFFFFF);
			vertex(vc, pose, q[2], 1, 1, 0xFFFFFFFF);
			vertex(vc, pose, q[3], 1, 0, 0xFFFFFFFF);
		}
	}

	/** The usual arrow pointer: white with a dark edge, smoothed by sampling each pixel 4 × 4. */
	private static int[] pointerPicture() {
		// The arrow on a 16-unit grid, tip at (0, 0).
		float[][] shape = {{0, 0}, {0, 13.5f}, {3.3f, 10.6f}, {5.6f, 15.4f}, {7.6f, 14.5f}, {5.3f, 9.8f}, {9.6f, 9.8f}};
		float k = (PT - 2) / 16f;
		float[] xs = new float[shape.length], ys = new float[shape.length];
		for (int i = 0; i < shape.length; i++) {
			xs[i] = 1 + shape[i][0] * k;
			ys[i] = 1 + shape[i][1] * k;
		}
		int[] out = new int[PT * PT];
		float edge = 1.4f * k;
		for (int y = 0; y < PT; y++) {
			for (int x = 0; x < PT; x++) {
				int in = 0, near = 0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						float px = x + (sx + 0.5f) / 4, py = y + (sy + 0.5f) / 4;
						if (inside(xs, ys, px, py)) {
							in++;
							if (distanceToEdge(xs, ys, px, py) > edge) near++;
						}
					}
				}
				if (in == 0) continue;
				int a = Math.round(255f * in / 16), white = Math.round(255f * near / Math.max(1, in));
				out[y * PT + x] = (a << 24) | (white << 16) | (white << 8) | white;
			}
		}
		return out;
	}

	private static boolean inside(float[] xs, float[] ys, float px, float py) {
		boolean in = false;
		for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
			if ((ys[i] > py) != (ys[j] > py) && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) in = !in;
		}
		return in;
	}

	private static float distanceToEdge(float[] xs, float[] ys, float px, float py) {
		float best = Float.MAX_VALUE;
		for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
			float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
			float t = Math.max(0, Math.min(1, ((px - xs[j]) * dx + (py - ys[j]) * dy) / (dx * dx + dy * dy)));
			best = Math.min(best, (float) Math.hypot(px - xs[j] - t * dx, py - ys[j] - t * dy));
		}
		return best;
	}

	/** Whether the player looks at a window's picture (the game's crosshair then gives way). */
	public static boolean pointing() {
		Hit h = aim();
		return h != null && h.part == Part.PANEL;
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
		// The bar: a white pill, smoothed at its ends.
		int bw = 128, bh = 16;
		int[] bar = new int[bw * bh];
		for (int y = 0; y < bh; y++) {
			for (int x = 0; x < bw; x++) {
				double cx = Math.max(bh / 2.0, Math.min(bw - bh / 2.0, x + 0.5)), d = Math.hypot(x + 0.5 - cx, y + 0.5 - bh / 2.0);
				double a = Math.max(0, Math.min(1, bh / 2.0 - d));
				if (a > 0) bar[y * bw + x] = ((int) (a * 255) << 24) | 0xFFFFFF;
			}
		}
		Textures.registerPixels(BAR, bw, bh, bar);
		// The close button: a white disc with a cut-out ×.
		int[] close = new int[n * n];
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				double fx = x + 0.5 - n / 2.0, fy = y + 0.5 - n / 2.0, d = Math.hypot(fx, fy);
				double disc = Math.max(0, Math.min(1, n / 2.0 - 0.5 - d));
				double cross = Math.min(Math.abs(fx - fy), Math.abs(fx + fy)) / Math.sqrt(2);
				boolean onCross = cross < 1.6 && Math.abs(fx) < n * 0.22 && Math.abs(fy) < n * 0.22;
				if (disc > 0 && !onCross) close[y * n + x] = ((int) (disc * 255) << 24) | 0xFFFFFF;
			}
		}
		Textures.registerPixels(CLOSE, n, n, close);
		// − and +: white discs with the sign cut out.
		for (boolean plus : new boolean[] {false, true}) {
			int[] img = new int[n * n];
			for (int y = 0; y < n; y++) {
				for (int x = 0; x < n; x++) {
					double fx = x + 0.5 - n / 2.0, fy = y + 0.5 - n / 2.0, d = Math.hypot(fx, fy);
					double disc = Math.max(0, Math.min(1, n / 2.0 - 0.5 - d));
					boolean line = Math.abs(fy) < 1.8 && Math.abs(fx) < n * 0.24;
					boolean stem = plus && Math.abs(fx) < 1.8 && Math.abs(fy) < n * 0.24;
					if (disc > 0 && !line && !stem) img[y * n + x] = ((int) (disc * 255) << 24) | 0xFFFFFF;
				}
			}
			Textures.registerPixels(plus ? PLUS : MINUS, n, n, img);
		}
		// Shape buttons: a disc with a straight line or an arc cut out.
		for (boolean curve : new boolean[] {false, true}) {
			int[] img = new int[n * n];
			for (int y = 0; y < n; y++) {
				for (int x = 0; x < n; x++) {
					double fx = x + 0.5 - n / 2.0, fy = y + 0.5 - n / 2.0, d = Math.hypot(fx, fy);
					double disc = Math.max(0, Math.min(1, n / 2.0 - 0.5 - d));
					boolean mark;
					if (curve) {
						// An arc bowing towards the viewer, like the panel seen from above.
						double r = n * 0.42, ay = fy - n * 0.22;
						mark = Math.abs(Math.hypot(fx, ay + r) - r) < 1.6 && Math.abs(fx) < n * 0.26 && fy < n * 0.25;
					} else {
						mark = Math.abs(fy) < 1.6 && Math.abs(fx) < n * 0.26;
					}
					if (disc > 0 && !mark) img[y * n + x] = ((int) (disc * 255) << 24) | 0xFFFFFF;
				}
			}
			Textures.registerPixels(curve ? SHAPE_CURVE : SHAPE_FLAT, n, n, img);
		}
		Textures.registerPixels(POINTER, PT, PT, pointerPicture());
		// The pin button: a disc with a small person cut out (head and shoulders): "goes with me".
		int[] pin = new int[n * n];
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				double fx = x + 0.5 - n / 2.0, fy = y + 0.5 - n / 2.0, d = Math.hypot(fx, fy);
				double disc = Math.max(0, Math.min(1, n / 2.0 - 0.5 - d));
				boolean head = Math.hypot(fx, fy + n * 0.1) < n * 0.13;
				boolean body = fy > n * 0.08 && fy < n * 0.3 && Math.hypot(fx, (fy - n * 0.3) * 1.2) < n * 0.24;
				if (disc > 0 && !head && !body) pin[y * n + x] = ((int) (disc * 255) << 24) | 0xFFFFFF;
			}
		}
		Textures.registerPixels(PIN, n, n, pin);
		dotReady = true;
	}

	// --- Aiming -------------------------------------------------------------------------------

	/** Where the player looks on a panel. */
	public enum Part { PANEL, BAR, CLOSE, SMALLER, BIGGER, SHAPE, PIN }

	public record Hit(Panel panel, float u, float v, double distance, Part part) {}

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
		if (hidden) return null;
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
		ensureDot();
		if (u >= 0 && u <= 1 && v >= 0 && v <= 1) return new Hit(p, u, v, t, Part.PANEL);
		if (showBar() || grabbed == p) {
			// A little more room than drawn, so the bar is easy to catch.
			float[] b = barBox(p), c = closeBox(p);
			float pad = 0.04f / p.height();
			if (u >= b[0] && u <= b[2] && v >= b[1] - pad && v <= b[3] + pad) return new Hit(p, u, v, t, Part.BAR);
			if (u >= c[0] && u <= c[2] && v >= c[1] && v <= c[3]) return new Hit(p, u, v, t, Part.CLOSE);
			float[] sh = shapeBox(p);
			if (u >= sh[0] && u <= sh[2] && v >= sh[1] && v <= sh[3]) return new Hit(p, u, v, t, Part.SHAPE);
			float[] pn = pinBox(p);
			if (u >= pn[0] && u <= pn[2] && v >= pn[1] && v <= pn[3]) return new Hit(p, u, v, t, Part.PIN);
			float[] s = sizeBox(p, false), g = sizeBox(p, true);
			if (u >= s[0] && u <= s[2] && v >= s[1] && v <= s[3]) return new Hit(p, u, v, t, Part.SMALLER);
			if (u >= g[0] && u <= g[2] && v >= g[1] && v <= g[3]) return new Hit(p, u, v, t, Part.BIGGER);
		}
		return null;
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

	private static String lastShape;
	private static Panel grabbed;
	private static double grabDistance;
	/** Taken by the bar with the mouse (let go with the button), not with the move key. */
	private static boolean byBar;

	private static void take(Panel p, boolean bar) {
		LocalPlayer pl = Minecraft.getInstance().player;
		if (pl == null) return;
		grabbed = p;
		byBar = bar;
		grabDistance = pl.getEyePosition().distanceTo(new Vec3(p.x, p.y, p.z));
	}

	/** When input last went to a window: the game keeps its focus and doesn't pause for a moment. */
	private static volatile long lastInput;

	/** True shortly after input was sent to a window (the game shouldn't pause then). */
	public static boolean justSent() {
		return System.currentTimeMillis() - lastInput < 1500;
	}

	/** After input, the clicked program may take the focus: take it back, now and a bit later. */
	private static void refocus() {
		lastInput = System.currentTimeMillis();
		WindowSource src = source();
		if (src == null) return;
		Runnable back = () -> {
			try {
				src.focusGame();
			} catch (Throwable e) {
				AerionClient.warnOnce("mirror-focus", e);
			}
		};
		start();
		for (int ms : new int[] {40, 200, 600}) worker.schedule(back, ms, TimeUnit.MILLISECONDS);
	}

	/** All windows hidden for a moment (a key shows them again); pictures pause meanwhile. */
	private static volatile boolean hidden;

	public static boolean hidden() {
		return hidden;
	}

	public static void toggleHidden() {
		hidden = !hidden;
	}

	/** All windows in a half circle around the player, at eye height, the widest in the middle. */
	public static void arrange() {
		LocalPlayer pl = Minecraft.getInstance().player;
		if (pl == null) return;
		List<Panel> list;
		synchronized (Mirrors.class) {
			list = new ArrayList<>(all());
		}
		if (list.isEmpty()) return;
		hidden = false;
		float radius = 2.4f, gap = 0.15f;
		float total = 0;
		for (Panel p : list) total += p.width + gap;
		total -= gap;
		// Arc lengths to angles: centred on where the player looks.
		double start = pl.getYRot() - Math.toDegrees(total / 2 / radius);
		Vec3 eye = pl.getEyePosition();
		double along = 0;
		for (Panel p : list) {
			double yaw = start + Math.toDegrees((along + p.width / 2) / radius);
			double a = Math.toRadians(yaw);
			p.x = eye.x - Math.sin(a) * radius;
			p.y = eye.y;
			p.z = eye.z + Math.cos(a) * radius;
			p.yaw = (float) yaw;
			p.pitch = 0;
			p.radius = radius;
			if (p.pinned) pin(p, pl);
			along += p.width + gap;
		}
		save();
	}

	/** Pins a panel to the player (or unpins it): it keeps its turn and distance from them. */
	public static void togglePin(Panel p) {
		LocalPlayer pl = Minecraft.getInstance().player;
		if (pl == null) return;
		p.pinned = !p.pinned;
		if (p.pinned) pin(p, pl);
		save();
	}

	private static void pin(Panel p, LocalPlayer pl) {
		Vec3 eye = pl.getEyePosition();
		double dx = p.x - eye.x, dz = p.z - eye.z;
		p.pinDistance = (float) Math.hypot(dx, dz);
		p.pinHeight = (float) (p.y - eye.y);
		float around = (float) Math.toDegrees(Math.atan2(-dx, dz));
		p.pinYaw = wrap(around - pl.getYRot());
		p.yaw = around;
	}

	private static float wrap(float deg) {
		deg %= 360;
		return deg > 180 ? deg - 360 : deg < -180 ? deg + 360 : deg;
	}

	/** Pinned panels follow the player: same turn and distance from them, at any time. */
	private static void follow(Panel p, LocalPlayer pl, float partial) {
		Vec3 eye = pl.getEyePosition(partial);
		float yaw = pl.getViewYRot(partial) + p.pinYaw;
		double a = Math.toRadians(yaw);
		p.x = eye.x - Math.sin(a) * p.pinDistance;
		p.y = eye.y + p.pinHeight;
		p.z = eye.z + Math.cos(a) * p.pinDistance;
		p.yaw = yaw;
	}

	/** Makes a panel bigger (`factor` > 1) or smaller, around its centre. */
	public static void resize(Panel p, float factor) {
		p.width = Math.max(0.3f, Math.min(12f, p.width * factor));
		save();
	}

	/** A click while looking at a panel goes to its window; true when it was taken. */
	public static boolean click(int button) {
		Hit h = aim();
		if (h == null) return false;
		if (h.part == Part.CLOSE) {
			if (button == 0) remove(h.panel);
			return true;
		}
		if (h.part == Part.PIN) {
			if (button == 0) togglePin(h.panel);
			return true;
		}
		if (h.part == Part.SHAPE) {
			if (button == 0) {
				h.panel.curved = !h.panel.curved;
				save();
			}
			return true;
		}
		if (h.part == Part.SMALLER || h.part == Part.BIGGER) {
			if (button == 0) resize(h.panel, h.part == Part.BIGGER ? 1.15f : 1 / 1.15f);
			return true;
		}
		if (h.part == Part.BAR) {
			// Held by the bar: it follows the look while the button is down.
			if (button == 0) take(h.panel, true);
			return true;
		}
		WindowSource src = source();
		if (src != null && h.panel.win != 0) {
			if (button == 0) {
				// The left button stays down while the attack key is held: dragging and selecting work.
				src.press(h.panel.win, h.u, h.v, 0);
				pressed = h.panel;
				pressU = h.u;
				pressV = h.v;
			} else {
				src.click(h.panel.win, h.u, h.v, button);
			}
			refocus();
		}
		return true;
	}

	/** The panel the left button went down on, and where the pointer is on it. */
	private static Panel pressed;
	private static float pressU, pressV, hoverU = -1, hoverV = -1;
	private static long hoverWin;
	private static long lastMove;

	/** Every tick: the held button drags, a moving pointer hovers, letting go releases. */
	private static void pointer(Minecraft mc) {
		WindowSource src = source();
		if (src == null) return;
		boolean down = mc.options.keyAttack.isDown() && pl.aerion.mod.platform.Game.screen() == null;
		Hit h = aim();
		if (pressed != null) {
			float u = pressU, v = pressV;
			if (h != null && h.panel == pressed && h.part == Part.PANEL) {
				u = h.u;
				v = h.v;
			}
			if (pressed.win != 0) {
				if (down) {
					if (u != pressU || v != pressV) src.move(pressed.win, u, v, true);
				} else {
					src.release(pressed.win, u, v, 0);
					refocus();
				}
			}
			pressU = u;
			pressV = v;
			if (!down) pressed = null;
			return;
		}
		// Hovering: a few times a second, only when the pointer moved on the window.
		long now = System.currentTimeMillis();
		if (h == null || h.part != Part.PANEL || h.panel.win == 0 || now - lastMove < 50) return;
		if (h.panel.win == hoverWin && Math.abs(h.u - hoverU) < 0.002f && Math.abs(h.v - hoverV) < 0.002f) return;
		src.move(h.panel.win, h.u, h.v, false);
		hoverWin = h.panel.win;
		hoverU = h.u;
		hoverV = h.v;
		lastMove = now;
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
		if (h.part != Part.PANEL) {
			// The wheel over the bar resizes the panel.
			resize(h.panel, dy > 0 ? 1.1f : 1 / 1.1f);
			return true;
		}
		if (src != null && h.panel.win != 0) {
			src.scroll(h.panel.win, h.u, h.v, (int) Math.signum(dy) * Math.max(1, (int) Math.round(Math.abs(dy))));
			refocus();
		}
		return true;
	}

	/** Called every tick with the state of the grab key: a held panel follows the look. */
	public static void tick(Minecraft mc, boolean grabKey, boolean on) {
		if (mc.player == null || !on) {
			grabbed = null;
			return;
		}
		// The world's saved panels come back as soon as it is joined.
		if (!all().isEmpty()) start();
		// Changing the shape in the settings changes every window, not only new ones.
		String shape = opt("shape", "curve");
		if (lastShape != null && !shape.equals(lastShape)) {
			synchronized (Mirrors.class) {
				for (Panel p : panels) p.curved = !"straight".equals(shape);
			}
			save();
		}
		lastShape = shape;
		if (hidden) return;
		pointer(mc);
		boolean held = byBar ? mc.options.keyAttack.isDown() && pl.aerion.mod.platform.Game.screen() == null : grabKey;
		if (grabKey && grabbed == null) {
			Hit h = aim();
			if (h != null) take(h.panel, false);
		} else if (!held && grabbed != null) {
			if (grabbed.pinned) pin(grabbed, mc.player);
			grabbed = null;
			byBar = false;
			save();
		}
		if (grabbed != null) {
			putInFront(grabbed, mc.player, (float) grabDistance);
			// Held by the bar, the bar stays under the crosshair: the panel sits above it.
			if (byBar) lift(grabbed, grabbed.height() / 2 + BAR_GAP + BAR_H / 2);
		}
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
