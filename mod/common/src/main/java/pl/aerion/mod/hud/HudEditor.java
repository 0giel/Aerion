package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 * The HUD layout screen, the first thing Right Shift opens: modules are dragged into place with
 * snapping, resized by a square on any corner, and the Mods button in the middle opens the menu.
 * The layout is saved to hud.json, which the launcher reads too.
 */
public final class HudEditor implements Page {
	private static final int HANDLE = 5, SNAP = 3, EDGE = 2;

	private String selected, dragging;
	/** Corner being dragged: 0 top left, 1 top right, 2 bottom left, 3 bottom right; -1 none. */
	private int corner = -1;
	private double grabX, grabY;
	private int startW, startH, fixedX, fixedY;
	private float startScale;
	private boolean changed, gestureSaved, help;
	private int guideX = -1, guideY = -1;
	private int screenW, screenH;
	private final Deque<JsonObject> undo = new ArrayDeque<>();
	private final List<Button> buttons = new ArrayList<>();
	/** The selected module's settings beside the layout, so every change shows on the HUD at once. */
	private AerionMenu panel;
	/** The pointer went down on the panel: its drags and release are the panel's. */
	private boolean panelGesture;

	/** Open (or move to) the settings of `id` in the side panel. */
	private void openPanel(String id) {
		if (panel == null) panel = AerionMenu.docked(id, this::closePanel);
		else panel.show(id);
		selected = id;
		sidePanel(id);
	}

	/** The panel goes to the side away from the module, right when it opens (not only after a drag). */
	private void sidePanel(String id) {
		int[] b = Hud.BOUNDS.get(id);
		if (panel != null && b != null) panel.dockLeft(b[0] + b[2] / 2 > screenW / 2);
	}

	private void closePanel() {
		if (panel != null) panel.close();
		panel = null;
	}

	private record Button(int x, int y, int w, int h, Runnable action) {
		boolean contains(double px, double py) {
			return px >= x && py >= y && px < x + w && py < y + h;
		}
	}

	private static boolean inside(int[] b, double x, double y) {
		return x >= b[0] && y >= b[1] && x < b[0] + b[2] && y < b[1] + b[3];
	}

	private static String at(double x, double y) {
		String hit = null;
		for (var e : Hud.BOUNDS.entrySet()) if (inside(e.getValue(), x, y)) hit = e.getKey();
		return hit;
	}

	private static int[] cornerPoint(int[] b, int c) {
		return new int[] {(c & 1) == 0 ? b[0] : b[0] + b[2], (c & 2) == 0 ? b[1] : b[1] + b[3]};
	}

	private static int cornerAt(int[] b, double x, double y) {
		for (int c = 0; c < 4; c++) {
			int[] p = cornerPoint(b, c);
			if (Math.abs(x - p[0]) <= HANDLE && Math.abs(y - p[1]) <= HANDLE) return c;
		}
		return -1;
	}

