package pl.aerion.screens.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.net.ScreenPayload;
import pl.aerion.screens.client.ScreenShare;

/** Shared windows from the server; handled in the next tick. */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class PayloadMixin {
	@Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
	private void aerionScreens$screen(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (packet.payload() instanceof ScreenPayload p) {
			ScreenShare.received(p.data());
			ci.cancel();
		}
	}
}
