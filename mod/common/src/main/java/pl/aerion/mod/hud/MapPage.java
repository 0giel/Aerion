package pl.aerion.mod.hud;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.features.Features;
import pl.aerion.mod.features.Waypoints;
import pl.aerion.mod.platform.Game;
import pl.aerion.mod.platform.Gfx;
import pl.aerion.mod.platform.Textures;

/**
 * A big map of the loaded terrain: click to put a waypoint there, click a waypoint to rename,
 * recolour, hide or delete it; drag to move, the wheel to zoom. Blocks only, like the minimap.
 */
public final class MapPage implements Page {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("aerion", "map");
	private static final int[] SWATCHES = {0xFF55AAFF, 0xFF55DD77, 0xFFFFE25A, 0xFFFFAA33, 0xFFFF5555, 0xFFB98CFF, 0xFFFFFFFF, 0xFF000000};
	private static final float[] ZOOMS = {0.25f, 0.5f, 1, 2, 4, 8};
	private static final int MAX_PIXELS = 512, TOP = 22, MARGIN = 8, PANEL_H = 40;

	/** Kept between openings: the map reopens at the same zoom. */
	private static int zoomIndex = 3;
	private double cx, cz;
	private boolean centred;

	/** What the texture holds: its north-west block, blocks per pixel, size; and when. */
	private int texX, texZ, texStep, texW, texH;
	private boolean texReady;
	private long sampledAt;
	private double sampledCx = Double.NaN, sampledCz;
	private float sampledZoom;

	private double pressX, pressY, lastX, lastY;
	private boolean pressed, dragging;
	private Waypoints.Point selected;
	private boolean naming, namingFresh;
	private int mapX, mapY, mapW, mapH;
	private int panelY;

	public MapPage() {}

	/** Opens centred on a place instead of on the player. */
	public MapPage(double x, double z) {
		cx = x;
		cz = z;
		centred = true;
	}

	private float zoom() {
		return ZOOMS[zoomIndex];
	}

	private static int colour(int fallback, java.util.function.ToIntFunction<Theme> pick) {
		Theme t = AerionClient.theme();
		return t != null ? pick.applyAsInt(t) : fallback;
	}

	// --- coordinates ---------------------------------------------------------------------------
	private double blockX(double sx) {
		return cx + (sx - (mapX + mapW / 2.0)) / zoom();
	}

	private double blockZ(double sy) {
		return cz + (sy - (mapY + mapH / 2.0)) / zoom();
	}

	private int screenX(double bx) {
		return (int) Math.round(mapX + mapW / 2.0 + (bx - cx) * zoom());
	}

	private int screenY(double bz) {
		return (int) Math.round(mapY + mapH / 2.0 + (bz - cz) * zoom());
	}

	private void resample(Minecraft mc) {
		if (mc.level == null) return;
		float z = zoom();
		int step = z >= 1 ? 1 : Math.round(1 / z);
		int blocksW = (int) Math.ceil(mapW / z) + 2, blocksH = (int) Math.ceil(mapH / z) + 2;
		int nx = Math.min(MAX_PIXELS, blocksW / step + 1), nz = Math.min(MAX_PIXELS, blocksH / step + 1);
		int x0 = (int) Math.floor(cx - nx * step / 2.0), z0 = (int) Math.floor(cz - nz * step / 2.0);
		int[] px = MapColors.sample(mc.level, x0, z0, nx, nz, step);
		for (int i = 0; i < px.length; i++) if (px[i] == 0) px[i] = 0xFF161616;
		Textures.registerPixels(TEXTURE, nx, nz, px);
		texX = x0;
		texZ = z0;
		texStep = step;
		texW = nx;
		texH = nz;
		texReady = true;
		sampledAt = System.currentTimeMillis();
		sampledCx = cx;
		sampledCz = cz;
		sampledZoom = z;
	}

