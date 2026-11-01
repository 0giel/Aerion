package pl.aerion.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Theme;

/** Sliders: a themed track and an accent-coloured handle. */
@Mixin(AbstractSliderButton.class)
abstract class AbstractSliderButtonMixin {
	private static final String BLIT =
			"Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V";

	@WrapOperation(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = BLIT, ordinal = 0))
	private void aerion$track(
			GuiGraphicsExtractor g, RenderPipeline p, Identifier s, int x, int y, int w, int h, int c, Operation<Void> original) {
		Theme t = AerionClient.theme();
		if (t == null) {
			original.call(g, p, s, x, y, w, h, c);
			return;
		}
		AbstractSliderButton b = (AbstractSliderButton) (Object) this;
		int a = (c >>> 24) & 0xFF;
		int edge = b.isHoveredOrFocused() ? t.accent : Colors.mix(t.surface2, t.accent, 0.4f);
		Draw.control(g, x, y, w, h, t.radius, Colors.alpha(edge, a), Colors.alpha(Colors.forWhiteText(t.surface), a * 92 / 100));
	}

	@WrapOperation(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = BLIT, ordinal = 1))
	private void aerion$handle(
			GuiGraphicsExtractor g, RenderPipeline p, Identifier s, int x, int y, int w, int h, int c, Operation<Void> original) {
		Theme t = AerionClient.theme();
		if (t == null) {
			original.call(g, p, s, x, y, w, h, c);
			return;
		}
		int a = (c >>> 24) & 0xFF;
		Draw.rounded(g, x + 1, y + 1, w - 2, h - 2, t.radius, Colors.alpha(t.accent, a));
	}
}
