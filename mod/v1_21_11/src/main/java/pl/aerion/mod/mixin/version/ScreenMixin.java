package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Particles;
import pl.aerion.mod.Theme;
import pl.aerion.mod.features.Screens;
import pl.aerion.mod.platform.Gfx;

/** Menu backgrounds: the theme's gradient instead of the panorama and the dark menu texture. */
@Mixin(Screen.class)
abstract class ScreenMixin {
	@Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true)
	private void aerion$panorama(GuiGraphics g, float delta, CallbackInfo ci) {
		Theme t = AerionClient.colors();
		if (t == null) return;
		Draw.background(new Gfx(g), t, 0xFF);
		Particles.draw(new Gfx(g), t);
		ci.cancel();
	}

	/** Particles over the game's own panorama when theme colours are off. */
	@Inject(method = "renderPanorama", at = @At("TAIL"))
	private void aerion$panoramaParticles(GuiGraphics g, float delta, CallbackInfo ci) {
		Theme t = AerionClient.theme();
		if (t != null && !t.colors) Particles.draw(new Gfx(g), t);
	}

	@Inject(
			method = "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;IIII)V",
			at = @At("HEAD"),
			cancellable = true)
	private void aerion$menu(GuiGraphics g, int x, int y, int w, int h, CallbackInfo ci) {
		if (Screens.drawPanel(new Gfx(g), x, y, w, h)) {
			ci.cancel();
			return;
		}
		Theme t = AerionClient.colors();
		if (t == null) return;
		g.fill(x, y, x + w, y + h, Colors.alpha(Draw.behindText(t, t.surface), 0x8C));
		ci.cancel();
	}

	/** Menus over a running world (pause, inventory-less screens): a tinted veil. */
	@Inject(method = "renderTransparentBackground", at = @At("HEAD"), cancellable = true)
	private void aerion$veil(GuiGraphics g, CallbackInfo ci) {
		if (Screens.drawVeil(new Gfx(g))) {
			ci.cancel();
			return;
		}
		Theme t = AerionClient.colors();
		if (t == null) return;
		Draw.background(new Gfx(g), t, 0xA0);
		ci.cancel();
	}

	@Inject(method = "renderBlurredBackground", at = @At("HEAD"), cancellable = true)
	private void aerion$blur(CallbackInfo ci) {
		if (Screens.noBlur()) ci.cancel();
	}
}
