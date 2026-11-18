package pl.aerion.mod.mirror;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Cost;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.EditorScreen;
import pl.aerion.mod.platform.Game;
import pl.aerion.screens.client.Screens;

/**
 * Windows in the world live in Aerion Screens (its own mod, carried inside this one). Here Aerion
 * gives it its settings (hud.json, which the launcher writes too), its menus in the launcher's look
 * and its cost meter, so nothing changes for Aerion players.
 */
public final class ScreensBridge {
	private ScreensBridge() {}

	/** The least Aerion Screens API this mod knows how to use. */
	private static final int NEEDS = 1;

	public static void install() {
		if (!FabricLoader.getInstance().isModLoaded("aerion_screens")) return;
		try {
			if (Screens.API < NEEDS) {
				AerionClient.LOG.warn("Aerion: Aerion Screens is too old here (API {}), its own menus stay", Screens.API);
				return;
			}
			Screens.settings = new Settings();
			Screens.ui = new Screens.Ui() {
				@Override
				public void openPicker() {
					Game.setScreen(new EditorScreen(new MirrorsPage(), null));
				}

				@Override
				public void openTyping(long window, String title) {
					Game.setScreen(new EditorScreen(new TypePage(window, title), null));
				}
			};
			Screens.cost = new Screens.Cost() {
				@Override
				public void frame(long startedNanos) {
					Cost.part("mirrors").add(startedNanos);
				}

				@Override
				public void background(long nanos) {
					Cost.background(nanos);
				}
			};
		} catch (LinkageError e) {
			// An Aerion Screens this mod doesn't fit: it keeps working on its own.
			AerionClient.LOG.warn("Aerion: Aerion Screens couldn't be joined: {}", e.toString());
		}
	}

	/** The "mirrors" feature in hud.json: enabled, and its options. */
	private static final class Settings implements Screens.Settings {
		private static JsonObject feature() {
			HudConfig cfg = AerionClient.hud();
			return cfg == null ? null : cfg.feature("mirrors");
		}

		@Override
		public String opt(String key, String fallback) {
			JsonObject f = feature();
			return f == null ? fallback : HudConfig.opt(f, key, fallback);
		}

		@Override
		public void set(String key, String value) {
			JsonObject f = feature();
			if (f == null) return;
			JsonObject options = f.get("options") instanceof JsonObject o ? o : new JsonObject();
			f.add("options", options);
			options.addProperty(key, value);
			AerionClient.saveHud();
		}

		@Override
		public boolean enabled() {
			return HudConfig.bool(feature(), "enabled", false);
		}

		@Override
		public void setEnabled(boolean on) {
			JsonObject f = feature();
			if (f == null) return;
			f.addProperty("enabled", on);
			AerionClient.saveHud();
		}

		@Override
		public int accent() {
			return AerionClient.theme() != null ? AerionClient.theme().accent : 0xFF7AA2FF;
		}
	}
}
