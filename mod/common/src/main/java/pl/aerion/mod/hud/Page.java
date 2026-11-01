package pl.aerion.mod.hud;

import pl.aerion.mod.platform.EditorScreen;
import pl.aerion.mod.platform.Game;
import pl.aerion.mod.platform.Gfx;

/** One view of Aerion's in-game screen: the module menu or the HUD layout editor. */
public interface Page {
	void render(Gfx g, int mouseX, int mouseY);

	/** True when the click closes the screen. */
	boolean pressed(double x, double y, int button);

	void dragged(double x, double y, int screenW, int screenH);

	void released();

	void scrolled(double x, double y, double amount);

	/** Save what changed; called when the page is left. */
	void close();

	static void go(Page next) {
		if (Game.screen() instanceof EditorScreen s) s.show(next);
	}
}
