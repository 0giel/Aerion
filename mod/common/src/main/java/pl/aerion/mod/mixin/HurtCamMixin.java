package pl.aerion.mod.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import pl.aerion.mod.features.Overlays;

/** Hurt cam strength: the tilt the game applies when the player takes damage. */
@Mixin(GameRenderer.class)
abstract class HurtCamMixin {
	@ModifyConstant(method = "bobHurt", constant = @Constant(doubleValue = 14.0))
	private double aerion$tilt(double tilt) {
		return tilt * Overlays.hurtCam();
	}
}
