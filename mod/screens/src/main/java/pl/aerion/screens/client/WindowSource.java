package pl.aerion.screens.client;

import java.util.List;

/**
 * The computer's own windows, as pictures and as targets for clicks: X11 on Linux, Win32 on
 * Windows. Everything is best effort: a window may refuse input sent this way.
 */
public interface WindowSource {
	/** A window: an id valid this session, its title and its class (for finding it again). */
	record Win(long id, String title, String cls) {}

	/** A captured picture, ARGB row by row. */
	record Frame(int width, int height, int[] argb) {}

	/** The windows that can be mirrored, without the game's own. */
	List<Win> list();

	/** The process the window belongs to, 0 when unknown (for finding its sound). */
	default long pid(long id) {
		return 0;
	}

	/** The window's program icon, about 32 pixels; null when it has none. */
	default Frame icon(long id) {
		return null;
	}

	/** The windows offered in the picker (may hold entries that open a window first). */
	default List<Win> pickable() {
		return list();
	}

	/** The window to hang for a picked entry; null when there is none (the entry failed). */
	default Win open(Win w) {
		return w;
	}

	/** A class that finds the window again later, when it changed since it was hung; else null. */
	default String clsOf(long id) {
		return null;
	}

	/** Finds a window this source can bring back from its saved class itself; 0 when it can't. */
	default long reopen(String cls) {
		return 0;
	}

	/** The panel showing this window went away. */
	default void closed(long id) {}

	/** The window's picture, at most `maxWidth` pixels wide; null when it can't be read now. */
	Frame capture(long id, int maxWidth);

	/** Click at (`x`, `y`) of the window, as fractions 0–1 of its size; button 0 left, 1 right. */
	default void click(long id, float x, float y, int button) {
		press(id, x, y, button);
		release(id, x, y, button);
	}

	/** A button goes down at (`x`, `y`) (to drag, select text, move a slider). */
	void press(long id, float x, float y, int button);

	/** The button goes up at (`x`, `y`). */
	void release(long id, float x, float y, int button);

	/** The pointer moves to (`x`, `y`), with the left button held or not (hover, dragging). */
	void move(long id, float x, float y, boolean held);

	/** Scroll at (`x`, `y`): `notches` up (positive) or down. */
	void scroll(long id, float x, float y, int notches);

	/** Type text into the window. */
	void type(long id, String text);

	/** A special key: "enter", "backspace", "tab", "escape", "left", "right", "up", "down". */
	void key(long id, String name);

	/**
	 * Gives the keyboard focus back to the game's window (the biggest one of this process): a
	 * program that was clicked may take the focus, and the game would pause.
	 */
	void focusGame();

	/** The source for this computer, or null when there is none (e.g. a Wayland-only desktop). */
	static WindowSource forThisComputer() {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		try {
			if (os.contains("win")) return new Win32Source();
			if (os.contains("linux") || os.contains("bsd")) {
				WindowSource x11 = null;
				try {
					x11 = X11Source.open();
				} catch (Throwable e) {
					// Wayland without XWayland: only windows from the desktop's dialog
					if (!PortalSource.wanted()) throw e;
				}
				return PortalSource.wrap(x11);
			}
		} catch (Throwable e) {
			Screens.LOG.warn("Aerion: window mirrors unavailable: {}", e.toString());
		}
		return null;
	}

	/** Shrinks a picture to at most `maxWidth` wide by averaging blocks of pixels. */
	static Frame fit(int w, int h, int[] px, int maxWidth) {
		if (w <= maxWidth) return new Frame(w, h, px);
		int k = (w + maxWidth - 1) / maxWidth;
		int nw = w / k, nh = h / k;
		int[] out = new int[nw * nh];
		for (int y = 0; y < nh; y++) {
			for (int x = 0; x < nw; x++) {
				int r = 0, g = 0, b = 0;
				for (int dy = 0; dy < k; dy++) {
					int row = (y * k + dy) * w + x * k;
					for (int dx = 0; dx < k; dx++) {
						int c = px[row + dx];
						r += (c >> 16) & 0xFF;
						g += (c >> 8) & 0xFF;
						b += c & 0xFF;
					}
				}
				int n = k * k;
				out[y * nw + x] = 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
			}
		}
		return new Frame(nw, nh, out);
	}
}
