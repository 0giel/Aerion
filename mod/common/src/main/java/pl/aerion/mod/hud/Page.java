package pl.aerion.mod.hud;

import com.mojang.blaze3d.platform.InputConstants;
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

	/** Whether the game blurs and tints the world behind this page. */
	default boolean veil() {
		return true;
	}

	/** A key press; true when the page used it. */
	default boolean keyPressed(InputConstants.Key key) {
		return false;
	}

	/** A typed character; true when the page used it. */
	default boolean charTyped(int codepoint) {
		return false;
	}

	/** Save what changed; called when the page is left. */
	void close();

	static void go(Page next) {
		if (Game.screen() instanceof EditorScreen s) s.show(next);
	}
}
