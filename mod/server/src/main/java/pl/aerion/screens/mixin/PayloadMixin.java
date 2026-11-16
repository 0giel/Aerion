package pl.aerion.screens.mixin;

import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.net.ScreenPayload;
import pl.aerion.screens.Relay;

/** Messages on `aerion:screen` from players in the game go to the relay, on the server thread. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class PayloadMixin {
	@Shadow
	@Final
	protected MinecraftServer server;

	@Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
	private void aerion$screen(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (!(packet.payload() instanceof ScreenPayload p)) return;
		ci.cancel();
		if ((Object) this instanceof ServerGamePacketListenerImpl game) {
			var player = game.player;
			byte[] data = p.data();
			server.executeIfPossible(() -> Relay.received(player, data));
		}
	}
}
