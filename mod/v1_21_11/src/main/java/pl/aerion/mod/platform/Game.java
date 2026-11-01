package pl.aerion.mod.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Game state whose place differs between versions. */
public final class Game {
	private Game() {}

	public static Screen screen() {
		return Minecraft.getInstance().screen;
	}

	public static void setScreen(Screen s) {
		Minecraft.getInstance().setScreen(s);
	}

	/** F1 hides the HUD. */
	public static boolean hudHidden() {
		return Minecraft.getInstance().options.hideGui;
	}

	public static void clearToasts() {
		Minecraft.getInstance().getToastManager().clear();
	}

	/** Ticks since the world began, by its clock. */
	public static long dayTime(net.minecraft.client.multiplayer.ClientLevel level) {
		return level.getDayTime();
	}
}
