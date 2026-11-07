package pl.aerion.mod.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Lang;
import pl.aerion.mod.platform.Game;

/**
 * HUD profiles (hud.json "profiles", "profile"): separate layouts switched in the menu, with a
 * key, or on joining a server listed in a profile. The root "modules" and "style" stay the
 * layout in use, so everything else reads them as before. Same rules as the launcher's
 * `HudConfig::switch_profile` (crates/launcher-core/src/aerion_mod/hud.rs).
 */
public final class Profiles {
	private Profiles() {}

	private static final int MAX = 16, MAX_SERVERS = 32;
	/** The profile in use before a server switched it; restored on leaving that server. */
	private static String before;
	private static String lastServer;

	static String mainName() {
		return Lang.tr("aerion.profile.main");
	}

	private static JsonArray list(JsonObject root) {
		if (root.get("profiles") instanceof JsonArray a) return a;
		JsonArray a = new JsonArray();
		root.add("profiles", a);
		return a;
	}

	private static JsonObject find(JsonObject root, String name) {
		for (JsonElement e : list(root)) {
			if (e instanceof JsonObject p && name.equals(HudConfig.str(p, "name", ""))) return p;
		}
		return null;
	}

	public static List<String> names(HudConfig cfg) {
		List<String> out = new ArrayList<>();
		for (JsonElement e : list(cfg.root)) if (e instanceof JsonObject p) out.add(HudConfig.str(p, "name", "?"));
		if (out.isEmpty()) out.add(mainName());
		return out;
	}

	public static String active(HudConfig cfg) {
		return HudConfig.str(cfg.root, "profile", mainName());
	}

	/** Use `name`'s layout; the current one is kept under the active profile first. */
	public static void switchTo(HudConfig cfg, String name) {
		JsonObject root = cfg.root;
		String active = active(cfg);
		JsonObject current = find(root, active);
		if (current == null) {
			current = new JsonObject();
			current.addProperty("name", active);
			current.add("servers", new JsonArray());
			list(root).add(current);
		}
		current.add("style", root.get("style").deepCopy());
		current.add("modules", root.get("modules").deepCopy());
		JsonObject next = find(root, name);
		if (next == null) {
			if (list(root).size() >= MAX) return;
			next = new JsonObject();
			next.addProperty("name", name);
			next.add("style", root.get("style").deepCopy());
			next.add("modules", root.get("modules").deepCopy());
			next.add("servers", new JsonArray());
			list(root).add(next);
		}
		if (next.get("style") instanceof JsonObject s) root.add("style", s.deepCopy());
		if (next.get("modules") instanceof JsonObject m) root.add("modules", m.deepCopy());
		root.addProperty("profile", name);
		AerionClient.saveHud();
	}

	/** A new profile, starting as a copy of the layout in use. */
	public static void add(HudConfig cfg) {
		List<String> names = names(cfg);
		int n = names.size() + 1;
		String name;
		do name = Lang.tr("aerion.profile.new").replace("{}", String.valueOf(n++));
		while (names.contains(name));
		switchTo(cfg, name);
	}

	/** Rename a profile; false when the name is empty, too long or taken. */
	public static boolean rename(HudConfig cfg, String from, String to) {
		String name = to.strip();
		if (name.isEmpty() || name.length() > 32 || name.chars().anyMatch(Character::isISOControl)) return false;
		if (name.equals(from)) return true;
		if (names(cfg).contains(name)) return false;
		if (find(cfg.root, from) == null) switchTo(cfg, from);
		find(cfg.root, from).addProperty("name", name);
		if (from.equals(active(cfg))) cfg.root.addProperty("profile", name);
		if (from.equals(before)) before = name;
		AerionClient.saveHud();
		return true;
	}

	/** A profile's servers, comma separated, for editing. */
	public static String servers(HudConfig cfg, String name) {
		JsonObject p = find(cfg.root, name);
		List<String> out = new ArrayList<>();
		if (p != null && p.get("servers") instanceof JsonArray a) for (JsonElement e : a) out.add(e.getAsString());
		return String.join(", ", out);
	}

	/**
	 * Set a profile's servers from typed text: names or addresses separated by commas or spaces.
	 * Only what the launcher accepts is kept (letters, digits, `.-_:`, an optional `*.` in front).
	 */
	public static void setServers(HudConfig cfg, String name, String text) {
		if (find(cfg.root, name) == null) switchTo(cfg, name);
		JsonArray list = new JsonArray();
		for (String part : text.toLowerCase(Locale.ROOT).split("[,;\\s]+")) {
			String s = part.strip();
			if (s.startsWith("minecraft://")) s = s.substring("minecraft://".length());
			if (s.matches("(\\*\\.)?[a-z0-9._:-]{1,250}") && list.size() < MAX_SERVERS && !list.contains(new com.google.gson.JsonPrimitive(s))) list.add(s);
		}
		find(cfg.root, name).add("servers", list);
		lastServer = null;
		AerionClient.saveHud();
	}

