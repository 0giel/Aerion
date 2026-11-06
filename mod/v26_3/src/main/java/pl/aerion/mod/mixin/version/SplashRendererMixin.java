package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.Splash;
import pl.aerion.mod.platform.Gfx;

/** The splash text in the theme's look instead of the game's yellow. */
@Mixin(SplashRenderer.class)
abstract class SplashRendererMixin {
	@Shadow
	@Final
	private Component splash;

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V", at = @At("HEAD"), cancellable = true)
	private void aerion$splash(GuiGraphicsExtractor g, int width, Font font, float alpha, CallbackInfo ci) {
		if (Splash.draw(new Gfx(g), width, splash.getString(), alpha)) ci.cancel();
	}
}
