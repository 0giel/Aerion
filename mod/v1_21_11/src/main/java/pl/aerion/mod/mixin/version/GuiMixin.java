package pl.aerion.mod.mixin.version;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.resources.Identifier;
import pl.aerion.mod.features.Crosshair;
import pl.aerion.mod.features.Overlays;
import pl.aerion.mod.platform.Gfx;

/** The Aerion HUD modules, drawn after the game's own HUD. */
@Mixin(Gui.class)
abstract class GuiMixin {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
	private void aerion$hud(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		pl.aerion.mod.hud.Hud.render(new Gfx(g), false);
	}

	@Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
	private void aerion$vignette(CallbackInfo ci) {
		if (Overlays.hidden("vignette")) ci.cancel();
	}

	@Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
	private void aerion$texture(GuiGraphics g, Identifier texture, float alpha, CallbackInfo ci) {
		if (Overlays.textureHidden(texture.getPath())) ci.cancel();
	}

	@Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
	private void aerion$portal(CallbackInfo ci) {
		if (Overlays.hidden("portal")) ci.cancel();
	}

	@Inject(method = "renderConfusionOverlay", at = @At("HEAD"), cancellable = true)
	private void aerion$nausea(CallbackInfo ci) {
		if (Overlays.hidden("nausea")) ci.cancel();
	}

	@Inject(method = "renderSpyglassOverlay", at = @At("HEAD"), cancellable = true)
	private void aerion$spyglass(CallbackInfo ci) {
		if (Overlays.hidden("spyglass")) ci.cancel();
	}

	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
	private void aerion$crosshair(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		if (Crosshair.draw(new Gfx(g))) ci.cancel();
	}

	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
	private void aerion$effects(CallbackInfo ci) {
		if (pl.aerion.mod.hud.Hud.hidesGameEffects()) ci.cancel();
	}
}
