package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Chroma;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Icons;
import pl.aerion.mod.Lang;
import pl.aerion.mod.MixinGate;
import pl.aerion.mod.Logo;
import pl.aerion.mod.Particles;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.features.Crosshair;
import pl.aerion.mod.features.Features;
import pl.aerion.mod.features.Sounds;
import pl.aerion.mod.features.Waypoints;
import pl.aerion.mod.platform.Gfx;

/**
 * Aerion's in-game menu: a sidebar (modules, features, sounds, keys, shared look) and calm tiles;
 * a tile opens its settings on tabs, one thing at a time. A module's look is set field by field on
 * top of the shared look. Controls are rebuilt every frame and leave their clickable areas in
 * {@link #hits}; changes go straight into hud.json's tree, which is saved when the menu closes.
 */
public final class AerionMenu implements Page {
	private static final int[] SWATCHES = {
		0xFFFFFFFF, 0xFF000000, 0xFFFF5555, 0xFFFFAA33, 0xFFFFE25A, 0xFF55DD77, 0xFF55AAFF, 0xFFB98CFF
	};
	private static final float TEXT = 8f, SMALL = 7f, TITLE = 10f;
	private static final int ROW = 16, SIDE = 104, GAP = 5;
	/** Space inside a setting's own panel: above and below its row, and on the sides. */
	private static final int ROW_PAD = 3, ROW_IN = 8;
	private static final String[] FEATURES = {"zoom", "toggleSprint", "toggleSneak", "freelook", "fullbright", "hitboxes", "overlays", "crosshair", "hurtCam", "lightLevels", "blockOutline", "chat", "perspective", "fovKey", "fovLock", "worldLook", "hitColor", "screens", "badges"};
	private static final String[] SOUND_GROUPS = {"all", "recent", "block", "entity", "item", "ambient", "music", "other"};
	private static final int WIN_W = 480, WIN_H = 280;

	/** A module setting; `formats` lists {value, label} choices for a text format. */
	private record Option(String key, String def, String[] choices, String[] labels, int min, int max, boolean color) {
		static Option toggle(String key, boolean on) {
			return new Option(key, on ? "1" : "0", null, null, 0, 0, false);
		}

		static Option select(String key, String def, String... choices) {
			return new Option(key, def, choices, null, 0, 0, false);
		}

		static Option format(String... pairs) {
			String[] values = new String[pairs.length / 2], labels = new String[pairs.length / 2];
			for (int i = 0; i < values.length; i++) {
				values[i] = pairs[2 * i];
				labels[i] = pairs[2 * i + 1];
			}
			return new Option("format", values[0], values, labels, 0, 0, false);
		}

		static Option number(String key, int def, int min, int max) {
			return new Option(key, String.valueOf(def), null, null, min, max, false);
		}

		static Option colour(String key, String def) {
			return new Option(key, def, null, null, 0, 0, true);
		}

		boolean isToggle() {
			return choices == null && max == 0 && !color;
		}
	}

	private static final Map<String, List<Option>> OPTIONS = Map.ofEntries(
			Map.entry("fps", List.of(
					Option.format("{fps} FPS", "144 FPS", "FPS: {fps}", "FPS: 144", "[{fps} FPS]", "[144 FPS]", "{fps}", "144"),
					Option.toggle("colored", false))),
			Map.entry("cps", List.of(Option.format("{left} | {right} CPS", "9 | 4 CPS", "{left} CPS", "9 CPS", "CPS: {left}", "CPS: 9"))),
			Map.entry("coords", List.of(
					Option.toggle("direction", true), Option.toggle("decimals", false), Option.toggle("lines", false),
					Option.toggle("nether", false), Option.toggle("biome", false))),
			Map.entry("direction", List.of(Option.toggle("degrees", true))),
			Map.entry("ping", List.of(
					Option.format("{ping} ms", "42 ms", "Ping: {ping}", "Ping: 42", "[{ping} ms]", "[42 ms]"),
					Option.toggle("colored", false))),
			Map.entry("clock", List.of(Option.toggle("h24", true), Option.toggle("seconds", false), Option.toggle("date", false))),
			Map.entry("memory", List.of(
					Option.format("{used}/{max} MB", "1840/6144 MB", "{percent}%", "30%", "RAM: {percent}%", "RAM: 30%"),
					Option.toggle("colored", false))),
			Map.entry("potions", List.of(
					Option.toggle("icon", true), Option.number("iconSize", 12, 9, 18), Option.toggle("name", true), Option.toggle("level", true),
					Option.toggle("duration", true), Option.toggle("colors", true), Option.toggle("tintText", false), Option.toggle("blink", false),
					Option.toggle("hideVanilla", true))),
			Map.entry("armor", List.of(
					Option.select("layout", "vertical", "vertical", "horizontal"),
					Option.select("durability", "percent", "percent", "value", "none"),
					Option.toggle("colored", true), Option.toggle("hand", true), Option.toggle("offhand", false))),
			Map.entry("keystrokes", List.of(
					Option.toggle("wasd", true), Option.toggle("mouse", true), Option.toggle("space", true), Option.toggle("cps", true),
					Option.number("keySize", 18, 12, 30), Option.colour("pressed", "accent"))),
			Map.entry("minimap", List.of(
					Option.number("size", 80, 48, 160), Option.number("zoom", 2, 1, 4), Option.toggle("coords", true), Option.toggle("north", true))),
			Map.entry("speed", List.of(
					Option.format("{speed} m/s", "4.32 m/s", "{speed} b/s", "4.32 b/s", "{speed}", "4.32"),
					Option.toggle("decimals", true), Option.toggle("vertical", false))),
			Map.entry("totems", List.of(
					Option.format("{count}", "2", "x{count}", "x2", "{count}x", "2x"),
					Option.toggle("icon", true), Option.toggle("hideZero", false), Option.toggle("warn", true))),
			Map.entry("items", List.of(
					Option.format("{count}", "2", "x{count}", "x2", "{count}x", "2x"),
					Option.toggle("arrow", true), Option.toggle("pearl", true), Option.toggle("gapple", true), Option.toggle("rocket", true), Option.toggle("xp", true),
					Option.toggle("icon", true), Option.toggle("hideZero", true), Option.toggle("warn", true))),
			Map.entry("death", List.of(Option.toggle("distance", true))),
			Map.entry("session", List.of(Option.toggle("label", true))),
			Map.entry("day", List.of(Option.toggle("time", true))),
			Map.entry("light", List.of(Option.toggle("colored", true), Option.toggle("sky", false))),
			Map.entry("packs", List.of(Option.toggle("all", false), Option.toggle("hideEmpty", true))),
			Map.entry("armorWarn", List.of(Option.number("threshold", 15, 1, 60), Option.toggle("held", true), Option.toggle("sound", true), Option.toggle("blink", true))),
			Map.entry("bossbar", List.of(Option.toggle("hide", false))),
			Map.entry("scoreboard", List.of(Option.toggle("hide", false))),
			Map.entry("chatbox", List.of(Option.toggle("hide", false))),
			Map.entry("blockOutline", List.of(
					Option.colour("color", "#ffffff"), Option.number("opacity", 100, 0, 100), Option.number("width", 2, 1, 8),
					Option.colour("fillColor", "accent"), Option.number("fill", 0, 0, 100))),
			Map.entry("chat", List.of(Option.toggle("time", true), Option.toggle("seconds", false), Option.toggle("stack", true), Option.toggle("history", true))),
			Map.entry("perspective", List.of(Option.toggle("hold", true), Option.select("view", "back", "back", "front"))),
			Map.entry("fovKey", List.of(Option.number("fov", 110, 10, 170))),
			Map.entry("fovLock", List.of(Option.toggle("spyglass", true))),
			Map.entry("fullbright", List.of(Option.number("level", 1500, 100, 1500))),
			Map.entry("worldLook", List.of(
					Option.toggle("fixTime", true), Option.number("hour", 12, 0, 23), Option.select("weather", "clear", "game", "clear", "rain", "thunder"))),
			Map.entry("hitColor", List.of(Option.colour("color", "#55aaff"), Option.number("opacity", 70, 0, 100))),
			Map.entry("screens", List.of(Option.select("veil", "none", "none2", "dark", "theme"), Option.number("opacity", 50, 0, 100), Option.toggle("blur", true))),
			Map.entry("lightLevels", List.of(
					Option.select("mode", "block", "block", "combined"), Option.number("radius", 8, 2, 16), Option.number("maxLevel", 15, 0, 15),
					Option.number("scale", 100, 40, 300), Option.colour("lowColor", "#ff5555"), Option.colour("midColor", "#ffe25a"), Option.colour("highColor", "#55dd77"))),
			Map.entry("crosshair", List.of(
					Option.select("shape", "cross", "cross", "dot", "circle", "x"),
					Option.number("size", 8, 1, 30), Option.number("gap", 3, 0, 20), Option.number("thickness", 2, 1, 8),
					Option.colour("color", "#ffffff"), Option.toggle("outline", true), Option.toggle("dot", false),
					Option.toggle("target", false), Option.colour("targetColor", "#ff5555"), Option.toggle("attack", true))),
			Map.entry("hurtCam", List.of(Option.number("strength", 50, 0, 200))),
			Map.entry("hitboxes", List.of(
					Option.colour("color", "#ffffff"), Option.toggle("reach", true), Option.colour("reachColor", "#ff5555"),
					Option.number("width", 2, 1, 6), Option.number("fill", 0, 0, 100), Option.number("range", 48, 8, 128),
					Option.toggle("players", true), Option.toggle("mobs", true), Option.toggle("others", false), Option.toggle("look", false))),
			Map.entry("overlays", List.of(
					Option.number("fireDrop", 0, 0, 100), Option.number("fireOpacity", 90, 0, 100), Option.toggle("pumpkin", false), Option.toggle("snow", false),
					Option.toggle("water", false), Option.toggle("block", false), Option.toggle("vignette", false), Option.toggle("portal", false),
					Option.toggle("nausea", false), Option.toggle("spyglass", false), Option.toggle("totem", false),
					Option.colour("tint", "#ff0000"), Option.number("tintOpacity", 0, 0, 80), Option.toggle("lowHealth", false), Option.number("lowHealthAt", 6, 1, 19))),
			Map.entry("blockInfo", List.of(Option.toggle("state", false))),
			Map.entry("fpsGraph", List.of(Option.number("width", 100, 50, 200), Option.number("height", 40, 20, 80), Option.toggle("value", true), Option.toggle("colored", true))),
			Map.entry("playerList", List.of(Option.number("max", 10, 1, 50), Option.toggle("ping", true), Option.toggle("dimension", false))),
			Map.entry("toggles", List.of(
					Option.toggle("sprint", true), Option.toggle("sneak", true), Option.toggle("fullbright", true), Option.toggle("freelook", true))));

	private record Hit(int x, int y, int w, int h, DoubleConsumer action, boolean slider, boolean clipped) {
		boolean contains(double px, double py) {
			return px >= x && py >= y && px < x + w && py < y + h;
		}

		void fire(double px) {
			action.accept(Math.max(0, Math.min(1, (px - x) / Math.max(1, w - 1))));
		}
	}

	private enum Section { HUD, PROFILES, FEATURES, SOUNDS, WAYPOINTS, KEYS, LOOK }

	private enum Sub { GENERAL, LOOK, KEY }

	// Kept between openings, so the menu reopens where it was left.
	private static Section section = Section.HUD;

	/** The tile whose settings are shown, or null for the list. */
	private String detail;

	private Sub sub = Sub.GENERAL;
	/** The colour row with its palette open. */
	private String expanded;
	/** The key waiting for its new binding. */
	private KeyMapping capturing;
	/** The key picked on the keyboard: the list shows only its actions (null = Aerion's keys). */
	private static String pickedKey;

	private final List<Hit> hits = new ArrayList<>();
	private Hit sliding;
	private boolean changed, clipping, gameOptionsChanged;
	/** The text field being typed in (its row key), what it holds, and where it goes on Enter. */
	private String fieldKey, fieldText = "";
	private Consumer<String> fieldDone;
	/** The waypoint whose name is being typed. */
	private Waypoints.Point naming;
	/** The first key replaces the suggested name. */
	private boolean namingFresh;
	/** Drawing units per GUI pixel: the menu has its own size, whatever the game's GUI scale. */
	private float toUnits = 1f;
	private int unitsW, unitsH;
	private String soundGroup = "all", query = "";
	/** Vertical position within the control being used, 0 at its top. */
	private double hitY;
	/** Picker state per colour row, so the hue survives black and white. */
	private final Map<String, float[]> pickers = new HashMap<>();
	private String hexRow, hexText = "";
	private Consumer<String> hexSet;
	private static List<String> allSounds;
	private int scroll, contentHeight, viewTop, viewBottom;
	private int x, y, colW, mouseX, mouseY;
	private Gfx g;
	private int text, muted, accent, accentText, surface, sidebar, tile, line, control, track;
	/** Eased values of switches and hovers, and the height of each card last frame. */
	private final Map<String, Float> eased = new HashMap<>();
	private final Map<String, Integer> cards = new HashMap<>();
	private String cardKey;
	private int cardTop, cardIndex;
	private long lastFrame;
	private float dt = 0.016f;

