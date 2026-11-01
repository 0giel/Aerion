package pl.aerion.mod.mixin.version;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.platform.Gfx;

/** The Aerion HUD modules, drawn after the game's own HUD. */
@Mixin(Gui.class)
abstract class GuiMixin {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
	private void aerion$hud(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		pl.aerion.mod.hud.Hud.render(new Gfx(g), false);
	}
}
