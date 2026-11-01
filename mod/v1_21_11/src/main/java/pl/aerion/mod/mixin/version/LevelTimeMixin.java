package pl.aerion.mod.mixin.version;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.WorldLook;

/** Time of day only this player sees; the server's world isn't touched. */
@Mixin(Level.class)
abstract class LevelTimeMixin {
	@Inject(method = "getDayTime", at = @At("RETURN"), cancellable = true)
	private void aerion$time(CallbackInfoReturnable<Long> cir) {
		if ((Object) this instanceof ClientLevel) cir.setReturnValue(WorldLook.ticks(cir.getReturnValue()));
	}
}
