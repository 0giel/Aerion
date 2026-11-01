package pl.aerion.mod.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.features.Features;

/** Freelook: mouse movement turns the camera instead of the player. */
@Mixin(Entity.class)
abstract class EntityMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void aerion$freelook(double dy, double dx, CallbackInfo ci) {
		if (Features.turn((Entity) (Object) this, dy, dx)) ci.cancel();
	}
}
