package pl.aerion.screens.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Aerion Screens' texts, read from its own language files. The game reads mods' language files
 * only with Fabric API, which Aerion Screens doesn't need, so they are offered to the game here.
 */
public final class Lang {
	private Lang() {}

	private static Map<String, String> en, pl;

	private static Map<String, String> load(String code) {
		Map<String, String> out = new HashMap<>();
		try (InputStream in = Lang.class.getResourceAsStream("/assets/aerion_screens/lang/" + code + ".json")) {
			if (in != null) {
				JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var e : o.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
			}
		} catch (Exception e) {
			Screens.warnOnce("texts", e);
		}
		return out;
	}

	private static boolean ours(String key) {
		return key.startsWith("aerion_screens.") || key.startsWith("key.aerion.mirror") || key.equals("key.category.aerion_screens.main");
	}

	/** The text for `key` in the game's language (Polish or English), or null when it isn't ours. */
	public static String get(String key) {
		if (key == null || !ours(key)) return null;
		if (en == null) {
			en = load("en_us");
			pl = load("pl_pl");
		}
		Minecraft mc = Minecraft.getInstance();
		boolean polish = mc != null && mc.options != null && mc.options.languageCode != null && mc.options.languageCode.startsWith("pl");
		String t = polish ? pl.get(key) : null;
		return t != null ? t : en.get(key);
	}
}
