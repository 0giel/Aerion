package pl.aerion.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
	private static volatile Map<UUID, Component> players = Map.of();

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
		try {
			long m = Files.getLastModifiedTime(file).toMillis();
			if (m == modified) return;
			modified = m;
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<UUID, Component> next = new HashMap<>();
			for (var e : o.entrySet()) {
				MutableComponent icon = ICONS.get(e.getValue().getAsString());
				if (icon == null || e.getKey().length() != 32) continue;
				String k = e.getKey();
				UUID id = new UUID(Long.parseUnsignedLong(k.substring(0, 16), 16), Long.parseUnsignedLong(k.substring(16), 16));
				next.put(id, icon);
			}
			players = next;
		} catch (java.nio.file.NoSuchFileException e) {
			modified = -2;
		} catch (Exception e) {
			if (modified != -1) AerionClient.LOG.warn("Aerion: can't read {}: {}", file, e.toString());
			modified = -1;
		}
	}

	/** The name with the player's badge in front, or the name itself. */
	public static Component decorate(UUID player, Component name) {
		if (name == null || player == null) return name;
		Map<UUID, Component> map = players;
		if (map.isEmpty()) return name;
		Component icon = map.get(player);
		if (icon == null || !HudConfig.bool(AerionClient.hud().feature("badges"), "enabled", true)) return name;
		return Component.empty().append(icon).append(" ").append(name);
	}
}