	private final long openedAt = System.nanoTime();
	/** The sidebar's highlight slides to the open section; and the page slides in when it changes. */
	private float navY = -1, navTargetY = -1;
	private String pageKey = "";
	private long pageAt;

	public AerionMenu() {}

	/** Opens on the HUD profiles. */
	static AerionMenu profiles() {
		section = Section.PROFILES;
		return new AerionMenu();
	}

	/** Opens directly on one module's settings. */
	public AerionMenu(String module) {
		section = Section.HUD;
		detail = module;
	}

	/** Width of the docked panel, in menu units. */
	private static final int DOCK_W = 232;

	/** Docked beside the HUD layout instead of in the middle: one module, changes seen live. */
	private boolean docked, dockLeft;
	private Runnable onClose;
	private int panelX, panelY, panelW, panelH;

	/** One module's settings in a panel at the screen's edge, over the HUD layout. */
	static AerionMenu docked(String module, Runnable onClose) {
		AerionMenu m = new AerionMenu(module);
		m.docked = true;
		m.onClose = onClose;
		return m;
	}

	/** Show another module's settings in the same panel. */
	void show(String module) {
		if (module.equals(detail)) return;
		detail = module;
		sub = isFeature(module) ? Sub.GENERAL : sub;
		expanded = null;
		capturing = null;
		scroll = 0;
	}

	String module() {
		return detail;
	}

	/** The panel goes to the side away from the module being set up. */
	void dockLeft(boolean left) {
		dockLeft = left;
	}

	/** Whether a point (GUI pixels) is on the docked panel. */
	boolean contains(double gx, double gy) {
		double px = gx * toUnits, py = gy * toUnits;
		return px >= panelX && py >= panelY && px < panelX + panelW && py < panelY + panelH;
	}

	private void leave() {
		if (docked) onClose.run();
		else toLayout();
	}

	private static boolean isFeature(String id) {
		return List.of(FEATURES).contains(id);
	}

	private static JsonObject item(HudConfig cfg, String id) {
		return isFeature(id) ? cfg.feature(id) : cfg.module(id);
	}

	private static boolean enabled(HudConfig cfg, String id) {
		return HudConfig.bool(item(cfg, id), "enabled", id.equals("zoom") || id.equals("badges"));
	}

	private void toLayout() {
		Page.go(new HudEditor());
	}

	private static String name(String id) {
		return Lang.tr((isFeature(id) ? "aerion.feature." : "aerion.module.") + id);
	}

