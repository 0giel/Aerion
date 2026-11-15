package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.GameLook;
import pl.aerion.mod.platform.Gfx;

/** Item tooltips in the theme's colours. */
@Mixin(TooltipRenderUtil.class)
abstract class TooltipLookMixin {
	@Inject(method = "extractTooltipBackground", at = @At("HEAD"), cancellable = true)
	private static void aerion$look(GuiGraphicsExtractor g, int x, int y, int w, int h, Identifier style, CallbackInfo ci) {
		if (GameLook.tooltip(new Gfx(g), x, y, w, h)) ci.cancel();
	}
}
