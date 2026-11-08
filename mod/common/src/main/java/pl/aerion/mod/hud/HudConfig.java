package pl.aerion.mod.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import pl.aerion.mod.AerionClient;

/**
 * hud.json, shared with the launcher and the in-game editor, which both edit it. The JSON tree is kept as read, so options this version doesn't know
 * survive a save. Positions are per-mille of the free space (0 = left/top edge, 1000 = right or
 * bottom edge), so a layout fits every window size; scale is a percentage.
 */
public final class HudConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public final JsonObject root;

	private HudConfig(JsonObject root) {
		this.root = root;
	}

	/** Used when the launcher hasn't written a file yet (same defaults as the launcher). */
	static final String DEFAULTS = """
			{"enabled":true,
			 "style":{"text":"theme","background":"theme","opacity":55,"border":"none","borderWidth":1,"radius":0,"padding":3,"shadow":true,"align":"left","font":"smooth","chromaSpeed":5,"chromaWave":false},
			 "modules":{
			  "fps":{"enabled":true,"x":8,"y":10,"scale":100},
			  "coords":{"enabled":true,"x":8,"y":110,"scale":100},
			  "potions":{"enabled":true,"x":992,"y":480,"scale":100},
			  "armor":{"enabled":true,"x":992,"y":900,"scale":100},
			  "toggles":{"enabled":true,"x":8,"y":960,"scale":100},
			  "keystrokes":{"enabled":false,"x":8,"y":760,"scale":100},
			  "cps":{"enabled":false,"x":8,"y":290,"scale":100},
			  "direction":{"enabled":false,"x":500,"y":10,"scale":100},
			  "ping":{"enabled":false,"x":8,"y":390,"scale":100},
			  "clock":{"enabled":false,"x":700,"y":10,"scale":100},
			  "minimap":{"enabled":false,"x":992,"y":10,"scale":100},
			  "memory":{"enabled":false,"x":8,"y":490,"scale":100},
			  "speed":{"enabled":false,"x":8,"y":590,"scale":100},
			  "death":{"enabled":false,"x":8,"y":680,"scale":100},
			  "totems":{"enabled":false,"x":860,"y":900,"scale":100},
			  "items":{"enabled":false,"x":860,"y":560,"scale":100},
			  "session":{"enabled":false,"x":500,"y":170,"scale":100},
			  "day":{"enabled":false,"x":500,"y":250,"scale":100},
			  "light":{"enabled":false,"x":500,"y":90,"scale":100},
			  "armorWarn":{"enabled":false,"x":500,"y":330,"scale":100},
			  "packs":{"enabled":false,"x":500,"y":410,"scale":100},
			  "bossbar":{"enabled":false,"x":500,"y":55,"scale":100},
			  "scoreboard":{"enabled":false,"x":1000,"y":500,"scale":100},
			  "chatbox":{"enabled":false,"x":0,"y":733,"scale":100},
			  "blockInfo":{"enabled":false,"x":300,"y":90,"scale":100},
			  "fpsGraph":{"enabled":false,"x":992,"y":700,"scale":100},
			  "playerList":{"enabled":false,"x":992,"y":300,"scale":100}},
			 "features":{"zoom":{"enabled":true,"factor":4,"smooth":true},"freelook":{"enabled":false,"hold":true},
			  "fullbright":{"enabled":false},"fovLock":{"enabled":false},"toggleSprint":{"enabled":false},"toggleSneak":{"enabled":false},
			  "hitboxes":{"enabled":false},"overlays":{"enabled":false},"sounds":{"enabled":true},"crosshair":{"enabled":false},"hurtCam":{"enabled":false},"lightLevels":{"enabled":false},
			  "blockOutline":{"enabled":false},"chat":{"enabled":false},"perspective":{"enabled":false},"fovKey":{"enabled":false},
			  "worldLook":{"enabled":false},"hitColor":{"enabled":false},"screens":{"enabled":false},"inventory":{"enabled":true},"menus":{"enabled":true},"waypoints":{"enabled":false},"badges":{"enabled":true}}}
			""";

	public static HudConfig defaults() {
		return new HudConfig(JsonParser.parseString(DEFAULTS).getAsJsonObject());
	}

	public static HudConfig read(Path file) throws Exception {
		JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		// Modules the launcher doesn't list yet keep their defaults.
		JsonObject defaults = defaults().root;
		for (String key : new String[] {"style", "modules", "features"}) {
			if (!(o.get(key) instanceof JsonObject)) o.add(key, defaults.get(key).deepCopy());
		}
		JsonObject mods = o.getAsJsonObject("modules");
		for (var e : defaults.getAsJsonObject("modules").entrySet()) {
			if (!mods.has(e.getKey())) mods.add(e.getKey(), e.getValue().deepCopy());
		}
		return new HudConfig(o);
	}

	public void write(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			AerionClient.LOG.warn("Aerion: can't save {}: {}", file, e.toString());
		}
	}

	public boolean enabled() {
		return bool(root, "enabled", true);
	}

	public JsonObject modules() {
		return root.getAsJsonObject("modules");
	}

	public JsonObject module(String id) {
		JsonElement e = modules().get(id);
		return e instanceof JsonObject o ? o : new JsonObject();
	}

	public JsonObject style() {
		return root.getAsJsonObject("style");
	}

	public JsonObject feature(String id) {
		JsonObject features = root.getAsJsonObject("features");
		if (features.get(id) instanceof JsonObject o) return o;
		JsonObject created = new JsonObject();
		features.add(id, created);
		return created;
	}

	// Lenient readers: a wrong type falls back to the default instead of failing the frame.
	public static boolean bool(JsonObject o, String key, boolean fallback) {
		JsonElement e = o == null ? null : o.get(key);
		try {
			return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
		} catch (Exception ex) {
			return fallback;
		}
	}

	public static int num(JsonObject o, String key, int fallback) {
		JsonElement e = o == null ? null : o.get(key);
		try {
			return e == null || e.isJsonNull() ? fallback : e.getAsInt();
		} catch (Exception ex) {
			return fallback;
		}
	}

	public static String str(JsonObject o, String key, String fallback) {
		JsonElement e = o == null ? null : o.get(key);
		try {
			return e == null || e.isJsonNull() ? fallback : e.getAsString();
		} catch (Exception ex) {
			return fallback;
		}
	}

	/** A module option (always strings, as the launcher writes them). */
	public static String opt(JsonObject module, String key, String fallback) {
		return module.get("options") instanceof JsonObject o ? str(o, key, fallback) : fallback;
	}
}
