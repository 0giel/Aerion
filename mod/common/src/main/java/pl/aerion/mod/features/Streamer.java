package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Lang;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.Game;

/**
 * Streamer mode: while it is on, the player's own name shows as "Player" in the chat, the player
 * list and the menus, and what tells where they are or play is hidden: the coordinates, death
 * point and server address modules, the coordinates under the minimap and on the map, and F3's
 * positions (the game's own "reduced debug info", set back when the mode goes off).
 */
public final class Streamer {
	private Streamer() {}

	/** HUD modules that are not drawn in streamer mode. */
	private static final Set<String> HIDDEN = Set.of("coords", "death", "server");

	public static boolean active() {
		HudConfig cfg = AerionClient.hud();
		return cfg != null && HudConfig.bool(cfg.feature("streamer"), "enabled", false);
	}

	/** Whether a HUD module stays out of sight now. */
	public static boolean hides(String module) {
		return HIDDEN.contains(module) && active();
	}

	private static String own() {
		return Minecraft.getInstance().getUser().getName();
	}

	private static String alias() {
		return Lang.tr("aerion.streamer.alias");
	}

	/** The name as shown: the stand-in when it is the player's own and the mode is on. */
	public static String name(String name) {
		return active() && name != null && name.equals(own()) ? alias() : name;
	}

	/** Letters a Minecraft name is made of: the own name only counts when not part of a longer word. */
	private static final String EDGE_BEFORE = "(?<![A-Za-z0-9_])", EDGE_AFTER = "(?![A-Za-z0-9_])";

	private static String cachedOwn, cachedServer;
	private static Pattern namePattern, serverPattern;

	/** The server's address (with and without the port) as typed, null in single player. */
	private static Pattern server() {
		var s = Minecraft.getInstance().getCurrentServer();
		String ip = s == null || Minecraft.getInstance().isLocalServer() ? null : s.ip;
		if (ip == null || ip.isBlank()) return null;
		if (!ip.equals(cachedServer)) {
			cachedServer = ip;
			String host = ip.contains(":") && !ip.startsWith("[") ? ip.substring(0, ip.lastIndexOf(':')) : ip;
			serverPattern = Pattern.compile("(?i)" + Pattern.quote(ip) + (host.equals(ip) ? "" : "|" + Pattern.quote(host)));
		}
		return serverPattern;
	}

	private static Pattern name() {
		String own = own();
		if (own == null || own.isEmpty()) return null;
		if (!own.equals(cachedOwn)) {
			cachedOwn = own;
			namePattern = Pattern.compile(EDGE_BEFORE + Pattern.quote(own) + EDGE_AFTER);
		}
		return namePattern;
	}

	/** The plain text with the own name and the server address swapped (for a test and for strings). */
	static String hide(String text, Pattern name, Pattern server, String alias, String hiddenServer) {
		if (name != null) text = name.matcher(text).replaceAll(Matcher.quoteReplacement(alias));
		if (server != null) text = server.matcher(text).replaceAll(Matcher.quoteReplacement(hiddenServer));
		return text;
	}

	/**
	 * The text with the own name swapped for the stand-in and the server's address hidden; styles,
	 * links and hovers stay.
	 */
	public static Component hide(Component message) {
		if (message == null || !active()) return message;
		Pattern name = name(), server = server();
		String plain = message.getString();
		boolean hasName = name != null && name.matcher(plain).find();
		boolean hasServer = server != null && server.matcher(plain).find();
		if (!hasName && !hasServer) return message;
		String alias = alias(), hiddenServer = Lang.tr("aerion.streamer.server");
		MutableComponent out = Component.empty();
		message.visit((style, text) -> {
			out.append(Component.literal(hide(text, name, server, alias, hiddenServer)).setStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	/** The key: switches the mode and says so above the hotbar. */
	public static void toggle() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return;
		JsonObject f = cfg.feature("streamer");
		boolean on = !HudConfig.bool(f, "enabled", false);
		f.addProperty("enabled", on);
		AerionClient.saveHud();
		Game.overlayMessage(Component.literal(Lang.tr(on ? "aerion.streamer.on" : "aerion.streamer.off")));
	}

	private static Boolean was;

	/**
	 * Every tick: keeps F3 in step however the mode was switched (key, menu, launcher). The
	 * first tick only notes the state, so a game started with the mode on keeps its F3 as saved.
	 */
	public static void sync() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return;
		boolean on = active();
		if (was != null && was != on) {
			reducedDebug(cfg.feature("streamer"), on);
			AerionClient.saveHud();
		}
		was = on;
	}

	/** Turns the game's reduced debug info on with the mode and back to what it was after. */
	private static void reducedDebug(JsonObject f, boolean on) {
		var option = Minecraft.getInstance().options.reducedDebugInfo();
		JsonObject o = f.has("options") && f.get("options").isJsonObject() ? f.getAsJsonObject("options") : new JsonObject();
		if (on) {
			if (!o.has("keptDebug")) o.addProperty("keptDebug", option.get() ? "1" : "0");
			option.set(true);
		} else {
			option.set("1".equals(HudConfig.opt(f, "keptDebug", "0")));
			o.remove("keptDebug");
		}
		f.add("options", o);
		Minecraft.getInstance().options.save();
	}
}
