package pl.aerion.mod.mixin.version;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.features.BlockOutline;

/** The game's block outline gives way to the player's own. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Inject(method = "renderBlockOutline", at = @At("HEAD"), cancellable = true)
	private void aerion$outline(CallbackInfo ci) {
		if (BlockOutline.active()) ci.cancel();
	}
}