	// --- drawing -------------------------------------------------------------------------------
	@Override
	public void render(Gfx g, int mouseX, int mouseY) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		int w = g.width(), h = g.height();
		mapX = MARGIN;
		mapY = TOP;
		mapW = w - 2 * MARGIN;
		mapH = h - TOP - MARGIN - (selected != null ? PANEL_H + 4 : 0);
		panelY = mapY + mapH + 4;
		int text = colour(0xFFFFFFFF, t -> t.text), muted = colour(0xFFAAAAAA, t -> t.textMuted);
		int accent = colour(0xFF55AAFF, t -> t.accent), surface = colour(0xFF202020, t -> t.surface);
		if (player == null) {
			Txt.center(g, Lang.tr("aerion.map.noWorld"), w / 2f, h / 2f, 9, text);
			return;
		}
		if (!centred) {
			cx = player.getX();
			cz = player.getZ();
			centred = true;
		}
		boolean moved = Math.abs(cx - sampledCx) * zoom() > mapW / 4.0 || Math.abs(cz - sampledCz) * zoom() > mapH / 4.0;
		if (!dragging && (!texReady || zoom() != sampledZoom || moved || System.currentTimeMillis() - sampledAt > 2000)) resample(mc);

		Txt.draw(g, Lang.tr("aerion.map.title"), MARGIN, TOP / 2f, 10, text);
		String hint = Lang.tr("aerion.map.hint");
		Txt.draw(g, hint, w - MARGIN - Txt.width(hint, 7), TOP / 2f, 7, muted);

