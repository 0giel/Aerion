package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.platform.Gfx;

/**
 * The HUD layout editor: modules are dragged into place, resized with the mouse wheel and opened
 * in the menu with a right click. The layout is saved to hud.json, which the launcher reads too.
 */
public final class HudEditor implements Page {
	private String dragging;
	private double grabX, grabY;
	private boolean changed;
	private int[] done = new int[4];
	private int[] menu = new int[4];

	private static boolean inside(int[] b, double x, double y) {
		return x >= b[0] && y >= b[1] && x < b[0] + b[2] && y < b[1] + b[3];
	}

	private static String at(double x, double y) {
		String hit = null;
		for (var e : Hud.BOUNDS.entrySet()) if (inside(e.getValue(), x, y)) hit = e.getKey();
		return hit;
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY) {
		Theme t = AerionClient.theme();
		int accent = t != null ? t.accent : 0xFF7AA2FF;
		if (Minecraft.getInstance().level == null && t != null) Draw.background(g, t, 0xFF);
		g.fill(0, 0, g.width(), g.height(), 0x66000000);
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
		float size = 8f;
		String hint = Lang.tr(Hud.BOUNDS.isEmpty() ? "aerion.editor.empty" : "aerion.editor.hint");
		float hintW = Txt.width(hint, size);
		int cx = g.width() / 2, hintY = g.height() / 2 + 14;
		Draw.rounded(g, Math.round(cx - hintW / 2) - 5, hintY - 2, Math.round(hintW) + 10, 13, 2, 0xCC000000);
		Txt.center(g, hint, cx, hintY + 4.5f, size, 0xFFFFFFFF);
		String doneLabel = Lang.tr("aerion.editor.done"), menuLabel = Lang.tr("aerion.editor.menu");
		int w1 = Math.round(Txt.width(doneLabel, size)) + 20, w2 = Math.round(Txt.width(menuLabel, size)) + 20, h = 18;
		int y = g.height() / 2 + 30, x = cx - (w1 + w2 + 4) / 2;
		menu = new int[] {x, y, w2, h};
		done = new int[] {x + w2 + 4, y, w1, h};
		button(g, t, accent, menu, menuLabel, mouseX, mouseY);
		button(g, t, accent, done, doneLabel, mouseX, mouseY);
	}

	private static void button(Gfx g, Theme t, int accent, int[] b, String label, int mouseX, int mouseY) {
		boolean over = inside(b, mouseX, mouseY);
		int surface = t != null ? t.surface2 : 0xFF2A2A2A;
		Draw.control(g, b[0], b[1], b[2], b[3], t != null ? t.radius : 2, over ? accent : Colors.mix(surface, accent, 0.4f),
				Colors.forWhiteText(Colors.mix(surface, accent, over ? 0.35f : 0.12f)));
		Txt.center(g, label, b[0] + b[2] / 2f, b[1] + b[3] / 2f, 8f, 0xFFFFFFFF);
	}

	@Override
	public boolean pressed(double x, double y, int button) {
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			String id = at(x, y);
			if (id != null) Page.go(new AerionMenu(id));
			return false;
		}
		if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
		if (inside(done, x, y)) return true;
		if (inside(menu, x, y)) {
			Page.go(new AerionMenu());
			return false;
		}
		dragging = at(x, y);
		if (dragging != null) {
			int[] b = Hud.BOUNDS.get(dragging);
			grabX = x - b[0];
			grabY = y - b[1];
		}
		return false;
	}

	@Override
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

	@Override
	public void released() {
		dragging = null;
	}

	@Override
	public void scrolled(double x, double y, double amount) {
		String id = at(x, y);
		if (id == null) return;
		JsonObject m = AerionClient.hud().module(id);
		int scale = Math.round(Hud.scaleOf(m) * 100) + (amount > 0 ? 10 : -10);
		m.add("scale", new JsonPrimitive(Math.max(25, Math.min(400, scale))));
		changed = true;
	}

	@Override
	public void close() {
		if (changed) AerionClient.saveHud();
		changed = false;
	}
}
