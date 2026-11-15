package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import pl.aerion.mod.GameLook;

/** The player list (Tab) in the theme's colours. */
@Mixin(PlayerTabOverlay.class)
abstract class TabListLookMixin {
	@ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"), index = 4)
	private int aerion$colour(int argb) {
		return GameLook.tabList(argb);
	}
}
