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

	public static net.minecraft.resources.Identifier effectSprite(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
		return net.minecraft.client.gui.Hud.getMobEffectSprite(effect);
	}

	/** A short message above the hotbar. */
	public static void overlayMessage(net.minecraft.network.chat.Component message) {
		Minecraft.getInstance().gui.hud.setOverlayMessage(message, false);
	}
}
