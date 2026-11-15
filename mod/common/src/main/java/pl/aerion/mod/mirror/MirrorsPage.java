package pl.aerion.mod.mirror;

import java.util.ArrayList;
import java.util.List;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.hud.Page;
import pl.aerion.mod.platform.Gfx;

/**
 * Picking windows to hang in the world, and the panels already there: bent or flat, or removed.
 * Clicking a window puts it in front of the player and closes this page.
 */
public final class MirrorsPage implements Page {
	private record Button(int x, int y, int w, int h, Runnable run) {}

	private final List<Button> buttons = new ArrayList<>();
	private List<WindowSource.Win> windows;
	private int scroll;

	private int c(java.util.function.ToIntFunction<Theme> f, int fallback) {
		Theme t = AerionClient.theme();
		return t != null ? f.applyAsInt(t) : fallback;
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY) {
		if (windows == null) {
			WindowSource src = Mirrors.source();
			windows = src == null ? List.of() : src.list();
		}
		buttons.clear();
		int text = c(t -> t.text, 0xFFFFFFFF), muted = c(t -> t.textMuted, 0xFFAAAAAA), accent = c(t -> t.accent, 0xFF55AAFF);
		int surface = c(t -> t.surface, 0xFF202020), control = c(t -> t.surface2, 0xFF303030), accentText = c(t -> t.accentText, 0xFF000000);
		int w = Math.min(420, g.width() - 40), x = (g.width() - w) / 2, y = 30 - scroll;
		Draw.rounded(g, x - 12, 16, w + 24, g.height() - 32, 8, Colors.alpha(surface, 0xF0));
		Txt.Display.draw(g, Lang.tr("aerion.mirrors.title"), x, y + 6, 9, text);
		y += 18;
		Txt.draw(g, Lang.tr("aerion.mirrors.hint"), x, y + 4, 6.5f, muted);
		y += 16;
		if (Mirrors.source() == null) {
			Txt.draw(g, Lang.tr("aerion.mirrors.unsupported"), x, y + 5, 7, muted);
			return;
		}
		if (windows.isEmpty()) Txt.draw(g, Lang.tr("aerion.mirrors.none"), x, y + 5, 7, muted);
		for (WindowSource.Win win : windows) {
			boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 18;
			Draw.rounded(g, x, y, w, 18, 4, over ? control : Colors.mix(surface, control, 0.5f));
			Txt.draw(g, ellipsize(win.title(), w - 70), x + 8, y + 9, 7.5f, text);
			Txt.draw(g, Lang.tr("aerion.mirrors.place"), x + w - 56, y + 9, 6.5f, over ? accent : muted);
			buttons.add(new Button(x, y, w, 18, () -> Mirrors.place(win)));
			y += 21;
		}
		List<Mirrors.Panel> panels = Mirrors.all();
		if (!panels.isEmpty()) {
			y += 8;
			Txt.Display.draw(g, Lang.tr("aerion.mirrors.panels").toUpperCase(java.util.Locale.ROOT), x, y + 4, 5.5f, muted);
			y += 12;
			for (Mirrors.Panel p : new ArrayList<>(panels)) {
				Draw.rounded(g, x, y, w, 18, 4, Colors.mix(surface, control, 0.5f));
				Txt.draw(g, ellipsize(p.title, w - 150), x + 8, y + 9, 7.5f, p.win != 0 ? text : muted);
				int bx = x + w - 136;
				String shape = Lang.tr(p.curved ? "aerion.mirrors.curved" : "aerion.mirrors.flat");
				small(g, bx, y + 3, 70, shape, mouseX, mouseY, control, text, () -> {
					p.curved = !p.curved;
					Mirrors.save();
				});
				small(g, bx + 74, y + 3, 58, Lang.tr("aerion.mirrors.remove"), mouseX, mouseY, accent, accentText, () -> Mirrors.remove(p));
				y += 21;
			}
		}
		y += 8;
		Txt.draw(g, Lang.tr("aerion.mirrors.keys"), x, y + 4, 6.5f, muted);
	}

	private void small(Gfx g, int bx, int by, int bw, String label, int mx, int my, int fill, int ink, Runnable run) {
		boolean over = mx >= bx && mx < bx + bw && my >= by && my < by + 12;
		Draw.rounded(g, bx, by, bw, 12, 3, over ? Colors.mix(fill, 0xFFFFFFFF, 0.15f) : fill);
		Txt.center(g, label, bx + bw / 2f, by + 6, 6.5f, ink);
		buttons.add(0, new Button(bx, by, bw, 12, run));
	}

	private static String ellipsize(String s, int width) {
		if (Txt.width(s, 7.5f) <= width) return s;
		while (s.length() > 1 && Txt.width(s + "…", 7.5f) > width) s = s.substring(0, s.length() - 1);
		return s + "…";
	}

	@Override
	public boolean pressed(double mx, double my, int button) {
		for (Button b : buttons) {
			if (mx >= b.x && mx < b.x + b.w && my >= b.y && my < b.y + b.h) {
				b.run.run();
				// Placing a window closes the page so the panel can be seen at once.
				return b.w > 100;
			}
		}
		return false;
	}

	@Override
	public void dragged(double x, double y, int screenW, int screenH) {}

	@Override
	public void released() {}

	@Override
	public void scrolled(double x, double y, double amount) {
		scroll = Math.max(0, scroll - (int) Math.round(amount * 20));
	}

	@Override
	public void close() {}
}
