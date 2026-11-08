package pl.aerion.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import pl.aerion.mod.platform.Game;

/**
 * The world or server the player was last in, remembered in the game folder (aerion-last.json,
 * local only) so the title screen can offer to go straight back.
 */
public final class LastPlayed {
	private LastPlayed() {}

	/** `world`: id is the save's folder name; otherwise the server's address. */
	public record Entry(boolean world, String id, String name) {}

	private static Entry current;
	private static boolean loaded;

	private static File file() {
		return new File(Minecraft.getInstance().gameDirectory, "aerion-last.json");
	}

	/** The remembered entry, or null when there is none (or its world has since been deleted). */
	public static Entry get() {
		if (!loaded) {
			loaded = true;
			try {
				Path p = file().toPath();
				if (Files.isRegularFile(p)) {
					JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
					String id = o.get("id").getAsString();
					String name = o.has("name") ? o.get("name").getAsString() : id;
					current = new Entry(o.get("kind").getAsString().equals("world"), id, name);
				}
			} catch (Exception e) {
				AerionClient.LOG.debug("Aerion UI: no last-played file: {}", e.toString());
			}
		}
		Entry e = current;
		if (e != null && e.world && !new File(new File(Minecraft.getInstance().gameDirectory, "saves"), e.id).isDirectory()) return null;
		return e;
	}

	/** Called every client tick: notes the world or server the player is in now. */
	public static void tick(Minecraft mc) {
		if (mc.player == null) return;
		Entry now = null;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
			if (root != null) now = new Entry(true, root.toString(), server.getWorldData().getLevelName());
		} else {
			ServerData data = mc.getCurrentServer();
			if (data != null && data.type() == ServerData.Type.OTHER && data.ip != null && !data.ip.isBlank()) {
				now = new Entry(false, data.ip, data.name == null || data.name.isBlank() ? data.ip : data.name);
			}
		}
		if (now == null || now.equals(current)) return;
		get();
		current = now;
		JsonObject o = new JsonObject();
		o.addProperty("kind", now.world ? "world" : "server");
		o.addProperty("id", now.id);
		o.addProperty("name", now.name);
		try {
			Files.writeString(file().toPath(), o.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			AerionClient.LOG.debug("Aerion UI: can't save the last-played file: {}", e.toString());
		}
	}

	/** Opens the world or joins the server; `back` is where Esc returns to. */
	public static void play(Entry e, Screen back) {
		Minecraft mc = Minecraft.getInstance();
		if (e.world) {
			mc.createWorldOpenFlows().openWorld(e.id, () -> Game.setScreen(back));
		} else {
			ConnectScreen.startConnecting(back, mc, ServerAddress.parseString(e.id), new ServerData(e.name, e.id, ServerData.Type.OTHER), false, null);
		}
	}
}
