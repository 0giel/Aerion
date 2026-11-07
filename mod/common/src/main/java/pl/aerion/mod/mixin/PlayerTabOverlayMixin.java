package pl.aerion.mod.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.Badges;

/** Badges in front of the names in the player list (Tab). */
@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void aerion$badge(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		cir.setReturnValue(Badges.decorate(info.getProfile().id(), cir.getReturnValue()));
	}
}
