package pl.aerion.mod;

/** ARGB colour helpers. */
public final class Colors {
	private Colors() {}

	/** Opaque colour from hue, saturation and value, each 0 to 1. */
	public static int hsv(float h, float s, float v) {
		float c = v * s, x = c * (1 - Math.abs((h * 6) % 2 - 1)), m = v - c;
		int sector = (int) (h * 6) % 6;
		float r = sector == 0 || sector == 5 ? c : sector == 1 || sector == 4 ? x : 0;
		float g = sector == 1 || sector == 2 ? c : sector == 0 || sector == 3 ? x : 0;
		float b = sector == 3 || sector == 4 ? c : sector == 2 || sector == 5 ? x : 0;
		return 0xFF000000 | Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
	}

	/** {hue, saturation, value}, each 0 to 1. */
	public static float[] toHsv(int c) {
		float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, b = (c & 255) / 255f;
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, h = 0;
		if (d > 0) {
			if (max == r) h = ((g - b) / d + 6) % 6;
			else if (max == g) h = (b - r) / d + 2;
			else h = (r - g) / d + 4;
		}
		return new float[] {h / 6, max == 0 ? 0 : d / max, max};
	}

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
