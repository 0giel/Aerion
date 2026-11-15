package pl.aerion.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.OwnName;

/** The player's own name above their head in third person, when that feature is on. */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"), cancellable = true)
	private void aerion$ownName(Avatar entity, double distance, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && entity == Minecraft.getInstance().player && OwnName.show()) cir.setReturnValue(true);
	}
}
