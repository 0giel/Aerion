package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Theme;
import pl.aerion.mod.platform.Gfx;

/**
 * The side panel of the in-game HUD editor: every module and feature on or off, each module's
 * size, options and own look, and the look they share. Controls are rebuilt every frame and leave
 * their clickable areas in {@link #hits}; changes go straight into hud.json's tree, which the
 * editor saves when it closes.
 */
final class EditorPanel {
	private static final int WIDTH = 164, PAD = 6, ROW = 13, TABS = 16;
	private static final int LABEL = 74;
	private static final String[] MODULE_ORDER = {
		"fps", "coords", "direction", "cps", "keystrokes", "armor", "potions", "ping", "clock", "memory", "minimap", "toggles"
	};
	private static final int[] SWATCHES = {
		0xFFFFFFFF, 0xFF000000, 0xFFFF5555, 0xFFFFAA33, 0xFFFFE25A, 0xFF55DD77, 0xFF55AAFF, 0xFFB98CFF
	};

	/** Module options the panel can change; text formats are edited in the launcher. */
	private record Option(String key, String def, String[] choices, int min, int max) {
		static Option toggle(String key, boolean on) {
			return new Option(key, on ? "1" : "0", null, 0, 0);
		}

		static Option select(String key, String def, String... choices) {
			return new Option(key, def, choices, 0, 0);
		}

		static Option number(String key, int def, int min, int max) {
			return new Option(key, String.valueOf(def), null, min, max);
		}

		boolean isToggle() {
			return choices == null && max == 0;
		}
	}

	private static final Map<String, List<Option>> OPTIONS = Map.of(
			"coords", List.of(Option.toggle("direction", true), Option.toggle("decimals", false), Option.toggle("nether", false)),
			"direction", List.of(Option.toggle("degrees", true)),
			"clock", List.of(Option.toggle("h24", true), Option.toggle("seconds", false)),
			"potions", List.of(Option.toggle("duration", true), Option.toggle("colors", true)),
			"armor", List.of(
					Option.select("layout", "vertical", "vertical", "horizontal"),
					Option.select("durability", "percent", "percent", "value", "none"),
					Option.toggle("hand", true)),
			"keystrokes", List.of(Option.toggle("mouse", true), Option.toggle("space", true), Option.toggle("cps", true)),
			"minimap", List.of(Option.number("size", 80, 48, 160), Option.number("zoom", 2, 1, 4), Option.toggle("coords", true)));

	private record Hit(int x, int y, int w, int h, DoubleConsumer action, boolean slider) {
		boolean contains(double px, double py) {
			return px >= x && py >= y && px < x + w && py < y + h;
		}

		void fire(double px) {
			action.accept(Math.max(0, Math.min(1, (px - x) / Math.max(1, w - 1))));
		}
	}

	private enum Tab { MODULES, MODULE, LOOK }

	// Kept between openings, so the editor reopens as it was left.
	private static Tab tab = Tab.MODULES;
	private static boolean open = true, onLeft = false;
	static String selected = "fps";

	private final Runnable changed;
	private final List<Hit> hits = new ArrayList<>();
	private Hit sliding;
	private int scroll, contentHeight, viewTop, viewBottom;
	private int x, y, mouseX, mouseY;

	// Colours of the frame being drawn.
	private int text, muted, accent, accentText, surface, line;
	private Gfx g;

	EditorPanel(Runnable changed) {
		this.changed = changed;
	}

	boolean isOpen() {
		return open;
	}

	void show() {
		open = true;
	}

	/** The part of the screen left for modules, hint and buttons: {left, right}. */
	int[] freeArea(int screenW) {
		if (!open) return new int[] {0, screenW};
		return onLeft ? new int[] {WIDTH, screenW} : new int[] {0, screenW - WIDTH};
	}

	void select(String id) {
		selected = id;
		tab = Tab.MODULE;
		scroll = 0;
		open = true;
	}

	boolean contains(double px, double py, int screenW) {
		if (!open) return false;
		return onLeft ? px < WIDTH : px >= screenW - WIDTH;
	}

