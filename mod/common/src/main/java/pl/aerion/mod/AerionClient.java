package pl.aerion.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;
import pl.aerion.mod.features.Features;
import pl.aerion.mod.hud.HudConfig;
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
	private static volatile HudConfig hud = HudConfig.defaults();
	private static Path file;
	/** hud.json, shared with the launcher. */
	private static Path hudFile;
	private static long hudModified = Long.MIN_VALUE;
	/** Where the launcher reads the game's FPS for its performance page. */
	private static Path status;
	private static long modified = Long.MIN_VALUE;
	private static int ticks;

	@Override
	public void onInitializeClient() {
		String hp = System.getProperty("aerion.hud");
		if (hp != null && !hp.isBlank()) {
			hudFile = Path.of(hp);
			reloadHud();
		}
		if (!MixinGate.skipped().isEmpty()) LOG.info("Aerion: off at start, so their mixins are left out: {}", MixinGate.skipped());
		Badges.init();
		String st = System.getProperty("aerion.status");
		if (st != null && !st.isBlank()) status = Path.of(st);
		String p = System.getProperty("aerion.theme");
		if (p == null || p.isBlank()) {
			LOG.info("Aerion UI: no theme file given, keeping the default look");
		} else {
			file = Path.of(p);
			reload();
		}
	}

	/** The active theme, or null for the game's own look. */
	public static Theme theme() {
		return theme;
	}

	/** HUD layout and features (defaults until the launcher has written hud.json). */
	public static HudConfig hud() {
		return hud;
	}

	/** Store the in-game editor's changes; the file stamp is noted so it isn't read back. */
	public static void saveHud() {
		if (hudFile == null) return;
		hud.write(hudFile);
		try {
			hudModified = Files.getLastModifiedTime(hudFile).toMillis();
		} catch (Exception ignored) {
			// next poll reads it again, which is harmless
		}
	}

	private static void reloadHud() {
		try {
			long m = Files.getLastModifiedTime(hudFile).toMillis();
			if (m == hudModified) return;
			hudModified = m;
			hud = HudConfig.read(hudFile);
		} catch (java.nio.file.NoSuchFileException e) {
			hudModified = -2;
		} catch (Exception e) {
			if (hudModified != -1) LOG.warn("Aerion: can't read {}: {}", hudFile, e.toString());
			hudModified = -1;
		}
	}

	private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Log a problem once instead of every frame. */
	public static void warnOnce(String key, Throwable e) {
		if (WARNED.add(key)) LOG.warn("Aerion: {} failed: {}", key, e.toString());
	}

	/** The theme when its colours should replace the game's (buttons, backgrounds). */
	public static Theme colors() {
		Theme t = theme;
		return t != null && t.colors ? t : null;
	}

	private static boolean keysSorted;

	/** Called every client tick (20 per second, also in menus); checks the file once a second. */
	public static void tick() {
		if (!keysSorted && Minecraft.getInstance().options != null) {
			keysSorted = true;
			try {
				Features.sortKeysOnce(Minecraft.getInstance());
			} catch (RuntimeException e) {
				warnOnce("keys", e);
			}
		}
		try {
			Features.tick(Minecraft.getInstance());
		} catch (RuntimeException e) {
			warnOnce("features", e);
		}
		try {
			Host.tick(Minecraft.getInstance());
		} catch (RuntimeException e) {
			warnOnce("host", e);
		}
		if (++ticks % 20 != 0) return;
		if (file != null) reload();
		if (hudFile != null) reloadHud();
		Badges.reload();
		restartNotice();
		try {
			pl.aerion.mod.hud.Profiles.tick(hud);
		} catch (RuntimeException e) {
			warnOnce("profiles", e);
		}
		if (status != null) writeStatus();
	}

	private static final java.util.Set<String> TOLD = new java.util.HashSet<>();

	/** A feature whose mixins were left out at start was switched on: it works after a restart. */
	private static void restartNotice() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		for (String f : MixinGate.skipped()) {
			if (TOLD.contains(f) || !HudConfig.bool(hud.feature(f), "enabled", false)) continue;
			TOLD.add(f);
			pl.aerion.mod.platform.Game.chat(net.minecraft.network.chat.Component.literal(
					"[Aerion] " + (Lang.get("aerion.feature." + f) != null ? Lang.tr("aerion.feature." + f) : Lang.tr("aerion.nav." + f)) + ": " + Lang.tr("aerion.hint.restart")));
		}
	}

	private static boolean statusFailed;

	private static void writeStatus() {
		try {
			Files.createDirectories(status.getParent());
			Path tmp = status.resolveSibling(status.getFileName() + ".tmp");
			// FPS, used heap and whether a world is open: the launcher's after-game summary.
			Minecraft mc = Minecraft.getInstance();
			Runtime rt = Runtime.getRuntime();
			long heapMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
			String json = "{\"fps\":" + mc.getFps() + ",\"heapMb\":" + heapMb + ",\"heapMaxMb\":" + (rt.maxMemory() >> 20)
					+ ",\"inWorld\":" + (mc.level != null) + ",\"time\":" + System.currentTimeMillis() + "}";
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			Files.move(tmp, status, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			if (!statusFailed) LOG.warn("Aerion UI: can't write {}: {}", status, e.toString());
			statusFailed = true;
		}
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