	// --- input -------------------------------------------------------------------------------
	@Override
	public boolean pressed(double gx, double gy, int button) {
		double px = gx * toUnits, py = gy * toUnits;
		if (capturing != null) {
			// Left click cancels, so the mouse can still leave this state; other buttons bind.
			if (button != InputConstants.MOUSE_BUTTON_LEFT) bind(InputConstants.Type.MOUSE.getOrCreate(button));
			capturing = null;
			return false;
		}
		if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
		commitField();
		hexRow = null;
		naming = null;
		for (Hit h : List.copyOf(hits)) {
			if (h.clipped && (py < viewTop || py >= viewBottom)) continue;
			if (h.contains(px, py)) {
				if (h.slider) sliding = h;
				hitY = Math.max(0, Math.min(1, (py - h.y) / Math.max(1, h.h - 1)));
				h.fire(px);
				break;
			}
		}
		return false;
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		boolean escape = key.getValue() == InputConstants.KEY_ESCAPE;
		if (capturing != null) {
			if (!escape) bind(key);
			capturing = null;
			return true;
		}
		if (fieldKey != null) {
			int k = key.getValue();
			if (k == InputConstants.KEY_BACKSPACE && !fieldText.isEmpty()) fieldText = fieldText.substring(0, fieldText.length() - 1);
			else if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) commitField();
			else if (escape) fieldKey = null;
			return true;
		}
		if (naming != null) {
			if (key.getValue() == InputConstants.KEY_BACKSPACE) {
				naming.name = namingFresh || naming.name.isEmpty() ? "" : naming.name.substring(0, naming.name.length() - 1);
				namingFresh = false;
			} else if (escape || key.getValue() == InputConstants.KEY_RETURN) {
				if (naming.name.isBlank()) naming.name = Lang.tr("aerion.waypoint.default");
				naming = null;
			}
			return true;
		}
		if (hexRow != null) {
			if (key.getValue() == InputConstants.KEY_BACKSPACE && !hexText.isEmpty()) hexText = hexText.substring(0, hexText.length() - 1);
			else if (escape || key.getValue() == InputConstants.KEY_RETURN) hexRow = null;
			return true;
		}
		if (searchable() && !query.isEmpty()) {
			if (escape) {
				query = "";
				scroll = 0;
				return true;
			}
			if (key.getValue() == InputConstants.KEY_BACKSPACE) {
				query = query.substring(0, query.length() - 1);
				scroll = 0;
				return true;
			}
		}
		// Esc steps back: settings to the list, the list to the HUD layout.
		if (escape && docked) {
			onClose.run();
			return true;
		}
		if (escape) {
			if (detail != null) {
				detail = null;
				expanded = null;
				scroll = 0;
			} else {
				toLayout();
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(int codepoint) {
		if (fieldKey != null) {
			if (fieldText.length() < 400 && !Character.isISOControl(codepoint)) fieldText += new String(Character.toChars(codepoint));
			return true;
		}
		if (naming != null) {
			if (namingFresh) naming.name = "";
			namingFresh = false;
			if (naming.name.length() < 32 && !Character.isISOControl(codepoint)) naming.name += new String(Character.toChars(codepoint));
			return true;
		}
		if (hexRow != null) {
			if (Character.digit(codepoint, 16) >= 0 && hexText.length() < 6) {
				hexText += Character.toLowerCase((char) codepoint);
				if (hexText.length() == 6) {
					hexSet.accept("#" + hexText);
					changed = true;
					hexRow = null;
				}
			}
			return true;
		}
		if (!searchable()) return false;
		if (query.length() < 40 && Character.isValidCodePoint(codepoint) && !Character.isISOControl(codepoint)) {
			query += new String(Character.toChars(codepoint));
			scroll = 0;
		}
		return true;
	}

	private void bind(InputConstants.Key key) {
		capturing.setKey(key);
		KeyMapping.resetMapping();
		Minecraft.getInstance().options.save();
	}

	@Override
	public void dragged(double px, double py, int screenW, int screenH) {
		if (sliding != null) {
			hitY = Math.max(0, Math.min(1, (py * toUnits - sliding.y) / Math.max(1, sliding.h - 1)));
			sliding.fire(px * toUnits);
		}
	}

	@Override
	public void released() {
		sliding = null;
	}

	@Override
	public void scrolled(double px, double py, double amount) {
		scroll -= (int) Math.signum(amount) * ROW * 2;
	}

	@Override
	public void close() {
		if (changed) AerionClient.saveHud();
		changed = false;
		if (gameOptionsChanged) Minecraft.getInstance().options.save();
		gameOptionsChanged = false;
		Waypoints.save();
	}

	// --- window ------------------------------------------------------------------------------
	@Override
	public void render(Gfx gfx, int mx, int my) {
		hits.clear();
		cardIndex = 0;
		g = gfx;
		Theme t = AerionClient.theme();
		text = t != null ? t.text : 0xFFFFFFFF;
		muted = t != null ? t.textMuted : 0xFFAAAAAA;
		accent = t != null ? t.accent : 0xFF7AA2FF;
		accentText = t != null ? t.accentText : 0xFF111111;
		surface = Colors.alpha(t != null ? t.surface : 0xFF1A1A1A, 0xFF);
		sidebar = Colors.mix(surface, 0xFF000000, t != null && t.light ? 0.04f : 0.18f);
		tile = Colors.mix(surface, text, 0.05f);
		line = Colors.mix(surface, text, 0.14f);
		control = Colors.mix(surface, text, 0.09f);
		track = Colors.mix(surface, text, 0.2f);
		long now = System.nanoTime();
		if (lastFrame != 0) dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;

		if (!docked && Minecraft.getInstance().level == null && t != null) {
			Draw.background(g, t, 0xFF);
			Particles.draw(g, t);
		}
		// A whole number of screen pixels per unit keeps text and lines sharp; about 60% of the
		// window's height, so a large window with a high GUI scale doesn't get a huge menu.
		int k = Gfx.realGuiScale(), realH = g.height() * k, realW = g.width() * k;
		int p = Math.max(2, (int) (realH * 0.62f / WIN_H));
		toUnits = (float) k / p;
		unitsW = realW / p;
		unitsH = realH / p;
		mouseX = (int) (mx * toUnits);
		mouseY = (int) (my * toUnits);
		g.push();
		g.scale(1f / toUnits);
		Gfx.pixels(p);
		// The window springs open: it grows from a little smaller and settles with a small overshoot.
		float open = animationsOn() ? Hud.spring(Math.min(1f, (now - openedAt) / 3.2e8f)) : 1f;
		float pop = 0.9f + 0.1f * open;
		g.translate(unitsW / 2f * (1 - pop), unitsH / 2f * (1 - pop));
		g.scale(pop);
		try {
			window();
		} finally {
			Gfx.pixels(0);
			g.pop();
		}
	}

	private void window() {
		int winW, winH, left, top, side;
		if (docked) {
			// Full height at the screen's edge, so the HUD stays in view beside it.
			winW = Math.min(unitsW - 12, DOCK_W);
			winH = unitsH - 12;
			left = dockLeft ? 6 : unitsW - winW - 6;
			top = 6;
			side = 0;
		} else {
			winW = Math.min(unitsW - 12, WIN_W);
			winH = Math.min(unitsH - 12, WIN_H);
			left = (unitsW - winW) / 2;
			top = (unitsH - winH) / 2;
			side = SIDE;
		}
		panelX = left;
		panelY = top;
		panelW = winW;
		panelH = winH;
		Draw.shadow(g, left, top, winW, winH, 6);
		Draw.rounded(g, left, top, winW, winH, 6, surface);
		if (!docked) {
			sidebar(left, top, winH);
			Draw.vline(g, left + SIDE, top + 1, winH - 2, line);
		}
		Draw.control(g, left, top, winW, winH, 6, line, 0);

		int mainX = left + side + 10, mainW = winW - side - 20;
		iconButton(left + winW - 20, top + 6, 14, "close", this::leave);
		int head = detail != null ? pageHead(mainX, top + 6, mainW - 18) : listHead(mainX, top + 7);
		if (detail == null && (section == Section.HUD || section == Section.FEATURES)) searchBox(left + winW - 134, top + 6, 110);

		viewTop = top + head;
		viewBottom = top + winH - 4;
		int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		clipping = true;
		g.scissor(left + side, viewTop, left + winW - 1, viewBottom);
		x = mainX;
		colW = mainW;
		y = viewTop + 2 - scroll;
		int start = y;
		// A new page slides in from the side with a little overshoot.
		String key = section + "/" + detail + "/" + sub;
		if (!key.equals(pageKey)) {
			pageKey = key;
			pageAt = System.nanoTime();
		}
		float slideIn = animationsOn() ? Hud.spring(Math.min(1f, (System.nanoTime() - pageAt) / 2.8e8f)) : 1f;
		g.push();
		g.translate((1f - slideIn) * 14f, 0);
		if (detail != null) detailBody();
		else switch (section) {
			case HUD -> tiles(mainX, mainW, found(List.of(Features.MODULE_IDS)));
			case FEATURES -> tiles(mainX, mainW, found(List.of(FEATURES)));
			case SOUNDS -> soundsPage();
			case WAYPOINTS -> waypointsPage();
			case PROFILES -> profilesPage();
			case KEYS -> keysPage();
			case LOOK -> lookPage();
		}
		g.pop();
		contentHeight = y - start + 6;
		g.endScissor();
		clipping = false;
		if (maxScroll > 0) {
			int view = viewBottom - viewTop;
			int barH = Math.max(12, view * view / Math.max(1, contentHeight));
			int barY = viewTop + (view - barH) * scroll / maxScroll;
			Draw.rounded(g, left + winW - 5, barY + 1, 2, barH - 2, 1, Colors.mix(surface, text, 0.3f));
		}
	}

	private void sidebar(int left, int top, int h) {
		Draw.rounded(g, left, top, SIDE, h, 6, sidebar);
		g.fill(left + SIDE - 6, top, left + SIDE, top + h, sidebar);
		Logo.draw(g, AerionClient.theme(), left + 9, top + 8, 13);
		int nx = left + 5, ny = top + 30;
		if (animationsOn() && navTargetY >= 0) {
			navY = navY < 0 ? navTargetY : navY + (navTargetY - navY) * Math.min(1f, dt * 14f);
			if (Math.abs(navTargetY - navY) < 0.3f) navY = navTargetY;
			Draw.rounded(g, nx, Math.round(navY), SIDE - 10, 16, 3, accent);
		}
		ny = navAction(nx, ny, "layout", "aerion.nav.layout", this::toLayout) + 5;
		ny = navHead(nx, ny, "aerion.navhead.mods");
		ny = navItem(nx, ny, Section.HUD, "fps", "aerion.nav.hud");
		ny = navItem(nx, ny, Section.PROFILES, "packs", "aerion.nav.profiles");
		ny = navItem(nx, ny, Section.FEATURES, "features", "aerion.nav.features") + 5;
		ny = navHead(nx, ny, "aerion.navhead.tools");
		ny = navItem(nx, ny, Section.SOUNDS, "sounds", "aerion.nav.sounds");
		ny = navItem(nx, ny, Section.WAYPOINTS, "waypoints", "aerion.nav.waypoints");
		ny = navItem(nx, ny, Section.KEYS, "keys", "aerion.nav.keys") + 5;
		ny = navHead(nx, ny, "aerion.navhead.look");
		navItem(nx, ny, Section.LOOK, "look", "aerion.nav.look");
		// The player at the foot, as in the launcher's sidebar.
		int py = top + h - 26;
		Draw.hline(g, left + 6, py - 4, SIDE - 12, line);
		g.playerFace(left + 9, py + 2, 16);
		String name = Minecraft.getInstance().getUser().getName();
		Txt.draw(g, ellipsize(name, SIDE - 36), left + 30, py + 6, TEXT, text);
		Txt.draw(g, "Aerion " + VERSION, left + 30, py + 15, 6f, muted);
	}

	private static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("aerion")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");

	private int navHead(int nx, int ny, String key) {
		Txt.draw(g, Lang.tr(key).toUpperCase(Locale.ROOT), nx + 4, ny + 4, 6f, Colors.mix(sidebar, muted, 0.75f));
		return ny + 10;
	}

	/** A sidebar entry that does something instead of opening a section. */
	private int navAction(int nx, int ny, String icon, String label, Runnable run) {
		int w = SIDE - 10, h = 16;
		boolean over = inside(nx, ny, w, h);
		float glow = ease("navAction" + label, over ? 1 : 0);
		if (glow > 0) Draw.rounded(g, nx, ny, w, h, 3, Colors.alpha(control, Math.round(255 * glow)));
		Icons.draw(g, icon, nx + 5, ny + 3, 10, over ? text : muted);
		Txt.draw(g, Lang.tr(label), nx + 19, ny + h / 2f, TEXT, over ? text : muted);
		hit(nx, ny, w, h, f -> run.run(), false);
		return ny + h + 2;
	}

	private int navItem(int nx, int ny, Section which, String icon, String label) {
		int w = SIDE - 10, h = 16;
		boolean on = section == which, over = inside(nx, ny, w, h);
		float glow = ease("nav" + which, over ? 1 : 0);
		// The open section is filled with the accent (a pill that slides there), the others light up under the pointer.
		boolean slide = animationsOn() && navY >= 0;
		if (on) navTargetY = ny;
		float cover = slide ? Math.max(0f, 1f - Math.abs(navY - ny) / (h + 2f)) : on ? 1f : 0f;
		if (on && !slide) Draw.rounded(g, nx, ny, w, h, 3, over ? Colors.mix(accent, 0xFFFFFFFF, 0.08f) : accent);
		else if (glow > 0 && cover < 0.5f) Draw.rounded(g, nx, ny, w, h, 3, Colors.alpha(control, Math.round(255 * glow)));
		int fg = Colors.mix(over ? text : muted, accentText, cover);
		Icons.draw(g, icon, nx + 5, ny + 3, 10, fg);
		Txt.draw(g, Lang.tr(label), nx + 19, ny + h / 2f, TEXT, fg);
		hit(nx, ny, w, h, f -> {
			section = which;
			detail = null;
			expanded = null;
			capturing = null;
			query = "";
			scroll = 0;
		}, false);
		return ny + h + 2;
	}

	private int listHead(int mx, int my) {
		String key = switch (section) {
			case HUD -> "hud";
			case PROFILES -> "profiles";
			case FEATURES -> "features";
			case SOUNDS -> "sounds";
			case WAYPOINTS -> "waypoints";
			case KEYS -> "keys";
			case LOOK -> "look";
		};
		Txt.draw(g, Lang.tr("aerion.nav." + key), mx, my + 6, TITLE, text);
		Txt.draw(g, Lang.tr("aerion.hint." + key), mx, my + 18, SMALL, muted);
		return 34;
	}

	private int pageHead(int mx, int my, int w) {
		HudConfig cfg = AerionClient.hud();
		String id = detail;
		if (docked) {
			// No list to go back to: the layout behind is where another module is picked.
			Icons.draw(g, id, mx, my + 1, 12, accent);
			Txt.draw(g, ellipsize(name(id), w - 46), mx + 16, my + 7, TITLE, text);
		} else {
			iconButton(mx, my, 14, "back", () -> {
				detail = null;
				expanded = null;
				capturing = null;
				scroll = 0;
			});
			Icons.draw(g, id, mx + 19, my + 1, 12, text);
			Txt.draw(g, name(id), mx + 35, my + 7, TITLE, text);
		}
		boolean on = enabled(cfg, id);
		switchAt("head" + id, mx + w - 22, my + 2, on);
		hit(mx + w - 24, my, 24, 14, f -> {
			item(cfg, id).addProperty("enabled", !on);
			changed = true;
		}, false);
		int tx = mx;
		tx = subTab(tx, my + 19, "aerion.tab.general", Sub.GENERAL);
		if (!isFeature(id)) tx = subTab(tx, my + 19, "aerion.tab.look", Sub.LOOK);
		// Some items (badges) have no key of their own.
		if (Features.key(id) != null) subTab(tx, my + 19, "aerion.tab.key", Sub.KEY);
		else if (sub == Sub.KEY) sub = Sub.GENERAL;
		Draw.hline(g, mx, my + 33, w + 18, line);
		return 42;
	}

	private int subTab(int tx, int ty, String label, Sub which) {
		String s = Lang.tr(label);
		int w = Math.round(Txt.width(s, TEXT)) + 12;
		boolean on = sub == which, over = inside(tx, ty, w, 14);
		Txt.center(g, s, tx + w / 2f, ty + 6, TEXT, on || over ? text : muted);
		if (on) g.fill(tx, ty + 13, tx + w, ty + 14, accent);
		hit(tx, ty, w, 14, f -> {
			sub = which;
			expanded = null;
			capturing = null;
			scroll = 0;
		}, false);
		return tx + w + 2;
	}

	// --- tiles -------------------------------------------------------------------------------
	/** The section's tiles; a search looks through modules and features together. */
	private List<String> found(List<String> section) {
		if (query.isEmpty()) return section;
		String q = query.toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>();
		List<String> all = new ArrayList<>(List.of(Features.MODULE_IDS));
		all.addAll(List.of(FEATURES));
		for (String id : all) {
			if (id.toLowerCase(Locale.ROOT).contains(q) || name(id).toLowerCase(Locale.ROOT).contains(q)) out.add(id);
		}
		return out;
	}

	private boolean searchable() {
		return detail == null && capturing == null && section != Section.KEYS && section != Section.LOOK && section != Section.WAYPOINTS && section != Section.PROFILES;
	}

	/** Typing goes straight here, so there is nothing to click first. */
	private void searchBox(int bx, int by, int w) {
		Draw.rounded(g, bx, by, w, 14, 3, tile);
		Icons.draw(g, "search", bx + 4, by + 3, 8, muted);
		if (query.isEmpty()) Txt.draw(g, Lang.tr("aerion.search"), bx + 15, by + 7, SMALL, muted);
		else Txt.draw(g, query + (System.currentTimeMillis() / 500 % 2 == 0 ? "|" : ""), bx + 15, by + 7, SMALL, text);
	}

	/** Which tiles the list shows: all, only the switched-on ones, or only the switched-off ones. */
	private static String listFilter = "all";

	private void filters(int left) {
		int bx = left;
		for (String f : new String[] {"all", "on", "off"}) {
			String label = Lang.tr("aerion.filter." + f);
			int w = Math.round(Txt.width(label, SMALL)) + 14;
			boolean on = f.equals(listFilter), over = inside(bx, y, w, 14);
			Draw.rounded(g, bx, y, w, 14, 3, on ? accent : over ? control : tile);
			Txt.center(g, label, bx + w / 2f, y + 7, SMALL, on ? accentText : over ? text : muted);
			hit(bx, y, w, 14, fr -> {
				listFilter = f;
				scroll = 0;
			}, false);
			bx += w + 3;
		}
		y += 20;
	}

	private void commitField() {
		if (fieldKey == null) return;
		fieldKey = null;
		fieldDone.accept(fieldText);
	}

	/** A text setting: click to type, Enter (or a click elsewhere) keeps it, Esc drops it. */
	private void textRow(String key, String label, String value, String placeholder, Consumer<String> done) {
		beginRow("field:" + key);
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		int bx = controlX(), w = x + colW - bx;
		boolean typing = key.equals(fieldKey), over = inside(bx, y + 1, w, ROW - 3);
		Draw.control(g, bx, y + 1, w, ROW - 3, 3, typing ? accent : over ? track : line, typing ? tile : control);
		String shown = typing ? fieldText + (System.currentTimeMillis() / 500 % 2 == 0 ? "|" : "") : value;
		boolean empty = !typing && value.isEmpty();
		Txt.draw(g, ellipsize(empty ? placeholder : shown, w - 10), bx + 5, y + 1 + (ROW - 3) / 2f, SMALL, empty ? muted : text);
		hit(bx, y + 1, w, ROW - 3, f -> {
			fieldKey = key;
			fieldText = value;
			fieldDone = done;
		}, false);
		y += ROW;
		endRow();
	}

	/** HUD profiles: which one is in use, and the active one's name, servers and removal. */
	private void profilesPage() {
		HudConfig cfg = AerionClient.hud();
		String active = Profiles.active(cfg);
		List<String> names = Profiles.names(cfg);
		beginCard(null);
		for (String name : names) {
			beginRow("profile:" + name);
			boolean on = name.equals(active);
			Icons.draw(g, "packs", x, y + 3, 10, on ? accent : muted);
			Txt.draw(g, ellipsize(name, colW - 90), x + 15, y + ROW / 2f, TEXT, text);
			String servers = Profiles.servers(cfg, name);
			int right = x + colW;
			if (on) {
				String inUse = Lang.tr("aerion.profile.inUse");
				Txt.draw(g, inUse, right - Txt.width(inUse, SMALL), y + ROW / 2f, SMALL, accent);
			} else {
				String use = Lang.tr("aerion.profile.use");
				int w = Math.round(Txt.width(use, SMALL)) + 14;
				smallButton(right - w, y + 2, w, ROW - 4, use, false, () -> {
					commitField();
					Profiles.switchTo(cfg, name);
				});
			}
			y += ROW;
			if (!servers.isEmpty()) {
				Txt.draw(g, ellipsize(servers, colW - 20), x + 15, y + 2, SMALL, muted);
				y += 8;
			}
			endRow();
		}
		String add = "+ " + Lang.tr("aerion.profile.addLong");
		button(x, y, Math.round(Txt.width(add, TEXT)) + 20, 15, add, false, () -> {
			commitField();
			Profiles.add(cfg);
		});
		y += 21;
		endCard();

		beginCard(Lang.tr("aerion.profile.settings").replace("{}", active));
		textRow("name", Lang.tr("aerion.profile.name"), active, "", v -> Profiles.rename(cfg, Profiles.active(cfg), v));
		endCard();
		beginCard(Lang.tr("aerion.profile.serversLabel"));
		note(Lang.tr("aerion.profile.serversHint"));
		for (String srv : Profiles.serverList(cfg, active)) {
			beginRow("server:" + srv);
			Icons.draw(g, "play", x, y + 3, 10, muted);
			Txt.draw(g, ellipsize(srv, colW - 40), x + 15, y + ROW / 2f, TEXT, text);
			iconButton(x + colW - 15, y + 1, 14, "close", () -> Profiles.removeServer(cfg, active, srv));
			y += ROW;
			endRow();
		}
		textRow("addServer", "+ " + Lang.tr("aerion.profile.addServer"), "", "pvp.serwer.pl", v -> Profiles.addServers(cfg, Profiles.active(cfg), v));
		String server = Profiles.server();
		if (server != null) {
			toggle(Lang.tr("aerion.profile.thisServer").replace("{}", server), Profiles.onServer(cfg, active, server), v -> Profiles.toggleServer(cfg, active));
		}
		endCard();
		if (names.size() > 1) {
			String del = Lang.tr("aerion.profile.delete");
			int w = Math.round(Txt.width(del, SMALL)) + 16;
			boolean over = inside(x, y, w, 14);
			Draw.rounded(g, x, y, w, 14, 3, over ? 0xFFE5484D : Colors.mix(control, 0xFFE5484D, 0.35f));
			Txt.center(g, del, x + w / 2f, y + 7, SMALL, 0xFFFFFFFF);
			hit(x, y, w, 14, f -> {
				fieldKey = null;
				Profiles.delete(cfg, active);
			}, false);
			y += 20;
		}
		note(Lang.tr("aerion.profile.keyHint"));
	}

	private void tiles(int left, int w, List<String> all) {
		filters(left);
		HudConfig cfg = AerionClient.hud();
		List<String> items = new ArrayList<>();
		for (String id : all) {
			boolean on = enabled(cfg, id);
			if (listFilter.equals("all") || listFilter.equals("on") == on) items.add(id);
		}
		if (items.isEmpty()) {
			note(Lang.tr("aerion.sound.empty"));
			return;
		}
		// Four columns on a wide menu, three otherwise; a tile is a little taller than wide.
		int cols = w >= 4 * 80 + 3 * GAP ? 4 : 3;
		int cw = (w - (cols - 1) * GAP) / cols, ch = 50;
		for (int i = 0; i < items.size(); i++) {
			tileAt(cfg, items.get(i), left + (i % cols) * (cw + GAP), y + (i / cols) * (ch + GAP), cw, ch);
		}
		y += ((items.size() + cols - 1) / cols) * (ch + GAP);
	}

	/** A big icon over a band with the name; the band is the accent while the tile is on. */
	private void tileAt(HudConfig cfg, String id, int cx, int cy, int w, int h) {
		boolean on = enabled(cfg, id), over = inside(cx, cy, w, h);
		float lift = ease("tile" + id, over ? 1 : 0);
		if (animationsOn()) cy -= Math.round(lift * 2f);
		int band = 15;
		Draw.rounded(g, cx, cy, w, h, 4, Colors.mix(tile, control, lift));
		int bandColor = on ? (over ? Colors.mix(accent, 0xFFFFFFFF, 0.08f) : accent) : Colors.mix(control, track, lift);
		Draw.rounded(g, cx, cy + h - band, w, band, 4, bandColor);
		g.fill(cx, cy + h - band, cx + w, cy + h - band + 4, bandColor);
		int icon = 16;
		Icons.draw(g, id, cx + (w - icon) / 2f, cy + (h - band - icon) / 2f, icon, on ? text : muted);
		Txt.center(g, ellipsize(name(id), w - 8, SMALL), cx + w / 2f, cy + h - band / 2f, SMALL, on ? accentText : over ? text : muted);
		int sx = cx + w - 24, sy = cy + 5;
		switchAt("tile" + id, sx, sy, on);
		hit(sx - 3, cy, 27, 16, f -> {
			item(cfg, id).addProperty("enabled", !on);
			changed = true;
		}, false);
		hit(cx, cy, w, h, f -> {
			detail = id;
			sub = Sub.GENERAL;
			scroll = 0;
		}, false);
	}

	private static String ellipsize(String s, int width) {
		return ellipsize(s, width, TEXT);
	}

	private static String ellipsize(String s, int width, float size) {
		if (Txt.width(s, size) <= width) return s;
		while (s.length() > 1 && Txt.width(s + "…", size) > width) s = s.substring(0, s.length() - 1);
		return s + "…";
	}

	// --- settings of one tile ----------------------------------------------------------------
	private void detailBody() {
		HudConfig cfg = AerionClient.hud();
		// What the module or feature does, before its settings.
		String about = sub == Sub.GENERAL ? Lang.get("aerion.desc." + detail) : null;
		if (about != null) description(about);
		if (sub == Sub.GENERAL && isFeature(detail) && enabled(cfg, detail) && MixinGate.skipped().contains(detail)) description(Lang.tr("aerion.hint.restart"));
		if (sub == Sub.KEY) {
			keyTab();
		} else if (isFeature(detail)) {
			featureGeneral(cfg);
		} else if (sub == Sub.LOOK) {
			moduleLook(cfg, cfg.module(detail));
		} else {
			moduleGeneral(cfg.module(detail));
		}
	}

	private void moduleGeneral(JsonObject m) {
		List<Option> options = OPTIONS.getOrDefault(detail, List.of());
		if (!options.isEmpty()) {
			beginCard(Lang.tr("aerion.sec.options"));
			for (Option o : options) option(m, o);
			if (options.stream().anyMatch(o -> o.key.equals("format"))) note(Lang.tr("aerion.panel.formatHint"));
			if (List.of("bossbar", "scoreboard", "chatbox").contains(detail)) note(Lang.tr("aerion.hint.element"));
			endCard();
		}
		// Shown only in some situations, and how it comes and goes.
		if (!List.of("bossbar", "scoreboard", "chatbox").contains(detail)) {
			beginCard(Lang.tr("aerion.sec.behavior"));
			String[] show = new String[Situations.IDS.size() + 1], hide = new String[Situations.IDS.size() + 1];
			show[0] = "always";
			hide[0] = "never";
			for (int i = 0; i < Situations.IDS.size(); i++) show[i + 1] = hide[i + 1] = Situations.IDS.get(i);
			cycle(Lang.tr("aerion.opt.showWhen"), show, choiceLabels(show), HudConfig.opt(m, "showWhen", "always"), v -> setOption(m, "showWhen", v));
			cycle(Lang.tr("aerion.opt.hideWhen"), hide, choiceLabels(hide), HudConfig.opt(m, "hideWhen", "never"), v -> setOption(m, "hideWhen", v));
			slider(Lang.tr("aerion.opt.showFor"), HudConfig.num(optionsOf(m), "showFor", 0), 0, 60, 1, " s", v -> setOption(m, "showFor", String.valueOf(v)));
			slider(Lang.tr("aerion.opt.delay"), HudConfig.num(optionsOf(m), "delay", 0), 0, 10, 1, " s", v -> setOption(m, "delay", String.valueOf(v)));
			slider(Lang.tr("aerion.opt.linger"), HudConfig.num(optionsOf(m), "linger", 0), 0, 30, 1, " s", v -> setOption(m, "linger", String.valueOf(v)));
			toggle(Lang.tr("aerion.opt.animate"), "1".equals(HudConfig.opt(m, "animate", "1")), v -> setOption(m, "animate", v ? "1" : "0"));
			slider(Lang.tr("aerion.opt.fade"), HudConfig.num(optionsOf(m), "fade", 170), 50, 1500, 50, " ms", v -> setOption(m, "fade", String.valueOf(v)));
			toggle(Lang.tr("aerion.opt.pulse"), "1".equals(HudConfig.opt(m, "pulse", "1")), v -> setOption(m, "pulse", v ? "1" : "0"));
			note(Lang.tr("aerion.hint.behavior"));
			endCard();
		}
		beginCard(Lang.tr("aerion.sec.place"));
		slider(Lang.tr("aerion.panel.scale"), HudConfig.num(m, "scale", 100), 25, 400, 5, "%", v -> m.addProperty("scale", v));
		grid(m);
		String[] anchors = {"free", "tl", "t", "tr", "l", "c", "r", "bl", "b", "br"};
		cycle(Lang.tr("aerion.place.anchor"), anchors, choiceLabels(anchors), HudConfig.opt(m, "anchor", "free"), v -> setOption(m, "anchor", v));
		if (!"free".equals(HudConfig.opt(m, "anchor", "free"))) {
			slider(Lang.tr("aerion.place.margin"), HudConfig.num(optionsOf(m), "margin", 4), 0, 40, 1, "", v -> setOption(m, "margin", String.valueOf(v)));
		} else {
			slider(Lang.tr("aerion.place.x"), HudConfig.num(m, "x", 0) / 10, 0, 100, 1, "%", v -> m.addProperty("x", v * 10));
			slider(Lang.tr("aerion.place.y"), HudConfig.num(m, "y", 0) / 10, 0, 100, 1, "%", v -> m.addProperty("y", v * 10));
		}
		endCard();
	}

	private static JsonObject optionsOf(JsonObject m) {
		return m.get("options") instanceof JsonObject o ? o : new JsonObject();
	}

	private static String[] choiceLabels(String[] ids) {
		String[] out = new String[ids.length];
		for (int i = 0; i < ids.length; i++) out[i] = Lang.tr("aerion.choice." + ids[i]);
		return out;
	}

	/** One button that steps through the values (for lists too long to show side by side). */
	private void cycle(String label, String[] values, String[] labels, String current, Consumer<String> set) {
		beginRow(label);
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		int idx = Math.max(0, java.util.Arrays.asList(values).indexOf(current));
		smallButton(controlX(), y + 2, controlW(), ROW - 4, labels[idx] + "  ›", idx != 0, () -> {
			set.accept(values[(idx + 1) % values.length]);
			changed = true;
		});
		y += ROW;
		endRow();
	}

	/** Nine spots for the corners, edges and centre, drawn on a little screen. */
	private void grid(JsonObject m) {
		beginRow("grid");
		int cw = 11, ch = 6, gap = 2, pad = 3;
		int frameW = 3 * cw + 2 * gap + 2 * pad, frameH = 3 * ch + 2 * gap + 2 * pad;
		Txt.draw(g, Lang.tr("aerion.place.corner"), x, y + 2 + frameH / 2f, TEXT, text);
		int cx = controlX(), top = y + 2;
		Draw.control(g, cx, top, frameW, frameH, 2, line, Colors.mix(tile, 0xFF000000, 0.25f));
		int[] xs = {8, 500, 992}, ys = {10, 500, 990};
		int mx = HudConfig.num(m, "x", 0), my = HudConfig.num(m, "y", 0);
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				int tx = cx + pad + col * (cw + gap), ty = top + pad + row * (ch + gap);
				int vx = xs[col], vy = ys[row];
				String here = HudConfig.opt(m, "anchor", "free");
				boolean on = here.equals("free") ? Math.abs(mx - vx) < 15 && Math.abs(my - vy) < 15 : here.equals(new String[][] {{"tl", "t", "tr"}, {"l", "c", "r"}, {"bl", "b", "br"}}[row][col]);
				boolean over = inside(tx, ty, cw, ch);
				Draw.rounded(g, tx, ty, cw, ch, 1, on ? accent : over ? Colors.mix(surface, text, 0.35f) : Colors.mix(surface, text, 0.16f));
				String code = new String[][] {{"tl", "t", "tr"}, {"l", "c", "r"}, {"bl", "b", "br"}}[row][col];
				hit(tx, ty, cw, ch, f -> {
					m.addProperty("x", vx);
					m.addProperty("y", vy);
					setOption(m, "anchor", code);
					changed = true;
				}, false);
			}
		}
		y += frameH + 4;
		endRow();
	}

