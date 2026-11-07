package pl.aerion.mod.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.Badges;

/** Badges in front of the names above players' heads. */
@Mixin(EntityRenderer.class)
abstract class NameTagMixin {
	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void aerion$badge(Entity entity, CallbackInfoReturnable<Component> cir) {
		if (entity instanceof Player p) cir.setReturnValue(Badges.decorate(p.getUUID(), cir.getReturnValue()));
	}
}