	// --- drawing -----------------------------------------------------------------------------
	/** The world stays as it looks in play, so the layout can be judged against it. */
	@Override
	public boolean veil() {
		return false;
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY) {
		buttons.clear();
		Theme t = AerionClient.theme();
		int accent = t != null ? t.accent : 0xFF7AA2FF;
		screenW = g.width();
		screenH = g.height();
		if (Minecraft.getInstance().level == null && t != null) {
			Draw.background(g, t, 0xFF);
			Particles.draw(g, t);
		}
		Hud.render(g, true);
		if (selected != null && !Hud.BOUNDS.containsKey(selected)) selected = null;
		boolean busy = dragging != null || corner >= 0;
		String hover = busy ? dragging : at(mouseX, mouseY);
		for (var e : Hud.BOUNDS.entrySet()) {
			int[] b = e.getValue();
			boolean lit = e.getKey().equals(selected) || e.getKey().equals(hover);
			Draw.outline(g, b[0], b[1], b[2], b[3], lit ? accent : 0x50FFFFFF);
			if (lit) {
				for (int c = 0; c < 4; c++) {
					int[] p = cornerPoint(b, c);
					boolean over = !busy && Math.abs(mouseX - p[0]) <= HANDLE && Math.abs(mouseY - p[1]) <= HANDLE;
					int px = Gfx.guiScale() * 2 + 1;
					Draw.handle(g, p[0], p[1], over ? px + 2 : px, over ? 0xFFFFFFFF : accent);
				}
			}
		}
		if (guideX >= 0) Draw.vline(g, guideX, 0, screenH, accent);
		if (guideY >= 0) Draw.hline(g, 0, guideY, screenW, accent);
		if (busy) {
			drawPanel(g, mouseX, mouseY);
			return;
		}

		if (panel == null) {
			center(g, t, accent, mouseX, mouseY);
			tools(g, t, accent, mouseX, mouseY);
		}
		if (selected != null) moduleBar(g, t, accent, Hud.BOUNDS.get(selected), mouseX, mouseY);
		drawPanel(g, mouseX, mouseY);
	}

	private void drawPanel(Gfx g, int mouseX, int mouseY) {
		if (panel == null) return;
		int[] b = selected != null ? Hud.BOUNDS.get(selected) : null;
		// Pick the side away from the module, but not mid-drag: the panel would jump around.
		if (b != null && dragging == null) panel.dockLeft(b[0] + b[2] / 2 > screenW / 2);
		panel.render(g, mouseX, mouseY);
	}

	/** The logo and the Mods button in the middle of the screen. */
	private void center(Gfx g, Theme t, int accent, int mouseX, int mouseY) {
		int logoH = 24, logoW = Logo.width(logoH);
		int cy = screenH / 2;
		Logo.draw(g, t, (screenW - logoW) / 2, cy - logoH - 6, logoH);
		String label = Lang.tr("aerion.editor.menu");
		int w = Math.max(84, Math.round(Txt.width(label, 8.5f)) + 30), h = 18;
		int bx = (screenW - w) / 2, by = cy - 2;
		boolean over = mouseX >= bx && mouseY >= by && mouseX < bx + w && mouseY < by + h;
		int surface = t != null ? t.surface : 0xFF1A1A1A;
		Draw.control(g, bx, by, w, h, 3, over ? accent : 0x40FFFFFF, Colors.alpha(over ? Colors.mix(surface, accent, 0.25f) : surface, 0xE6));
		Txt.center(g, label, screenW / 2f, by + h / 2f, 8.5f, t != null ? t.text : 0xFFFFFFFF);
		buttons.add(new Button(bx, by, w, h, () -> Page.go(new AerionMenu())));
	}

	private int profileChipWidth() {
		return Math.round(Txt.width(Profiles.active(AerionClient.hud()), 7f)) + 20;
	}

	/** The HUD profile in use, a small chip next to the tools; a click opens the profiles. */
	private void profiles(Gfx g, Theme t, int accent, int x, int y, int h, int w, int mx, int my) {
		int surface = t != null ? t.surface : 0xFF1A1A1A;
		boolean over = mx >= x && my >= y && mx < x + w && my < y + h;
		Draw.rounded(g, x, y, w, h, 3, Colors.alpha(over ? Colors.mix(surface, accent, 0.4f) : surface, 0xE0));
		Icons.draw(g, "packs", x + 2.5f, y + 2.5f, h - 5, 0xFFFFFFFF);
		Txt.draw(g, Profiles.active(AerionClient.hud()), x + h + 1, y + h / 2f, 7f, 0xFFFFFFFF);
		buttons.add(new Button(x, y, w, h, () -> Page.go(AerionMenu.profiles())));
	}

