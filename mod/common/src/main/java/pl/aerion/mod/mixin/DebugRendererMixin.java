package pl.aerion.mod.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.features.Hitboxes;
import pl.aerion.mod.features.LightLevels;

/** Hitboxes and light numbers join the shapes the game collects for the frame. */
@Mixin(DebugRenderer.class)
abstract class DebugRendererMixin {
	@Inject(method = "emitGizmos", at = @At("TAIL"))
	private void aerion$hitboxes(Frustum frustum, double x, double y, double z, float partial, CallbackInfo ci) {
		try {
			Hitboxes.emit(partial);
			LightLevels.emit();
		} catch (RuntimeException e) {
			AerionClient.warnOnce("hitboxes", e);
		}
	}
}
