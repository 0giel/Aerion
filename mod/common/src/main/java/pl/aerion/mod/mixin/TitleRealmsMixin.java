package pl.aerion.mod.mixin;

import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.MenuLook;

/** With the Realms button hidden, its news and invitation icons (the newspaper) go too. */
@Mixin(TitleScreen.class)
abstract class TitleRealmsMixin {
	@Inject(method = "realmsNotificationsEnabled", at = @At("HEAD"), cancellable = true)
	private void aerion$noRealmsNews(CallbackInfoReturnable<Boolean> cir) {
		if (MenuLook.hidden("realms")) cir.setReturnValue(false);
	}
}
