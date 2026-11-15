package pl.aerion.mod;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.hud.Page;
import pl.aerion.mod.platform.EditorScreen;
import pl.aerion.mod.platform.Game;
import pl.aerion.mod.platform.Gfx;

/**
 * The title screen's look in a panel on its right, the screen itself still showing (live):
 * background (aurora, the game's panorama, your world; blur, shade), which buttons show, whether
 * they wear the theme, and the theme. Everything is saved at once, like the Aerion menu.
 */
public final class MenuStylePage implements Page {
	private record Hit(int x, int y, int w, int h, Runnable run) {}

	private record Slider(int x, int y, int w, int min, int max, IntConsumer set) {}

	private final List<Hit> hits = new ArrayList<>();
	private final List<Slider> sliders = new ArrayList<>();
	private Slider dragging;
	private int scroll, contentH, viewH;
	private float slideIn;
	private long opened = System.nanoTime();

	private static final int W = 196, PAD = 10, ROW = 18;

	@Override
	public boolean veil() {
		return false;
	}

	@Override
	public boolean showParent() {
		return true;
	}

	private static JsonObject menus() {
		return AerionClient.hud().feature("menus");
	}

	private static String opt(String key, String fallback) {
		return HudConfig.opt(menus(), key, fallback);
	}

	private static void set(String key, String value) {
		JsonObject f = menus();
		JsonObject o = f.has("options") && f.get("options").isJsonObject() ? f.getAsJsonObject("options") : new JsonObject();
		o.addProperty(key, value);
		f.add("options", o);
		AerionClient.saveHud();
	}

	/** The title screen lays itself out again (buttons shown or hidden, the player card). */
	private static void relayout() {
		if (Game.screen() instanceof EditorScreen s) s.refreshParent();
	}

	// --- Drawing ---------------------------------------------------------------------------------

	private int text, muted, surface, control, accent, accentText, line;

