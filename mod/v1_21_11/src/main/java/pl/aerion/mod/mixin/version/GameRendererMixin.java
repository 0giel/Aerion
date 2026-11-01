package pl.aerion.mod.mixin.version;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.Features;

/** Zoom narrows the field of view. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void aerion$zoom(Camera camera, float partial, boolean useSetting, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(cir.getReturnValue() * Features.fovMultiplier());
	}
}
