package pl.aerion.mod.mixin;

import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.Lang;

/** The mod's key and category names (mod language files need Fabric API to load). */
@Mixin(ClientLanguage.class)
abstract class ClientLanguageMixin {
	@Inject(method = "getOrDefault", at = @At("HEAD"), cancellable = true)
	private void aerion$text(String key, String fallback, CallbackInfoReturnable<String> cir) {
		String t = Lang.get(key);
		if (t != null) cir.setReturnValue(t);
	}

	@Inject(method = "has", at = @At("HEAD"), cancellable = true)
	private void aerion$has(String key, CallbackInfoReturnable<Boolean> cir) {
		if (Lang.get(key) != null) cir.setReturnValue(true);
	}
}
