package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

/** What is going on in the game, for modules that show or hide themselves by situation. */
final class Situations {
	private Situations() {}

	/** Situations a module can be shown or hidden in ("always" and "never" are the plain cases). */
	static final List<String> IDS = List.of("combat", "underground", "underwater", "lowHealth", "moving", "sprinting", "nether", "multiplayer");

	/** Whether the situation holds right now. */
	static boolean holds(String id, Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return false;
		return switch (id) {
			case "always" -> true;
			case "combat" -> p.hurtTime > 0 || p.tickCount - p.getLastHurtMobTimestamp() < 200 || p.tickCount - p.getLastHurtByMobTimestamp() < 200;
			case "underground" -> mc.level != null && !mc.level.canSeeSky(p.blockPosition()) && p.getY() < 60;
			case "underwater" -> p.isUnderWater();
			case "lowHealth" -> p.getHealth() <= 6f;
			case "moving" -> p.getDeltaMovement().horizontalDistanceSqr() > 0.0004;
			case "sprinting" -> p.isSprinting();
			case "nether" -> p.level().dimension().equals(Level.NETHER);
			case "multiplayer" -> mc.getCurrentServer() != null;
			default -> false;
		};
	}

	/** True when the module's `showWhen`/`hideWhen` options allow it to be on screen. */
	static boolean visible(JsonObject module, Minecraft mc) {
		String show = HudConfig.opt(module, "showWhen", "always");
		String hide = HudConfig.opt(module, "hideWhen", "never");
		if (!"always".equals(show) && !holds(show, mc)) return false;
		return "never".equals(hide) || !holds(hide, mc);
	}
}
