package pl.aerion.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import pl.aerion.mod.hud.HudConfig;

/**
 * Badges next to players' names (above their heads and in the player list). The launcher writes
 * `-Daerion.badges=<file>`: {uuid without dashes: badge id}, fetched from the Aerion repository.
 * The icons are pixel glyphs of the game's own font, so they match its look.
 */
public final class Badges {
	/** Badge id → icon and colour; ids the file has but this version doesn't know are skipped. */
	private static final Map<String, MutableComponent> ICONS = Map.of(
			"creator", icon("⭐", 0xFFC83D),
			"bugHunter", icon("⛏", 0x5BE37A),
			"tester", icon("🧪", 0x55D6FF),
			"contributor", icon("❤", 0xFF6FA8));

	private static Path file;
	private static long modified = Long.MIN_VALUE;
	/** Every badge id of every player, the one others see first. */
	private static volatile Map<UUID, List<String>> players = Map.of();

	private Badges() {}

	private static MutableComponent icon(String glyph, int rgb) {
		return Component.literal(glyph).withColor(rgb);
	}

	static void init() {
		String p = System.getProperty("aerion.badges");
		if (p != null && !p.isBlank()) {
			file = Path.of(p);
			reload();
		}
	}

	/** Re-read the file when the launcher has replaced it (called once a second). */
	static void reload() {
		if (file == null) return;
		// `badges-all.json` beside it has every badge of a player; older launchers write only `badges.json`.
		Path all = file.resolveSibling("badges-all.json");
		Path src = Files.exists(all) ? all : file;
		try {
			long m = Files.getLastModifiedTime(src).toMillis();
			if (m == modified) return;
			modified = m;
			JsonObject o = JsonParser.parseString(Files.readString(src, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<UUID, List<String>> next = new HashMap<>();
			for (var e : o.entrySet()) {
				if (e.getKey().length() != 32) continue;
				List<String> ids = new ArrayList<>();
				if (e.getValue().isJsonArray()) for (var x : e.getValue().getAsJsonArray()) ids.add(x.getAsString());
				else ids.add(e.getValue().getAsString());
				ids.removeIf(id -> !ICONS.containsKey(id));
				if (ids.isEmpty()) continue;
				String k = e.getKey();
				next.put(new UUID(Long.parseUnsignedLong(k.substring(0, 16), 16), Long.parseUnsignedLong(k.substring(16), 16)), ids);
			}
			players = next;
		} catch (java.nio.file.NoSuchFileException e) {
			modified = -2;
		} catch (Exception e) {
			if (modified != -1) AerionClient.LOG.warn("Aerion: can't read {}: {}", src, e.toString());
			modified = -1;
		}
	}

	/** The badge ids the local player has (the ones this version knows), in the order the list gives. */
	public static List<String> mine() {
		UUID self = Minecraft.getInstance().getUser().getProfileId();
		return players.getOrDefault(self, List.of());
	}

	/** The name with the player's badge in front, or the name itself. */
	public static Component decorate(UUID player, Component name) {
		if (name == null || player == null) return name;
		Map<UUID, List<String>> map = players;
		if (map.isEmpty()) return name;
		List<String> owned = map.get(player);
		if (owned == null || !HudConfig.bool(AerionClient.hud().feature("badges"), "enabled", true)) return name;
		String shown = owned.get(0);
		// Your own badge can be another of yours, or none; everyone else sees the first.
		if (player.equals(Minecraft.getInstance().getUser().getProfileId())) {
			String pick = HudConfig.opt(AerionClient.hud().feature("badges"), "mine", "auto");
			if ("none".equals(pick)) return name;
			if (owned.contains(pick)) shown = pick;
		}
		return Component.empty().append(ICONS.get(shown)).append(" ").append(name);
	}
}
