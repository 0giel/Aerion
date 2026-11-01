package pl.aerion.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.Sounds;

/**
 * Per-sound volume. The game works out a volume in two places: when a sound starts (from the
 * sound's volume and category) and while a running sound is updated; both need the multiplier.
 */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
	@WrapOperation(
			method = "play",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
	private float aerion$started(SoundEngine engine, float volume, SoundSource source, Operation<Float> original, @Local(argsOnly = true) SoundInstance sound) {
		String id = sound.getIdentifier().toString();
		Sounds.heard(id);
		return original.call(engine, volume, source) * Sounds.volume(id);
	}

	@Inject(
			method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
			at = @At("RETURN"),
			cancellable = true)
	private void aerion$running(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		float k = Sounds.volume(sound.getIdentifier().toString());
		if (k != 1f) cir.setReturnValue(cir.getReturnValue() * k);
	}
}