	/** Help, undo and reset under the Mods button, where they can't cover a module in a corner. */
	private void tools(Gfx g, Theme t, int accent, int mouseX, int mouseY) {
		int size = 14, count = undo.isEmpty() ? 2 : 3;
		int chipW = profileChipWidth();
		int y = screenH / 2 + 20, x = (screenW - count * size - (count - 1) * 3 - 3 - chipW) / 2;
		x = tool(g, t, accent, x, y, size, "help", help, mouseX, mouseY, () -> help = !help);
		if (!undo.isEmpty()) x = tool(g, t, accent, x, y, size, "undo", false, mouseX, mouseY, this::undo);
		x = tool(g, t, accent, x, y, size, "reset", false, mouseX, mouseY, this::resetLayout);
		profiles(g, t, accent, x, y, size, chipW, mouseX, mouseY);
		if (!help) return;
		int boxW = Math.min(228, screenW - 8);
		String[] rows = wrap(Lang.tr(Hud.BOUNDS.isEmpty() ? "aerion.editor.empty" : "aerion.editor.hint"), boxW - 8, 7.5f);
		int boxH = rows.length * 10 + 8, boxX = (screenW - boxW) / 2, boxY = y + size + 4;
		Draw.rounded(g, boxX, boxY, boxW, boxH, 3, 0xE6000000);
		for (int i = 0; i < rows.length; i++) Txt.draw(g, rows[i], boxX + 4, boxY + 9 + i * 10, 7.5f, 0xFFFFFFFF);
	}

	private int tool(Gfx g, Theme t, int accent, int x, int y, int size, String icon, boolean on, int mx, int my, Runnable action) {
		boolean over = mx >= x && my >= y && mx < x + size && my < y + size;
		int surface = t != null ? t.surface : 0xFF1A1A1A;
		Draw.rounded(g, x, y, size, size, 3, on ? accent : Colors.alpha(over ? Colors.mix(surface, accent, 0.4f) : surface, 0xE0));
		Icons.draw(g, icon, x + 2.5f, y + 2.5f, size - 5, on && t != null ? t.accentText : 0xFFFFFFFF);
		buttons.add(new Button(x, y, size, size, action));
		return x + size + 3;
	}

	/** Size, settings and hide next to the selected module. */
	private void moduleBar(Gfx g, Theme t, int accent, int[] b, int mx, int my) {
		String id = selected;
		JsonObject m = AerionClient.hud().module(id);
		String size = Math.round(Hud.scaleOf(m) * 100) + "%";
		int textW = Math.round(Txt.width(size, 7.5f)) + 8, barW = textW + 2 * 15, barH = 13;
		int x = Math.max(2, Math.min(screenW - barW - 2, b[0]));
		int y = b[1] - barH - 3 >= 0 ? b[1] - barH - 3 : b[1] + b[3] + 3;
		int surface = t != null ? t.surface : 0xFF1A1A1A;
		Draw.rounded(g, x, y, barW, barH, 3, Colors.alpha(surface, 0xF0));
		Txt.center(g, size, x + textW / 2f, y + barH / 2f, 7.5f, t != null ? t.text : 0xFFFFFFFF);
		int ink = t != null ? t.text : 0xFFFFFF;
		barIcon(g, x + textW, y, barH, "settings", ink, accent, mx, my, () -> openPanel(id));
		barIcon(g, x + textW + 15, y, barH, "hide", ink, accent, mx, my, () -> {
			remember();
			m.addProperty("enabled", false);
			selected = null;
			closePanel();
			changed = true;
		});
	}

	private void barIcon(Gfx g, int x, int y, int h, String icon, int ink, int accent, int mx, int my, Runnable action) {
		boolean over = mx >= x && my >= y && mx < x + 15 && my < y + h;
		Icons.draw(g, icon, x + 2.5f, y + 1.5f, 10, over ? accent : ink);
		buttons.add(new Button(x, y, 15, h, action));
	}

