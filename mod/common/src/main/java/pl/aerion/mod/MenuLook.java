package pl.aerion.mod;

import com.google.gson.JsonObject;
import pl.aerion.mod.hud.HudConfig;

/**
 * Which parts of the game's menus follow the launcher theme (hud.json "features.menus", the
 * "Game look" page): the themed buttons and sliders, the themed menu background, and the player
 * with the "Host a world" button on the title screen, each on unless switched off; and the
 * parts beyond the menus ({@link GameLook}), each off until switched on.
 */
public final class MenuLook {
	private MenuLook() {}

	private static boolean buttons = true, background = true, player = true;
	/** The theme beyond the menus (hotbar, tooltips, chat, tabList): off until switched on. */
	private static java.util.Set<String> parts = java.util.Set.of();
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
		java.util.Set<String> on = new java.util.HashSet<>();
		for (String p : PARTS) if ("1".equals(HudConfig.opt(f, p, "0"))) on.add(p);
		parts = on;
	}

	/** The parts of the game beyond the menus that can follow the theme. */
	public static final String[] PARTS = {"hotbar", "tooltips", "chat", "tabList"};

	/** Whether that part ({@link #PARTS}) follows the theme. */
	public static boolean part(String name) {
		read();
		return parts.contains(name);
	}

	/** Whether a title screen button ("realms", "icons") was hidden by the player. */
	public static boolean hidden(String name) {
		pl.aerion.mod.hud.HudConfig cfg = AerionClient.hud();
		if (cfg == null) return false;
		return "0".equals(HudConfig.opt(cfg.feature("menus"), "show." + name, "1"));
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
