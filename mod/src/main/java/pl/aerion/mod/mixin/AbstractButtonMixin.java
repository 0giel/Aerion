package pl.aerion.mod.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Theme;

/** Buttons: a flat themed body instead of the stone sprite; hover and focus use the accent. */
@Mixin(AbstractButton.class)
abstract class AbstractButtonMixin {
	@Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
	private void aerion$button(GuiGraphicsExtractor g, CallbackInfo ci) {
		Theme t = AerionClient.colors();
		if (t == null) return;
		AbstractButton b = (AbstractButton) (Object) this;
		int a = Math.round(b.getAlpha() * 255);
		int body;
		int edge;
		if (!b.isActive()) {
			body = Colors.alpha(Colors.forWhiteText(t.surface), a * 6 / 10);
			edge = Colors.alpha(t.border, Math.min(a, (t.border >>> 24) & 0xFF));
		} else if (b.isHoveredOrFocused()) {
			body = Colors.alpha(Colors.forWhiteText(Colors.mix(t.surface2, t.accent, 0.35f)), a);
			edge = Colors.alpha(t.accent, a);
		} else {
			// A hint of the accent, so the theme shows even where surfaces are neutral grey.
			body = Colors.alpha(Colors.forWhiteText(Colors.mix(t.surface2, t.accent, 0.12f)), a * 92 / 100);
			edge = Colors.alpha(Colors.mix(t.surface2, t.accent, 0.4f), a);
		}
		Draw.control(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), t.radius, edge, body);
		ci.cancel();
	}
}
