package pl.aerion.mod.hud;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Icons;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.features.Features;
import pl.aerion.mod.platform.Game;
import pl.aerion.mod.platform.Gfx;

/**
 * The quick wheel: shown while its key is held, the mouse points at one of the slots around the
 * middle and letting the key go (or a click) runs it. The game goes on behind it.
 */
public final class WheelPage implements Page {
	private static final int RADIUS = 62, INNER = 18;
	private final List<String> slots;
	private int chosen = -1;
	private boolean done;

	public WheelPage(List<String> slots) {
		this.slots = new ArrayList<>(slots);
	}

	@Override
	public boolean veil() {
		return false;
	}

	@Override
	public boolean pauses() {
		return false;
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY) {
		Theme t = AerionClient.theme();
		int surface = t != null ? t.surface : 0xFF202020, accent = t != null ? t.accent : 0xFF55AAFF, text = t != null ? t.text : 0xFFFFFFFF;
		int accentText = t != null ? t.accentText : 0xFF000000;
		float cx = g.width() / 2f, cy = g.height() / 2f;
		int n = slots.size();
		if (n == 0) return;
		double dx = mouseX - cx, dy = mouseY - cy;
		chosen = dx * dx + dy * dy < INNER * INNER ? -1 : slot(Math.atan2(dx, -dy), n);
		Draw.rounded(g, Math.round(cx - 9), Math.round(cy - 9), 18, 18, 9, Colors.alpha(surface, 0xC0));
		for (int i = 0; i < n; i++) {
			double a = i * 2 * Math.PI / n;
			int sx = Math.round(cx + (float) Math.sin(a) * RADIUS), sy = Math.round(cy - (float) Math.cos(a) * RADIUS);
			boolean on = i == chosen;
			int size = on ? 34 : 30;
			Draw.rounded(g, sx - size / 2, sy - size / 2, size, size, 8, on ? accent : Colors.alpha(surface, 0xE6));
			String id = slots.get(i);
			Icons.draw(g, Features.wheelIcon(id), sx - 8, sy - 8, 16, on ? accentText : text);
			if (on) {
				String label = Features.wheelLabel(id);
				float w = Txt.width(label, 7);
				// Above the wheel: below it the hotbar may be.
				Draw.rounded(g, Math.round(cx - w / 2 - 6), Math.round(cy - RADIUS - 38), Math.round(w + 12), 14, 4, Colors.alpha(surface, 0xE6));
				Txt.center(g, label, cx, cy - RADIUS - 31, 7, text);
			}
		}
	}

	/** The slot an angle (0 = up, clockwise) points at. */
	static int slot(double angle, int n) {
		double turn = (angle + 2 * Math.PI + Math.PI / n) % (2 * Math.PI);
		return (int) (turn / (2 * Math.PI / n)) % n;
	}

	private void pick() {
		if (done) return;
		done = true;
		String action = chosen >= 0 && chosen < slots.size() ? slots.get(chosen) : null;
		Game.setScreen(null);
		if (action != null) Features.runWheel(action);
	}

	@Override
	public boolean keyReleased(InputConstants.Key key) {
		if (Features.isWheelKey(key)) {
			pick();
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		// Holding the key repeats it: not a choice (letting go or a click is).
		return Features.isWheelKey(key);
	}

	@Override
	public boolean pressed(double mx, double my, int button) {
		pick();
		return true;
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
