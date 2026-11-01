package pl.aerion.mod.mixin.version;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.features.Overlays;

/** Fire, water and in-block overlays, drawn in the world pass. */
@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererMixin {
	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void aerion$fireStart(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		float drop = Overlays.fireDrop();
		if (drop >= 1f) {
			ci.cancel();
			return;
		}
		pose.pushPose();
		pose.translate(0f, -0.9f * drop, 0f);
	}

	@Inject(method = "submitFire", at = @At("RETURN"))
	private static void aerion$fireEnd(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		pose.popPose();
	}

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void aerion$water(CallbackInfo ci) {
		if (Overlays.hidden("water")) ci.cancel();
	}

	@Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
	private static void aerion$block(CallbackInfo ci) {
		if (Overlays.hidden("block")) ci.cancel();
	}
}
