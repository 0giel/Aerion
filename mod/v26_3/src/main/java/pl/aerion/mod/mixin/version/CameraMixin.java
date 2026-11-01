package pl.aerion.mod.mixin.version;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.Features;

/** Freelook turns the camera on its own; zoom narrows the field of view. */
@Mixin(Camera.class)
abstract class CameraMixin {
	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
	private float aerion$yaw(Entity e, float partial, Operation<Float> original) {
		return Features.freelookActive() ? Features.freeYaw() : original.call(e, partial);
	}

	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
	private float aerion$pitch(Entity e, float partial, Operation<Float> original) {
		return Features.freelookActive() ? Features.freePitch() : original.call(e, partial);
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void aerion$zoom(float partial, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(cir.getReturnValue() * Features.fovMultiplier());
	}
}
