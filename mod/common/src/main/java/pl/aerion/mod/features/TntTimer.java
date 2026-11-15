package pl.aerion.mod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.Vec3;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.hud.HudConfig;

/** Seconds left above lit TNT nearby, green turning red as it runs out. */
public final class TntTimer {
	private TntTimer() {}

	private static final double RANGE = 48;

	/** Called while the game collects the frame's debug shapes. */
	public static void emit(float partial) {
		HudConfig cfg = AerionClient.hud();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (cfg == null || p == null || mc.level == null || !HudConfig.bool(cfg.feature("tntTimer"), "enabled", false)) return;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof PrimedTnt tnt) || e.distanceToSqr(p) > RANGE * RANGE) continue;
			float left = Math.max(0, tnt.getFuse() - partial) / 20f;
			// 4 seconds (a fresh fuse) is green, none left is red.
			float k = Math.min(1f, left / 4f);
			int r = Math.round(255 * Math.min(1f, 2 * (1 - k))), g = Math.round(255 * Math.min(1f, 2 * k));
			int color = 0xFF000000 | (r << 16) | (g << 8) | 0x40;
			Vec3 at = e.getPosition(partial).add(0, 1.4, 0);
			Gizmos.billboardText(String.format("%.1f s", left), at, TextGizmo.Style.forColorAndCentered(color).withScale(TextGizmo.Style.DEFAULT_SCALE * 1.4f));
		}
	}
}
