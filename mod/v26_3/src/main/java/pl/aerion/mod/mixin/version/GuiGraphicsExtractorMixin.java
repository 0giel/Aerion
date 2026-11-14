package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

	/** Progress arrows, flames and bubbles drawn partly (as far as the work has got): from the
	 *  recoloured picture of the sprite, so they match the themed panel. */
	@Inject(method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIIII)V", at = @At("HEAD"), cancellable = true)
	private void aerion$partialSprite(RenderPipeline pipeline, Identifier sprite, int spriteW, int spriteH, int u, int v, int x, int y, int w, int h, int color, CallbackInfo ci) {
		Identifier made = Containers.spriteTexture(sprite);
		if (made == null) return;
		((GuiGraphicsExtractor) (Object) this).blit(pipeline, made, x, y, (float) u, (float) v, w, h, spriteW, spriteH, color);
		ci.cancel();
	}

	/** The same for such sprites drawn whole (the villager's trade arrow). */
	@Inject(method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true)
	private void aerion$wholeSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color, CallbackInfo ci) {
		Identifier made = Containers.spriteTexture(sprite);
		if (made == null) return;
		((GuiGraphicsExtractor) (Object) this).blit(pipeline, made, x, y, 0f, 0f, w, h, w, h, color);
		ci.cancel();
	}
}
