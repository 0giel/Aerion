package pl.aerion.mod.mixin;

import net.minecraft.client.particle.ItemPickupParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.features.Effects;

/** Fewer particles (Effects): only the chosen share of new ones is let in. */
@Mixin(ParticleEngine.class)
abstract class ParticleEngineMixin {
	@Inject(method = "add", at = @At("HEAD"), cancellable = true)
	private void aerion$fewer(Particle particle, CallbackInfo ci) {
		// Items flying into the inventory are not effects: they always show.
		if (!(particle instanceof ItemPickupParticle) && !Effects.keepParticle()) ci.cancel();
	}
}