	private void featureGeneral(HudConfig cfg) {
		JsonObject f = cfg.feature(detail);
		if (detail.equals("crosshair")) crosshairPreview(f);
		beginCard(null);
		if (detail.equals("zoom")) {
			slider(Lang.tr("aerion.feature.zoomFactor"), HudConfig.num(f, "factor", 4), 2, 10, 1, "x", v -> f.addProperty("factor", v));
			toggle(Lang.tr("aerion.feature.zoomSmooth"), HudConfig.bool(f, "smooth", true), v -> f.addProperty("smooth", v));
			toggle(Lang.tr("aerion.feature.zoomScroll"), HudConfig.bool(f, "scroll", true), v -> f.addProperty("scroll", v));
		} else if (detail.equals("freelook")) {
			toggle(Lang.tr("aerion.feature.freelookHold"), HudConfig.bool(f, "hold", true), v -> f.addProperty("hold", v));
		}
		for (Option o : OPTIONS.getOrDefault(detail, List.of())) option(f, o);
		if (detail.equals("badges")) badgePick(f);
		if (detail.equals("hurtCam")) note(Lang.tr("aerion.hint.hurtCam"));
		if (detail.equals("chat")) chatOpacity();
		if (y == cardTop) note(Lang.tr(Features.key(detail) != null ? "aerion.hint.noOptions" : "aerion.hint.noOptionsSwitch"));
		endCard();
		if (List.of("freelook", "fullbright", "hitboxes", "overlays").contains(detail)) note(Lang.tr("aerion.panel.serverWarning"));
	}

