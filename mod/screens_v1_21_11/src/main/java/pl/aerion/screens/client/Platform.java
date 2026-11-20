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

	/** The modifier keys held now, as bits: Shift 1, Ctrl 2, Alt 4, Super 8. */
	public static int modifiers() {
		int m = 0;
		int[][] keys = {{com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT, com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT},
				{com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL, com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL},
				{com.mojang.blaze3d.platform.InputConstants.KEY_LALT, com.mojang.blaze3d.platform.InputConstants.KEY_RALT},
				{343, 347}}; // GLFW left and right Super
		for (int i = 0; i < keys.length; i++) {
			for (int k : keys[i]) if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), k)) m |= 1 << i;
		}
		return m;
	}
}
