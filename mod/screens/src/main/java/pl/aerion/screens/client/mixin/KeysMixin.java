package pl.aerion.screens.client.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.screens.client.ScreensClient;

/** The windows' keys join the game's list before options.txt is read, so bindings are saved. */
@Mixin(Options.class)
abstract class KeysMixin {
	@Shadow
	@Final
	@Mutable
	public KeyMapping[] keyMappings;

	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;load()V"))
	private void aerionScreens$keys(CallbackInfo ci) {
		KeyMapping[] own = ScreensClient.keys();
		KeyMapping[] all = new KeyMapping[keyMappings.length + own.length];
		System.arraycopy(keyMappings, 0, all, 0, keyMappings.length);
		System.arraycopy(own, 0, all, keyMappings.length, own.length);
		keyMappings = all;
	}
}
