package pl.aerion.screens.client.mixin;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.screens.client.ScreensClient;

/** Over a window's panel, its arrow pointer shows where a click goes instead of the crosshair. */
@Mixin(net.minecraft.client.gui.Hud.class)
abstract class CrosshairMixin {
	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void aerionScreens$crosshair(net.minecraft.client.gui.GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		if (ScreensClient.pointing()) ci.cancel();
	}
}
