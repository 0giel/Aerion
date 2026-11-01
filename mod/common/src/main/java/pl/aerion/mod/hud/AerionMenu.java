package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import net.minecraft.client.Minecraft;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Icons;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Logo;
import pl.aerion.mod.Particles;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.platform.Gfx;

/**
 * Aerion's in-game menu: a window with a card for every HUD module and game feature (switch and
 * options), a page of options per card and a tab with the look all modules share. Controls are
 * rebuilt every frame and leave their clickable areas in {@link #hits}; changes go straight into
 * hud.json's tree, which is saved when the menu closes.
 */
public final class AerionMenu implements Page {
	private static final int[] SWATCHES = {
		0xFFFFFFFF, 0xFF000000, 0xFFFF5555, 0xFFFFAA33, 0xFFFFE25A, 0xFF55DD77, 0xFF55AAFF, 0xFFB98CFF
	};
	private static final int GREEN = 0xFF23935A, RED = 0xFFB02C4C;
	private static final float TEXT = 8f, SMALL = 7f;
	private static final int ROW = 15, HEADER = 26, FOOTER = 24, GAP = 5;

	private static final String[] MODULES = {
		"fps", "coords", "direction", "cps", "keystrokes", "armor", "potions", "ping", "clock", "memory", "minimap", "toggles"
	};
	private static final String[] FEATURES = {"zoom", "toggleSprint", "toggleSneak", "freelook", "fullbright"};

	/** Options a card can change; text formats are edited in the launcher. */
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

	private record Hit(int x, int y, int w, int h, DoubleConsumer action, boolean slider, boolean clipped) {
		boolean contains(double px, double py) {
			return px >= x && py >= y && px < x + w && py < y + h;
		}

		void fire(double px) {
			action.accept(Math.max(0, Math.min(1, (px - x) / Math.max(1, w - 1))));
		}
	}

	private enum Tab { MODULES, LOOK }

	private enum Filter { ALL, HUD, FEATURES }

	// Kept between openings, so the menu reopens where it was left.
	private static Tab tab = Tab.MODULES;
	private static Filter filter = Filter.ALL;

	/** The card whose options are shown, or null for the cards. */
	private String detail;

	private final List<Hit> hits = new ArrayList<>();
	private Hit sliding;
	private boolean changed, closing, clipping;
	private int scroll, contentHeight, viewTop, viewBottom;
	private int x, y, colW, mouseX, mouseY;
	private Gfx g;
	private int text, muted, accent, accentText, surface, surface2, line, control;

	public AerionMenu() {}

