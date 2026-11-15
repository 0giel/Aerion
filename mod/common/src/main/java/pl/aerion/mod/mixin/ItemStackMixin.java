package pl.aerion.mod.mixin;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.FoodOverlay;

/** Food values in item tooltips. */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {
	@Inject(method = "getTooltipLines", at = @At("RETURN"))
	private void aerion$food(Item.TooltipContext context, Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
		FoodOverlay.tooltip((ItemStack) (Object) this, cir.getReturnValue());
	}
}
