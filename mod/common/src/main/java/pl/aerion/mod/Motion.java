package pl.aerion.mod;

import pl.aerion.mod.hud.AerionMenu;

/** Small easing helpers for menu motion; everything is off when animations are off. */
public final class Motion {
	private Motion() {}

	/** Animations on: the HUD file's setting, else the theme's. */
	public static boolean on() {
		return AerionMenu.animationsOn();
	}

	/** Seconds on a clock that keeps running (wraps every hour). */
	public static float seconds() {
		return (System.nanoTime() % 3_600_000_000_000L) / 1e9f;
	}

	/** Eases 0..1 smoothly at both ends. */
	public static float smooth(float t) {
		t = Math.max(0f, Math.min(1f, t));
		return t * t * (3f - 2f * t);
	}

	/** Eases 0..1 with a small overshoot, like the HUD's springy menus. */
	public static float spring(float t) {
		t = Math.max(0f, Math.min(1f, t));
		float c1 = 1.70158f, c3 = c1 + 1;
		return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
	}

	/** Eases 0..1 out, without overshoot. */
	public static float out(float t) {
		t = Math.max(0f, Math.min(1f, t));
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}

	/** Where the mouse is across the window, -1 (left) to 1 (right); 0 when unknown. */
	public static float mouseX() {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc == null || mc.getWindow() == null) return 0f;
		double w = Math.max(1, mc.getWindow().getScreenWidth());
		return (float) Math.max(-1, Math.min(1, mc.mouseHandler.xpos() / w * 2 - 1));
	}

	public static float mouseY() {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc == null || mc.getWindow() == null) return 0f;
		double h = Math.max(1, mc.getWindow().getScreenHeight());
		return (float) Math.max(-1, Math.min(1, mc.mouseHandler.ypos() / h * 2 - 1));
	}
}
