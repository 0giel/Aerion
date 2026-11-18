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
}
