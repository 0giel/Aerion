package pl.aerion.screens.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.screens.Relay;

/** Who sees which windows is worked out a few times a second. */
@Mixin(MinecraftServer.class)
abstract class ServerTickMixin {
	@Inject(method = "tickServer", at = @At("TAIL"))
	private void aerion$screens(BooleanSupplier hasTime, CallbackInfo ci) {
		Relay.tick((MinecraftServer) (Object) this);
	}
}