	// --- input -------------------------------------------------------------------------------
	/** True when the click belongs to the panel. */
	boolean pressed(double px, double py, int screenW) {
		if (!contains(px, py, screenW)) return false;
		for (Hit h : List.copyOf(hits)) {
			if (h.contains(px, py)) {
				if (h.slider) sliding = h;
				h.fire(px);
				break;
			}
		}
		return true;
	}

	boolean dragged(double px) {
		if (sliding == null) return false;
		sliding.fire(px);
		return true;
	}

	void released() {
		sliding = null;
	}

	boolean scrolled(double px, double py, double amount, int screenW) {
		if (!contains(px, py, screenW)) return false;
		scroll -= (int) Math.signum(amount) * ROW * 2;
		return true;
	}

	// --- drawing -----------------------------------------------------------------------------
	void render(Gfx gfx, int mx, int my) {
		hits.clear();
		if (!open) return;
		g = gfx;
		mouseX = mx;
		mouseY = my;
		Theme t = AerionClient.theme();
		text = t != null ? t.text : 0xFFFFFFFF;
		muted = t != null ? t.textMuted : 0xFFAAAAAA;
		accent = t != null ? t.accent : 0xFF7AA2FF;
		accentText = t != null ? t.accentText : 0xFF111111;
		surface = t != null ? t.surface : 0xFF1A1A1A;
		line = Colors.mix(surface, text, 0.2f);

		int left = onLeft ? 0 : g.width() - WIDTH;
		g.fill(left, 0, left + WIDTH, g.height(), Colors.alpha(surface, 0xFF));
		g.fill(onLeft ? WIDTH - 1 : left, 0, onLeft ? WIDTH : left + 1, g.height(), line);

		// Scrolling content first, so the fixed tabs always stay reachable.
		viewTop = TABS + 2;
		viewBottom = g.height() - 2;
		int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		x = left + PAD;
		y = viewTop - scroll;
		int start = y;
		switch (tab) {
			case MODULES -> modules();
			case MODULE -> module();
			case LOOK -> look();
		}
		contentHeight = y - start;
		if (maxScroll > 0) {
			int barH = Math.max(12, (viewBottom - viewTop) * (viewBottom - viewTop) / contentHeight);
			int barY = viewTop + (viewBottom - viewTop - barH) * scroll / maxScroll;
			int barX = left + WIDTH - 3;
			g.fill(barX, barY, barX + 2, barY + barH, line);
		}
		tabs(left);
	}

	private void tabs(int left) {
		g.fill(left, 0, left + WIDTH, TABS, Colors.alpha(surface, 0xFF));
		g.fill(left, TABS, left + WIDTH, TABS + 1, line);
		int tx = left + 2;
		tx = tab(tx, Lang.tr("aerion.panel.modules"), Tab.MODULES);
		tx = tab(tx, Lang.tr("aerion.panel.module"), Tab.MODULE);
		tab(tx, Lang.tr("aerion.panel.look"), Tab.LOOK);
		// Move the panel to the other side, or hide it, when it covers a module.
		int bx = left + WIDTH - 22;
		button(bx, 2, 10, 12, onLeft ? ">" : "<", false, () -> onLeft = !onLeft);
		button(bx + 11, 2, 10, 12, "x", false, () -> open = false);
	}

	private int tab(int tx, String label, Tab which) {
		int w = Gfx.textWidth(label) + 6;
		boolean on = tab == which;
		boolean over = inside(tx, 2, w, 13);
		if (on) g.fill(tx, 13, tx + w, 15, accent);
		g.text(label, tx + 3, 4, on || over ? text : muted, false);
		hits.add(new Hit(tx, 0, w, TABS, f -> {
			tab = which;
			scroll = 0;
		}, false));
		return tx + w + 1;
	}

	private boolean inside(int cx, int cy, int w, int h) {
		return mouseX >= cx && mouseY >= cy && mouseX < cx + w && mouseY < cy + h;
	}

