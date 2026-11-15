package pl.aerion.mod.mixin.version;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.mirror.Mirrors;

/** Panels with the computer's windows join the world's entities. */
@Mixin(LevelRenderer.class)
abstract class MirrorRenderMixin {
	@Inject(method = "submitEntities", at = @At("TAIL"))
	private void aerion$mirrors(PoseStack pose, net.minecraft.client.renderer.state.LevelRenderState state, SubmitNodeCollector out, CallbackInfo ci) {
		try {
			Mirrors.submit(pose, out);
		} catch (RuntimeException e) {
			AerionClient.warnOnce("mirrors-render", e);
		}
	}
}
