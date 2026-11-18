package pl.aerion.screens.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.Vec3;

/** What differs between Minecraft versions, for Minecraft 1.21_11. */
public final class Platform {
	private Platform() {}

	public static Screen screen() {
		return Minecraft.getInstance().screen;
	}

	public static void setScreen(Screen s) {
		Minecraft.getInstance().setScreen(s);
	}

	public static Vec3 cameraPosition() {
		return Minecraft.getInstance().gameRenderer.getMainCamera().position();
	}

	/** Typed characters for a screen that reads keys itself. */
	public static void startTextInput(Screen s) {
		// 1.21.11 always delivers typed characters.
	}

	public static void stopTextInput(Screen s) {
		// nothing to stop on 1.21.11
	}
}
