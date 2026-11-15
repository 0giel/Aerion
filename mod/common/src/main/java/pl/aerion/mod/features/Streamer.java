package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.Set;
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

	/** The text with the player's own name swapped for the stand-in (styles, links and hovers stay). */
	public static Component hide(Component message) {
		if (message == null || !active()) return message;
		String own = own();
		if (own == null || own.isEmpty() || !message.getString().contains(own)) return message;
		String alias = alias();
		MutableComponent out = Component.empty();
		message.visit((style, text) -> {
			out.append(Component.literal(text.replace(own, alias)).setStyle(style));
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
