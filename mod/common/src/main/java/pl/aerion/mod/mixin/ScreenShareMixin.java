package pl.aerion.mod.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.mirror.ScreenShare;
import pl.aerion.net.ScreenPayload;

/** Shared windows from the server (the Aerion Screens server mod); handled in the next tick. */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ScreenShareMixin {
	@Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
	private void aerion$screen(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (packet.payload() instanceof ScreenPayload p) {
			ScreenShare.received(p.data());
			ci.cancel();
		}
	}
}
