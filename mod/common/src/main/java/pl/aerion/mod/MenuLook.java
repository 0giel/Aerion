package pl.aerion.mod;

import com.google.gson.JsonObject;
import pl.aerion.mod.hud.HudConfig;

/**
 * Which parts of the game's menus follow the launcher theme (hud.json "features.menus", the
 * "Game look" page): the themed buttons and sliders, the themed menu background, and the player
 * with the "Host a world" button on the title screen. Each is on unless switched off.
 */
public final class MenuLook {
	private MenuLook() {}

	private static boolean buttons = true, background = true, player = true;
	private static long at;

	private static void read() {
		long now = System.currentTimeMillis();
		if (now - at < 250) return;
		at = now;
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return;
		JsonObject f = cfg.feature("menus");
		buttons = !"0".equals(HudConfig.opt(f, "buttons", "1"));
		background = !"0".equals(HudConfig.opt(f, "background", "1"));
		player = !"0".equals(HudConfig.opt(f, "player", "1"));
	}

	public static boolean buttons() {
		read();
		return buttons;
	}

	public static boolean background() {
		read();
		return background;
	}

	public static boolean player() {
		read();
		return player;
	}
}
