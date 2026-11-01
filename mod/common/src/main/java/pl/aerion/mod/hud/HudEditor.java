package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Theme;
import pl.aerion.mod.platform.Gfx;

/**
 * The in-game HUD editor: modules are dragged into place and resized with the mouse wheel; the
 * layout is saved to hud.json, which the launcher reads too. Everything else (colours, options)
 * is edited in the launcher.
 */
public final class HudEditor {
	private String dragging;
	private double grabX, grabY;
	private boolean changed;
	private int[] done = new int[4];

	private static boolean inside(int[] b, double x, double y) {
		return x >= b[0] && y >= b[1] && x < b[0] + b[2] && y < b[1] + b[3];
	}

	private static String at(double x, double y) {
		String hit = null;
		for (var e : Hud.BOUNDS.entrySet()) if (inside(e.getValue(), x, y)) hit = e.getKey();
		return hit;
	}

	public void render(Gfx g, int mouseX, int mouseY) {
		Theme t = AerionClient.theme();
		int accent = t != null ? t.accent : 0xFF7AA2FF;
		g.fill(0, 0, g.width(), g.height(), 0x88000000);
		// Faint guides through the middle help to centre modules.
		g.fill(g.width() / 2, 0, g.width() / 2 + 1, g.height(), 0x22FFFFFF);
		g.fill(0, g.height() / 2, g.width(), g.height() / 2 + 1, 0x22FFFFFF);
		Hud.render(g, true);
		String hover = dragging != null ? dragging : at(mouseX, mouseY);
		for (var e : Hud.BOUNDS.entrySet()) {
			int[] b = e.getValue();
			int c = e.getKey().equals(hover) ? accent : 0x88FFFFFF;
			g.fill(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1], c);
			g.fill(b[0] - 1, b[1] + b[3], b[0] + b[2] + 1, b[1] + b[3] + 1, c);
			g.fill(b[0] - 1, b[1], b[0], b[1] + b[3], c);
			g.fill(b[0] + b[2], b[1], b[0] + b[2] + 1, b[1] + b[3], c);
		}
		String hint = Lang.tr(Hud.BOUNDS.isEmpty() ? "aerion.editor.empty" : "aerion.editor.hint");
		int hintX = (g.width() - Gfx.textWidth(hint)) / 2, hintY = g.height() / 2 + 14;
		g.fill(hintX - 4, hintY - 3, hintX + Gfx.textWidth(hint) + 4, hintY + 11, 0xCC000000);
		g.text(hint, hintX, hintY, 0xFFFFFFFF, true);
		String label = Lang.tr("aerion.editor.done");
		int w = Gfx.textWidth(label) + 24, h = 18;
		done = new int[] {(g.width() - w) / 2, g.height() / 2 + 30, w, h};
		boolean over = inside(done, mouseX, mouseY);
		int surface = t != null ? t.surface2 : 0xFF2A2A2A;
		Draw.control(g, done[0], done[1], w, h, t != null ? t.radius : 2, over ? accent : Colors.mix(surface, accent, 0.4f),
				Colors.forWhiteText(Colors.mix(surface, accent, over ? 0.35f : 0.12f)));
		g.text(label, done[0] + 12, done[1] + 5, 0xFFFFFFFF, true);
	}

	/** True when the click closes the editor (the Done button). */
	public boolean pressed(double x, double y, int button) {
		if (inside(done, x, y)) return true;
		if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
		dragging = at(x, y);
		if (dragging != null) {
			int[] b = Hud.BOUNDS.get(dragging);
			grabX = x - b[0];
			grabY = y - b[1];
		}
		return false;
	}

	public void dragged(double x, double y, int screenW, int screenH) {
		if (dragging == null) return;
		int[] b = Hud.BOUNDS.get(dragging);
		if (b == null) return;
		JsonObject m = AerionClient.hud().module(dragging);
		double left = Math.max(0, Math.min(screenW - b[2], x - grabX));
		double top = Math.max(0, Math.min(screenH - b[3], y - grabY));
		m.add("x", new JsonPrimitive(Math.round(left / Math.max(1, screenW - b[2]) * 1000)));
		m.add("y", new JsonPrimitive(Math.round(top / Math.max(1, screenH - b[3]) * 1000)));
		changed = true;
	}

	public void released() {
		dragging = null;
	}

	public void scrolled(double x, double y, double amount) {
		String id = at(x, y);
		if (id == null) return;
		JsonObject m = AerionClient.hud().module(id);
		int scale = Math.round(Hud.scaleOf(m) * 100) + (amount > 0 ? 10 : -10);
		m.add("scale", new JsonPrimitive(Math.max(25, Math.min(400, scale))));
		changed = true;
	}

	public void close() {
		if (changed) AerionClient.saveHud();
		changed = false;
	}
}
