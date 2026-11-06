package pl.aerion.mod;

import net.minecraft.client.Minecraft;
import pl.aerion.mod.platform.Gfx;

/** The title screen's splash text in the smooth font and the theme's accent, under the logo, pulsing gently. */
public final class Splash {
	private Splash() {}

	private static final float SIZE = 8f;

	/** False leaves the game's own yellow splash, e.g. with theme colours off. */
	public static boolean draw(Gfx g, int width, String text, float alpha) {
		Theme t = AerionClient.colors();
		if (t == null || text == null || text.isEmpty()) return false;
		double beat = (System.currentTimeMillis() % 1600L) / 1600.0 * Math.PI * 2;
		// Centred under the logo, level, as a line between the logo and the buttons: the right side
		// is the player's (see TitleCard). Never wider than the button column.
		float fit = Math.min(1f, 190f / Math.max(1f, Txt.width(text, SIZE)));
		float scale = fit * (1f - (float) Math.abs(Math.sin(beat)) * 0.04f);
		int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
		int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
		g.push();
		g.translate(width / 2f, height / 4f + 34);
		g.scale(scale);
		Txt.center(g, text, 0.6f, 0.6f, SIZE, Colors.alpha(0xFF000000, a * 0x70 / 0xFF));
		Txt.center(g, text, 0, 0, SIZE, Colors.alpha(t.accent, a));
		g.pop();
		return true;
	}
}
