package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import pl.aerion.mod.Containers;

/** Inventories in the theme's colours: the panel pictures are tinted and dark titles recoloured. */
@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsExtractorMixin {
	@ModifyArgs(
			method = "blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;innerBlit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIFFFFI)V"))
	private void aerion$recolour(Args args) {
		args.set(1, Containers.texture((Identifier) args.get(1)));
	}

	@ModifyArgs(
			method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;IIIII)V"))
	private void aerion$tintSprite(Args args) {
		args.set(6, Containers.tintSprite(((TextureAtlasSprite) args.get(1)).contents().name(), (int) args.get(6)));
	}

	@ModifyArgs(
			method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitNineSlicedSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/client/resources/metadata/gui/GuiSpriteScaling$NineSlice;IIIII)V"))
	private void aerion$tintNineSlice(Args args) {
		args.set(7, Containers.tintSprite(((TextureAtlasSprite) args.get(1)).contents().name(), (int) args.get(7)));
	}

	@ModifyVariable(
			method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
			at = @At("HEAD"),
			argsOnly = true,
			ordinal = 2)
	private int aerion$title(int color) {
		return Containers.text(color);
	}
}