	/** A profile's servers, one entry each. */
	public static List<String> serverList(HudConfig cfg, String name) {
		JsonObject p = find(cfg.root, name);
		List<String> out = new ArrayList<>();
		if (p != null && p.get("servers") instanceof JsonArray a) for (JsonElement e : a) out.add(e.getAsString());
		return out;
	}

	/** Add servers typed by the player (one or more, separated by commas) to a profile. */
	public static void addServers(HudConfig cfg, String name, String text) {
		List<String> list = serverList(cfg, name);
		list.add(text);
		setServers(cfg, name, String.join(",", list));
	}

	public static void removeServer(HudConfig cfg, String name, String server) {
		List<String> list = serverList(cfg, name);
		list.remove(server);
		setServers(cfg, name, String.join(",", list));
	}

	/** Delete a profile (never the last one); the active one gives way to the first other. */
	public static void delete(HudConfig cfg, String name) {
		List<String> names = names(cfg);
		if (names.size() < 2 || find(cfg.root, name) == null) return;
		if (name.equals(active(cfg))) {
			for (String other : names) {
				if (!other.equals(name)) {
					switchTo(cfg, other);
					break;
				}
			}
		}
		JsonArray list = list(cfg.root);
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) instanceof JsonObject o && name.equals(HudConfig.str(o, "name", ""))) {
				list.remove(i);
				break;
			}
		}
		if (name.equals(before)) before = null;
		AerionClient.saveHud();
	}

	/** The server joined right now (null in single player and menus). */
	static String server() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getCurrentServer() == null || mc.isLocalServer()) return null;
		return mc.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
	}

	/** `hypixel.net` matches `hypixel.net`, `mc.hypixel.net` and either with a port. */
	static boolean matches(String pattern, String address) {
		String p = pattern.trim().toLowerCase(Locale.ROOT);
		if (p.startsWith("*.")) p = p.substring(2);
		if (p.isEmpty()) return false;
		// Without a port in the pattern, the address's port doesn't matter.
		String a = address;
		if (!p.contains(":") && a.lastIndexOf(':') > a.lastIndexOf(']')) a = a.substring(0, a.lastIndexOf(':'));
		return a.equals(p) || a.endsWith("." + p);
	}

	public static boolean onServer(HudConfig cfg, String name, String address) {
		JsonObject p = find(cfg.root, name);
		if (p == null || !(p.get("servers") instanceof JsonArray a)) return false;
		for (JsonElement e : a) if (matches(e.getAsString(), address)) return true;
		return false;
	}

	/** Add or remove the server joined right now from a profile's list. */
	public static void toggleServer(HudConfig cfg, String name) {
		String address = server();
		if (address == null) return;
		if (find(cfg.root, name) == null) switchTo(cfg, name);
		JsonObject p = find(cfg.root, name);
		JsonArray servers = p.get("servers") instanceof JsonArray a ? a : new JsonArray();
		JsonArray next = new JsonArray();
		boolean had = false;
		for (JsonElement e : servers) {
			if (matches(e.getAsString(), address)) had = true;
			else next.add(e);
		}
		// Only what the launcher accepts as a server name (no IPv6 brackets).
		if (!had && next.size() < MAX_SERVERS && address.matches("[a-z0-9._:-]{1,253}")) next.add(address);
		p.add("servers", next);
		AerionClient.saveHud();
	}

	/** Next profile in the list (the profile key). */
	public static void cycle(HudConfig cfg) {
		List<String> names = names(cfg);
		if (names.size() < 2) {
			Game.overlayMessage(Component.literal(Lang.tr("aerion.profile.none")));
			return;
		}
		String next = names.get((names.indexOf(active(cfg)) + 1) % names.size());
		switchTo(cfg, next);
		before = null;
		Game.overlayMessage(Component.literal(Lang.tr("aerion.profile.now").replace("{}", next)));
	}

	/** Called once a second: joining a listed server switches to its profile, leaving switches back. */
	public static void tick(HudConfig cfg) {
		String address = server();
		if (address == null ? lastServer == null : address.equals(lastServer)) return;
		lastServer = address;
		if (address == null) {
			if (before != null && names(cfg).contains(before) && !before.equals(active(cfg))) switchTo(cfg, before);
			before = null;
			return;
		}
		for (String name : names(cfg)) {
			if (!onServer(cfg, name, address)) continue;
			if (!name.equals(active(cfg))) {
				before = active(cfg);
				switchTo(cfg, name);
				Game.overlayMessage(Component.literal(Lang.tr("aerion.profile.now").replace("{}", name)));
			}
			return;
		}
	}
}
