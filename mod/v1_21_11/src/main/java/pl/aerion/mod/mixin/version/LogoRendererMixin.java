package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LogoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Logo;
import pl.aerion.mod.Theme;
import pl.aerion.mod.platform.Gfx;

/** The Aerion logo instead of "Minecraft: Java Edition", when the player turned it on. */
@Mixin(LogoRenderer.class)
abstract class LogoRendererMixin {
	@Inject(
			method = "renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IFI)V",
			at = @At("HEAD"),
			cancellable = true)
	private void aerion$logo(GuiGraphics g, int width, float alpha, int top, CallbackInfo ci) {
		Theme t = AerionClient.theme();
		if (t == null || !t.logo) return;
		if (Logo.draw(new Gfx(g), t, width, top, alpha)) ci.cancel();
	}
}
