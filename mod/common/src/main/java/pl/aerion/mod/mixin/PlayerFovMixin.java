package pl.aerion.mod.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.Features;

/** FOV lock: the field of view stays put while sprinting, under speed or slowness, or drawing a bow. */
@Mixin(AbstractClientPlayer.class)
abstract class PlayerFovMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
	private void aerion$fovLock(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> cir) {
		if (Features.fovLocked((AbstractClientPlayer) (Object) this)) cir.setReturnValue(1f);
	}
}
