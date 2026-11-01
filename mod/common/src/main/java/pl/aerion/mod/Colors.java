package pl.aerion.mod;

/** ARGB colour helpers. */
public final class Colors {
	private Colors() {}

	public static int alpha(int c, int a) {
		return (a << 24) | (c & 0xFFFFFF);
	}

	public static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		int al = Math.round(((a >>> 24) & 0xFF) * (1 - t) + ((b >>> 24) & 0xFF) * t);
		return (al << 24) | (r << 16) | (g << 8) | bl;
	}

	static float luminance(int c) {
		return (0.2126f * ((c >> 16) & 0xFF) + 0.7152f * ((c >> 8) & 0xFF) + 0.0722f * (c & 0xFF)) / 255f;
	}

	/** Minecraft draws button labels white: darken light surfaces until white text reads well. */
	public static int forWhiteText(int c) {
		for (int i = 0; i < 12 && luminance(c) > 0.33f; i++) c = mix(c, c & 0xFF000000, 0.18f);
		return c;
	}
}
