package pl.aerion.mod.features;

import net.minecraft.client.Minecraft;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.hud.HudConfig;

/** The player's own name (and badge) above their head, seen in third person. */
public final class OwnName {
	private OwnName() {}

	public static boolean show() {
		HudConfig cfg = AerionClient.hud();
		Minecraft mc = Minecraft.getInstance();
		return cfg != null && HudConfig.bool(cfg.feature("ownName"), "enabled", false) && !mc.options.getCameraType().isFirstPerson();
	}
}
