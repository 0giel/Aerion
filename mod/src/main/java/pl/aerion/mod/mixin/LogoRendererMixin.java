package pl.aerion.mod.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Logo;
import pl.aerion.mod.Theme;

/** The Aerion logo instead of "Minecraft: Java Edition", when the player turned it on. */
@Mixin(LogoRenderer.class)
abstract class LogoRendererMixin {
	@Inject(
			method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IFI)V",
			at = @At("HEAD"),
			cancellable = true)
	private void aerion$logo(GuiGraphicsExtractor g, int width, float alpha, int top, CallbackInfo ci) {
		Theme t = AerionClient.theme();
		if (t == null || !t.logo) return;
		Logo.draw(g, t, width, top, alpha);
		ci.cancel();
	}
}
