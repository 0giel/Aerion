package pl.aerion.mod.hud;

/** Facing as text: eight directions and, optionally, degrees (0° = south, as Minecraft counts). */
final class Compass {
	private Compass() {}

	private static final String[] NAMES = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

	static String label(float yaw, boolean degrees) {
		float d = ((yaw % 360) + 360) % 360;
		String name = NAMES[Math.round(d / 45f) % 8];
		return degrees ? String.format("%s  %.0f°", name, d) : name;
	}
}
