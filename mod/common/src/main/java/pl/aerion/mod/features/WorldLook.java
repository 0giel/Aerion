package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.hud.HudConfig;

/** Time of day and weather as only this player sees them (hud.json "features.worldLook"). */
public final class WorldLook {
	private WorldLook() {}

	/** Set while the HUD reads the world's real clock. */
	private static boolean real;

	private static JsonObject feature() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null || real) return null;
		JsonObject f = cfg.feature("worldLook");
		return HudConfig.bool(f, "enabled", false) ? f : null;
	}

	/** The clock as the sky should show it. Keeps the day, so moon phases stay right. */
	public static long ticks(long actual) {
		JsonObject f = feature();
		if (f == null || !"1".equals(HudConfig.opt(f, "fixTime", "1"))) return actual;
		int hour;
		try {
			hour = Math.max(0, Math.min(23, Integer.parseInt(HudConfig.opt(f, "hour", "12"))));
		} catch (NumberFormatException e) {
			hour = 12;
		}
		// Tick 0 of a Minecraft day is 06:00.
		long time = ((hour + 18) % 24) * 1000L;
		return actual - Math.floorMod(actual, 24000L) + time;
	}

	/** The real clock for the HUD, whatever the sky shows. */
	public static long actual(java.util.function.LongSupplier clock) {
		real = true;
		try {
			return clock.getAsLong();
		} finally {
			real = false;
		}
	}

	public static float rain(float actual) {
		JsonObject f = feature();
		if (f == null) return actual;
		return switch (HudConfig.opt(f, "weather", "clear")) {
			case "clear" -> 0f;
			case "rain", "thunder" -> 1f;
			default -> actual;
		};
	}

	public static float thunder(float actual) {
		JsonObject f = feature();
		if (f == null) return actual;
		return switch (HudConfig.opt(f, "weather", "clear")) {
			case "clear", "rain" -> 0f;
			case "thunder" -> 1f;
			default -> actual;
		};
	}
}
