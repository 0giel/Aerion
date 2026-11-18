package pl.aerion.screens.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.screens.client.Mirrors;
import pl.aerion.screens.client.Screens;

/** Panels with the computer's windows join the world's entities. */
@Mixin(LevelRenderer.class)
abstract class RenderMixin {
	@Inject(method = "submitEntities", at = @At("TAIL"))
	private void aerionScreens$panels(PoseStack pose, net.minecraft.client.renderer.state.LevelRenderState state, SubmitNodeCollector out, CallbackInfo ci) {
		try {
			Mirrors.submit(pose, out);
		} catch (RuntimeException e) {
			Screens.warnOnce("render", e);
		}
	}
}
