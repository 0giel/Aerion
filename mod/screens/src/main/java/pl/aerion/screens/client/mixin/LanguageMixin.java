package pl.aerion.screens.client.mixin;

import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.screens.client.Lang;

/** The mod's texts, also without Fabric API (which the game needs to read mods' language files). */
@Mixin(ClientLanguage.class)
abstract class LanguageMixin {
	@Inject(method = "getOrDefault", at = @At("HEAD"), cancellable = true)
	private void aerionScreens$text(String key, String fallback, CallbackInfoReturnable<String> cir) {
		String t = Lang.get(key);
		if (t != null) cir.setReturnValue(t);
	}

	@Inject(method = "has", at = @At("HEAD"), cancellable = true)
	private void aerionScreens$has(String key, CallbackInfoReturnable<Boolean> cir) {
		if (Lang.get(key) != null) cir.setReturnValue(true);
	}
}
