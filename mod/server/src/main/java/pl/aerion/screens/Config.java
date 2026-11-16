package pl.aerion.screens;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** `config/aerion-screens.properties`: written with the defaults on first start, read once. */
final class Config {
	boolean enabled = true;
	/** How far (blocks) a shared window is seen. */
	int range = 48;
	/** Pictures per second passed on per window. */
	int fps = 5;
	/** What one player may send, in kilobits per second (all their windows together). */
	int kbps = 6000;
	int maxPanels = 3;
	/** The largest picture passed on, in KiB. */
	int maxFrameKb = 500;

	static Config load() {
		Config c = new Config();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("aerion-screens.properties");
		Properties p = new Properties();
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file)) {
					p.load(r);
				}
			}
			c.enabled = Boolean.parseBoolean(p.getProperty("enabled", String.valueOf(c.enabled)));
			c.range = number(p, "range", c.range, 8, 256);
			c.fps = number(p, "fps", c.fps, 1, 15);
			c.kbps = number(p, "kbps", c.kbps, 100, 20_000);
			c.maxPanels = number(p, "maxPanels", c.maxPanels, 1, 8);
			c.maxFrameKb = number(p, "maxFrameKb", c.maxFrameKb, 16, 900);
			p.setProperty("enabled", String.valueOf(c.enabled));
			p.setProperty("range", String.valueOf(c.range));
			p.setProperty("fps", String.valueOf(c.fps));
			p.setProperty("kbps", String.valueOf(c.kbps));
			p.setProperty("maxPanels", String.valueOf(c.maxPanels));
			p.setProperty("maxFrameKb", String.valueOf(c.maxFrameKb));
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				p.store(w, "Aerion shared windows: players with the Aerion mod see the windows others share near them");
			}
		} catch (IOException | RuntimeException e) {
			AerionScreens.LOG.warn("Aerion screens: settings not read ({}), using the defaults", e.toString());
		}
		return c;
	}

	private static int number(Properties p, String key, int fallback, int min, int max) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
