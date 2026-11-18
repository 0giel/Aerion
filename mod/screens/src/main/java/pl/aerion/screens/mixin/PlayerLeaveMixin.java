package pl.aerion.screens.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.screens.Relay;

/** A player who leaves takes their shared windows along. */
@Mixin(PlayerList.class)
abstract class PlayerLeaveMixin {
	@Inject(method = "remove", at = @At("HEAD"))
	private void aerion$left(ServerPlayer player, CallbackInfo ci) {
		Relay.left(player);
	}
}