		Draw.rounded(g, mapX - 1, mapY - 1, mapW + 2, mapH + 2, 4, Colors.alpha(surface, 0xE0));
		g.scissor(mapX, mapY, mapX + mapW, mapY + mapH);
		if (texReady) {
			int sx = screenX(texX), sy = screenY(texZ);
			int dw = Math.round(texW * texStep * zoom()), dh = Math.round(texH * texStep * zoom());
			g.texture(TEXTURE, sx, sy, dw, dh, texW, texH, 0xFFFFFFFF);
		}
		String here = Waypoints.dimension(player);
		Waypoints.Point hover = pointAt(mouseX, mouseY, here);
		for (Waypoints.Point p : Waypoints.all()) {
			double[] at = Waypoints.position(p, here);
			if (at == null) continue;
			int px = screenX(at[0] + 0.5), py = screenY(at[2] + 0.5);
			int c = Waypoints.color(p);
			int r = p == selected || p == hover ? 4 : 3;
			g.fill(px - r - 1, py - r - 1, px + r + 1, py + r + 1, 0xFF000000);
			g.fill(px - r, py - r, px + r, py + r, p.enabled ? c : Colors.alpha(c, 0x70));
			String label = p == selected && naming ? p.name + (System.currentTimeMillis() / 500 % 2 == 0 ? "|" : "") : p.name;
			float lw = Txt.width(label, 7);
			Draw.rounded(g, px + 6, py - 5, Math.round(lw) + 6, 10, 2, 0xA0000000);
			Txt.draw(g, label, px + 9, py, 7, p.enabled ? 0xFFFFFFFF : 0xFFAAAAAA);
		}
		// The player: a dot with a tick towards where they look.
		int ppx = screenX(player.getX()), ppy = screenY(player.getZ());
		g.fill(ppx - 3, ppy - 3, ppx + 3, ppy + 3, 0xFF000000);
		g.fill(ppx - 2, ppy - 2, ppx + 2, ppy + 2, accent);
		double a = Math.toRadians(player.getYRot());
		for (int k = 3; k <= 8; k++) {
			int tx = ppx + (int) Math.round(-Math.sin(a) * k), ty = ppy + (int) Math.round(Math.cos(a) * k);
			g.fill(tx, ty, tx + 1, ty + 1, accent);
		}
		g.endScissor();
		Txt.center(g, "N", mapX + mapW / 2f, mapY + 6, 7, 0xFFFFFFFF);
		if (insideMap(mouseX, mouseY)) {
			String at = "X " + (int) Math.floor(blockX(mouseX)) + "   Z " + (int) Math.floor(blockZ(mouseY));
			Draw.rounded(g, mapX + 4, mapY + mapH - 14, Math.round(Txt.width(at, 7)) + 8, 11, 2, 0xA0000000);
			Txt.draw(g, at, mapX + 8, mapY + mapH - 8.5f, 7, 0xFFFFFFFF);
		}
		if (selected != null) panel(g, mouseX, mouseY, text, muted, accent, surface);
	}

	/** The chosen waypoint: its name (click to type), colours, hide/show and delete. */
	private void panel(Gfx g, int mouseX, int mouseY, int text, int muted, int accent, int surface) {
		Draw.rounded(g, mapX, panelY, mapW, PANEL_H, 4, Colors.alpha(surface, 0xF0));
		int x = mapX + 8, y = panelY + 6;
		String name = naming ? selected.name + (System.currentTimeMillis() / 500 % 2 == 0 ? "|" : "") : selected.name;
		Txt.draw(g, name, x, y + 5, 9, text);
		Txt.draw(g, selected.x + ", " + selected.y + ", " + selected.z, x, y + 19, 7, muted);
		int sx = mapX + mapW / 2 - SWATCHES.length * 7;
		for (int i = 0; i < SWATCHES.length; i++) {
			int bx = sx + i * 14;
			boolean on = Waypoints.color(selected) == SWATCHES[i];
			Draw.control(g, bx, y + 4, 11, 11, 2, on ? accent : 0xFF000000, SWATCHES[i]);
		}
		int right = mapX + mapW - 8;
		for (Button b : buttons()) {
			boolean over = mouseX >= b.x && mouseX < b.x + b.w && mouseY >= y + 3 && mouseY < y + 17;
			Draw.rounded(g, b.x, y + 3, b.w, 14, 3, b.danger ? (over ? 0xFFE5484D : 0xFFB33A3E) : over ? Colors.mix(surface, accent, 0.5f) : Colors.mix(surface, 0xFFFFFFFF, 0.12f));
			Txt.center(g, b.label, b.x + b.w / 2f, y + 10, 7, 0xFFFFFFFF);
		}
		Txt.draw(g, Lang.tr(naming ? "aerion.map.typing" : "aerion.map.rename"), right - Txt.width(Lang.tr(naming ? "aerion.map.typing" : "aerion.map.rename"), 6.5f), y + 26, 6.5f, muted);
	}

	private record Button(int x, int w, String label, boolean danger, Runnable action) {}

	private List<Button> buttons() {
		String del = Lang.tr("aerion.map.delete"), hide = Lang.tr(selected.enabled ? "aerion.map.hide" : "aerion.map.show");
		int right = mapX + mapW - 8;
		int dw = Math.round(Txt.width(del, 7)) + 14, hw = Math.round(Txt.width(hide, 7)) + 14;
		return List.of(
				new Button(right - dw, dw, del, true, () -> {
					Waypoints.remove(selected);
					selected = null;
					naming = false;
				}),
				new Button(right - dw - 4 - hw, hw, hide, false, () -> {
					selected.enabled = !selected.enabled;
					Waypoints.save();
				}));
	}

	private boolean insideMap(double x, double y) {
		return x >= mapX && y >= mapY && x < mapX + mapW && y < mapY + mapH;
	}

	private Waypoints.Point pointAt(double x, double y, String dimension) {
		if (!insideMap(x, y)) return null;
		Waypoints.Point best = null;
		double bestD = 7 * 7;
		for (Waypoints.Point p : Waypoints.all()) {
			double[] at = Waypoints.position(p, dimension);
			if (at == null) continue;
			double dx = screenX(at[0] + 0.5) - x, dy = screenY(at[2] + 0.5) - y, d = dx * dx + dy * dy;
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	// --- input ---------------------------------------------------------------------------------
	@Override
	public boolean pressed(double x, double y, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
		if (selected != null && y >= panelY && y < panelY + PANEL_H) {
			panelClick(x, y);
			return false;
		}
		if (!insideMap(x, y)) return false;
		pressed = true;
		dragging = false;
		pressX = lastX = x;
		pressY = lastY = y;
		return false;
	}

	private void panelClick(double x, double y) {
		int top = panelY + 6;
		for (Button b : buttons()) {
			if (x >= b.x && x < b.x + b.w && y >= top + 3 && y < top + 17) {
				b.action.run();
				return;
			}
		}
		int sx = mapX + mapW / 2 - SWATCHES.length * 7;
		for (int i = 0; i < SWATCHES.length; i++) {
			int bx = sx + i * 14;
			if (x >= bx && x < bx + 11 && y >= top + 4 && y < top + 15) {
				selected.color = String.format("#%06x", SWATCHES[i] & 0xFFFFFF);
				Waypoints.save();
				return;
			}
		}
		if (x < sx) {
			naming = true;
			namingFresh = true;
		}
	}

	@Override
	public void dragged(double x, double y, int screenW, int screenH) {
		if (!pressed) return;
		if (!dragging && Math.hypot(x - pressX, y - pressY) > 3) dragging = true;
		if (dragging) {
			cx -= (x - lastX) / zoom();
			cz -= (y - lastY) / zoom();
		}
		lastX = x;
		lastY = y;
	}

	@Override
	public void released() {
		if (!pressed) return;
		pressed = false;
		if (dragging) {
			dragging = false;
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || Minecraft.getInstance().level == null) return;
		finishNaming();
		Waypoints.Point hit = pointAt(pressX, pressY, Waypoints.dimension(player));
		if (hit != null) {
			selected = hit == selected ? null : hit;
			return;
		}
		// A new point where the map was clicked, standing on the ground when it is loaded.
		int bx = (int) Math.floor(blockX(pressX)), bz = (int) Math.floor(blockZ(pressY));
		int by = MapColors.surface(Minecraft.getInstance().level, bx, bz, player.getBlockY());
		selected = Waypoints.add(Lang.tr("aerion.waypoint.default"), bx, by, bz, Waypoints.dimension(player));
		naming = true;
		namingFresh = true;
		// Points are drawn in the world only with the feature on.
		HudConfig cfg = AerionClient.hud();
		if (!HudConfig.bool(cfg.feature("waypoints"), "enabled", false)) {
			cfg.feature("waypoints").addProperty("enabled", true);
			AerionClient.saveHud();
		}
	}

	private void finishNaming() {
		if (!naming || selected == null) return;
		naming = false;
		if (selected.name.isBlank()) selected.name = Lang.tr("aerion.waypoint.default");
		Waypoints.save();
	}

	@Override
	public void scrolled(double x, double y, double amount) {
		if (amount == 0) return;
		double bx = blockX(x), bz = blockZ(y);
		zoomIndex = Math.max(0, Math.min(ZOOMS.length - 1, zoomIndex + (amount > 0 ? 1 : -1)));
		// The block under the pointer stays under it.
		if (insideMap(x, y)) {
			cx = bx - (x - (mapX + mapW / 2.0)) / zoom();
			cz = bz - (y - (mapY + mapH / 2.0)) / zoom();
		}
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		int k = key.getValue();
		if (naming && selected != null) {
			if (k == InputConstants.KEY_BACKSPACE) {
				selected.name = namingFresh || selected.name.isEmpty() ? "" : selected.name.substring(0, selected.name.length() - 1);
				namingFresh = false;
			} else if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_ESCAPE) {
				finishNaming();
			}
			return true;
		}
		if (k == InputConstants.KEY_ESCAPE && selected != null) {
			selected = null;
			return true;
		}
		if (k == InputConstants.KEY_SPACE) {
			centred = false;
			return true;
		}
		if (selected != null && k == InputConstants.KEY_DELETE) {
			Waypoints.remove(selected);
			selected = null;
			return true;
		}
		var map = Features.key("map");
		if (map != null && !map.isUnbound() && map.saveString().equals(key.getName())) {
			if (Game.screen() != null) Game.screen().onClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(int codepoint) {
		if (!naming || selected == null) return false;
		if (namingFresh) selected.name = "";
		namingFresh = false;
		if (selected.name.length() < 32 && !Character.isISOControl(codepoint)) selected.name += new String(Character.toChars(codepoint));
		return true;
	}

	@Override
	public void close() {
		finishNaming();
		if (texReady) Textures.release(TEXTURE);
		texReady = false;
	}
}
