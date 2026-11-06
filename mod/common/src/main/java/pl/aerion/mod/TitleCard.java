package pl.aerion.mod;

import net.minecraft.client.Minecraft;
import pl.aerion.mod.platform.Gfx;

/**
 * The player beside the title screen's buttons, on no panel of its own: the whole player model (the game's own
 * widget, turned by dragging), name and "Host a world"; the model and the button are real widgets
 * added in {@code MenuButtonMixin}. The model takes the height left down to the copyright line, so
 * it grows with the window. Hidden when the window is too narrow or too low for it.
 */
public final class TitleCard {
	private TitleCard() {}

	private static final int PAD = 6, NAME = 12, HINT = 9, BUTTON = 20, GAP = 4;
	private static final int MIN_MODEL = 50, MAX_MODEL = 128, HINT_FROM = 72;

	/** x, y, width, height of the card, the model's height, and 1 when the hint line is shown. */
	public static int[] place(int width, int height) {
		int left = width / 2 + 100 + 6, room = width - 4 - left;
		// From the logo's height down: the splash sits under the logo, so this side is free.
		int top = 30;
		// Room above the bottom lines of text (version and copyright).
		int free = height - 13 - top, base = 2 * PAD + NAME + GAP + BUTTON;
		// A little under the full height, so the player doesn't crowd the logo.
		int model = Math.min(MAX_MODEL, (free - base - HINT) * 85 / 100);
		boolean hint = model >= HINT_FROM;
		if (!hint) model = Math.min(MAX_MODEL, (free - base) * 85 / 100);
		if (model < MIN_MODEL) return null;
		// The rest of the room goes above, so the button stays level with the bottom row.
		top += free - (base + model + (hint ? HINT : 0));
		// Wide enough for the model (half as wide as tall) and the button's label.
		int w = Math.max(90, model / 2 + 2 * PAD + 16);
		if (room < w) return null;
		return new int[] {left + (room - w) / 2, top, w, base + model + (hint ? HINT : 0), model, hint ? 1 : 0};
	}

	/** Where the player model goes inside the card: x, y, width, height. */
	public static int[] model(int[] c) {
		int h = c[4], w = h / 2;
		return new int[] {c[0] + (c[2] - w) / 2, c[1] + PAD, w, h};
	}

	/** Where the host button goes inside the card. */
	public static int[] button(int[] c) {
		return new int[] {c[0] + PAD, c[1] + c[3] - PAD - BUTTON, c[2] - 2 * PAD, BUTTON};
	}

	public static void draw(Gfx g, int width, int height, float alpha) {
		int[] c = place(width, height);
		if (c == null) return;
		Theme t = AerionClient.theme();
		int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
		if (a <= 4) return;
		int text = Colors.alpha(t != null ? t.text : 0xFFE9ECF2, a);
		int muted = Colors.alpha(t != null ? t.textMuted : 0xFFA3A9B6, a);
		int x = c[0], y = c[1], w = c[2];
		int below = y + PAD + c[4];
		String name = Minecraft.getInstance().getUser().getName();
		// No panel behind: a soft shadow keeps the text readable on the background.
		shadowed(g, name, x + w / 2f, below + 7, 7.5f, text, a);
		// Who can join, when there is a line for it and it fits.
		String hint = Lang.tr(Host.hasE4mc() ? "aerion.host.hintOnline" : "aerion.host.hintLan");
		if (c[5] == 1 && Txt.width(hint, 6f) <= w - 2 * PAD) shadowed(g, hint, x + w / 2f, below + NAME + 4, 6f, muted, a);
	}

	private static void shadowed(Gfx g, String s, float cx, float cy, float size, int color, int alpha) {
		Txt.center(g, s, cx + 0.6f, cy + 0.6f, size, Colors.alpha(0xFF000000, alpha * 0x90 / 0xFF));
		Txt.center(g, s, cx, cy, size, color);
	}
}
