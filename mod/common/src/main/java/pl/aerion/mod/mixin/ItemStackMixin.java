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

/** Food values in item tooltips; the inside of shulker boxes as a grid. */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {
	@Inject(method = "getTooltipLines", at = @At("HEAD"))
	private void aerion$building(Item.TooltipContext context, Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
		pl.aerion.mod.features.ShulkerPreview.building((ItemStack) (Object) this);
	}

	@Inject(method = "getTooltipLines", at = @At("RETURN"))
	private void aerion$food(Item.TooltipContext context, Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
		pl.aerion.mod.features.ShulkerPreview.building(null);
		pl.aerion.mod.features.ShulkerPreview.hint((ItemStack) (Object) this, cir.getReturnValue());
		FoodOverlay.tooltip((ItemStack) (Object) this, cir.getReturnValue());
	}

	@Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
	private void aerion$shulker(CallbackInfoReturnable<java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent>> cir) {
		if (cir.getReturnValue().isEmpty()) {
			var grid = pl.aerion.mod.features.ShulkerPreview.image((ItemStack) (Object) this);
			if (grid.isPresent()) cir.setReturnValue(grid);
		}
	}
}