	@Override
	public void render(Gfx g, int mouseX, int mouseY) {
		Theme t = AerionClient.theme();
		text = t != null ? t.text : 0xFFFFFFFF;
		muted = t != null ? t.textMuted : 0xFFAAAAAA;
		surface = t != null ? t.surface : 0xFF1E1E22;
		control = t != null ? t.surface2 : 0xFF2A2A30;
		accent = t != null ? t.accent : 0xFF7AA2FF;
		accentText = t != null ? t.accentText : 0xFF000000;
		line = t != null ? t.border : 0xFF3A3A40;
		hits.clear();
		sliders.clear();
		// Slides in from the right.
		slideIn = Math.min(1f, (System.nanoTime() - opened) / 2.2e8f);
		float ease = 1 - (1 - slideIn) * (1 - slideIn);
		int x0 = g.width() - Math.round((W + 8) * ease), top = 8, h = g.height() - 16;
		Draw.shadow(g, x0, top, W, h, 8);
		Draw.rounded(g, x0, top, W, h, 8, Colors.alpha(surface, 0xF4));
		Txt.Display.draw(g, Lang.tr("aerion.look.title"), x0 + PAD, top + 12, 8.5f, text);
		closeButton(g, x0 + W - 22, top + 5, mouseX, mouseY);
		viewH = h - 34 - 28;
		g.scissor(x0, top + 26, x0 + W, top + 26 + viewH);
		int y = top + 30 - scroll, x = x0 + PAD, w = W - 2 * PAD;
		int startY = y;

		y = heading(g, Lang.tr("aerion.look.background"), x, y);
		String scene = MenuScene.mode();
		y = segments(g, x, y, w, new String[] {"aurora", "panorama", "world"}, scene, mouseX, mouseY, v -> set("scene", v));
		if (!"aurora".equals(scene)) {
			if ("world".equals(scene)) y = slider(g, Lang.tr("aerion.look.blur"), x, y, w, number("blur", 6), 0, 20, mouseX, mouseY, v -> set("blur", String.valueOf(v)));
			y = slider(g, Lang.tr("aerion.look.dim"), x, y, w, number("dim", 55), 0, 90, mouseX, mouseY, v -> set("dim", String.valueOf(v)));
		}
		if ("world".equals(scene) && !MenuScene.hasWorldPicture()) y = note(g, Lang.tr("aerion.menu.sceneWorldSoon"), x, y, w);

		y += 6;
		y = heading(g, Lang.tr("aerion.look.buttons"), x, y);
		y = toggle(g, Lang.tr("aerion.look.styled"), x, y, w, !"0".equals(opt("buttons", "1")), mouseX, mouseY, v -> set("buttons", v ? "1" : "0"));
		y = toggle(g, Lang.tr("aerion.look.realms"), x, y, w, !MenuLook.hidden("realms"), mouseX, mouseY, v -> {
			set("show.realms", v ? "1" : "0");
			relayout();
		});
		y = toggle(g, Lang.tr("aerion.look.icons"), x, y, w, !MenuLook.hidden("icons"), mouseX, mouseY, v -> {
			set("show.icons", v ? "1" : "0");
			relayout();
		});
		y = toggle(g, Lang.tr("aerion.look.player"), x, y, w, !"0".equals(opt("player", "1")), mouseX, mouseY, v -> {
			set("player", v ? "1" : "0");
			relayout();
		});
		Minecraft mc = Minecraft.getInstance();
		boolean splash = !mc.options.hideSplashTexts().get();
		y = toggle(g, Lang.tr("aerion.look.splash"), x, y, w, splash, mouseX, mouseY, v -> {
			mc.options.hideSplashTexts().set(!v);
			mc.options.save();
			relayout();
		});

		Theme now = AerionClient.theme();
		if (now != null && !now.choices.isEmpty()) {
			y += 6;
			y = heading(g, Lang.tr("aerion.look.theme"), x, y);
			int tw = (w - 6) / 3, th = 24, i = 0;
			for (Theme.Choice c : now.choices) {
				int tx = x + (i % 3) * (tw + 3), ty = y + (i / 3) * (th + 3);
				boolean on = c.id().equals(now.id);
				boolean over = inside(mouseX, mouseY, tx, ty, tw, th);
				if (on) Draw.rounded(g, tx - 1, ty - 1, tw + 2, th + 2, 4, accent);
				Draw.rounded(g, tx, ty, tw, th, 3, c.bg());
				Draw.rounded(g, tx + 4, ty + 4, 6, 6, 2, c.accent());
				if (over && !on) Draw.outline(g, tx, ty, tw, th, Colors.alpha(text, 0x80));
				String name = c.name();
				while (name.length() > 1 && Txt.width(name, 6f) > tw - 8) name = name.substring(0, name.length() - 1);
				Txt.draw(g, name, tx + 4, ty + th - 7, 6f, Colors.luminance(c.bg()) > 0.55f ? 0xFF1A1A1A : 0xFFF2F2F2);
				if (!on) hits.add(new Hit(tx, ty, tw, th, () -> AerionClient.requestTheme(c.id())));
				i++;
			}
			y += ((now.choices.size() + 2) / 3) * (th + 3);
		}
		contentH = y - startY + 8;
		g.endScissor();

		// Footer: done.
		int fy = top + h - 26;
		Draw.hline(g, x0 + PAD, fy - 4, W - 2 * PAD, Colors.alpha(line, 0x80));
		String done = Lang.tr("aerion.look.done");
		int dw = Math.round(Txt.width(done, 7f)) + 20;
		button(g, x0 + W - PAD - dw, fy, dw, 18, done, true, mouseX, mouseY, null);
	}

