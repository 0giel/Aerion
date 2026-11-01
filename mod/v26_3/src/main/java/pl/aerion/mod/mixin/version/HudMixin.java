package pl.aerion.mod.mixin.version;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.resources.Identifier;
import pl.aerion.mod.features.ChatTweaks;
import pl.aerion.mod.features.Crosshair;
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

	@Inject(method = "extractSpyglassOverlay", at = @At("HEAD"), cancellable = true)
	private void aerion$spyglass(CallbackInfo ci) {
		if (Overlays.hidden("spyglass")) ci.cancel();
	}

	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void aerion$crosshair(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		if (Crosshair.draw(new Gfx(g))) ci.cancel();
	}

	@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
	private void aerion$effects(CallbackInfo ci) {
		if (pl.aerion.mod.hud.Hud.hidesGameEffects()) ci.cancel();
	}

	@Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
	private void aerion$bossBegin(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		if (!pl.aerion.mod.hud.Hud.beginElement(new Gfx(g), "bossbar")) ci.cancel();
	}

	@Inject(method = "extractBossOverlay", at = @At("RETURN"))
	private void aerion$bossEnd(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		pl.aerion.mod.hud.Hud.endElement(new Gfx(g), "bossbar");
	}

	@Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void aerion$scoreBegin(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		if (!pl.aerion.mod.hud.Hud.beginElement(new Gfx(g), "scoreboard")) ci.cancel();
	}

	@Inject(method = "extractScoreboardSidebar", at = @At("RETURN"))
	private void aerion$scoreEnd(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		pl.aerion.mod.hud.Hud.endElement(new Gfx(g), "scoreboard");
	}

	@Inject(method = "extractChat", at = @At("HEAD"), cancellable = true)
	private void aerion$chatBegin(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		if (!pl.aerion.mod.hud.Hud.beginElement(new Gfx(g), "chatbox")) ci.cancel();
	}

	@Inject(method = "extractChat", at = @At("RETURN"))
	private void aerion$chatEnd(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		pl.aerion.mod.hud.Hud.endElement(new Gfx(g), "chatbox");
	}

	/** Chat can stay across disconnects. */
	@WrapOperation(method = "onDisconnected", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;clearMessages(Z)V"))
	private void aerion$keepChat(ChatComponent chat, boolean history, Operation<Void> original) {
		if (!ChatTweaks.keepHistory()) original.call(chat, history);
	}
}
