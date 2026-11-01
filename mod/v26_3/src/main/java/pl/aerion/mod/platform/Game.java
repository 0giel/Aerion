package pl.aerion.mod.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Game state whose place differs between versions (26.x keeps screens in Gui). */
public final class Game {
	private Game() {}

	public static Screen screen() {
		return Minecraft.getInstance().gui.screen();
	}

	public static void setScreen(Screen s) {
		Minecraft.getInstance().gui.setScreen(s);
	}

	/** F1 hides the HUD. */
	public static boolean hudHidden() {
		return Minecraft.getInstance().gui.hud.isHidden();
	}

	public static void clearToasts() {
		Minecraft.getInstance().gui.toastManager().clear();
	}

	/** Ticks since the world began, by its clock. */
	public static long dayTime(net.minecraft.client.multiplayer.ClientLevel level) {
		return level.getOverworldClockTime();
	}
}