	private int number(String key, int fallback) {
		try {
			return Integer.parseInt(opt(key, String.valueOf(fallback)));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	private int heading(Gfx g, String s, int x, int y) {
		Txt.Display.draw(g, s.toUpperCase(java.util.Locale.ROOT), x, y + 5, 5.5f, muted);
		return y + 13;
	}

	private int note(Gfx g, String s, int x, int y, int w) {
		StringBuilder row = new StringBuilder();
		for (String word : s.split(" ")) {
			String next = row.isEmpty() ? word : row + " " + word;
			if (Txt.width(next, 6f) > w && !row.isEmpty()) {
				Txt.draw(g, row.toString(), x, y + 4, 6f, muted);
				y += 9;
				row = new StringBuilder(word);
			} else {
				row = new StringBuilder(next);
			}
		}
		if (!row.isEmpty()) {
			Txt.draw(g, row.toString(), x, y + 4, 6f, muted);
			y += 9;
		}
		return y + 3;
	}

	private int segments(Gfx g, int x, int y, int w, String[] values, String now, int mx, int my, java.util.function.Consumer<String> set) {
		Draw.rounded(g, x, y, w, ROW, 4, control);
		int sw = (w - 4) / values.length;
		for (int i = 0; i < values.length; i++) {
			int sx = x + 2 + i * sw;
			boolean on = values[i].equals(now), over = inside(mx, my, sx, y + 2, sw, ROW - 4);
			if (on) Draw.rounded(g, sx, y + 2, sw, ROW - 4, 3, accent);
			else if (over) Draw.rounded(g, sx, y + 2, sw, ROW - 4, 3, Colors.alpha(text, 0x18));
			Txt.center(g, Lang.tr("aerion.choice." + values[i]), sx + sw / 2f, y + ROW / 2f, 6.5f, on ? accentText : text);
			String v = values[i];
			if (!on) hits.add(new Hit(sx, y + 2, sw, ROW - 4, () -> set.accept(v)));
		}
		return y + ROW + 5;
	}

	private int toggle(Gfx g, String label, int x, int y, int w, boolean on, int mx, int my, java.util.function.Consumer<Boolean> set) {
		boolean over = inside(mx, my, x, y, w, ROW);
		if (over) Draw.rounded(g, x - 3, y, w + 6, ROW, 3, Colors.alpha(text, 0x10));
		Txt.draw(g, label, x, y + ROW / 2f, 7f, text);
		int sx = x + w - 20, sy = y + (ROW - 10) / 2;
		Draw.rounded(g, sx, sy, 20, 10, 5, on ? accent : control);
		Draw.rounded(g, on ? sx + 11 : sx + 1, sy + 1, 8, 8, 4, on ? accentText : muted);
		hits.add(new Hit(x - 3, y, w + 6, ROW, () -> set.accept(!on)));
		return y + ROW + 1;
	}

	private int slider(Gfx g, String label, int x, int y, int w, int value, int min, int max, int mx, int my, IntConsumer set) {
		Txt.draw(g, label, x, y + 6, 7f, text);
		String v = String.valueOf(value);
		Txt.draw(g, v, x + w - Txt.width(v, 6.5f), y + 6, 6.5f, muted);
		int ty = y + 14, tw = w;
		float f = (value - min) / (float) Math.max(1, max - min);
		Draw.rounded(g, x, ty, tw, 4, 2, control);
		Draw.rounded(g, x, ty, Math.max(4, Math.round(tw * f)), 4, 2, accent);
		int kx = x + Math.round(tw * f);
		Draw.rounded(g, kx - 4, ty - 3, 8, 10, 3, inside(mx, my, x, ty - 5, tw, 14) ? text : Colors.mix(text, accent, 0.3f));
		sliders.add(new Slider(x, ty - 5, tw, min, max, set));
		return y + 24;
	}

	private void button(Gfx g, int x, int y, int w, int h, String label, boolean primary, int mx, int my, Runnable run) {
		boolean over = inside(mx, my, x, y, w, h);
		Draw.rounded(g, x, y, w, h, 4, primary ? (over ? Colors.mix(accent, 0xFFFFFFFF, 0.1f) : accent) : (over ? Colors.mix(control, text, 0.1f) : control));
		Txt.center(g, label, x + w / 2f, y + h / 2f, 7f, primary ? accentText : text);
		hits.add(new Hit(x, y, w, h, run == null ? () -> closeNow = true : run));
	}

	private void closeButton(Gfx g, int x, int y, int mx, int my) {
		boolean over = inside(mx, my, x, y, 16, 16);
		if (over) Draw.rounded(g, x, y, 16, 16, 3, control);
		Icons.draw(g, "close", x + 3, y + 3, 10, over ? text : muted);
		hits.add(new Hit(x, y, 16, 16, () -> closeNow = true));
	}

	// --- Input ---------------------------------------------------------------------------------

	private boolean closeNow;

	@Override
	public boolean pressed(double x, double y, int button) {
		for (Slider s : sliders) {
			if (inside((int) x, (int) y, s.x, s.y, s.w, 14)) {
				dragging = s;
				drag(x);
				return false;
			}
		}
		for (Hit h : hits) {
			if (inside((int) x, (int) y, h.x, h.y, h.w, h.h)) {
				h.run.run();
				boolean close = closeNow;
				closeNow = false;
				return close;
			}
		}
		// A click outside the panel closes it.
		return x < Minecraft.getInstance().getWindow().getGuiScaledWidth() - W - 8;
	}

	private void drag(double x) {
		Slider s = dragging;
		float f = (float) Math.max(0, Math.min(1, (x - s.x) / s.w));
		s.set.accept(Math.round(s.min + f * (s.max - s.min)));
	}

	@Override
	public void dragged(double x, double y, int screenW, int screenH) {
		if (dragging != null) drag(x);
	}

	@Override
	public void released() {
		dragging = null;
	}

	@Override
	public void scrolled(double x, double y, double amount) {
		scroll = (int) Math.max(0, Math.min(Math.max(0, contentH - viewH), scroll - amount * 16));
	}

	@Override
	public void close() {}
}
