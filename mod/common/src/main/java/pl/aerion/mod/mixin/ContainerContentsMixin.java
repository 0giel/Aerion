package pl.aerion.mod.mixin;

import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.features.ShulkerPreview;

/** A shulker box shown as a grid leaves out the game's list of the first five item names. */
@Mixin(ItemContainerContents.class)
abstract class ContainerContentsMixin {
	@Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
	private void aerion$grid(Item.TooltipContext context, Consumer<Component> out, TooltipFlag flag, DataComponentGetter item, CallbackInfo ci) {
		// The game passes the stack's components here, not the stack: the stack is noted while its
		// tooltip is built.
		ItemStack stack = ShulkerPreview.building();
		if (stack != null && ShulkerPreview.shows(stack)) ci.cancel();
	}
}
