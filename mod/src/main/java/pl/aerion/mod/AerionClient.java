package pl.aerion.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the theme the launcher writes (`-Daerion.theme=<file>`) and re-reads it when it changes,
 * so switching themes in the launcher restyles a running game within a second. Without the file
 * the game keeps its normal look.
 */
public final class AerionClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("Aerion");
	private static volatile Theme theme;
	private static Path file;
	private static long modified = Long.MIN_VALUE;
	private static int ticks;

	@Override
	public void onInitializeClient() {
		String p = System.getProperty("aerion.theme");
		if (p == null || p.isBlank()) {
			LOG.info("Aerion UI: no theme file given, keeping the default look");
			return;
		}
		file = Path.of(p);
		reload();
	}

	/** The active theme, or null for the game's own look. */
	public static Theme theme() {
		return theme;
	}

	/** Called every client tick (20 per second, also in menus); checks the file once a second. */
	public static void tick() {
		if (file != null && ++ticks % 20 == 0) reload();
	}

	private static void reload() {
		try {
			long m = Files.getLastModifiedTime(file).toMillis();
			if (m == modified) return;
			modified = m;
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			theme = Theme.parse(o);
			LOG.info("Aerion UI: theme \"{}\"", theme.name);
		} catch (Exception e) {
			if (modified != -1) LOG.warn("Aerion UI: can't read {}: {}", file, e.toString());
			modified = -1;
		}
	}
}
