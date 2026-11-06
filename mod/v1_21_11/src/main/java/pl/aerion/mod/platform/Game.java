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

	public static net.minecraft.resources.Identifier effectSprite(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
		return net.minecraft.client.gui.Gui.getMobEffectSprite(effect);
	}

	/** A short message above the hotbar. */
	public static void overlayMessage(net.minecraft.network.chat.Component message) {
		Minecraft.getInstance().gui.setOverlayMessage(message, false);
	}

	/** Opens the running single-player world to others on `port`. */
	public static boolean publish(net.minecraft.client.server.IntegratedServer server, int port) {
		var mode = Minecraft.getInstance().gameMode;
		return server.publishServer(mode != null ? mode.getPlayerMode() : net.minecraft.world.level.GameType.SURVIVAL, false, port);
	}

	/** A line in the chat from the client, not from a player. */
	public static void chat(net.minecraft.network.chat.Component message) {
		Minecraft.getInstance().gui.getChat().addMessage(message);
	}
}
