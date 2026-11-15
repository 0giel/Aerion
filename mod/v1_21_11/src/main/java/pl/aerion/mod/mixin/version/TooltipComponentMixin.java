package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.aerion.mod.features.ShulkerPreview;
import pl.aerion.mod.platform.ShulkerTooltip;

/** Draws Aerion's own tooltip parts (the shulker box grid). */
@Mixin(ClientTooltipComponent.class)
interface TooltipComponentMixin {
	@Inject(method = "create(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;", at = @At("HEAD"), cancellable = true)
	private static void aerion$own(TooltipComponent data, CallbackInfoReturnable<ClientTooltipComponent> cir) {
		if (data instanceof ShulkerPreview.Contents c) cir.setReturnValue(new ShulkerTooltip(c));
	}
}
