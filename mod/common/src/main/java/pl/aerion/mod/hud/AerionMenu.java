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
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Icons;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Logo;
import pl.aerion.mod.Particles;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.features.Features;
import pl.aerion.mod.features.Sounds;
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
	private static final int ROW = 16, SIDE = 92, GAP = 5;
	private static final String[] FEATURES = {"zoom", "toggleSprint", "toggleSneak", "freelook", "fullbright", "hitboxes", "overlays"};
	private static final String[] SOUND_GROUPS = {"all", "recent", "block", "entity", "item", "ambient", "music", "other"};
	private static final int WIN_W = 440, WIN_H = 250;

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
			Map.entry("potions", List.of(Option.toggle("duration", true), Option.toggle("level", true), Option.toggle("colors", true))),
			Map.entry("armor", List.of(
					Option.select("layout", "vertical", "vertical", "horizontal"),
					Option.select("durability", "percent", "percent", "value", "none"),
					Option.toggle("colored", true), Option.toggle("hand", true), Option.toggle("offhand", false))),
			Map.entry("keystrokes", List.of(
					Option.toggle("wasd", true), Option.toggle("mouse", true), Option.toggle("space", true), Option.toggle("cps", true),
					Option.number("keySize", 18, 12, 30), Option.colour("pressed", "accent"))),
			Map.entry("minimap", List.of(
					Option.number("size", 80, 48, 160), Option.number("zoom", 2, 1, 4), Option.toggle("coords", true), Option.toggle("north", true))),
			Map.entry("hitboxes", List.of(
					Option.colour("color", "#ffffff"), Option.toggle("reach", true), Option.colour("reachColor", "#ff5555"),
					Option.number("width", 2, 1, 6), Option.number("fill", 0, 0, 100), Option.number("range", 48, 8, 128),
					Option.toggle("players", true), Option.toggle("mobs", true), Option.toggle("others", false), Option.toggle("look", false))),
			Map.entry("overlays", List.of(
					Option.number("fireDrop", 0, 0, 100), Option.toggle("pumpkin", false), Option.toggle("snow", false), Option.toggle("water", false),
					Option.toggle("block", false), Option.toggle("vignette", false), Option.toggle("portal", false), Option.toggle("nausea", false))),
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

	private enum Section { HUD, FEATURES, SOUNDS, KEYS, LOOK }

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

	private final List<Hit> hits = new ArrayList<>();
	private Hit sliding;
	private boolean changed, clipping;
	/** Drawing units per GUI pixel: the menu has its own size, whatever the game's GUI scale. */
	private float toUnits = 1f;
	private int unitsW, unitsH;
	private String soundGroup = "all", query = "";
	private static List<String> allSounds;
	private int scroll, contentHeight, viewTop, viewBottom;
	private int x, y, colW, mouseX, mouseY;
	private Gfx g;
	private int text, muted, accent, accentText, surface, sidebar, tile, line, control, track, pick;
	/** Eased values of switches and hovers, and the height of each card last frame. */
	private final Map<String, Float> eased = new HashMap<>();
	private final Map<String, Integer> cards = new HashMap<>();
	private String cardKey;
	private int cardTop;
	private long lastFrame;
	private float dt = 0.016f;

	public AerionMenu() {}

	/** Opens directly on one module's settings. */
	public AerionMenu(String module) {
		section = Section.HUD;
		detail = module;
	}

	private static boolean isFeature(String id) {
		return List.of(FEATURES).contains(id);
	}

	private static JsonObject item(HudConfig cfg, String id) {
		return isFeature(id) ? cfg.feature(id) : cfg.module(id);
	}

	private static boolean enabled(HudConfig cfg, String id) {
		return HudConfig.bool(item(cfg, id), "enabled", id.equals("zoom"));
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
		for (Hit h : List.copyOf(hits)) {
			if (h.clipped && (py < viewTop || py >= viewBottom)) continue;
			if (h.contains(px, py)) {
				if (h.slider) sliding = h;
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
		if (section == Section.SOUNDS && detail == null && !query.isEmpty()) {
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
		if (section != Section.SOUNDS || detail != null || capturing != null) return false;
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
		if (sliding != null) sliding.fire(px * toUnits);
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
	}

	// --- window ------------------------------------------------------------------------------
	@Override
	public void render(Gfx gfx, int mx, int my) {
		hits.clear();
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
		pick = Colors.mix(surface, accent, 0.32f);
		long now = System.nanoTime();
		if (lastFrame != 0) dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;

		if (Minecraft.getInstance().level == null && t != null) {
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
		try {
			window();
		} finally {
			Gfx.pixels(0);
			g.pop();
		}
	}

	private void window() {
		int winW = Math.min(unitsW - 12, WIN_W), winH = Math.min(unitsH - 12, WIN_H);
		int left = (unitsW - winW) / 2, top = (unitsH - winH) / 2;
		Draw.shadow(g, left, top, winW, winH, 6);
		Draw.rounded(g, left, top, winW, winH, 6, surface);
		sidebar(left, top, winH);
		Draw.vline(g, left + SIDE, top + 1, winH - 2, line);
		Draw.control(g, left, top, winW, winH, 6, line, 0);

		int mainX = left + SIDE + 10, mainW = winW - SIDE - 20;
		iconButton(left + winW - 20, top + 6, 14, "close", this::toLayout);
		int head = detail != null ? pageHead(mainX, top + 6, mainW - 18) : listHead(mainX, top + 7);

		viewTop = top + head;
		viewBottom = top + winH - 4;
		int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		clipping = true;
		g.scissor(left + SIDE, viewTop, left + winW - 1, viewBottom);
		x = mainX;
		colW = mainW;
		y = viewTop + 2 - scroll;
		int start = y;
		if (detail != null) detailBody();
		else switch (section) {
			case HUD -> tiles(mainX, mainW, List.of(Features.MODULE_IDS));
			case FEATURES -> tiles(mainX, mainW, List.of(FEATURES));
			case SOUNDS -> soundsPage();
			case KEYS -> keysPage();
			case LOOK -> lookPage();
		}
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
		int logoH = 13;
		Logo.draw(g, AerionClient.theme(), left + 8, top + 7, logoH);
		int ny = top + 27;
		ny = navItem(left + 4, ny, Section.HUD, "fps", "aerion.nav.hud");
		ny = navItem(left + 4, ny, Section.FEATURES, "zoom", "aerion.nav.features");
		ny = navItem(left + 4, ny, Section.SOUNDS, "sounds", "aerion.nav.sounds");
		ny = navItem(left + 4, ny, Section.KEYS, "keys", "aerion.nav.keys");
		navItem(left + 4, ny, Section.LOOK, "look", "aerion.nav.look");
		int by = top + h - 21;
		button(left + 4, by, SIDE - 8, 16, Lang.tr("aerion.nav.layout"), false, this::toLayout);
		Icons.draw(g, "layout", left + 8, by + 3, 10, muted);
	}

	private int navItem(int nx, int ny, Section which, String icon, String label) {
		int w = SIDE - 8, h = 16;
		boolean on = section == which, over = inside(nx, ny, w, h);
		float glow = ease("nav" + which, over ? 1 : 0);
		if (on) Draw.rounded(g, nx, ny, w, h, 3, Colors.mix(sidebar, accent, 0.2f));
		else if (glow > 0) Draw.rounded(g, nx, ny, w, h, 3, Colors.alpha(control, Math.round(255 * glow)));
		if (on) Draw.rounded(g, nx, ny + 4, 2, h - 8, 1, accent);
		Icons.draw(g, icon, nx + 5, ny + 3, 10, on ? accent : muted);
		Txt.draw(g, Lang.tr(label), nx + 18, ny + h / 2f, TEXT, on || over ? text : muted);
		hit(nx, ny, w, h, f -> {
			section = which;
			detail = null;
			expanded = null;
			capturing = null;
			scroll = 0;
		}, false);
		return ny + h + 2;
	}

	private int listHead(int mx, int my) {
		String key = switch (section) {
			case HUD -> "hud";
			case FEATURES -> "features";
			case SOUNDS -> "sounds";
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
		iconButton(mx, my, 14, "back", () -> {
			detail = null;
			expanded = null;
			capturing = null;
			scroll = 0;
		});
		Icons.draw(g, id, mx + 19, my + 1, 12, text);
		Txt.draw(g, name(id), mx + 35, my + 7, TITLE, text);
		boolean on = enabled(cfg, id);
		switchAt("head" + id, mx + w - 22, my + 2, on);
		hit(mx + w - 24, my, 24, 14, f -> {
			item(cfg, id).addProperty("enabled", !on);
			changed = true;
		}, false);
		int tx = mx;
		tx = subTab(tx, my + 19, "aerion.tab.general", Sub.GENERAL);
		if (!isFeature(id)) tx = subTab(tx, my + 19, "aerion.tab.look", Sub.LOOK);
		subTab(tx, my + 19, "aerion.tab.key", Sub.KEY);
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
	private void tiles(int left, int w, List<String> items) {
		// Features have longer names, and there are few of them.
		int cols = items.size() < 8 ? 2 : Math.max(2, (w + GAP) / (96 + GAP));
		int cw = (w - (cols - 1) * GAP) / cols, ch = 26;
		HudConfig cfg = AerionClient.hud();
		for (int i = 0; i < items.size(); i++) {
			tileAt(cfg, items.get(i), left + (i % cols) * (cw + GAP), y + (i / cols) * (ch + GAP), cw, ch);
		}
		y += ((items.size() + cols - 1) / cols) * (ch + GAP);
	}

	private void tileAt(HudConfig cfg, String id, int cx, int cy, int w, int h) {
		boolean on = enabled(cfg, id), over = inside(cx, cy, w, h);
		Draw.rounded(g, cx, cy, w, h, 4, Colors.mix(tile, control, ease("tile" + id, over ? 1 : 0)));
		Draw.rounded(g, cx + 4, cy + 4, h - 8, h - 8, 3, on ? pick : control);
		Icons.draw(g, id, cx + 7, cy + 7, h - 14, on ? accent : muted);
		Txt.draw(g, ellipsize(name(id), w - 56), cx + h + 1, cy + h / 2f, TEXT, on ? text : muted);
		int sx = cx + w - 25, sy = cy + (h - 9) / 2;
		switchAt("tile" + id, sx, sy, on);
		hit(sx - 3, cy, 26, h, f -> {
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
		if (Txt.width(s, TEXT) <= width) return s;
		while (s.length() > 1 && Txt.width(s + "…", TEXT) > width) s = s.substring(0, s.length() - 1);
		return s + "…";
	}

	// --- settings of one tile ----------------------------------------------------------------
	private void detailBody() {
		HudConfig cfg = AerionClient.hud();
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
			endCard();
		}
		beginCard(Lang.tr("aerion.sec.place"));
		slider(Lang.tr("aerion.panel.scale"), HudConfig.num(m, "scale", 100), 25, 400, 5, "%", v -> m.addProperty("scale", v));
		grid(m);
		slider(Lang.tr("aerion.place.x"), HudConfig.num(m, "x", 0) / 10, 0, 100, 1, "%", v -> m.addProperty("x", v * 10));
		slider(Lang.tr("aerion.place.y"), HudConfig.num(m, "y", 0) / 10, 0, 100, 1, "%", v -> m.addProperty("y", v * 10));
		endCard();
	}

	/** Nine squares for the corners, edges and centre of the screen. */
	private void grid(JsonObject m) {
		Txt.draw(g, Lang.tr("aerion.place.corner"), x, y + 13, TEXT, text);
		int cx = controlX(), size = 7;
		int[] xs = {8, 500, 992}, ys = {10, 500, 990};
		int mx = HudConfig.num(m, "x", 0), my = HudConfig.num(m, "y", 0);
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				int tx = cx + col * (size + 2), ty = y + 2 + row * (size + 2);
				int vx = xs[col], vy = ys[row];
				boolean on = Math.abs(mx - vx) < 15 && Math.abs(my - vy) < 15, over = inside(tx, ty, size, size);
				Draw.rounded(g, tx, ty, size, size, 1, on ? accent : over ? Colors.mix(surface, text, 0.3f) : control);
				hit(tx, ty, size, size, f -> {
					m.addProperty("x", vx);
					m.addProperty("y", vy);
					changed = true;
				}, false);
			}
		}
		y += 3 * (size + 2) + 4;
	}

	private void featureGeneral(HudConfig cfg) {
		JsonObject f = cfg.feature(detail);
		beginCard(null);
		if (detail.equals("zoom")) {
			slider(Lang.tr("aerion.feature.zoomFactor"), HudConfig.num(f, "factor", 4), 2, 10, 1, "x", v -> f.addProperty("factor", v));
			toggle(Lang.tr("aerion.feature.zoomSmooth"), HudConfig.bool(f, "smooth", true), v -> f.addProperty("smooth", v));
		} else if (detail.equals("freelook")) {
			toggle(Lang.tr("aerion.feature.freelookHold"), HudConfig.bool(f, "hold", true), v -> f.addProperty("hold", v));
		}
		for (Option o : OPTIONS.getOrDefault(detail, List.of())) option(f, o);
		if (y == cardTop + 5) note(Lang.tr("aerion.hint.noOptions"));
		endCard();
		if (List.of("freelook", "fullbright", "hitboxes", "overlays").contains(detail)) note(Lang.tr("aerion.panel.serverWarning"));
	}

	private void option(JsonObject m, Option o) {
		String label = Lang.tr(o.key.equals("format") || o.key.equals("colored") && !detail.equals("armor")
				? "aerion.opt." + o.key
				: "aerion.opt." + detail + "." + o.key);
		String value = HudConfig.opt(m, o.key, o.def);
		if (o.isToggle()) {
			toggle(label, "1".equals(value), v -> setOption(m, o.key, v ? "1" : "0"));
		} else if (o.color) {
			color(detail + "." + o.key, label, new String[] {"accent"}, new String[] {Lang.tr("aerion.style.accent")}, value, v -> setOption(m, o.key, v));
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
			boolean over = inside(ix, rowY + 2, 11, 11);
			Icons.draw(g, "undo", ix + 1, rowY + 3, 9, over ? text : muted);
			hit(ix, rowY + 2, 11, 11, f -> reset(key), false);
		}
	}

	private void lookPage() {
		HudConfig cfg = AerionClient.hud();
		beginCard(null);
		toggle(Lang.tr("aerion.panel.hud"), cfg.enabled(), v -> cfg.root.addProperty("enabled", v));
		endCard();
		lookFields(new Look(cfg.style(), null));
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

	private void lookFields(Look l) {
		String theme = Lang.tr("aerion.style.theme"), none = Lang.tr("aerion.style.none"), acc = Lang.tr("aerion.style.accent");
		reserve = l.module != null ? 14 : 0;

		beginCard(Lang.tr("aerion.sec.text"));
		int top = y;
		color("text", Lang.tr("aerion.style.text"), new String[] {"theme"}, new String[] {theme}, HudConfig.str(l.read("text"), "text", "theme"), v -> l.write().addProperty("text", v));
		l.mark("text", top);
		top = y;
		choiceRow(Lang.tr("aerion.style.font"), new String[] {"game", "smooth"}, new String[] {Lang.tr("aerion.style.game"), Lang.tr("aerion.style.smooth")}, HudConfig.str(l.read("font"), "font", "smooth"), v -> l.write().addProperty("font", v));
		l.mark("font", top);
		top = y;
		toggle(Lang.tr("aerion.style.shadow"), HudConfig.bool(l.read("shadow"), "shadow", true), v -> l.write().addProperty("shadow", v));
		l.mark("shadow", top);
		top = y;
		choiceRow(Lang.tr("aerion.style.align"), new String[] {"left", "center", "right"}, new String[] {Lang.tr("aerion.style.left"), Lang.tr("aerion.style.center"), Lang.tr("aerion.style.right")}, HudConfig.str(l.read("align"), "align", "left"), v -> l.write().addProperty("align", v));
		l.mark("align", top);

		endCard();
		beginCard(Lang.tr("aerion.sec.background"));
		top = y;
		color("background", Lang.tr("aerion.style.background"), new String[] {"theme", "none"}, new String[] {theme, none}, HudConfig.str(l.read("background"), "background", "theme"), v -> l.write().addProperty("background", v));
		l.mark("background", top);
		top = y;
		slider(Lang.tr("aerion.style.opacity"), HudConfig.num(l.read("opacity"), "opacity", 55), 0, 100, 5, "%", v -> l.write().addProperty("opacity", v));
		l.mark("opacity", top);
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
		String q = query.toLowerCase(java.util.Locale.ROOT);
		List<String> out = new ArrayList<>();
		for (String id : source) {
			boolean group = soundGroup.equals("all") || soundGroup.equals("recent") || groupOf(id).equals(soundGroup);
			if (group && (q.isEmpty() || id.contains(q))) out.add(id);
		}
		return out;
	}

	private void soundsPage() {
		HudConfig cfg = AerionClient.hud();
		JsonObject f = cfg.feature("sounds");
		toggle(Lang.tr("aerion.sound.enable"), HudConfig.bool(f, "enabled", true), v -> f.addProperty("enabled", v));
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
		float room = bx - x - 4;
		String label = shown;
		if (Txt.width(label, SMALL) > room) {
			while (label.length() > 1 && Txt.width("…" + label, SMALL) > room) label = label.substring(1);
			label = "…" + label;
		}
		Txt.draw(g, label, x, y + 7, SMALL, value == 100 ? muted : text);
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

	private static void preview(String id) {
		SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id));
		if (event != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, 1f));
	}

	// --- keys --------------------------------------------------------------------------------
	private void keyTab() {
		KeyMapping k = Features.key(detail);
		String label = switch (detail) {
			case "zoom", "freelook" -> Lang.tr("aerion.key.hold");
			case "toggleSprint" -> Lang.tr("aerion.key.sprint");
			case "toggleSneak" -> Lang.tr("aerion.key.sneak");
			default -> Lang.tr("aerion.key.toggle");
		};
		beginCard(null);
		keyRow(label, k);
		endCard();
		note(Lang.tr("aerion.hint.keys"));
	}

	private void keysPage() {
		beginCard(null);
		keyRow(Lang.tr("aerion.key.menu"), Features.key("menu"));
		endCard();
		beginCard(Lang.tr("aerion.key.features"));
		keyRow(Lang.tr("aerion.feature.zoom"), Features.key("zoom"));
		keyRow(Lang.tr("aerion.feature.freelook"), Features.key("freelook"));
		keyRow(Lang.tr("aerion.feature.fullbright"), Features.key("fullbright"));
		keyRow(Lang.tr("aerion.feature.hitboxes"), Features.key("hitboxes"));
		keyRow(Lang.tr("aerion.feature.overlays"), Features.key("overlays"));
		keyRow(Lang.tr("aerion.nav.sounds"), Features.key("sounds"));
		keyRow(Lang.tr("aerion.key.sprint"), Features.key("toggleSprint"));
		keyRow(Lang.tr("aerion.key.sneak"), Features.key("toggleSneak"));
		endCard();
		beginCard(Lang.tr("aerion.key.modules"));
		for (String id : Features.MODULE_IDS) keyRow(Lang.tr("aerion.module." + id), Features.key(id));
		endCard();
	}

	private void keyRow(String label, KeyMapping k) {
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		int bx = controlX(), w = x + colW - bx - 15;
		boolean listening = capturing == k;
		String shown = listening ? Lang.tr("aerion.key.press") : k.isUnbound() ? Lang.tr("aerion.key.none") : k.getTranslatedKeyMessage().getString();
		button(bx, y + 1, w, ROW - 3, shown, listening, () -> capturing = k);
		iconButton(bx + w + 2, y + 1, ROW - 3, "close", () -> {
			capturing = k;
			bind(InputConstants.UNKNOWN);
			capturing = null;
		});
		y += ROW;
		if (!k.isUnbound()) {
			for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
				if (other != k && other.same(k)) {
					String who = Component.translatable(other.getName()).getString();
					Txt.draw(g, Lang.tr("aerion.key.taken").replace("{}", who), bx, y + 3, SMALL, 0xFFFFAA33);
					y += 9;
					break;
				}
			}
		}
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

	private int body(boolean on, boolean over) {
		return on ? (over ? Colors.mix(pick, accent, 0.2f) : pick) : over ? track : control;
	}

	private void button(int bx, int by, int w, int h, String label, boolean on, Runnable action) {
		boolean over = inside(bx, by, w, h);
		Draw.rounded(g, bx, by, w, h, 3, body(on, over));
		Txt.center(g, label, bx + w / 2f, by + h / 2f, TEXT, text);
		hit(bx, by, w, h, f -> action.run(), false);
	}

	private void smallButton(int bx, int by, int w, int h, String label, boolean on, Runnable action) {
		boolean over = inside(bx, by, w, h);
		Draw.rounded(g, bx, by, w, h, 3, body(on, over));
		Txt.center(g, label, bx + w / 2f, by + h / 2f, SMALL, on || over ? text : muted);
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

	/** A group of settings on a slightly lighter panel; its height comes from the last frame. */
	private void beginCard(String title) {
		cardKey = section + ":" + detail + ":" + sub + ":" + title;
		cardTop = y;
		Integer h = cards.get(cardKey);
		if (h != null) Draw.rounded(g, x, y, colW, h, 4, tile);
		x += 7;
		colW -= 14;
		y += 5;
		if (title != null) {
			Txt.draw(g, title.toUpperCase(Locale.ROOT), x, y + 4, 6.5f, muted);
			y += 12;
		}
	}

	private void endCard() {
		y += 5;
		cards.put(cardKey, y - cardTop);
		x -= 7;
		colW += 14;
		y += 5;
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
		int w = colW - reserve;
		if (inside(x - 2, y, w + 4, ROW)) Draw.rounded(g, x - 2, y, w + 4, ROW, 2, control);
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		switchAt(section + label, x + w - 20, y + 3, on);
		hit(x - 2, y, w + 4, ROW, f -> {
			set.accept(!on);
			changed = true;
		}, false);
		y += ROW;
	}

	/** Value slider; the number sits on the bar. */
	private void slider(String label, int value, int min, int max, int step, String unit, Consumer<Integer> set) {
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
	}

	/** Mutually exclusive buttons on the label's row, or under it when they don't fit. */
	private void choice(String label, String[] values, String[] labels, String current, Consumer<String> set) {
		float widest = 0;
		for (String l : labels) widest = Math.max(widest, Txt.width(l, SMALL));
		if (widest + 8 > (float) controlW() / values.length) {
			Txt.draw(g, label, x, y + 6, TEXT, text);
			y += 13;
			segments(x, colW - reserve, values, labels, current, set);
			y += 16;
		} else {
			choiceRow(label, values, labels, current, set);
		}
	}

	private void choiceRow(String label, String[] values, String[] labels, String current, Consumer<String> set) {
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		y += 2;
		segments(controlX(), controlW(), values, labels, current, set);
		y += ROW - 2;
	}

	private void segments(int sx, int width, String[] values, String[] labels, String current, Consumer<String> set) {
		int w = width / values.length, h = ROW - 4;
		Draw.rounded(g, sx, y, w * values.length, h, 3, control);
		for (int i = 0; i < values.length; i++) {
			String v = values[i];
			int cx = sx + i * w;
			boolean on = v.equals(current), over = inside(cx, y, w, h);
			if (on) Draw.rounded(g, cx + 1, y + 1, w - 2, h - 2, 2, pick);
			Txt.center(g, labels[i], cx + w / 2f, y + h / 2f, SMALL, on || over ? text : muted);
			hit(cx, y, w, h, f -> {
				set.accept(v);
				changed = true;
			}, false);
		}
	}

	/** Named choices, plus a custom colour whose palette and R/G/B sliders open on demand. */
	private void color(String id, String label, String[] words, String[] wordLabels, String current, Consumer<String> set) {
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		int bx = controlX(), by = y + 2, bh = ROW - 4;
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
		boolean custom = current != null && current.startsWith("#");
		String word = Lang.tr("aerion.style.custom");
		int w = Math.round(Txt.width(word, SMALL)) + 10 + (custom ? 11 : 0);
		String swatchValue = custom ? current : "#ffffff";
		smallButton(bx, by, w, bh, custom ? "" : word, custom, () -> {
			if (!custom) {
				set.accept(swatchValue);
				changed = true;
			}
			expanded = id.equals(expanded) ? null : id;
		});
		if (custom) {
			Txt.draw(g, word, bx + 5, by + bh / 2f, SMALL, text);
			int c = Theme.color(current, 0xFFFFFFFF);
			Draw.control(g, bx + w - 12, by + 2, 9, bh - 4, 1, 0xFF000000, c);
		}
		y += ROW;
		if (!id.equals(expanded)) return;
		int sx = controlX();
		for (int c : SWATCHES) {
			String hex = hex(c);
			Draw.control(g, sx, y, 11, 11, 2, hex.equalsIgnoreCase(current) ? accent : line, c);
			hit(sx, y, 11, 11, f -> {
				set.accept(hex);
				changed = true;
			}, false);
			sx += 13;
		}
		y += 14;
		if (custom) {
			int c = Theme.color(current, 0xFFFFFFFF);
			slider("R", c >> 16 & 255, 0, 255, 1, "", v -> set.accept(hex(c & 0xFF00FFFF | v << 16)));
			slider("G", c >> 8 & 255, 0, 255, 1, "", v -> set.accept(hex(c & 0xFFFF00FF | v << 8)));
			slider("B", c & 255, 0, 255, 1, "", v -> set.accept(hex(c & 0xFFFFFF00 | v)));
		}
	}

	private static String hex(int argb) {
		return String.format("#%06x", argb & 0xFFFFFF);
	}
}
