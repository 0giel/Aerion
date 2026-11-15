package pl.aerion.mod.mirror;

import com.mojang.blaze3d.platform.InputConstants;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.hud.Page;
import pl.aerion.mod.platform.Gfx;

/** Typing into the window on a panel: letters and a few keys go there until Esc. */
public final class TypePage implements Page {
	private final long win;
	private final String title;

	public TypePage(long win, String title) {
		this.win = win;
		this.title = title;
	}

	@Override
	public boolean veil() {
		return false;
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY) {
		Theme t = AerionClient.theme();
		int surface = t != null ? t.surface : 0xFF202020, text = t != null ? t.text : 0xFFFFFFFF, accent = t != null ? t.accent : 0xFF55AAFF;
		String s = Lang.tr("aerion.mirrors.typing").replace("{name}", title);
		int w = Math.round(Txt.width(s, 7.5f)) + 24, x = (g.width() - w) / 2, y = g.height() - 70;
		Draw.rounded(g, x, y, w, 18, 5, Colors.alpha(surface, 0xE8));
		g.fill(x + 8, y + 7, x + 12, y + 11, accent);
		Txt.draw(g, s, x + 16, y + 9, 7.5f, text);
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		WindowSource src = Mirrors.source();
		if (src == null) return false;
		String name = switch (key.getValue()) {
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> "enter";
			case InputConstants.KEY_BACKSPACE -> "backspace";
			case InputConstants.KEY_TAB -> "tab";
			case InputConstants.KEY_LEFT -> "left";
			case InputConstants.KEY_RIGHT -> "right";
			case InputConstants.KEY_UP -> "up";
			case InputConstants.KEY_DOWN -> "down";
			default -> null;
		};
		if (name == null) return false;
		src.key(win, name);
		return true;
	}

	@Override
	public boolean charTyped(int codepoint) {
		WindowSource src = Mirrors.source();
		if (src != null) src.type(win, new String(Character.toChars(codepoint)));
		return true;
	}

	@Override
	public boolean pressed(double x, double y, int button) {
		return false;
	}

	@Override
	public void dragged(double x, double y, int screenW, int screenH) {}

	@Override
	public void released() {}

	@Override
	public void scrolled(double x, double y, double amount) {}

	@Override
	public void close() {}
}
