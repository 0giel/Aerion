package pl.aerion.mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import pl.aerion.mod.platform.Game;

/**
 * Inventories, chests, furnaces, the crafting table and the rest in the launcher theme's colours.
 * The game draws every container panel from a grey picture (panel #C6C6C6, slots #8B8B8B, white
 * edge, dark edge), so the picture is multiplied by a tint that turns that grey into the theme's
 * surface colour: the slots stay a shade darker and the bevel stays, in the theme's hue. Titles
 * that the game writes in dark grey become the theme's text colour on dark themes. Only with
 * "theme colours" on; the game's own look comes back otherwise.
 */
public final class Containers {
	private Containers() {}

	private static final int PANEL_GREY = 0xC6;
	private static final int VANILLA_TITLE = 0xFF404040;

	private static Theme cachedFor;
	private static int cachedTint = -1;

	private static boolean inContainer() {
		return Game.screen() instanceof AbstractContainerScreen<?>;
	}

	/** True for the pictures of container panels (inventory, chest, furnace, creative tabs...). */
	private static boolean isPanel(Identifier id) {
		return id != null && id.getPath().startsWith("textures/gui/container/");
	}

	/** The colour to draw the picture `id` with, given the one the game asked for. */
	public static int tint(Identifier id, int color) {
		if (color != -1 || !isPanel(id)) return color;
		Theme t = AerionClient.colors();
		if (t == null || !inContainer()) return color;
		if (t != cachedFor) {
			cachedFor = t;
			// A touch lighter than the surface on dark themes, so the panel stands off the dark
			// background, with a hint of the accent like the buttons; light themes can't go
			// brighter than the game's grey, only take its hue.
			int panel = Colors.mix(t.light ? t.surface2 : Colors.mix(t.surface2, 0xFFFFFFFF, 0.05f), t.accent, 0.1f);
			int r = Math.min(255, ((panel >> 16) & 0xFF) * 255 / PANEL_GREY);
			int g = Math.min(255, ((panel >> 8) & 0xFF) * 255 / PANEL_GREY);
			int b = Math.min(255, (panel & 0xFF) * 255 / PANEL_GREY);
			cachedTint = 0xFF000000 | r << 16 | g << 8 | b;
		}
		return cachedTint;
	}

	/** The colour for text the game asks to be dark grey (container titles), on dark themes. */
	public static int text(int color) {
		if (color != VANILLA_TITLE) return color;
		Theme t = AerionClient.colors();
		if (t == null || t.light || !inContainer()) return color;
		return t.text;
	}
}
