package pl.aerion.screens.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Aerion Screens' own settings, in config/aerion-screens-client.json (a separate name from the
 * server's aerion-screens.properties: a world hosted from the game shares the folder). Windows are
 * on from the start: that is what the mod is for.
 */
final class ScreensConfig implements Screens.Settings {
	static final ScreensConfig INSTANCE = new ScreensConfig();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private JsonObject data;

	private ScreensConfig() {}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("aerion-screens-client.json");
	}

	private synchronized JsonObject data() {
		if (data == null) {
			data = new JsonObject();
			try {
				Path p = path();
				if (Files.exists(p)) {
					JsonElement e = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
					if (e.isJsonObject()) data = e.getAsJsonObject();
				}
			} catch (IOException | RuntimeException e) {
				Screens.LOG.warn("Aerion Screens: settings not read ({}), using the defaults", e.toString());
			}
			if (!data.has("options") || !data.get("options").isJsonObject()) data.add("options", new JsonObject());
		}
		return data;
	}

	private synchronized void save() {
		try {
			Path p = path();
			Files.createDirectories(p.getParent());
			Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(data()), StandardCharsets.UTF_8);
			Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Screens.LOG.warn("Aerion Screens: settings not saved: {}", e.toString());
		}
	}

	@Override
	public synchronized String opt(String key, String fallback) {
		JsonElement e = data().getAsJsonObject("options").get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}

	@Override
	public synchronized void set(String key, String value) {
		data().getAsJsonObject("options").addProperty(key, value);
		save();
	}

	@Override
	public synchronized boolean enabled() {
		JsonElement e = data().get("enabled");
		return e == null || !e.isJsonPrimitive() || e.getAsBoolean();
	}

	@Override
	public synchronized void setEnabled(boolean on) {
		data().addProperty("enabled", on);
		save();
	}
}
