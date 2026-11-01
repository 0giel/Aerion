package pl.aerion.mod.mixin.version;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.resources.Identifier;
import pl.aerion.mod.features.Overlays;
import pl.aerion.mod.platform.Gfx;

/** The Aerion HUD modules, drawn after the game's own HUD. */
@Mixin(Hud.class)
abstract class HudMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
	private void aerion$hud(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		pl.aerion.mod.hud.Hud.render(new Gfx(g), false);
	}

	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void aerion$vignette(CallbackInfo ci) {
		if (Overlays.hidden("vignette")) ci.cancel();
	}

	@Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
	private void aerion$texture(GuiGraphicsExtractor g, Identifier texture, float alpha, CallbackInfo ci) {
		if (Overlays.textureHidden(texture.getPath())) ci.cancel();
	}

	@Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
	private void aerion$portal(CallbackInfo ci) {
		if (Overlays.hidden("portal")) ci.cancel();
	}

	@Inject(method = "extractConfusionOverlay", at = @At("HEAD"), cancellable = true)
	private void aerion$nausea(CallbackInfo ci) {
		if (Overlays.hidden("nausea")) ci.cancel();
	}
}