	private static String[] wrap(String s, int width, float size) {
		List<String> rows = new ArrayList<>();
		StringBuilder row = new StringBuilder();
		for (String word : s.split(" ")) {
			String next = row.isEmpty() ? word : row + " " + word;
			if (Txt.width(next, size) > width && !row.isEmpty()) {
				rows.add(row.toString());
				row = new StringBuilder(word);
			} else {
				row = new StringBuilder(next);
			}
		}
		if (!row.isEmpty()) rows.add(row.toString());
		return rows.toArray(new String[0]);
	}

	// --- undo --------------------------------------------------------------------------------
	/** Keep the layout as it was before a change, once per gesture. */
	private void remember() {
		if (gestureSaved) return;
		gestureSaved = true;
		undo.addFirst(AerionClient.hud().modules().deepCopy());
		while (undo.size() > 30) undo.removeLast();
	}

	private void undo() {
		if (undo.isEmpty()) return;
		AerionClient.hud().root.add("modules", undo.removeFirst());
		changed = true;
	}

	private void resetLayout() {
		gestureSaved = false;
		remember();
		gestureSaved = false;
		HudConfig cfg = AerionClient.hud();
		JsonObject defaults = HudConfig.defaults().modules();
		for (var e : defaults.entrySet()) {
			JsonObject d = e.getValue().getAsJsonObject(), m = cfg.module(e.getKey());
			m.add("x", d.get("x"));
			m.add("y", d.get("y"));
			m.add("scale", d.get("scale"));
		}
		changed = true;
	}

