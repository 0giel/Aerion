package pl.aerion.mod.mirror;

import pl.aerion.screens.client.Mirrors;
import pl.aerion.screens.client.PortalSource;
import pl.aerion.screens.client.WindowSource;
import com.mojang.blaze3d.platform.InputConstants;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.hud.Page;
import pl.aerion.mod.platform.Gfx;

/**
 * Typing into the window on a panel: the window takes the keyboard (every key and shortcut goes
 * straight to it) until Esc or a click; where it can't, keys are passed on one by one.
 */
public final class TypePage implements Page {
	private final long win;
	private final String title;
	private boolean started, held;

	private void start() {
		if (started) return;
		started = true;
		WindowSource src = Mirrors.source();
		held = src != null && src.holdKeyboard(win, () -> net.minecraft.client.Minecraft.getInstance().execute(() -> pl.aerion.mod.platform.Game.setScreen(null)));
	}

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
		start();
		Theme t = AerionClient.theme();
		int surface = t != null ? t.surface : 0xFF202020, text = t != null ? t.text : 0xFFFFFFFF, accent = t != null ? t.accent : 0xFF55AAFF;
		// Long window titles (a file's whole path) are cut so the bar stays on the screen.
		String name = title;
		String key = held ? "aerion.mirrors.typingHeld" : "aerion.mirrors.typing";
		String s = Lang.tr(key).replace("{name}", name);
		while (Txt.width(s, 7.5f) > g.width() - 60 && name.length() > 8) {
			name = name.substring(0, name.length() - 2);
			s = Lang.tr(key).replace("{name}", name + "…");
		}
		int w = Math.round(Txt.width(s, 7.5f)) + 24, x = (g.width() - w) / 2, y = g.height() - 70;
		Draw.rounded(g, x, y, w, 18, 5, Colors.alpha(surface, 0xE8));
		g.fill(x + 8, y + 7, x + 12, y + 11, accent);
		Txt.draw(g, s, x + 16, y + 9, 7.5f, text);
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		WindowSource src = Mirrors.source();
		if (src == null) return false;
		int mods = pl.aerion.screens.client.Platform.modifiers();
		// Esc ends typing (the screen does that); Shift+Esc sends Esc to the window.
		if (key.getValue() == InputConstants.KEY_ESCAPE) {
			if ((mods & WindowSource.SHIFT) == 0) return false;
			src.key(win, "escape", mods & ~WindowSource.SHIFT);
			return true;
		}
		String name = pl.aerion.screens.client.Keys.toSend(key.getValue(), mods);
		if (name == null) return false;
		src.key(win, name, mods);
		return true;
	}

	@Override
	public boolean charTyped(int codepoint) {
		WindowSource src = Mirrors.source();
		if (src != null) src.type(win, new String(Character.toChars(codepoint)));
		return true;
	}

	/** A click in the game ends typing. */
	@Override
	public boolean pressed(double x, double y, int button) {
		if (!held) return false;
		close();
		pl.aerion.mod.platform.Game.setScreen(null);
		return true;
	}

	@Override
	public void dragged(double x, double y, int screenW, int screenH) {}

	@Override
	public void released() {}

	@Override
	public void scrolled(double x, double y, double amount) {}

	@Override
	public void close() {
		WindowSource src = Mirrors.source();
		if (held && src != null) src.releaseKeyboard();
		held = false;
	}
}
