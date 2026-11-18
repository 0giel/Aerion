package pl.aerion.screens.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Aerion Screens in the game: its keys (the same names the Aerion mod always used, so saved
 * bindings stay) and the work done every tick.
 */
public final class ScreensClient implements ClientModInitializer {
	private static KeyMapping[] keys;
	private static KeyMapping mirrors, grab, type, arrange, hide;

	@Override
	public void onInitializeClient() {
		Screens.LOG.info("Aerion Screens: ready (API {})", Screens.API);
	}

	/** Made while the game's options are built, so options.txt keeps their bindings. */
	public static KeyMapping[] keys() {
		if (keys == null) {
			KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aerion_screens", "main"));
			mirrors = new KeyMapping("key.aerion.mirrors", InputConstants.KEY_J, cat);
			grab = new KeyMapping("key.aerion.mirrorGrab", InputConstants.KEY_Y, cat);
			type = new KeyMapping("key.aerion.mirrorType", InputConstants.KEY_U, cat);
			arrange = new KeyMapping("key.aerion.mirrorArrange", InputConstants.KEY_K, cat);
			hide = new KeyMapping("key.aerion.mirrorHide", InputConstants.KEY_R, cat);
			keys = new KeyMapping[] {mirrors, grab, type, arrange, hide};
		}
		return keys;
	}

	/** A key by its short name ("mirrors", "mirrorGrab", "mirrorType", "mirrorArrange", "mirrorHide"). */
	public static KeyMapping key(String id) {
		if (keys == null) return null;
		return Map.of("mirrors", mirrors, "mirrorGrab", grab, "mirrorType", type, "mirrorArrange", arrange, "mirrorHide", hide).get(id);
	}

	/** Windows answer clicks and the wheel now: on, in the world, no menu open. */
	public static boolean active() {
		return keys != null && Platform.screen() == null && Screens.settings.enabled();
	}

	/** The pointer is over a panel: the crosshair gives way to the panel's own arrow. */
	public static boolean pointing() {
		return active() && Mirrors.pointing();
	}

	/** Opens the window picker; opening it is a clear wish, so windows turn on with it. */
	public static void openPicker() {
		if (!Screens.settings.enabled()) Screens.settings.setEnabled(true);
		Screens.ui.openPicker();
	}

	/** Every game tick, before the game's own work. */
	public static void tick(Minecraft mc) {
		if (keys == null) return;
		try {
			while (mirrors.consumeClick()) {
				if (Platform.screen() == null && mc.player != null) openPicker();
			}
			boolean on = Screens.settings.enabled();
			Mirrors.tick(mc, on && Platform.screen() == null && grab.isDown(), on);
			ScreenShare.tick(mc);
			while (arrange.consumeClick()) if (on) Mirrors.arrange();
			while (hide.consumeClick()) if (on) Mirrors.toggleHidden();
			while (type.consumeClick()) {
				long win = on && Platform.screen() == null ? Mirrors.aimedWindow() : 0;
				if (win != 0) {
					var hit = Mirrors.aim();
					Screens.ui.openTyping(win, hit != null ? hit.panel().title : "");
				}
			}
		} catch (RuntimeException e) {
			Screens.warnOnce("tick", e);
		}
	}
}
