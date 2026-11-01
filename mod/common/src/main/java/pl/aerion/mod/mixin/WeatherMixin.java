package pl.aerion.mod.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.WorldLook;

/** Weather only this player sees; the server's world isn't touched. */
@Mixin(Level.class)
abstract class WeatherMixin {
	@Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true)
	private void aerion$rain(float partial, CallbackInfoReturnable<Float> cir) {
		if ((Object) this instanceof ClientLevel) cir.setReturnValue(WorldLook.rain(cir.getReturnValue()));
	}

	@Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true)
	private void aerion$thunder(float partial, CallbackInfoReturnable<Float> cir) {
		if ((Object) this instanceof ClientLevel) cir.setReturnValue(WorldLook.thunder(cir.getReturnValue()));
	}
}