	private int inner() {
		return WIDTH - 2 * PAD - 2;
	}

	/** A row is drawn and clickable only when it fits the visible part of the panel. */
	private boolean visible(int h) {
		return y >= viewTop && y + h <= viewBottom;
	}

	private void heading(String s) {
		if (visible(11)) g.text(s, x, y + 2, muted, false);
		y += 12;
	}

	private void note(String s) {
		for (String row : wrap(s, inner())) {
			if (visible(10)) g.text(row, x, y + 1, muted, false);
			y += 10;
		}
		y += 2;
	}

	private static List<String> wrap(String s, int width) {
		List<String> rows = new ArrayList<>();
		StringBuilder row = new StringBuilder();
		for (String word : s.split(" ")) {
			String next = row.isEmpty() ? word : row + " " + word;
			if (Gfx.textWidth(next) > width && !row.isEmpty()) {
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
		if (visible(ROW)) {
			int w = inner();
			if (inside(x - 2, y, w + 4, ROW)) g.fill(x - 2, y, x + w + 2, y + ROW, line);
			g.text(label, x, y + 3, text, false);
			int sx = x + w - 16;
			g.fill(sx, y + 3, sx + 16, y + 10, on ? accent : Colors.mix(surface, text, 0.3f));
			g.fill(on ? sx + 10 : sx + 1, y + 4, on ? sx + 15 : sx + 6, y + 9, on ? accentText : surface);
			hits.add(new Hit(x - 2, y, w + 4, ROW, f -> {
				set.accept(!on);
				changed.run();
			}, false));
		}
		y += ROW;
	}

	private void button(int bx, int by, int w, int h, String label, boolean on, Runnable action) {
		boolean over = inside(bx, by, w, h);
		g.fill(bx, by, bx + w, by + h, on ? accent : over ? Colors.mix(surface, text, 0.3f) : Colors.mix(surface, text, 0.14f));
		g.text(label, bx + (w - Gfx.textWidth(label)) / 2, by + (h - 8) / 2, on ? accentText : text, false);
		hits.add(new Hit(bx, by, w, h, f -> action.run(), false));
	}

	/** Value slider; the number sits on the bar. */
	private void slider(String label, int value, int min, int max, int step, String unit, Consumer<Integer> set) {
		if (visible(ROW)) {
			int bx = x + LABEL, w = inner() - LABEL;
			g.text(label, x, y + 3, text, false);
			g.fill(bx, y + 1, bx + w, y + ROW - 2, Colors.mix(surface, text, 0.14f));
			float f = (float) (Math.max(min, Math.min(max, value)) - min) / Math.max(1, max - min);
			g.fill(bx, y + 1, bx + Math.round(w * f), y + ROW - 2, Colors.alpha(accent, 0x99));
			String s = value + unit;
			g.text(s, bx + (w - Gfx.textWidth(s)) / 2, y + 2, text, false);
			hits.add(new Hit(bx, y, w, ROW, fr -> {
				int v = min + Math.round((float) (fr * (max - min)) / step) * step;
				set.accept(Math.max(min, Math.min(max, v)));
				changed.run();
			}, true));
		}
		y += ROW;
	}

	/** A row of mutually exclusive buttons under its label. */
	private void choice(String label, String[] values, String[] labels, String current, Consumer<String> set) {
		heading(label);
		if (visible(12)) {
			int w = inner() / values.length;
			for (int i = 0; i < values.length; i++) {
				String v = values[i];
				button(x + i * w, y, w - 1, 12, labels[i], v.equals(current), () -> {
					set.accept(v);
					changed.run();
				});
			}
		}
		y += 14;
	}

	/** Named choices ("theme", "none"…), swatches and, for a colour, exact R/G/B sliders. */
	private void color(String label, String[] words, String[] wordLabels, String current, Consumer<String> set) {
		heading(label);
		if (visible(12)) {
			int bx = x;
			for (int i = 0; i < words.length; i++) {
				String v = words[i];
				int w = Gfx.textWidth(wordLabels[i]) + 6;
				button(bx, y, w, 11, wordLabels[i], v.equals(current), () -> {
					set.accept(v);
					changed.run();
				});
				bx += w + 1;
			}
			for (int c : SWATCHES) {
				if (bx + 9 > x + inner()) break;
				String hex = hex(c);
				boolean on = hex.equalsIgnoreCase(current);
				g.fill(bx, y, bx + 9, y + 11, on ? accent : line);
				g.fill(bx + 1, y + 1, bx + 8, y + 10, c);
				hits.add(new Hit(bx, y, 9, 11, f -> {
					set.accept(hex);
					changed.run();
				}, false));
				bx += 10;
			}
		}
		y += 13;
		if (current != null && current.startsWith("#")) {
			int c = Theme.color(current, 0xFFFFFFFF);
			slider("R", c >> 16 & 255, 0, 255, 1, "", v -> set.accept(hex(c & 0xFF00FFFF | v << 16)));
			slider("G", c >> 8 & 255, 0, 255, 1, "", v -> set.accept(hex(c & 0xFFFF00FF | v << 8)));
			slider("B", c & 255, 0, 255, 1, "", v -> set.accept(hex(c & 0xFFFFFF00 | v)));
		}
	}

	private static String hex(int argb) {
		return String.format("#%06x", argb & 0xFFFFFF);
	}

	// --- tabs --------------------------------------------------------------------------------
	private void modules() {
		HudConfig cfg = AerionClient.hud();
		toggle(Lang.tr("aerion.panel.hud"), cfg.enabled(), v -> cfg.root.add("enabled", new JsonPrimitive(v)));
		y += 2;
		heading(Lang.tr("aerion.panel.modules"));
		for (String id : MODULE_ORDER) {
			JsonObject m = cfg.module(id);
			boolean on = HudConfig.bool(m, "enabled", false);
			if (visible(ROW)) {
				int w = inner(), sx = x + w - 16;
				if (inside(x - 2, y, w + 4, ROW)) g.fill(x - 2, y, x + w + 2, y + ROW, line);
				g.text(Lang.tr("aerion.module." + id), x, y + 3, id.equals(selected) ? accent : text, false);
				g.text(">", sx - 8, y + 3, muted, false);
				g.fill(sx, y + 3, sx + 16, y + 10, on ? accent : Colors.mix(surface, text, 0.3f));
				g.fill(on ? sx + 10 : sx + 1, y + 4, on ? sx + 15 : sx + 6, y + 9, on ? accentText : surface);
				hits.add(new Hit(sx - 2, y, 18, ROW, f -> {
					m.add("enabled", new JsonPrimitive(!on));
					changed.run();
				}, false));
				hits.add(new Hit(x - 2, y, w - 14, ROW, f -> select(id), false));
			}
			y += ROW;
		}
		y += 3;
		heading(Lang.tr("aerion.panel.features"));
		JsonObject zoom = cfg.feature("zoom");
		toggle(Lang.tr("aerion.feature.zoom"), HudConfig.bool(zoom, "enabled", true), v -> zoom.add("enabled", new JsonPrimitive(v)));
		if (HudConfig.bool(zoom, "enabled", true)) {
			slider(Lang.tr("aerion.feature.zoomFactor"), HudConfig.num(zoom, "factor", 4), 2, 10, 1, "x", v -> zoom.add("factor", new JsonPrimitive(v)));
			toggle(Lang.tr("aerion.feature.zoomSmooth"), HudConfig.bool(zoom, "smooth", true), v -> zoom.add("smooth", new JsonPrimitive(v)));
		}
		feature(cfg, "toggleSprint");
		feature(cfg, "toggleSneak");
		feature(cfg, "freelook");
		JsonObject look = cfg.feature("freelook");
		if (HudConfig.bool(look, "enabled", false)) {
			toggle(Lang.tr("aerion.feature.freelookHold"), HudConfig.bool(look, "hold", true), v -> look.add("hold", new JsonPrimitive(v)));
		}
		feature(cfg, "fullbright");
		y += 2;
		note(Lang.tr("aerion.panel.serverWarning"));
	}

	private void feature(HudConfig cfg, String id) {
		JsonObject f = cfg.feature(id);
		toggle(Lang.tr("aerion.feature." + id), HudConfig.bool(f, "enabled", false), v -> f.add("enabled", new JsonPrimitive(v)));
	}

	private void module() {
		HudConfig cfg = AerionClient.hud();
		JsonObject m = cfg.module(selected);
		heading(Lang.tr("aerion.module." + selected));
		toggle(Lang.tr("aerion.panel.enabled"), HudConfig.bool(m, "enabled", false), v -> m.add("enabled", new JsonPrimitive(v)));
		slider(Lang.tr("aerion.panel.scale"), HudConfig.num(m, "scale", 100), 25, 400, 5, "%", v -> m.add("scale", new JsonPrimitive(v)));
		for (Option o : OPTIONS.getOrDefault(selected, List.of())) option(m, o);
		if (List.of("fps", "cps", "ping", "memory").contains(selected)) note(Lang.tr("aerion.panel.formatHint"));
		y += 2;
		JsonObject own = m.get("style") instanceof JsonObject s && !s.isEmpty() ? s : null;
		toggle(Lang.tr("aerion.panel.ownLook"), own != null, v -> {
			if (v) m.add("style", cfg.style().deepCopy());
			else m.remove("style");
		});
		if (own != null) styleFields(own);
		else note(Lang.tr("aerion.panel.ownLookHint"));
	}

	private void option(JsonObject m, Option o) {
		String label = Lang.tr("aerion.opt." + selected + "." + o.key);
		String value = HudConfig.opt(m, o.key, o.def);
		if (o.isToggle()) {
			toggle(label, "1".equals(value), v -> setOption(m, o.key, v ? "1" : "0"));
		} else if (o.choices != null) {
			String[] labels = new String[o.choices.length];
			for (int i = 0; i < labels.length; i++) labels[i] = Lang.tr("aerion.choice." + o.choices[i]);
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

	private void look() {
		heading(Lang.tr("aerion.panel.lookTitle"));
		styleFields(AerionClient.hud().style());
	}

	private void styleFields(JsonObject s) {
		String theme = Lang.tr("aerion.style.theme"), none = Lang.tr("aerion.style.none"), acc = Lang.tr("aerion.style.accent");
		color(Lang.tr("aerion.style.text"), new String[] {"theme"}, new String[] {theme}, HudConfig.str(s, "text", "theme"), v -> s.addProperty("text", v));
		color(Lang.tr("aerion.style.background"), new String[] {"theme", "none"}, new String[] {theme, none}, HudConfig.str(s, "background", "theme"), v -> s.addProperty("background", v));
		slider(Lang.tr("aerion.style.opacity"), HudConfig.num(s, "opacity", 55), 0, 100, 5, "%", v -> s.addProperty("opacity", v));
		color(Lang.tr("aerion.style.border"), new String[] {"none", "accent"}, new String[] {none, acc}, HudConfig.str(s, "border", "none"), v -> s.addProperty("border", v));
		slider(Lang.tr("aerion.style.radius"), HudConfig.num(s, "radius", 0), 0, 8, 1, "", v -> s.addProperty("radius", v));
		slider(Lang.tr("aerion.style.padding"), HudConfig.num(s, "padding", 3), 0, 10, 1, "", v -> s.addProperty("padding", v));
		toggle(Lang.tr("aerion.style.shadow"), HudConfig.bool(s, "shadow", true), v -> s.addProperty("shadow", v));
		choice(Lang.tr("aerion.style.align"), new String[] {"left", "center", "right"},
				new String[] {Lang.tr("aerion.style.left"), Lang.tr("aerion.style.center"), Lang.tr("aerion.style.right")},
				HudConfig.str(s, "align", "left"), v -> s.addProperty("align", v));
	}
}
