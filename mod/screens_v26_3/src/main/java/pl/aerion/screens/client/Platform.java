package pl.aerion.screens.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.Vec3;

/** What differs between Minecraft versions, for Minecraft 26.x. */
public final class Platform {
	private Platform() {}

	public static Screen screen() {
		return Minecraft.getInstance().gui.screen();
	}

	public static void setScreen(Screen s) {
		Minecraft.getInstance().gui.setScreen(s);
	}

	public static Vec3 cameraPosition() {
		return Minecraft.getInstance().gameRenderer.mainCamera().position();
	}

	/** 26.x delivers typed characters only while a screen asks for text input. */
	public static void startTextInput(Screen s) {
		Minecraft.getInstance().textInputManager().startTextInput(s);
	}

	public static void stopTextInput(Screen s) {
		Minecraft.getInstance().textInputManager().stopTextInput(s);
	}

	/** The modifier keys held now, as bits: Shift 1, Ctrl 2, Alt 4, Super 8. */
	public static int modifiers() {
		int m = 0;
		int[][] keys = {{com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT, com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT},
				{com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL, com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL},
				{com.mojang.blaze3d.platform.InputConstants.KEY_LALT, com.mojang.blaze3d.platform.InputConstants.KEY_RALT},
				{343, 347}}; // GLFW left and right Super
		for (int i = 0; i < keys.length; i++) {
			for (int k : keys[i]) if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(k)) m |= 1 << i;
		}
		return m;
	}
}
