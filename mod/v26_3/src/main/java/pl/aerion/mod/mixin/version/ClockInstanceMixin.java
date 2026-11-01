package pl.aerion.mod.mixin.version;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.WorldLook;

/** Time of day only this player sees: the client's copy of the world clock. */
@Mixin(targets = "net.minecraft.client.ClientClockManager$ClientClockInstance")
abstract class ClockInstanceMixin {
	@Inject(method = "totalTicks", at = @At("RETURN"), cancellable = true)
	private void aerion$time(CallbackInfoReturnable<Long> cir) {
		cir.setReturnValue(WorldLook.ticks(cir.getReturnValue()));
	}
}
