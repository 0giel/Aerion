package pl.aerion.screens.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What another mod may plug into Aerion Screens. On its own, Aerion Screens keeps its settings in
 * config/aerion-screens-client.json and shows the game's own menus. The Aerion mod (the Aerion
 * launcher's mod, which carries Aerion Screens inside it) puts in its settings (written by the
 * launcher too), its menus in the launcher's look and its cost meter.
 */
public final class Screens {
	private Screens() {}

	/** Raised when what other mods may call or replace here changes. */
	public static final int API = 1;

	public static final Logger LOG = LoggerFactory.getLogger("aerion-screens");

	/** The player's settings for windows. */
	public interface Settings {
		/** A setting by name ("opacity", "quality", "shareSound"…), as text. */
		String opt(String key, String fallback);

		void set(String key, String value);

		/** Whether windows in the world are on. */
		boolean enabled();

		void setEnabled(boolean on);

		/** The colour of marks over panels (the LIVE label). */
		default int accent() {
			return 0xFF7AA2FF;
		}
	}

	/** The menus: the window picker and typing into a window. */
	public interface Ui {
		void openPicker();

		void openTyping(long window, String title);
	}

	/** Where the time windows take goes (a cost meter); null when nobody measures. */
	public interface Cost {
		/** Time spent on the render thread for one frame, from `startedNanos` until now. */
		void frame(long startedNanos);

		/** Time spent on the capture thread. */
		void background(long nanos);
	}

	public static volatile Settings settings = ScreensConfig.INSTANCE;
	public static volatile Ui ui = VanillaUi.INSTANCE;
	public static volatile Cost cost;

	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	/** Logs a problem once per kind, so a failing frame doesn't fill the log. */
	public static void warnOnce(String kind, Throwable e) {
		if (WARNED.add(kind)) LOG.warn("Aerion Screens: {} failed", kind, e);
	}
}
