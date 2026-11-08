package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import pl.aerion.mod.Containers;

/** Inventories in the theme's colours: the panel pictures are tinted and dark titles recoloured. */
@Mixin(GuiGraphics.class)
abstract class GuiGraphicsMixin {
	@ModifyArgs(
			method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;innerBlit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIFFFFI)V"))
	private void aerion$tint(Args args) {
		args.set(10, Containers.tint((Identifier) args.get(1), (int) args.get(10)));
	}

	@ModifyVariable(
			method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
			at = @At("HEAD"),
			argsOnly = true,
			ordinal = 2)
	private int aerion$title(int color) {
		return Containers.text(color);
	}
}
