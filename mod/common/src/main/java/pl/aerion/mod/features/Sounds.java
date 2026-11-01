package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.hud.HudConfig;

/**
 * The sound editor (hud.json "features.sounds"): a volume per sound, as a percentage stored under
 * the sound's id; sounds without an entry play normally. Recent sounds are listed so a sound can
 * be found right after hearing it.
 */
public final class Sounds {
	private Sounds() {}

	private static final int RECENT = 40;
	private static final Deque<String> HEARD = new ArrayDeque<>();

	/** Volume multiplier for a sound id. */
	public static float volume(String id) {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return 1f;
		JsonObject f = cfg.feature("sounds");
		if (!HudConfig.bool(f, "enabled", true)) return 1f;
		String v = HudConfig.opt(f, id, null);
		if (v == null) return 1f;
		try {
			return Math.max(0, Math.min(100, Integer.parseInt(v))) / 100f;
		} catch (NumberFormatException e) {
			return 1f;
		}
	}

	public static synchronized void heard(String id) {
		HEARD.remove(id);
		HEARD.addFirst(id);
		while (HEARD.size() > RECENT) HEARD.removeLast();
	}

	public static synchronized List<String> recent() {
		return new ArrayList<>(HEARD);
	}
}
