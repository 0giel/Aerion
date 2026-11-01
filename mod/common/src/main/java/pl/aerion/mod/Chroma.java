package pl.aerion.mod;

/** Colours as hud.json stores them, including `chroma`: a hue that keeps moving. */
public final class Chroma {
	private Chroma() {}

	public static final String VALUE = "chroma";
	public static final int DEFAULT_SPEED = 5;

	/** The moving colour; `offset` is a part of the hue circle, speed is 1 (slow) to 10 (fast). */
	public static int color(float offset, int speed) {
		float period = 20000f / Math.max(1, Math.min(10, speed));
		float hue = (System.currentTimeMillis() % (long) period) / period + offset;
		return Colors.hsv(hue - (float) Math.floor(hue), 0.75f, 1f);
	}

	/** `chroma`, `accent` or `#rrggbb`. */
	public static int parse(String value, int fallback) {
		if (VALUE.equals(value)) return color(0, DEFAULT_SPEED);
		if ("accent".equals(value)) {
			Theme t = AerionClient.theme();
			return t != null ? t.accent : fallback;
		}
		return Theme.color(value, fallback);
	}
}
