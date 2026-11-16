package pl.aerion.net.mixin;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.net.ScreenPayload;

/**
 * Channels the game doesn't know get a codec that drops their body; `aerion:screen` gets its own
 * instead (both ways: the game looks the codec up here for sending too).
 */
@Mixin(DiscardedPayload.class)
abstract class DiscardedPayloadMixin {
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "codec(Lnet/minecraft/resources/Identifier;I)Lnet/minecraft/network/codec/StreamCodec;", at = @At("HEAD"), cancellable = true)
	private static void aerion$screen(Identifier id, int max, CallbackInfoReturnable<StreamCodec> cir) {
		if (ScreenPayload.ID.equals(id)) cir.setReturnValue((StreamCodec) ScreenPayload.CODEC);
	}
}
