package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import pl.aerion.mod.features.WorldLook;
import pl.aerion.mod.platform.Game;

/** What is going on in the game, for modules that show or hide themselves by situation. */
final class Situations {
	private Situations() {}

	/** Situations a module can be shown or hidden in ("always" and "never" are the plain cases). */
	static final List<String> IDS = List.of("combat", "underground", "underwater", "lowHealth", "hungry", "lowArmor", "moving", "sprinting", "sneaking", "flying", "riding",
			"night", "raining", "overworld", "nether", "end", "multiplayer", "singleplayer");

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
			case "hungry" -> p.getFoodData().getFoodLevel() <= 6;
			case "lowArmor" -> lowArmor(p);
			case "sneaking" -> p.isShiftKeyDown();
			case "flying" -> p.getAbilities().flying || p.isFallFlying();
			case "riding" -> p.isPassenger();
			case "night" -> mc.level != null && WorldLook.actual(() -> Game.dayTime(mc.level)) % 24000 >= 13000;
			case "raining" -> mc.level != null && mc.level.isRaining();
			case "overworld" -> p.level().dimension().equals(Level.OVERWORLD);
			case "nether" -> p.level().dimension().equals(Level.NETHER);
			case "end" -> p.level().dimension().equals(Level.END);
			case "multiplayer" -> mc.getCurrentServer() != null;
			case "singleplayer" -> mc.getCurrentServer() == null;
			default -> false;
		};
	}

	private static boolean lowArmor(LocalPlayer p) {
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack i = p.getItemBySlot(slot);
			if (!i.isEmpty() && i.isDamageableItem() && i.getMaxDamage() > 0 && (i.getMaxDamage() - i.getDamageValue()) * 100 / i.getMaxDamage() < 15) return true;
		}
		return false;
	}

	/** True when the module's `showWhen`/`hideWhen` options allow it to be on screen. */
	static boolean visible(JsonObject module, Minecraft mc) {
		return condition(module, mc);
	}

	/** The situation options alone, without any timing. */
	static boolean condition(JsonObject module, Minecraft mc) {
		String show = HudConfig.opt(module, "showWhen", "always");
		String hide = HudConfig.opt(module, "hideWhen", "never");
		if (!"always".equals(show) && !holds(show, mc)) return false;
		return "never".equals(hide) || !holds(hide, mc);
	}
}
