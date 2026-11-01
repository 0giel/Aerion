package pl.aerion.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Colours of the launcher theme, as ARGB ints, resolved by the launcher into theme.json. */
public final class Theme {
	public final String name;
	public final boolean light;
	public final int bg;
	public final int surface;
	public final int surface2;
	public final int border;
	public final int text;
	public final int textMuted;
	public final int accent;
	public final int accentText;
	/** Background from top to bottom (at least one colour). */
	public final int[] gradient;
	/** Corner radius in GUI pixels. */
	public final int radius;
	/** Player choices in the launcher (Settings → Appearance → In game). */
	public final boolean colors;
	/** `sparkles`, `dust`, `snow` or `none`. */
	public final String particles;
	public final boolean logo;

	private Theme(JsonObject o) {
		name = str(o, "name", "Aerion");
		light = "light".equals(str(o, "base", "dark"));
		bg = color(str(o, "bg", null), 0xFF101218);
		surface = color(str(o, "surface", null), 0xFF181B22);
		surface2 = color(str(o, "surface2", null), 0xFF20242C);
		border = color(str(o, "border", null), 0x14FFFFFF);
		text = color(str(o, "text", null), 0xFFE9ECF2);
		textMuted = color(str(o, "textMuted", null), 0xFFA3A9B6);
		accent = color(str(o, "accent", null), 0xFF7AA2FF);
		accentText = color(str(o, "accentText", null), 0xFF0B1020);
		int[] stops = new int[0];
		if (o.get("gradient") instanceof JsonArray a) {
			stops = new int[a.size()];
			for (int i = 0; i < a.size(); i++) stops[i] = color(a.get(i).getAsString(), bg);
		}
		gradient = stops.length > 0 ? stops : new int[] {bg};
		// CSS pixels on a ~3× scaled GUI.
		radius = Math.max(1, Math.round((o.has("radius") ? o.get("radius").getAsInt() : 10) / 3.5f));
		colors = bool(o, "colors");
		particles = str(o, "particles", "none");
		logo = bool(o, "logo");
	}

	public static Theme parse(JsonObject o) {
		return new Theme(o);
	}

	/** Missing flags (themes written before they existed) count as on. */
	private static boolean bool(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || !e.isJsonPrimitive() || e.getAsBoolean();
	}

	private static String str(JsonObject o, String key, String fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}

	/** `#rgb`, `#rrggbb` or `#rrggbbaa` → ARGB. */
	static int color(String hex, int fallback) {
		if (hex == null || !hex.startsWith("#")) return fallback;
		String h = hex.substring(1);
		try {
			if (h.length() == 3) {
				h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
			}
			if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
			if (h.length() == 8) {
				long v = Long.parseLong(h, 16);
				return (int) (((v & 0xFF) << 24) | (v >>> 8));
			}
		} catch (NumberFormatException ignored) {
			// fall through
		}
		return fallback;
	}
}
