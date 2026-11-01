package pl.aerion.mod.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.Sounds;

/** Per-sound volume, applied where the game works out a sound's final volume. */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
	@Inject(
			method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
			at = @At("RETURN"),
			cancellable = true)
	private void aerion$volume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		String id = sound.getIdentifier().toString();
		Sounds.heard(id);
		float k = Sounds.volume(id);
		if (k != 1f) cir.setReturnValue(cir.getReturnValue() * k);
	}
}
