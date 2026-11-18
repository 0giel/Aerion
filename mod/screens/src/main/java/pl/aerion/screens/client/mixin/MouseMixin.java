package pl.aerion.screens.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.screens.client.Mirrors;
import pl.aerion.screens.client.ScreensClient;

/** The wheel over a panel scrolls the window (or resizes it over its bar). */
@Mixin(MouseHandler.class)
abstract class MouseMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void aerionScreens$scroll(long window, double dx, double dy, CallbackInfo ci) {
		if (ScreensClient.active() && Mirrors.scroll(dy)) ci.cancel();
	}
}
