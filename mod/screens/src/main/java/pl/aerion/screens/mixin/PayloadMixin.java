package pl.aerion.screens.mixin;

import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.net.ScreenPayload;
import pl.aerion.screens.Relay;

/**
 * Messages on `aerion:screen` from players in the game go to the relay, on the server thread.
 * (The game listener has its own handler for these; the common one only sees the login steps.)
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PayloadMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
	private void aerion$screen(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (!(packet.payload() instanceof ScreenPayload p)) return;
		ci.cancel();
		ServerPlayer from = player;
		byte[] data = p.data();
		var server = from.level().getServer();
		if (server != null) server.executeIfPossible(() -> Relay.received(from, data));
	}
}
