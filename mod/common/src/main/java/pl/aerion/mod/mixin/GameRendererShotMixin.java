package pl.aerion.mod.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.MenuScene;

/** The world as it is drawn, before the HUD goes over it: the picture for the menus. */
@Mixin(GameRenderer.class)
abstract class GameRendererShotMixin {
	@Inject(method = "renderLevel", at = @At("TAIL"))
	private void aerion$menuShot(CallbackInfo ci) {
		MenuScene.afterLevel();
	}
}