	/** Opens directly on one module's options. */
	public AerionMenu(String module) {
		tab = Tab.MODULES;
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

	private static String name(String id) {
		return Lang.tr((isFeature(id) ? "aerion.feature." : "aerion.module.") + id);
	}

	// --- input -------------------------------------------------------------------------------
	@Override
	public boolean pressed(double px, double py, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
		for (Hit h : List.copyOf(hits)) {
			if (h.clipped && (py < viewTop || py >= viewBottom)) continue;
			if (h.contains(px, py)) {
				if (h.slider) sliding = h;
				h.fire(px);
				break;
			}
		}
		return closing;
	}

	@Override
	public void dragged(double px, double py, int screenW, int screenH) {
		if (sliding != null) sliding.fire(px);
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
		mouseX = mx;
		mouseY = my;
		Theme t = AerionClient.theme();
		text = t != null ? t.text : 0xFFFFFFFF;
		muted = t != null ? t.textMuted : 0xFFAAAAAA;
		accent = t != null ? t.accent : 0xFF7AA2FF;
		accentText = t != null ? t.accentText : 0xFF111111;
		surface = Colors.alpha(t != null ? t.surface : 0xFF1A1A1A, 0xFF);
		surface2 = Colors.alpha(t != null ? t.surface2 : 0xFF2A2A2A, 0xFF);
		line = Colors.mix(surface, text, 0.16f);
		control = Colors.mix(surface, text, 0.1f);

		if (Minecraft.getInstance().level == null && t != null) {
			Draw.background(g, t, 0xFF);
			Particles.draw(g, t);
		} else {
			g.fill(0, 0, g.width(), g.height(), 0x70000000);
		}
		int winW = Math.min(g.width() - 12, 440), winH = Math.min(g.height() - 12, 250);
		int left = (g.width() - winW) / 2, top = (g.height() - winH) / 2;
		Draw.control(g, left, top, winW, winH, 4, line, Colors.alpha(surface, 0xF2));

		// Body first: its hits must not win over the fixed parts drawn after it.
		viewTop = top + HEADER + 1;
		viewBottom = top + winH - FOOTER;
		int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		header(left, top, winW);
		footer(left, top + winH - FOOTER, winW);
		clipping = true;
		g.scissor(left + 1, viewTop, left + winW - 1, viewBottom);
		x = left + 8;
		colW = winW - 16;
		y = viewTop + 4 - scroll;
		int start = y;
		if (tab == Tab.LOOK) look();
		else if (detail != null) detailPage(left + 8, winW - 16);
		else cards(left + 8, winW - 16);
		contentHeight = y - start + 8;
		g.endScissor();
		clipping = false;
		if (maxScroll > 0) {
			int view = viewBottom - viewTop;
			int barH = Math.max(12, view * view / Math.max(1, contentHeight));
			int barY = viewTop + (view - barH) * scroll / maxScroll;
			Draw.rounded(g, left + winW - 5, barY + 1, 2, barH - 2, 1, Colors.mix(surface, text, 0.3f));
		}
	}

	private void header(int left, int top, int w) {
		Theme t = AerionClient.theme();
		int logoH = 13;
		Logo.draw(g, t, left + 8, top + (HEADER - logoH) / 2, logoH);
		int bx = left + 14 + Logo.width(logoH);
		bx = tabButton(bx, top + 6, Lang.tr("aerion.panel.modules"), Tab.MODULES);
		tabButton(bx + 3, top + 6, Lang.tr("aerion.panel.look"), Tab.LOOK);
		iconButton(left + w - 21, top + 6, 14, "close", () -> closing = true);
		g.fill(left + 1, top + HEADER, left + w - 1, top + HEADER + 1, line);
	}

	private int tabButton(int bx, int by, String label, Tab which) {
		int w = Math.round(Txt.width(label, TEXT)) + 14;
		boolean on = tab == which;
		button(bx, by, w, 14, label, on, () -> {
			tab = which;
			detail = null;
			scroll = 0;
		});
		return bx + w;
	}

	private void footer(int left, int top, int w) {
		g.fill(left + 1, top, left + w - 1, top + 1, line);
		String edit = Lang.tr("aerion.menu.editLayout");
		int bw = Math.round(Txt.width(edit, TEXT)) + 16;
		button(left + 8, top + 5, bw, 14, edit, true, () -> Page.go(new HudEditor()));
		String warn = Lang.tr("aerion.panel.serverWarning");
		float room = w - bw - 26;
		if (Txt.width(warn, SMALL) <= room) Txt.draw(g, warn, left + w - 8 - Txt.width(warn, SMALL), top + 12, SMALL, muted);
	}

	// --- cards -------------------------------------------------------------------------------
	private void cards(int left, int w) {
		String[][] groups = {{"aerion.menu.all", "ALL"}, {"aerion.menu.hud", "HUD"}, {"aerion.menu.features", "FEATURES"}};
		int bx = left;
		for (String[] f : groups) {
			Filter which = Filter.valueOf(f[1]);
			String label = Lang.tr(f[0]);
			int bw = Math.round(Txt.width(label, SMALL)) + 12;
			smallButton(bx, y, bw, 12, label, filter == which, () -> {
				filter = which;
				scroll = 0;
			});
			bx += bw + 3;
		}
		y += 16;
		List<String> items = new ArrayList<>();
		if (filter != Filter.FEATURES) items.addAll(List.of(MODULES));
		if (filter != Filter.HUD) items.addAll(List.of(FEATURES));
		int cols = Math.max(2, (w + GAP) / (92 + GAP));
		int cw = (w - (cols - 1) * GAP) / cols, ch = 64;
		HudConfig cfg = AerionClient.hud();
		for (int i = 0; i < items.size(); i++) {
			card(cfg, items.get(i), left + (i % cols) * (cw + GAP), y + (i / cols) * (ch + GAP), cw, ch);
		}
		y += ((items.size() + cols - 1) / cols) * (ch + GAP);
	}

	private void card(HudConfig cfg, String id, int cx, int cy, int w, int h) {
		boolean on = enabled(cfg, id);
		boolean over = inside(cx, cy, w, h);
		Draw.control(g, cx, cy, w, h, 3, over ? Colors.mix(surface, accent, 0.6f) : line, surface2);
		int ink = on ? text : muted;
		Icons.draw(g, id, cx + w / 2f - 8, cy + 5, 16, ink);
		Txt.center(g, name(id), cx + w / 2f, cy + 28, TEXT, ink);
		smallButton(cx + 3, cy + 35, w - 6, 12, Lang.tr("aerion.menu.options"), false, () -> {
			detail = id;
			scroll = 0;
		});
		boolean barOver = inside(cx + 3, cy + 49, w - 6, 12);
		int bar = on ? GREEN : RED;
		Draw.rounded(g, cx + 3, cy + 49, w - 6, 12, 2, barOver ? Colors.mix(bar, 0xFFFFFFFF, 0.12f) : bar);
		Txt.center(g, Lang.tr(on ? "aerion.menu.on" : "aerion.menu.off"), cx + w / 2f, cy + 55, SMALL, 0xFFFFFFFF);
		hit(cx + 3, cy + 49, w - 6, 12, f -> {
			item(cfg, id).add("enabled", new JsonPrimitive(!on));
			changed = true;
		}, false);
	}

	// --- options of one card -----------------------------------------------------------------
	private void detailPage(int left, int w) {
		HudConfig cfg = AerionClient.hud();
		iconButton(left, y, 14, "back", () -> {
			detail = null;
			scroll = 0;
		});
		Icons.draw(g, detail, left + 19, y + 1, 12, text);
		Txt.draw(g, name(detail), left + 35, y + 7, 9f, text);
		String reset = Lang.tr("aerion.menu.reset");
		int rw = Math.round(Txt.width(reset, SMALL)) + 12;
		smallButton(left + w - rw, y + 1, rw, 12, reset, false, () -> {
			reset(cfg);
			changed = true;
		});
		y += 19;
		int top = y;
		int col = isFeature(detail) ? w : (w - 8) / 2;
		x = left;
		colW = col;
		toggle(Lang.tr("aerion.panel.enabled"), enabled(cfg, detail), v -> item(cfg, detail).add("enabled", new JsonPrimitive(v)));
		if (isFeature(detail)) {
			featureOptions(cfg);
			return;
		}
		JsonObject m = cfg.module(detail);
		slider(Lang.tr("aerion.panel.scale"), HudConfig.num(m, "scale", 100), 25, 400, 5, "%", v -> m.add("scale", new JsonPrimitive(v)));
		for (Option o : OPTIONS.getOrDefault(detail, List.of())) option(m, o);
		if (List.of("fps", "cps", "ping", "memory").contains(detail)) note(Lang.tr("aerion.panel.formatHint"));
		int leftEnd = y;
		// Right column: the module's own look.
		y = top;
		x = left + col + 8;
		JsonObject own = m.get("style") instanceof JsonObject s && !s.isEmpty() ? s : null;
		toggle(Lang.tr("aerion.panel.ownLook"), own != null, v -> {
			if (v) m.add("style", cfg.style().deepCopy());
			else m.remove("style");
		});
		if (own != null) styleFields(own);
		else note(Lang.tr("aerion.panel.ownLookHint"));
		y = Math.max(y, leftEnd);
	}

	private void reset(HudConfig cfg) {
		JsonObject o = item(cfg, detail);
		if (detail.equals("zoom")) {
			o.addProperty("factor", 4);
			o.addProperty("smooth", true);
		} else if (detail.equals("freelook")) {
			o.addProperty("hold", true);
		} else if (!isFeature(detail)) {
			o.remove("style");
			o.remove("options");
			o.addProperty("scale", 100);
		}
	}

	private void featureOptions(HudConfig cfg) {
		JsonObject f = cfg.feature(detail);
		if (detail.equals("zoom")) {
			slider(Lang.tr("aerion.feature.zoomFactor"), HudConfig.num(f, "factor", 4), 2, 10, 1, "x", v -> f.add("factor", new JsonPrimitive(v)));
			toggle(Lang.tr("aerion.feature.zoomSmooth"), HudConfig.bool(f, "smooth", true), v -> f.add("smooth", new JsonPrimitive(v)));
		} else if (detail.equals("freelook")) {
			toggle(Lang.tr("aerion.feature.freelookHold"), HudConfig.bool(f, "hold", true), v -> f.add("hold", new JsonPrimitive(v)));
		}
		note(Lang.tr("aerion.menu.keyHint"));
		if (detail.equals("freelook") || detail.equals("fullbright")) note(Lang.tr("aerion.panel.serverWarning"));
	}

	private void option(JsonObject m, Option o) {
		String label = Lang.tr("aerion.opt." + detail + "." + o.key);
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

	// --- shared look -------------------------------------------------------------------------
	private void look() {
		HudConfig cfg = AerionClient.hud();
		int left = x, w = colW, col = (w - 8) / 2;
		colW = col;
		toggle(Lang.tr("aerion.panel.hud"), cfg.enabled(), v -> cfg.root.add("enabled", new JsonPrimitive(v)));
		note(Lang.tr("aerion.panel.lookTitle"));
		int top = y;
		JsonObject s = cfg.style();
		colorFields(s);
		int end = y;
		y = top;
		x = left + col + 8;
		shapeFields(s);
		y = Math.max(y, end);
	}

	private void styleFields(JsonObject s) {
		colorFields(s);
		shapeFields(s);
	}

	private void colorFields(JsonObject s) {
		String theme = Lang.tr("aerion.style.theme"), none = Lang.tr("aerion.style.none"), acc = Lang.tr("aerion.style.accent");
		color(Lang.tr("aerion.style.text"), new String[] {"theme"}, new String[] {theme}, HudConfig.str(s, "text", "theme"), v -> s.addProperty("text", v));
		color(Lang.tr("aerion.style.background"), new String[] {"theme", "none"}, new String[] {theme, none}, HudConfig.str(s, "background", "theme"), v -> s.addProperty("background", v));
		slider(Lang.tr("aerion.style.opacity"), HudConfig.num(s, "opacity", 55), 0, 100, 5, "%", v -> s.addProperty("opacity", v));
		color(Lang.tr("aerion.style.border"), new String[] {"none", "accent"}, new String[] {none, acc}, HudConfig.str(s, "border", "none"), v -> s.addProperty("border", v));
	}

	private void shapeFields(JsonObject s) {
		slider(Lang.tr("aerion.style.radius"), HudConfig.num(s, "radius", 0), 0, 8, 1, "", v -> s.addProperty("radius", v));
		slider(Lang.tr("aerion.style.padding"), HudConfig.num(s, "padding", 3), 0, 10, 1, "", v -> s.addProperty("padding", v));
		toggle(Lang.tr("aerion.style.shadow"), HudConfig.bool(s, "shadow", true), v -> s.addProperty("shadow", v));
		choice(Lang.tr("aerion.style.align"), new String[] {"left", "center", "right"},
				new String[] {Lang.tr("aerion.style.left"), Lang.tr("aerion.style.center"), Lang.tr("aerion.style.right")},
				HudConfig.str(s, "align", "left"), v -> s.addProperty("align", v));
	}

	// --- controls ----------------------------------------------------------------------------
	private void hit(int hx, int hy, int w, int h, DoubleConsumer action, boolean slider) {
		hits.add(new Hit(hx, hy, w, h, action, slider, clipping));
	}

	private boolean inside(int cx, int cy, int w, int h) {
		if (clipping && (mouseY < viewTop || mouseY >= viewBottom)) return false;
		return mouseX >= cx && mouseY >= cy && mouseX < cx + w && mouseY < cy + h;
	}

	private void button(int bx, int by, int w, int h, String label, boolean on, Runnable action) {
		boolean over = inside(bx, by, w, h);
		int body = on ? (over ? Colors.mix(accent, 0xFFFFFFFF, 0.12f) : accent) : over ? Colors.mix(surface, text, 0.2f) : control;
		Draw.rounded(g, bx, by, w, h, 3, body);
		Txt.center(g, label, bx + w / 2f, by + h / 2f, TEXT, on ? accentText : text);
		hit(bx, by, w, h, f -> action.run(), false);
	}

	private void smallButton(int bx, int by, int w, int h, String label, boolean on, Runnable action) {
		boolean over = inside(bx, by, w, h);
		int body = on ? (over ? Colors.mix(accent, 0xFFFFFFFF, 0.12f) : accent) : over ? Colors.mix(surface, text, 0.2f) : control;
		Draw.rounded(g, bx, by, w, h, 2, body);
		Txt.center(g, label, bx + w / 2f, by + h / 2f, SMALL, on ? accentText : text);
		hit(bx, by, w, h, f -> action.run(), false);
	}

	private void iconButton(int bx, int by, int size, String icon, Runnable action) {
		boolean over = inside(bx, by, size, size);
		Draw.rounded(g, bx, by, size, size, 3, over ? Colors.mix(surface, text, 0.2f) : control);
		Icons.draw(g, icon, bx + 2, by + 2, size - 4, text);
		hit(bx, by, size, size, f -> action.run(), false);
	}

	private void heading(String s) {
		Txt.draw(g, s, x, y + 5, SMALL, muted);
		y += 11;
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
		if (inside(x - 2, y, colW + 4, ROW)) Draw.rounded(g, x - 2, y, colW + 4, ROW, 2, control);
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		int sx = x + colW - 20, sy = y + 3;
		Draw.rounded(g, sx, sy, 20, 9, 4, on ? accent : Colors.mix(surface, text, 0.3f));
		Draw.rounded(g, on ? sx + 12 : sx + 1, sy + 1, 7, 7, 3, on ? accentText : surface);
		hit(x - 2, y, colW + 4, ROW, f -> {
			set.accept(!on);
			changed = true;
		}, false);
		y += ROW;
	}

	/** Value slider; the number sits on the bar. */
	private void slider(String label, int value, int min, int max, int step, String unit, Consumer<Integer> set) {
		int bx = x + Math.min(78, colW / 2), w = x + colW - bx;
		Txt.draw(g, label, x, y + ROW / 2f, TEXT, text);
		Draw.rounded(g, bx, y + 1, w, ROW - 3, 2, control);
		float f = (float) (Math.max(min, Math.min(max, value)) - min) / Math.max(1, max - min);
		int fill = Math.round(w * f);
		if (fill > 0) Draw.rounded(g, bx, y + 1, fill, ROW - 3, 2, Colors.alpha(accent, 0xB0));
		Txt.center(g, value + unit, bx + w / 2f, y + ROW / 2f - 0.5f, SMALL, text);
		hit(bx, y, w, ROW, fr -> {
			int v = min + Math.round((float) (fr * (max - min)) / step) * step;
			set.accept(Math.max(min, Math.min(max, v)));
			changed = true;
		}, true);
		y += ROW;
	}

	/** A row of mutually exclusive buttons under its label. */
	private void choice(String label, String[] values, String[] labels, String current, Consumer<String> set) {
		heading(label);
		int w = colW / values.length;
		for (int i = 0; i < values.length; i++) {
			String v = values[i];
			smallButton(x + i * w, y, w - 2, 13, labels[i], v.equals(current), () -> {
				set.accept(v);
				changed = true;
			});
		}
		y += 16;
	}

	/** Named choices ("theme", "none"…), swatches and, for a colour, exact R/G/B sliders. */
	private void color(String label, String[] words, String[] wordLabels, String current, Consumer<String> set) {
		heading(label);
		int bx = x;
		for (int i = 0; i < words.length; i++) {
			String v = words[i];
			int w = Math.round(Txt.width(wordLabels[i], SMALL)) + 8;
			smallButton(bx, y, w, 11, wordLabels[i], v.equals(current), () -> {
				set.accept(v);
				changed = true;
			});
			bx += w + 2;
		}
		for (int c : SWATCHES) {
			if (bx + 10 > x + colW) break;
			String hex = hex(c);
			boolean on = hex.equalsIgnoreCase(current);
			Draw.control(g, bx, y, 10, 11, 2, on ? accent : line, c);
			hit(bx, y, 10, 11, f -> {
				set.accept(hex);
				changed = true;
			}, false);
			bx += 11;
		}
		y += 14;
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
}
