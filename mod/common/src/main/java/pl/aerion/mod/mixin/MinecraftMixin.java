package pl.aerion.mod.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.features.Features;
import pl.aerion.mod.mirror.Mirrors;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void aerion$tick(CallbackInfo ci) {
		AerionClient.tick();
	}

	/** Looking at a window's panel, the attack click goes to the window instead. */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void aerion$panelClick(CallbackInfoReturnable<Boolean> cir) {
		if (Features.mirrorsActive() && Mirrors.click(0)) cir.setReturnValue(false);
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void aerion$panelUse(CallbackInfo ci) {
		if (Features.mirrorsActive() && Mirrors.click(1)) ci.cancel();
	}

	/** Clicking a window may move the focus away for a moment: the game doesn't pause for that. */
	@Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
	private void aerion$noPause(boolean pauseOnly, CallbackInfo ci) {
		if (Mirrors.justSent()) ci.cancel();
	}

	/** No block breaking behind a panel while the button is held. */
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void aerion$panelHold(boolean down, CallbackInfo ci) {
		if (Features.mirrorsActive() && (Mirrors.holding() || Mirrors.aim() != null)) ci.cancel();
	}
}