	/** Which of your badges shows next to your own name (others see the first one on the list). */
	private void badgePick(JsonObject f) {
		List<String> owned = pl.aerion.mod.Badges.mine();
		if (owned.isEmpty()) {
			note(Lang.tr("aerion.badge.none"));
			return;
		}
		List<String> values = new ArrayList<>(List.of("auto", "none"));
		values.addAll(owned);
		String[] ids = values.toArray(new String[0]);
		cycle(Lang.tr("aerion.badge.mine"), ids, choiceLabels(ids), HudConfig.opt(f, "mine", "auto"), v -> setOption(f, "mine", v));
		note(Lang.tr("aerion.badge.hint"));
	}

	/** The game's own chat options, here because they belong with the rest. */
	private void chatOpacity() {
		var options = Minecraft.getInstance().options;
		slider(Lang.tr("aerion.chat.background"), (int) Math.round(options.textBackgroundOpacity().get() * 100), 0, 100, 5, "%", v -> {
			options.textBackgroundOpacity().set(v / 100.0);
			gameOptionsChanged = true;
		});
		slider(Lang.tr("aerion.chat.text"), (int) Math.round(options.chatOpacity().get() * 100), 10, 100, 5, "%", v -> {
			options.chatOpacity().set(v / 100.0);
			gameOptionsChanged = true;
		});
	}

	/** The crosshair on a sample of sky and ground. */
	private void crosshairPreview(JsonObject f) {
		int h = 34, k = Gfx.guiScale();
		g.gradient(x, y, x + colW, y + h / 2, 0xFF6F9BE8, 0xFFA8C8FF);
		g.gradient(x, y + h / 2, x + colW, y + h, 0xFF6A9A45, 0xFF4B7D2E);
		g.push();
		g.scale(1f / k);
		Crosshair.paint(g, f, (x + colW / 2) * k, (y + h / 2) * k, 0xFF000000 | Chroma.parse(HudConfig.opt(f, "color", "#ffffff"), 0xFFFFFFFF));
		g.pop();
		y += h + 5;
	}

	private void option(JsonObject m, Option o) {
		option(m, o, detail);
	}

	private void option(JsonObject m, Option o, String owner) {
		// A module's own wording when it has one, else the shared one ("Text format", "Color by value").
		String own = Lang.get("aerion.opt." + owner + "." + o.key);
		String label = own != null ? own : Lang.tr("aerion.opt." + o.key);
		String value = HudConfig.opt(m, o.key, o.def);
		if (o.isToggle()) {
			toggle(label, "1".equals(value), v -> setOption(m, o.key, v ? "1" : "0"));
		} else if (o.color) {
			color(owner + "." + o.key, label, new String[] {"accent"}, new String[] {Lang.tr("aerion.style.accent")}, value, v -> setOption(m, o.key, v));
		} else if (o.choices != null) {
			String[] labels = o.labels;
			if (labels == null) {
				labels = new String[o.choices.length];
				for (int i = 0; i < labels.length; i++) labels[i] = Lang.tr("aerion.choice." + o.choices[i]);
			}
			choice(label, o.choices, labels, value, v -> setOption(m, o.key, v));
		} else {
			int n;
			try {
				n = Integer.parseInt(value);
			} catch (NumberFormatException e) {
				n = Integer.parseInt(o.def);
			}
			slider(label, n, o.min, o.max, 1, "", v -> setOption(m, o.key, String.valueOf(v)));
		}
	}

	private static void setOption(JsonObject m, String key, String value) {
		JsonObject options;
		if (m.get("options") instanceof JsonObject o) {
			options = o;
		} else {
			options = new JsonObject();
			m.add("options", options);
		}
		options.addProperty(key, value);
	}

	// --- look --------------------------------------------------------------------------------
	/** Reads and writes one look: the shared one, or a module's values on top of it. */
	private final class Look {
		final JsonObject shared, module;

		Look(JsonObject shared, JsonObject module) {
			this.shared = shared;
			this.module = module;
		}

		JsonObject own() {
			return module != null && module.get("style") instanceof JsonObject s ? s : null;
		}

		boolean overridden(String key) {
			JsonObject o = own();
			return o != null && o.has(key);
		}

		JsonObject read(String key) {
			return overridden(key) ? own() : shared;
		}

		JsonObject write() {
			if (module == null) return shared;
			JsonObject o = own();
			if (o == null) {
				o = new JsonObject();
				module.add("style", o);
			}
			return o;
		}

		void reset(String key) {
			JsonObject o = own();
			if (o == null) return;
			o.remove(key);
			if (o.isEmpty()) module.remove("style");
			changed = true;
		}

		/** The arrow at the end of a row, when this module differs from the shared look. */
		void mark(String key, int rowY) {
			if (!overridden(key)) return;
			int ix = x + colW - 11;
			int iy = rowY + ROW_PAD + 2;
			boolean over = inside(ix, iy, 11, 11);
			Icons.draw(g, "undo", ix + 1, iy + 1, 9, over ? text : muted);
			hit(ix, iy, 11, 11, f -> reset(key), false);
		}
	}

	private void lookPage() {
		HudConfig cfg = AerionClient.hud();
		beginCard(null);
		toggle(Lang.tr("aerion.panel.hud"), cfg.enabled(), v -> cfg.root.addProperty("enabled", v));
		toggle(Lang.tr("aerion.style.animations"), animationsOn(), v -> cfg.root.addProperty("animations", v));
		endCard();
		beginCard(Lang.tr("aerion.sec.presets"));
		beginRow("presets");
		int bx = x, by = y + 2;
		String active = activePreset(cfg.style());
		for (String id : PRESETS) {
			String label = Lang.tr("aerion.preset." + id);
			int w = Math.round(Txt.width(label, SMALL)) + 14;
			smallButton(bx, by, w, ROW - 4, label, id.equals(active), () -> {
				// Picking the active look again takes it off: back to the default one.
				applyPreset(cfg.style(), id.equals(active) && !id.equals("default") ? "default" : id);
				changed = true;
			});
			bx += w + 3;
		}
		if (active == null) Txt.draw(g, Lang.tr("aerion.preset.custom"), bx + 4, by + (ROW - 4) / 2f, SMALL, muted);
		y += ROW;
		endRow();
		note(Lang.tr("aerion.hint.presets"));
		endCard();
		lookFields(new Look(cfg.style(), null));
	}

	private static final String[] PRESETS = {"default", "minimal", "glass", "neon", "retro"};

	/** What an unset look value means. */
	private static final Map<String, String> LOOK_DEFAULTS = Map.ofEntries(
			Map.entry("text", "theme"), Map.entry("background", "theme"), Map.entry("opacity", "55"), Map.entry("border", "none"),
			Map.entry("borderWidth", "1"), Map.entry("radius", "0"), Map.entry("padding", "3"), Map.entry("shadow", "true"),
			Map.entry("align", "left"), Map.entry("font", "smooth"), Map.entry("outline", "false"), Map.entry("gradient", "none"),
			Map.entry("bar", "none"), Map.entry("chromaWave", "false"));

	/** The values a preset sets; everything else it leaves at the default. */
	private static Map<String, String> presetValues(String id) {
		return switch (id) {
			case "minimal" -> Map.of("background", "none", "border", "none", "outline", "true", "shadow", "false", "padding", "2");
			case "glass" -> Map.of("background", "theme", "opacity", "40", "gradient", "#000000", "radius", "4", "padding", "4", "border", "#ffffff", "shadow", "true");
			case "neon" -> Map.of("background", "theme", "opacity", "75", "border", "accent", "radius", "2", "bar", "left");
			case "retro" -> Map.of("font", "game", "background", "#000000", "opacity", "60", "border", "#ffffff", "shadow", "true", "padding", "3");
			default -> Map.of();
		};
	}

	/** The ready-made look the shared style is exactly, or null when it was changed by hand. */
	static String activePreset(JsonObject style) {
		for (String id : PRESETS) {
			Map<String, String> values = presetValues(id);
			boolean same = true;
			for (var e : LOOK_DEFAULTS.entrySet()) {
				String have = HudConfig.str(style, e.getKey(), e.getValue());
				if (!have.equals(values.getOrDefault(e.getKey(), e.getValue()))) {
					same = false;
					break;
				}
			}
			if (same) return id;
		}
		return null;
	}

	/** Menu animations (springy window and tiles); also read by the launcher's switch. */
	static boolean animationsOn() {
		HudConfig cfg = AerionClient.hud();
		// What the HUD file says, else what the theme says.
		pl.aerion.mod.Theme t = AerionClient.theme();
		return cfg != null && cfg.root.has("animations") ? HudConfig.bool(cfg.root, "animations", true) : t == null || t.animations;
	}

	/** A whole look in one click: the shared style's values are replaced, module overrides stay. */
	private static void applyPreset(JsonObject st, String id) {
		for (String k : LOOK_DEFAULTS.keySet()) st.remove(k);
		for (var e : presetValues(id).entrySet()) {
			String v = e.getValue();
			if (v.equals("true") || v.equals("false")) st.addProperty(e.getKey(), Boolean.parseBoolean(v));
			else if (v.chars().allMatch(Character::isDigit)) st.addProperty(e.getKey(), Integer.parseInt(v));
			else st.addProperty(e.getKey(), v);
		}
	}

	private void moduleLook(HudConfig cfg, JsonObject m) {
		note(Lang.tr("aerion.look.ownHint"));
		Look look = new Look(cfg.style(), m);
		lookFields(look);
		if (look.own() != null) {
			y += 2;
			String label = Lang.tr("aerion.look.useShared");
			int w = Math.round(Txt.width(label, SMALL)) + 14;
			smallButton(x, y, w, 13, label, false, () -> {
				m.remove("style");
				changed = true;
			});
			y += 16;
		}
	}

	private static boolean usesChroma(Look l) {
		for (String key : new String[] {"text", "background", "border"}) {
			if (Chroma.VALUE.equals(HudConfig.str(l.read(key), key, ""))) return true;
		}
		return false;
	}

	private void lookFields(Look l) {
		String theme = Lang.tr("aerion.style.theme"), none = Lang.tr("aerion.style.none"), acc = Lang.tr("aerion.style.accent");
		reserve = l.module != null ? 14 : 0;

		beginCard(Lang.tr("aerion.sec.text"));
		int top = y;
		color("text", Lang.tr("aerion.style.text"), new String[] {"theme"}, new String[] {theme}, HudConfig.str(l.read("text"), "text", "theme"), v -> l.write().addProperty("text", v));
		l.mark("text", top);
		if (Chroma.VALUE.equals(HudConfig.str(l.read("text"), "text", "theme"))) {
			top = y;
			toggle(Lang.tr("aerion.style.chromaWave"), HudConfig.bool(l.read("chromaWave"), "chromaWave", false), v -> l.write().addProperty("chromaWave", v));
			l.mark("chromaWave", top);
		}
		top = y;
		choiceRow(Lang.tr("aerion.style.font"), new String[] {"game", "smooth"}, new String[] {Lang.tr("aerion.style.game"), Lang.tr("aerion.style.smooth")}, HudConfig.str(l.read("font"), "font", "smooth"), v -> l.write().addProperty("font", v));
		l.mark("font", top);
		top = y;
		toggle(Lang.tr("aerion.style.shadow"), HudConfig.bool(l.read("shadow"), "shadow", true), v -> l.write().addProperty("shadow", v));
		l.mark("shadow", top);
		top = y;
		toggle(Lang.tr("aerion.style.outline"), HudConfig.bool(l.read("outline"), "outline", false), v -> l.write().addProperty("outline", v));
		l.mark("outline", top);
		top = y;
		choiceRow(Lang.tr("aerion.style.align"), new String[] {"left", "center", "right"}, new String[] {Lang.tr("aerion.style.left"), Lang.tr("aerion.style.center"), Lang.tr("aerion.style.right")}, HudConfig.str(l.read("align"), "align", "left"), v -> l.write().addProperty("align", v));
		l.mark("align", top);

		endCard();
		beginCard(Lang.tr("aerion.sec.background"));
		top = y;
		color("background", Lang.tr("aerion.style.background"), new String[] {"theme", "none"}, new String[] {theme, none}, HudConfig.str(l.read("background"), "background", "theme"), v -> l.write().addProperty("background", v));
		l.mark("background", top);
		if (usesChroma(l)) {
			top = y;
			slider(Lang.tr("aerion.style.chromaSpeed"), HudConfig.num(l.read("chromaSpeed"), "chromaSpeed", Chroma.DEFAULT_SPEED), 1, 10, 1, "", v -> l.write().addProperty("chromaSpeed", v));
			l.mark("chromaSpeed", top);
		}
		top = y;
		slider(Lang.tr("aerion.style.opacity"), HudConfig.num(l.read("opacity"), "opacity", 55), 0, 100, 5, "%", v -> l.write().addProperty("opacity", v));
		l.mark("opacity", top);
		top = y;
		color("gradient", Lang.tr("aerion.style.gradient"), new String[] {"none", "accent"}, new String[] {none, acc}, HudConfig.str(l.read("gradient"), "gradient", "none"), v -> l.write().addProperty("gradient", v));
		l.mark("gradient", top);
		top = y;
		slider(Lang.tr("aerion.style.padding"), HudConfig.num(l.read("padding"), "padding", 3), 0, 10, 1, "", v -> l.write().addProperty("padding", v));
		l.mark("padding", top);
		top = y;
		slider(Lang.tr("aerion.style.radius"), HudConfig.num(l.read("radius"), "radius", 0), 0, 8, 1, "", v -> l.write().addProperty("radius", v));
		l.mark("radius", top);

		endCard();
		beginCard(Lang.tr("aerion.sec.border"));
		top = y;
		String border = HudConfig.str(l.read("border"), "border", "none");
		color("border", Lang.tr("aerion.style.border"), new String[] {"none", "accent"}, new String[] {none, acc}, border, v -> l.write().addProperty("border", v));
		l.mark("border", top);
		top = y;
		String[] bars = {"none", "left", "top", "right", "bottom"};
		choice(Lang.tr("aerion.style.bar"), bars, choiceLabels(bars), HudConfig.str(l.read("bar"), "bar", "none"), v -> l.write().addProperty("bar", v));
		l.mark("bar", top);
		if (!"none".equals(border)) {
			top = y;
			slider(Lang.tr("aerion.style.borderWidth"), HudConfig.num(l.read("borderWidth"), "borderWidth", 1), 1, 4, 1, "", v -> l.write().addProperty("borderWidth", v));
			l.mark("borderWidth", top);
		}
		endCard();
		reserve = 0;
	}

