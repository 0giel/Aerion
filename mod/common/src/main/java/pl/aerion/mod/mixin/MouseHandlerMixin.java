package pl.aerion.mod.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.features.Features;
import pl.aerion.mod.hud.Cps;
import pl.aerion.mod.platform.Game;

/** Clicks in the game (not in menus) for the CPS counter, and the wheel for zooming. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void aerion$cps(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (action == InputConstants.PRESS && Game.screen() == null) Cps.click(info.button());
	}

	/** While zooming, the wheel zooms instead of changing the hotbar slot. */
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void aerion$zoomScroll(long window, double dx, double dy, CallbackInfo ci) {
		if (Features.zoomScroll(dy)) ci.cancel();
	}
}
