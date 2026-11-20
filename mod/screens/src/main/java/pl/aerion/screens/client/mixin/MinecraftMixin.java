package pl.aerion.screens.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.screens.client.Mirrors;
import pl.aerion.screens.client.ScreensClient;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void aerionScreens$tick(CallbackInfo ci) {
		ScreensClient.tick((Minecraft) (Object) this);
	}

	/** Looking at a window's panel, the attack click goes to the window instead. */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void aerionScreens$panelClick(CallbackInfoReturnable<Boolean> cir) {
		if (ScreensClient.active() && Mirrors.click(0)) cir.setReturnValue(false);
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void aerionScreens$panelUse(CallbackInfo ci) {
		if (ScreensClient.active() && Mirrors.click(1)) ci.cancel();
	}

	/** Clicking a window may move the focus away for a moment: the game doesn't pause for that. */
	@Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
	private void aerionScreens$noPause(boolean pauseOnly, CallbackInfo ci) {
		if (Mirrors.justSent() || Mirrors.keyboardHeld) ci.cancel();
	}

	/** No block breaking behind a panel while the button is held. */
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void aerionScreens$panelHold(boolean down, CallbackInfo ci) {
		if (ScreensClient.active() && (Mirrors.holding() || Mirrors.aim() != null)) ci.cancel();
	}
}