	// --- sounds ------------------------------------------------------------------------------
	private static List<String> allSounds() {
		if (allSounds == null) {
			List<String> ids = new ArrayList<>();
			for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) ids.add(id.toString());
			java.util.Collections.sort(ids);
			allSounds = ids;
		}
		return allSounds;
	}

	private static String groupOf(String id) {
		String path = id.substring(id.indexOf(':') + 1);
		int dot = path.indexOf('.');
		String first = dot < 0 ? path : path.substring(0, dot);
		return switch (first) {
			case "block", "entity", "item" -> first;
			case "ambient", "weather" -> "ambient";
			case "music", "music_disc" -> "music";
			default -> "other";
		};
	}

	private List<String> shownSounds() {
		List<String> source = soundGroup.equals("recent") ? Sounds.recent() : allSounds();
		// Spaces match the underscores and dots of the ids, as the names are shown with spaces.
		String q = query.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("[\\s·]+", "_");
		List<String> out = new ArrayList<>();
		for (String id : source) {
			boolean group = soundGroup.equals("all") || soundGroup.equals("recent") || groupOf(id).equals(soundGroup);
			if (group && (q.isEmpty() || id.replace('.', '_').contains(q))) out.add(id);
		}
		return out;
	}

	private void soundsPage() {
		HudConfig cfg = AerionClient.hud();
		JsonObject f = cfg.feature("sounds");
		toggle(Lang.tr("aerion.sound.enable"), HudConfig.bool(f, "enabled", true), v -> f.addProperty("enabled", v));
		if (HudConfig.bool(f, "enabled", true) && MixinGate.skipped().contains("sounds")) note(Lang.tr("aerion.hint.restart"));
		// Groups, wrapping to a second row when the labels don't fit.
		int bx = x;
		for (String group : SOUND_GROUPS) {
			String label = Lang.tr("aerion.sound." + group);
			int w = Math.round(Txt.width(label, SMALL)) + 10;
			if (bx + w > x + colW) {
				bx = x;
				y += 14;
			}
			smallButton(bx, y, w, 12, label, group.equals(soundGroup), () -> {
				soundGroup = group;
				scroll = 0;
			});
			bx += w + 2;
		}
		y += 16;
		// Typing goes straight into the search box.
		String reset = Lang.tr("aerion.sound.resetAll");
		int resetW = Math.round(Txt.width(reset, SMALL)) + 10, boxW = colW - resetW - 4;
		Draw.control(g, x, y, boxW, 13, 2, line, tile);
		Icons.draw(g, "search", x + 3, y + 2.5f, 8, muted);
		boolean blink = System.currentTimeMillis() / 500 % 2 == 0;
		if (query.isEmpty()) Txt.draw(g, Lang.tr("aerion.sound.search"), x + 14, y + 6.5f, SMALL, muted);
		else Txt.draw(g, query + (blink ? "|" : ""), x + 14, y + 6.5f, SMALL, text);
		smallButton(x + boxW + 4, y, resetW, 13, reset, false, () -> {
			f.remove("options");
			changed = true;
		});
		y += 17;
		List<String> list = shownSounds();
		if (list.isEmpty()) {
			note(Lang.tr(soundGroup.equals("recent") && query.isEmpty() ? "aerion.sound.recentEmpty" : "aerion.sound.empty"));
			return;
		}
		// There are well over a thousand sounds: only the rows on screen are drawn.
		for (String id : list) {
			if (y + 14 > viewTop && y < viewBottom) soundRow(f, id);
			y += 14;
		}
	}

	private void soundRow(JsonObject f, String id) {
		String shown = id.startsWith("minecraft:") ? id.substring(10) : id;
		int value;
		try {
			value = Integer.parseInt(HudConfig.opt(f, id, "100"));
		} catch (NumberFormatException e) {
			value = 100;
		}
		int bx = x + colW - 110, w = 92;
		// "ambient.basalt_deltas.additions" reads as a faint "ambient" and "Basalt deltas · additions".
		int dot = shown.indexOf('.');
		String kind = dot > 0 ? shown.substring(0, dot) : "";
		String label = soundName(dot > 0 ? shown.substring(dot + 1) : shown);
		float kindW = kind.isEmpty() ? 0 : Txt.width(kind, SMALL) + 5;
		float room = bx - x - 6 - kindW;
		if (Txt.width(label, SMALL) > room) {
			while (label.length() > 1 && Txt.width(label + "…", SMALL) > room) label = label.substring(0, label.length() - 1);
			label = label + "…";
		}
		if (!kind.isEmpty()) Txt.draw(g, kind, x, y + 7, SMALL, Colors.mix(surface, muted, 0.6f));
		Txt.draw(g, label, x + kindW, y + 7, SMALL, value == 100 ? muted : text);
		int trackW = w - 26, ty = y + 6;
		Draw.rounded(g, bx, ty, trackW, 3, 1, track);
		int fill = Math.round(trackW * value / 100f);
		if (fill > 0) Draw.rounded(g, bx, ty, fill, 3, 1, value == 100 ? muted : accent);
		Draw.rounded(g, bx + Math.max(0, Math.min(trackW - 6, fill - 3)), ty - 2, 6, 7, 3, value == 100 ? text : accent);
		String pct = value + "%";
		Txt.draw(g, pct, bx + w - Txt.width(pct, SMALL), y + 7.5f, SMALL, value == 100 ? muted : text);
		hit(bx, y, trackW, 14, fr -> {
			int v = (int) Math.round(fr * 20) * 5;
			if (v == 100) {
				if (f.get("options") instanceof JsonObject o) o.remove(id);
			} else {
				setOption(f, id, String.valueOf(v));
			}
			changed = true;
		}, true);
		int px = bx + w + 2;
		boolean over = inside(px, y + 1, 14, 12);
		if (over) Draw.rounded(g, px, y + 1, 14, 12, 2, control);
		Icons.draw(g, "play", px + 3, y + 3, 8, over ? text : muted);
		hit(px, y + 1, 14, 12, fr -> preview(id), false);
	}

	/** A sound path for people: underscores to spaces, dots to separators, first letter capital. */
	static String soundName(String path) {
		String s = path.replace('_', ' ').replace(".", " · ");
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static void preview(String id) {
		SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id));
		if (event != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, 1f));
	}

	// --- waypoints ---------------------------------------------------------------------------
	private void waypointsPage() {
		HudConfig cfg = AerionClient.hud();
		JsonObject f = cfg.feature("waypoints");
		beginCard(null);
		toggle(Lang.tr("aerion.waypoint.show"), HudConfig.bool(f, "enabled", false), v -> f.addProperty("enabled", v));
		if (HudConfig.bool(f, "enabled", false) && MixinGate.skipped().contains("waypoints")) note(Lang.tr("aerion.hint.restart"));
		for (Option o : List.of(Option.toggle("beam", true), Option.toggle("distance", true), Option.toggle("minimap", true), Option.toggle("death", true), Option.toggle("nether", true), Option.number("scale", 100, 40, 300))) {
			option(f, o, "waypoints");
		}
		endCard();
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		String add = Lang.tr("aerion.waypoint.add"), open = Lang.tr("aerion.map.open");
		int addW = Math.round(Txt.width(add, TEXT)) + 20;
		button(x, y, addW, 15, add, true, () -> {
			f.addProperty("enabled", true);
			changed = true;
			naming = Waypoints.addHere(Lang.tr("aerion.waypoint.default"));
			namingFresh = true;
		});
		button(x + addW + 6, y, Math.round(Txt.width(open, TEXT)) + 20, 15, open, false, () -> Page.go(new MapPage()));
		y += 19;
		List<Waypoints.Point> points = Waypoints.all();
		if (points.isEmpty()) {
			note(Lang.tr("aerion.waypoint.empty"));
			return;
		}
		// Nearest first; points in other dimensions after them.
		String here = Waypoints.dimension(player);
		List<Waypoints.Point> sorted = new ArrayList<>(points);
		sorted.sort(java.util.Comparator.comparingDouble(w -> {
			double[] at = Waypoints.position(w, here);
			return at == null ? Double.MAX_VALUE : player.distanceToSqr(at[0] + 0.5, at[1], at[2] + 0.5);
		}));
		beginCard(null);
		for (Waypoints.Point w : sorted) waypointRow(w, player);
		endCard();
	}

	private void waypointRow(Waypoints.Point w, net.minecraft.client.player.LocalPlayer player) {
		String id = "wp" + System.identityHashCode(w);
		beginRow(id);
		int rowY = y, right = x + colW;
		Draw.control(g, x, rowY + 3, 10, 10, 2, line, Waypoints.color(w));
		hit(x, rowY + 2, 10, 12, f -> expanded = id.equals(expanded) ? null : id, false);
		boolean editing = naming == w;
		String shown = editing ? w.name + (System.currentTimeMillis() / 500 % 2 == 0 ? "|" : "") : w.name;
		Txt.draw(g, ellipsize(shown, colW - 156), x + 15, rowY + ROW / 2f, TEXT, w.enabled ? text : muted);
		hit(x + 15, rowY, colW - 156, ROW, f -> naming = w, false);
		double[] at = Waypoints.position(w, Waypoints.dimension(player));
		String where = at != null
				? Math.round(Math.sqrt(player.distanceToSqr(at[0] + 0.5, at[1], at[2] + 0.5))) + " m"
				: Lang.tr("aerion.waypoint.otherDim");
		Txt.draw(g, where, right - 52 - Txt.width(where, SMALL), rowY + ROW / 2f, SMALL, muted);
		iconButton(right - 47, rowY + 1, 14, "settings", () -> expanded = id.equals(expanded) ? null : id);
		iconButton(right - 31, rowY + 1, 14, w.enabled ? "freelook" : "hide", () -> {
			w.enabled = !w.enabled;
			Waypoints.save();
		});
		iconButton(right - 15, rowY + 1, 14, "close", () -> Waypoints.remove(w));
		y += ROW;
		endRow();
		if (!id.equals(expanded)) return;
		color(id, Lang.tr("aerion.waypoint.color"), new String[] {}, new String[] {}, w.color, v -> w.color = v);
		textRow(id + "x", "X", String.valueOf(w.x), "0", v -> setCoord(w, 0, v));
		textRow(id + "y", "Y", String.valueOf(w.y), "64", v -> setCoord(w, 1, v));
		textRow(id + "z", "Z", String.valueOf(w.z), "0", v -> setCoord(w, 2, v));
		String share = Lang.tr("aerion.waypoint.share"), map = Lang.tr("aerion.waypoint.onMap"), here = Lang.tr("aerion.waypoint.moveHere");
		int bx = x;
		for (String[] b : new String[][] {{"share", share}, {"map", map}, {"here", here}}) {
			int bw = Math.round(Txt.width(b[1], SMALL)) + 14;
			smallButton(bx, y, bw, 13, b[1], false, () -> {
				switch (b[0]) {
					case "share" -> Waypoints.share(w);
					case "map" -> Page.go(new MapPage(w.x, w.z));
					default -> {
						w.x = player.getBlockX();
						w.y = player.getBlockY();
						w.z = player.getBlockZ();
						w.dimension = Waypoints.dimension(player);
						Waypoints.save();
					}
				}
			});
			bx += bw + 4;
		}
		y += 19;
	}

	/** A coordinate typed into a waypoint's field; anything but a whole number is ignored. */
	private static void setCoord(Waypoints.Point w, int axis, String v) {
		try {
			int n = Math.max(-30_000_000, Math.min(30_000_000, Integer.parseInt(v.strip())));
			if (axis == 0) w.x = n;
			else if (axis == 1) w.y = n;
			else w.z = n;
			Waypoints.save();
		} catch (NumberFormatException e) {
			// Not a number: the old value stays.
		}
	}

	// --- keys --------------------------------------------------------------------------------
	private void keyTab() {
		KeyMapping k = Features.key(detail);
		if (k == null) {
			note(Lang.tr("aerion.key.none.feature"));
			return;
		}
		String label = switch (detail) {
			case "zoom", "freelook", "perspective", "fovKey" -> Lang.tr("aerion.key.hold");
			case "toggleSprint" -> Lang.tr("aerion.key.sprint");
			case "toggleSneak" -> Lang.tr("aerion.key.sneak");
			default -> Lang.tr("aerion.key.toggle");
		};
		beginCard(null);
		keyRow(label, k);
		if (detail.equals("fullbright")) {
			keyRow(Lang.tr("aerion.key.brighter"), Features.key("fullbrightUp"));
			keyRow(Lang.tr("aerion.key.darker"), Features.key("fullbrightDown"));
		}
		endCard();
		note(Lang.tr("aerion.hint.keys"));
	}

	private void keysPage() {
		keyboard();
		if (pickedKey != null) {
			List<KeyMapping> on = KeyBoard.byKey().getOrDefault(pickedKey, List.of());
			beginCard(Lang.tr("aerion.key.onKey").replace("{}", keyName(pickedKey)));
			if (on.isEmpty()) note(Lang.tr("aerion.key.free"));
			for (KeyMapping k : on) keyRow(Component.translatable(k.getName()).getString(), k);
			endCard();
			return;
		}
		beginCard(null);
		keyRow(Lang.tr("aerion.key.menu"), Features.key("menu"));
		endCard();
		beginCard(Lang.tr("aerion.key.features"));
		keyRow(Lang.tr("aerion.feature.zoom"), Features.key("zoom"));
		keyRow(Lang.tr("aerion.feature.freelook"), Features.key("freelook"));
		keyRow(Lang.tr("aerion.feature.fullbright"), Features.key("fullbright"));
		keyRow(Lang.tr("aerion.feature.fullbright") + ": " + Lang.tr("aerion.key.brighter"), Features.key("fullbrightUp"));
		keyRow(Lang.tr("aerion.feature.fullbright") + ": " + Lang.tr("aerion.key.darker"), Features.key("fullbrightDown"));
		keyRow(Lang.tr("aerion.feature.fovLock"), Features.key("fovLock"));
		keyRow(Lang.tr("aerion.feature.hitboxes"), Features.key("hitboxes"));
		keyRow(Lang.tr("aerion.feature.overlays"), Features.key("overlays"));
		keyRow(Lang.tr("aerion.nav.sounds"), Features.key("sounds"));
		keyRow(Lang.tr("aerion.feature.crosshair"), Features.key("crosshair"));
		keyRow(Lang.tr("aerion.feature.hurtCam"), Features.key("hurtCam"));
		keyRow(Lang.tr("aerion.feature.lightLevels"), Features.key("lightLevels"));
		keyRow(Lang.tr("aerion.feature.blockOutline"), Features.key("blockOutline"));
		keyRow(Lang.tr("aerion.feature.chat"), Features.key("chat"));
		keyRow(Lang.tr("aerion.feature.perspective"), Features.key("perspective"));
		keyRow(Lang.tr("aerion.feature.fovKey"), Features.key("fovKey"));
		keyRow(Lang.tr("aerion.feature.worldLook"), Features.key("worldLook"));
		keyRow(Lang.tr("aerion.feature.hitColor"), Features.key("hitColor"));
		keyRow(Lang.tr("aerion.feature.screens"), Features.key("screens"));
		keyRow(Lang.tr("aerion.waypoint.add"), Features.key("waypointAdd"));
		keyRow(Lang.tr("aerion.map.title"), Features.key("map"));
		keyRow(Lang.tr("key.aerion.profile"), Features.key("profile"));
		keyRow(Lang.tr("aerion.key.sprint"), Features.key("toggleSprint"));
		keyRow(Lang.tr("aerion.key.sneak"), Features.key("toggleSneak"));
		endCard();
		beginCard(Lang.tr("aerion.key.modules"));
		for (String id : Features.MODULE_IDS) keyRow(Lang.tr("aerion.module." + id), Features.key(id));
		endCard();
	}

	/** A key's name as the game shows it. */
	private static String keyName(String key) {
		return InputConstants.getKey(key).getDisplayName().getString();
	}

	/**
	 * The keyboard and mouse as in the launcher: bound keys in the accent, keys whose actions clash
	 * in red. Clicking a key lists its actions; while a binding waits, clicking a key binds it.
	 */
	private void keyboard() {
		Map<String, List<KeyMapping>> byKey = KeyBoard.byKey();
		int u = Math.max(10, (int) ((colW - 2) / KeyBoard.WIDTH));
		int top = y + 2, left = x + Math.max(0, (colW - Math.round(u * KeyBoard.WIDTH)) / 2);
		int conflicts = 0;
		for (List<KeyMapping> l : byKey.values()) if (KeyBoard.clashes(l)) conflicts++;
		keyBlock(KeyBoard.MAIN, left, top, u, byKey);
		keyBlock(KeyBoard.NAV, left + Math.round(15.5f * u), top, u, byKey);
		y = top + 6 * u + u / 4 + 4;
		String mouse = Lang.tr("aerion.key.mouse");
		Txt.draw(g, mouse, left, y + u / 2f, SMALL, muted);
		float mx = left + Txt.width(mouse, SMALL) + 6;
		for (KeyBoard.Cap c : KeyBoard.MOUSE) {
			keyCap(c, Math.round(mx), y, Math.round(c.w() * u) - 1, u - 1, byKey);
			mx += c.w() * u;
		}
		String status = conflicts > 0 ? Lang.tr("aerion.key.conflicts").replace("{}", String.valueOf(conflicts)) : Lang.tr("aerion.key.noConflicts");
		Txt.draw(g, status, mx + 8, y + u / 2f, SMALL, conflicts > 0 ? 0xFFFF6B6B : muted);
		if (pickedKey != null) {
			String all = Lang.tr("aerion.key.showAll");
			int w = Math.round(Txt.width(all, SMALL)) + 12;
			smallButton(x + colW - w, y, w, u - 1, all, false, () -> pickedKey = null);
		}
		y += u + 6;
		if (capturing != null) {
			Txt.draw(g, Lang.tr("aerion.key.pressFor").replace("{}", Component.translatable(capturing.getName()).getString()), x + 2, y + 4, SMALL, accent);
			y += 11;
		}
	}

	private void keyBlock(List<List<KeyBoard.Cap>> rows, int left, int top, int u, Map<String, List<KeyMapping>> byKey) {
		for (int r = 0; r < rows.size(); r++) {
			float cx = left;
			int cy = top + r * u + (r > 0 ? u / 4 : 0);
			for (KeyBoard.Cap c : rows.get(r)) {
				if (c.name() != null) keyCap(c, Math.round(cx), cy, Math.round(c.w() * u) - 1, u - 1, byKey);
				cx += c.w() * u;
			}
		}
	}

	private void keyCap(KeyBoard.Cap c, int cx, int cy, int w, int h, Map<String, List<KeyMapping>> byKey) {
		List<KeyMapping> on = byKey.get(c.name());
		boolean clash = KeyBoard.clashes(on), bound = on != null && !on.isEmpty(), over = inside(cx, cy, w, h);
		int red = 0xFFE5484D;
		int fill = clash ? Colors.mix(control, red, 0.45f) : bound ? Colors.mix(control, accent, 0.45f) : over ? track : control;
		int edge = clash ? red : bound || over ? accent : line;
		Draw.control(g, cx, cy, w, h, 2, edge, fill);
		if (c.name().equals(pickedKey)) Draw.outline(g, cx - 1, cy - 1, w + 2, h + 2, text);
		float size = Math.min(SMALL, h * 0.42f);
		String label = c.label();
		if (Txt.width(label, size) > w - 2) label = label.substring(0, Math.min(2, label.length()));
		Txt.center(g, label, cx + w / 2f, cy + h / 2f, size, bound || clash ? text : muted);
		if (clash) Txt.draw(g, String.valueOf(on.size()), cx + w - 5, cy + 3, 5f, text);
		hit(cx, cy, w, h, f -> {
			if (capturing != null) {
				bind(InputConstants.getKey(c.name()));
				capturing = null;
			} else {
				pickedKey = c.name().equals(pickedKey) ? null : c.name();
				scroll = 0;
			}
		}, false);
	}

	private void keyRow(String label, KeyMapping k) {
		if (k == null) return;
		beginRow("key:" + label);
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		int bx = controlX(), w = x + colW - bx - 15;
		boolean listening = capturing == k;
		if (k.isUnbound() && !listening) {
			// An empty slot stays quiet, so the bound keys stand out in the list.
			boolean over = inside(bx, y + 1, w, ROW - 3);
			Draw.control(g, bx, y + 1, w, ROW - 3, 3, over ? track : control, over ? control : tile);
			Txt.center(g, Lang.tr("aerion.key.none"), bx + w / 2f, y + 1 + (ROW - 3) / 2f, SMALL, muted);
			hit(bx, y + 1, w, ROW - 3, f -> capturing = k, false);
		} else {
			String shown = listening ? Lang.tr("aerion.key.press") : k.getTranslatedKeyMessage().getString();
			button(bx, y + 1, w, ROW - 3, shown, listening, () -> capturing = k);
			iconButton(bx + w + 2, y + 1, ROW - 3, "close", () -> {
				capturing = k;
				bind(InputConstants.UNKNOWN);
				capturing = null;
			});
		}
		y += ROW;
		if (!k.isUnbound()) {
			for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
				if (other != k && other.same(k) && KeyBoard.context(other).equals(KeyBoard.context(k))) {
					String who = Component.translatable(other.getName()).getString();
					Txt.draw(g, Lang.tr("aerion.key.taken").replace("{}", who), bx, y + 3, SMALL, 0xFFFFAA33);
					y += 9;
					break;
				}
			}
		}
		endRow();
	}

	// --- controls ----------------------------------------------------------------------------
	/** Room kept free at the end of rows for the arrow that restores a shared value. */
	private int reserve;

	private int controlX() {
		return x + Math.min(112, colW * 45 / 100);
	}

	private int controlW() {
		return x + colW - controlX() - reserve;
	}

	private void hit(int hx, int hy, int w, int h, DoubleConsumer action, boolean slider) {
		hits.add(new Hit(hx, hy, w, h, action, slider, clipping));
	}

	private boolean inside(int cx, int cy, int w, int h) {
		if (clipping && (mouseY < viewTop || mouseY >= viewBottom)) return false;
		return mouseX >= cx && mouseY >= cy && mouseX < cx + w && mouseY < cy + h;
	}

	/** A control's fill: the accent when chosen (as in the sidebar), lighter under the pointer. */
	private int body(boolean on, boolean over) {
		return on ? (over ? Colors.mix(accent, 0xFFFFFFFF, 0.08f) : accent) : over ? track : control;
	}

	private void button(int bx, int by, int w, int h, String label, boolean on, Runnable action) {
		boolean over = inside(bx, by, w, h);
		Draw.rounded(g, bx, by, w, h, 3, body(on, over));
		Txt.center(g, label, bx + w / 2f, by + h / 2f, TEXT, on ? accentText : text);
		hit(bx, by, w, h, f -> action.run(), false);
	}

	private void smallButton(int bx, int by, int w, int h, String label, boolean on, Runnable action) {
		boolean over = inside(bx, by, w, h);
		Draw.rounded(g, bx, by, w, h, 3, body(on, over));
		Txt.center(g, label, bx + w / 2f, by + h / 2f, SMALL, on ? accentText : over ? text : muted);
		hit(bx, by, w, h, f -> action.run(), false);
	}

	private void iconButton(int bx, int by, int size, String icon, Runnable action) {
		boolean over = inside(bx, by, size, size);
		if (over) Draw.rounded(g, bx, by, size, size, 3, control);
		Icons.draw(g, icon, bx + 2, by + 2, size - 4, over ? text : muted);
		hit(bx, by, size, size, f -> action.run(), false);
	}

	private float ease(String key, float target) {
		float v = eased.getOrDefault(key, target);
		v += (target - v) * Math.min(1f, dt * 16f);
		if (Math.abs(target - v) < 0.01f) v = target;
		eased.put(key, v);
		return v;
	}

	private void switchAt(String key, int sx, int sy, boolean on) {
		float t = ease("sw" + key, on ? 1 : 0);
		Draw.rounded(g, sx, sy, 20, 9, 4, Colors.mix(track, accent, t));
		Draw.rounded(g, sx + 1 + Math.round(11 * t), sy + 1, 7, 7, 3, Colors.mix(muted, accentText, t));
	}

	/** A group of settings: a quiet heading over panels of their own (see {@link #beginRow}). */
	private void beginCard(String title) {
		cardKey = section + ":" + detail + ":" + sub + ":" + cardIndex++;
		cardTop = y;
		if (title != null) {
			Txt.draw(g, title.toUpperCase(Locale.ROOT), x + 2, y + 5, 6.5f, muted);
			y += 13;
		}
	}

	private void endCard() {
		y += 6;
	}

	/** Heights of setting panels last frame, so a panel can be drawn before its contents. */
	private final Map<String, Integer> rows = new HashMap<>();
	private String rowKey;
	private int rowTop;

	/** One setting on its own panel, lighter under the pointer; the contents go inside it. */
	private void beginRow(String key) {
		rowKey = section + ":" + detail + ":" + sub + ":" + key;
		rowTop = y;
		int h = rows.getOrDefault(rowKey, ROW + 2 * ROW_PAD);
		boolean over = inside(x, y, colW, h);
		Draw.rounded(g, x, y, colW, h, 4, Colors.mix(tile, control, ease("row" + rowKey, over ? 1 : 0)));
		x += ROW_IN;
		colW -= 2 * ROW_IN;
		y += ROW_PAD;
	}

	private void endRow() {
		y += ROW_PAD;
		rows.put(rowKey, y - rowTop);
		x -= ROW_IN;
		colW += 2 * ROW_IN;
		y += 3;
	}

	/** A module's description: readable text, a little apart from the settings under it. */
	private void description(String s) {
		y += 1;
		// Wrapped by the small size's widths, with room for the slightly larger letters.
		for (String row : wrap(s, colW - 16)) {
			Txt.draw(g, row, x + 2, y + 5, 7.5f, Colors.mix(muted, text, 0.45f));
			y += 11;
		}
		y += 6;
	}

	private void note(String s) {
		for (String row : wrap(s, colW)) {
			Txt.draw(g, row, x, y + 5, SMALL, muted);
			y += 10;
		}
		y += 3;
	}

	private static List<String> wrap(String s, int width) {
		List<String> rows = new ArrayList<>();
		StringBuilder row = new StringBuilder();
		for (String word : s.split(" ")) {
			String next = row.isEmpty() ? word : row + " " + word;
			if (Txt.width(next, SMALL) > width && !row.isEmpty()) {
				rows.add(row.toString());
				row = new StringBuilder(word);
			} else {
				row = new StringBuilder(next);
			}
		}
		if (!row.isEmpty()) rows.add(row.toString());
		return rows;
	}

	private void toggle(String label, boolean on, Consumer<Boolean> set) {
		beginRow(label);
		int w = colW - reserve;
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		String state = Lang.tr(on ? "aerion.state.on" : "aerion.state.off");
		Txt.draw(g, state, x + w - 24 - Txt.width(state, SMALL), y + ROW / 2f, SMALL, muted);
		switchAt(section + label, x + w - 20, y + 3, on);
		hit(x - ROW_IN, y - ROW_PAD, w + 2 * ROW_IN, ROW + 2 * ROW_PAD, f -> {
			set.accept(!on);
			changed = true;
		}, false);
		y += ROW;
		endRow();
	}

	/** Value slider; the number sits on the bar. */
	private void slider(String label, int value, int min, int max, int step, String unit, Consumer<Integer> set) {
		beginRow(label);
		int bx = controlX(), w = controlW() - 30, ty = y + ROW / 2 - 1;
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		Draw.rounded(g, bx, ty, w, 3, 1, track);
		float f = (float) (Math.max(min, Math.min(max, value)) - min) / Math.max(1, max - min);
		int fill = Math.round(w * f);
		if (fill > 0) Draw.rounded(g, bx, ty, fill, 3, 1, accent);
		boolean over = inside(bx, y, w, ROW);
		Draw.rounded(g, bx + Math.max(0, Math.min(w - 7, fill - 3)), ty - 2, 7, 7, 3, over ? 0xFFFFFFFF : accent);
		String shown = value + unit;
		Txt.draw(g, shown, bx + w + 30 - Txt.width(shown, SMALL), y + ROW / 2f, SMALL, muted);
		hit(bx, y, w, ROW, fr -> {
			int v = min + Math.round((float) (fr * (max - min)) / step) * step;
			set.accept(Math.max(min, Math.min(max, v)));
			changed = true;
		}, true);
		y += ROW;
		endRow();
	}

	/** Mutually exclusive buttons on the label's row, or under it when they don't fit. */
	private void choice(String label, String[] values, String[] labels, String current, Consumer<String> set) {
		float widest = 0;
		for (String l : labels) widest = Math.max(widest, Txt.width(l, SMALL));
		if (widest + 8 > (float) controlW() / values.length) {
			beginRow(label);
			Txt.draw(g, label, x, y + 6, TEXT, text);
			y += 13;
			segments(x, colW - reserve, values, labels, current, set);
			y += ROW - 4;
			endRow();
		} else {
			choiceRow(label, values, labels, current, set);
		}
	}

	private void choiceRow(String label, String[] values, String[] labels, String current, Consumer<String> set) {
		beginRow(label);
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		y += 2;
		segments(controlX(), controlW(), values, labels, current, set);
		y += ROW - 2;
		endRow();
	}

	private void segments(int sx, int width, String[] values, String[] labels, String current, Consumer<String> set) {
		int w = width / values.length, h = ROW - 4;
		Draw.rounded(g, sx, y, w * values.length, h, 3, control);
		for (int i = 0; i < values.length; i++) {
			String v = values[i];
			int cx = sx + i * w;
			boolean on = v.equals(current), over = inside(cx, y, w, h);
			if (on) Draw.rounded(g, cx + 1, y + 1, w - 2, h - 2, 2, accent);
			Txt.center(g, labels[i], cx + w / 2f, y + h / 2f, SMALL, on ? accentText : over ? text : muted);
			hit(cx, y, w, h, f -> {
				set.accept(v);
				changed = true;
			}, false);
		}
	}

	/** Named choices, chroma, and a custom colour whose picker opens on demand. */
	private void color(String id, String label, String[] words, String[] wordLabels, String current, Consumer<String> set) {
		beginRow("color:" + id);
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		// The chips' width: on the label's row when they fit, else on a row of their own below.
		float chips = Txt.width(Lang.tr("aerion.style.chroma"), SMALL) + Txt.width(Lang.tr("aerion.style.custom"), SMALL) + 31 + 2 * (words.length + 1);
		for (String l : wordLabels) chips += Txt.width(l, SMALL) + 10;
		int bx = controlX();
		if (bx + chips > x + colW - reserve) {
			y += ROW - 2;
			bx = x;
		}
		int by = y + 2, bh = ROW - 4;
		for (int i = 0; i < words.length; i++) {
			String v = words[i];
			int w = Math.round(Txt.width(wordLabels[i], SMALL)) + 10;
			smallButton(bx, by, w, bh, wordLabels[i], v.equals(current), () -> {
				set.accept(v);
				changed = true;
				if (id.equals(expanded)) expanded = null;
			});
			bx += w + 2;
		}
		boolean chroma = Chroma.VALUE.equals(current);
		String chromaLabel = Lang.tr("aerion.style.chroma");
		int cw = Math.round(Txt.width(chromaLabel, SMALL)) + 10;
		smallButton(bx, by, cw, bh, "", chroma, () -> {
			set.accept(Chroma.VALUE);
			changed = true;
			if (id.equals(expanded)) expanded = null;
		});
		// The word itself shows what chroma does.
		float pen = bx + 5;
		for (int cp : chromaLabel.codePoints().toArray()) {
			String ch = new String(Character.toChars(cp));
			Txt.draw(g, ch, pen, by + bh / 2f, SMALL, Chroma.color(-pen / 60f + 8f, Chroma.DEFAULT_SPEED));
			pen += Txt.width(ch, SMALL);
		}
		bx += cw + 2;
		boolean custom = current != null && current.startsWith("#");
		String word = Lang.tr("aerion.style.custom");
		int w = Math.round(Txt.width(word, SMALL)) + 10 + 11;
		String swatchValue = custom ? current : "#ffffff";
		smallButton(bx, by, w, bh, "", custom, () -> {
			if (!custom) {
				set.accept(swatchValue);
				changed = true;
				expanded = id;
			} else {
				expanded = id.equals(expanded) ? null : id;
			}
		});
		Txt.draw(g, word, bx + 5, by + bh / 2f, SMALL, custom ? accentText : muted);
		Draw.control(g, bx + w - 12, by + 2, 9, bh - 4, 1, 0x80000000, custom ? Theme.color(current, 0xFFFFFFFF) : 0xFFFFFFFF);
		y += ROW;
		if (id.equals(expanded) && custom) picker(id, current, set);
		endRow();
	}

	/** Saturation and brightness square, hue bar, preview, colour code and ready colours. */
	private void picker(String id, String current, Consumer<String> set) {
		int color = Theme.color(current, 0xFFFFFFFF);
		float[] kept = pickers.get(id);
		if (kept == null || (Colors.hsv(kept[0], kept[1], kept[2]) & 0xFFFFFF) != (color & 0xFFFFFF)) {
			kept = Colors.toHsv(color);
			pickers.put(id, kept);
		}
		float[] hsv = kept;
		Runnable apply = () -> {
			set.accept(hex(Colors.hsv(hsv[0], hsv[1], hsv[2])));
			changed = true;
		};
		int sx = controlX(), total = x + colW - sx - reserve, side = 54, w = total - side - 5, h = 56;
		for (int i = 0; i < w; i++) {
			g.gradient(sx + i, y, sx + i + 1, y + h, Colors.hsv(hsv[0], (float) i / Math.max(1, w - 1), 1f), 0xFF000000);
		}
		int mx = sx + Math.round(hsv[1] * (w - 1)), my = y + Math.round((1 - hsv[2]) * (h - 1));
		Draw.outline(g, mx - 1, my - 1, 3, 3, 0xFF000000);
		Draw.rounded(g, mx - 1, my - 1, 3, 3, 1, 0xFFFFFFFF);
		hit(sx, y, w, h, f -> {
			hsv[1] = (float) f;
			hsv[2] = 1f - (float) hitY;
			apply.run();
		}, true);
		// Preview and code on the right.
		int rx = sx + w + 5;
		Draw.rounded(g, rx, y, side, 26, 3, 0xFF000000 | color);
		boolean editing = id.equals(hexRow);
		Draw.control(g, rx, y + 30, side, 14, 2, editing ? accent : line, control);
		String shown = editing ? "#" + hexText + (System.currentTimeMillis() / 500 % 2 == 0 ? "|" : "") : current;
		Txt.center(g, shown, rx + side / 2f, y + 37, SMALL, text);
		hit(rx, y + 30, side, 14, f -> {
			hexRow = id;
			hexText = "";
			hexSet = set;
		}, false);
		y += h + 3;
		// Hue.
		for (int i = 0; i < total; i++) g.fill(sx + i, y, sx + i + 1, y + 6, Colors.hsv((float) i / total, 1f, 1f));
		int hx = sx + Math.round(hsv[0] * (total - 1));
		Draw.outline(g, hx - 1, y, 2, 6, 0xFF000000);
		g.fill(hx - 1, y, hx + 1, y + 6, 0xFFFFFFFF);
		hit(sx, y - 1, total, 8, f -> {
			hsv[0] = Math.min(0.999f, (float) f);
			apply.run();
		}, true);
		y += 9;
		int cx = sx;
		for (int c : SWATCHES) {
			String code = hex(c);
			Draw.control(g, cx, y, 11, 11, 2, code.equalsIgnoreCase(current) ? accent : line, c);
			hit(cx, y, 11, 11, f -> {
				set.accept(code);
				changed = true;
			}, false);
			cx += 13;
		}
		y += 15;
	}

	private static String hex(int argb) {
		return String.format("#%06x", argb & 0xFFFFFF);
	}
}
