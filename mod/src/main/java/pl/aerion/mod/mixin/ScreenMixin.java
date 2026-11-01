package pl.aerion.mod.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Theme;

/** Menu backgrounds: the theme's gradient instead of the panorama and the dark menu texture. */
@Mixin(Screen.class)
abstract class ScreenMixin {
	@Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
	private void aerion$panorama(GuiGraphicsExtractor g, float delta, CallbackInfo ci) {
		Theme t = AerionClient.theme();
		if (t == null) return;
		Draw.background(g, t, 0xFF);
		ci.cancel();
	}

	@Inject(
			method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V",
			at = @At("HEAD"),
			cancellable = true)
	private void aerion$menu(GuiGraphicsExtractor g, int x, int y, int w, int h, CallbackInfo ci) {
		Theme t = AerionClient.theme();
		if (t == null) return;
		g.fill(x, y, x + w, y + h, Colors.alpha(Draw.behindText(t, t.surface), 0x8C));
		ci.cancel();
	}

	/** Menus over a running world (pause, inventory-less screens): a tinted veil. */
	@Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true)
	private void aerion$veil(GuiGraphicsExtractor g, CallbackInfo ci) {
		Theme t = AerionClient.theme();
		if (t == null) return;
		Draw.background(g, t, 0xA0);
		ci.cancel();
	}
}
