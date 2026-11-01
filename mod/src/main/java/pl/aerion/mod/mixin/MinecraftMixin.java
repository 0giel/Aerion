package pl.aerion.mod.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void aerion$pollTheme(CallbackInfo ci) {
		AerionClient.tick();
	}
}