	// --- input -------------------------------------------------------------------------------
	@Override
	public boolean pressed(double x, double y, int button) {
		if (panel != null && panel.contains(x, y)) {
			panelGesture = true;
			return panel.pressed(x, y, button);
		}
		panelGesture = false;
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			String id = at(x, y);
			if (id != null) openPanel(id);
			return false;
		}
		if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
		gestureSaved = false;
		for (Button b : List.copyOf(buttons)) {
			if (b.contains(x, y)) {
				b.action.run();
				gestureSaved = false;
				return false;
			}
		}
		// Corners first: they stick out of the module, and win over a neighbour underneath.
		if (selected != null && Hud.BOUNDS.get(selected) != null && startCorner(selected, x, y)) return false;
		String hit = at(x, y);
		if (hit != null && startCorner(hit, x, y)) return false;
		selected = hit;
		dragging = hit;
		// Picking a module shows its settings; a click on empty space puts the panel away.
		if (hit != null) openPanel(hit);
		else closePanel();
		if (hit != null) {
			int[] b = Hud.BOUNDS.get(hit);
			grabX = x - b[0];
			grabY = y - b[1];
		}
		return false;
	}

	private boolean startCorner(String id, double x, double y) {
		int[] b = Hud.BOUNDS.get(id);
		int c = cornerAt(b, x, y);
		if (c < 0) return false;
		if (panel != null) panel.show(id);
		selected = id;
		sidePanel(id);
		dragging = id;
		corner = c;
		int[] fixed = cornerPoint(b, 3 - c);
		fixedX = fixed[0];
		fixedY = fixed[1];
		startW = b[2];
		startH = b[3];
		startScale = Hud.scaleOf(AerionClient.hud().module(id));
		return true;
	}

	/** Stores a position as the per-mille of free space hud.json uses. */
	private void place(String id, double left, double top, int w, int h) {
		JsonObject m = AerionClient.hud().module(id);
		m.addProperty("x", Math.round(Math.max(0, Math.min(1, left / Math.max(1, screenW - w))) * 1000));
		m.addProperty("y", Math.round(Math.max(0, Math.min(1, top / Math.max(1, screenH - h))) * 1000));
		changed = true;
	}

	@Override
	public void dragged(double x, double y, int w, int h) {
		if (panelGesture && panel != null) {
			panel.dragged(x, y, w, h);
			return;
		}
		if (dragging == null) return;
		int[] b = Hud.BOUNDS.get(dragging);
		if (b == null) return;
		remember();
		if (corner >= 0) resize(x, y);
		else move(x, y, b);
	}

	private void resize(double x, double y) {
		float ratio = Math.max((float) Math.abs(x - fixedX) / Math.max(1, startW), (float) Math.abs(y - fixedY) / Math.max(1, startH));
		int scale = Math.max(25, Math.min(400, Math.round(startScale * ratio * 100)));
		if (Math.abs(scale - 100) <= 3) scale = 100;
		JsonObject m = AerionClient.hud().module(dragging);
		m.addProperty("scale", scale);
		// The corner opposite the handle stays where it is.
		int w = Math.round(startW * (scale / 100f) / startScale), h = Math.round(startH * (scale / 100f) / startScale);
		double left = (corner & 1) == 0 ? fixedX - w : fixedX;
		double top = (corner & 2) == 0 ? fixedY - h : fixedY;
		place(dragging, left, top, w, h);
	}

	private void move(double x, double y, int[] b) {
		double left = Math.max(0, Math.min(screenW - b[2], x - grabX));
		double top = Math.max(0, Math.min(screenH - b[3], y - grabY));
		guideX = -1;
		guideY = -1;
		// Candidate lines: the screen's edges (first, so a module pushed against an edge stays
		// there), small margins, the middle, and the edges and middles of other modules.
		List<Integer> xs = new ArrayList<>(List.of(0, screenW, EDGE, screenW / 2, screenW - EDGE));
		List<Integer> ys = new ArrayList<>(List.of(0, screenH, EDGE, screenH / 2, screenH - EDGE));
		for (var e : Hud.BOUNDS.entrySet()) {
			if (e.getKey().equals(dragging)) continue;
			int[] o = e.getValue();
			xs.addAll(List.of(o[0], o[0] + o[2] / 2, o[0] + o[2]));
			ys.addAll(List.of(o[1], o[1] + o[3] / 2, o[1] + o[3]));
		}
		double[] sx = snap(left, b[2], xs);
		double[] sy = snap(top, b[3], ys);
		left = sx[0];
		top = sy[0];
		guideX = (int) sx[1];
		guideY = (int) sy[1];
		place(dragging, left, top, b[2], b[3]);
	}

	/** The position snapped to the nearest line by the box's start, middle or end, and that line. */
	private static double[] snap(double pos, int size, List<Integer> lines) {
		double best = SNAP + 1, result = pos, guide = -1;
		for (int line : lines) {
			for (double offset : new double[] {0, size / 2.0, size}) {
				double d = Math.abs(pos + offset - line);
				if (d < best) {
					best = d;
					result = line - offset;
					guide = line;
				}
			}
		}
		return new double[] {result, guide};
	}

	@Override
	public void released() {
		if (panel != null) panel.released();
		panelGesture = false;
		dragging = null;
		corner = -1;
		guideX = -1;
		guideY = -1;
		gestureSaved = false;
	}

	@Override
	public void scrolled(double x, double y, double amount) {
		if (panel != null && panel.contains(x, y)) panel.scrolled(x, y, amount);
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		// The panel first: typing in it, and Esc puts it away before leaving the layout.
		if (panel != null && panel.keyPressed(key)) return true;
		if (selected == null) return false;
		int[] b = Hud.BOUNDS.get(selected);
		if (b == null) return false;
		int k = key.getValue();
		int dx = k == InputConstants.KEY_LEFT ? -1 : k == InputConstants.KEY_RIGHT ? 1 : 0;
		int dy = k == InputConstants.KEY_UP ? -1 : k == InputConstants.KEY_DOWN ? 1 : 0;
		if (dx == 0 && dy == 0) return false;
		gestureSaved = false;
		remember();
		gestureSaved = false;
		place(selected, b[0] + dx, b[1] + dy, b[2], b[3]);
		return true;
	}

	@Override
	public boolean charTyped(int codepoint) {
		return panel != null && panel.charTyped(codepoint);
	}

	@Override
	public void close() {
		closePanel();
		if (changed) AerionClient.saveHud();
		changed = false;
	}
}
