package pl.aerion.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import pl.aerion.mod.GameLook;

/** The chat's backgrounds (closed and open) in the theme's colours. */
@Mixin(targets = {
		"net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess",
		"net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess"})
abstract class ChatBackgroundMixin {
	@ModifyVariable(method = "fill", at = @At("HEAD"), argsOnly = true, ordinal = 4)
	private int aerion$colour(int argb) {
		return GameLook.chat(argb);
	}
}
