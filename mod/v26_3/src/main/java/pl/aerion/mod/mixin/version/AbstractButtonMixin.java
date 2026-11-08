package pl.aerion.mod.mixin.version;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Motion;
import pl.aerion.mod.Theme;
import pl.aerion.mod.platform.Game;
import pl.aerion.mod.platform.Gfx;

/** Buttons: a flat themed body instead of the stone sprite; hover and focus use the accent. */
@Mixin(AbstractButton.class)
abstract class AbstractButtonMixin {
	/** How far the pointer's highlight has come, 0 (none) to 1 (full); eased over a short time. */
	@Unique private float aerion$hover;
	@Unique private long aerion$last;
	/** Entrance on the title screen: where the button belongs and when it first showed. */
	@Unique private int aerion$tx = Integer.MIN_VALUE;
	@Unique private int aerion$ty;
	@Unique private long aerion$born;
	@Unique private boolean aerion$entering;

	private static Screen aerion$enterFor;
	private static long aerion$enterAt;

	@Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
	private void aerion$button(GuiGraphicsExtractor g, CallbackInfo ci) {
		Draw.current = new Gfx(g);
		Theme t = AerionClient.colors();
		if (t == null) return;
		AbstractButton b = (AbstractButton) (Object) this;
		boolean motion = Motion.on();
		long now = System.nanoTime();
		float dt = aerion$last == 0 ? 0f : Math.min(0.1f, (now - aerion$last) / 1e9f);
		aerion$last = now;
		float target = b.isActive() && b.isHoveredOrFocused() ? 1f : 0f;
		// The highlight eases in and out instead of snapping.
		aerion$hover = motion ? aerion$hover + Math.max(-dt / 0.13f, Math.min(dt / 0.13f, target - aerion$hover)) : target;
		float k = Motion.smooth(aerion$hover);
		float shown = motion ? aerion$enter(b, now) : 1f;

		int a = Math.round(b.getAlpha() * shown * 255);
		int body;
		int edge;
		if (!b.isActive()) {
			body = Colors.alpha(Colors.forWhiteText(t.surface), a * 6 / 10);
			edge = Colors.alpha(t.border, Math.min(a, (t.border >>> 24) & 0xFF));
		} else {
			// A hint of the accent, so the theme shows even where surfaces are neutral grey; the
			// hover colours blend in with `k`.
			int rest = Colors.mix(t.surface2, t.accent, 0.12f), lit = Colors.mix(t.surface2, t.accent, 0.35f);
			body = Colors.alpha(Colors.forWhiteText(Colors.mix(rest, lit, k)), Math.round(a * (0.92f + 0.08f * k)));
			edge = Colors.alpha(Colors.mix(Colors.mix(t.surface2, t.accent, 0.4f), t.accent, k), a);
		}
		Gfx gfx = new Gfx(g);
		Draw.control(gfx, b.getX(), b.getY(), b.getWidth(), b.getHeight(), t.radius, edge, body);
		// A bar of the accent grows from the middle of the left edge (not on the small icon buttons).
		if (motion && k > 0.02f && b.getWidth() >= 60) {
			int half = Math.round((b.getHeight() / 2f - 4f) * k);
			int cy = b.getY() + b.getHeight() / 2;
			gfx.fill(b.getX() + 3, cy - half, b.getX() + 4, cy + half, Colors.alpha(t.accent, Math.round(a * k)));
		}
		ci.cancel();
	}

	/**
	 * The first time the title screen shows, its buttons slide in one after another, top to bottom:
	 * the left ones from the left, the right ones from the right, the rest from below. Returns how
	 * visible the button is (0..1). Buttons made later (a window resize) just appear.
	 */
	@Unique
	private float aerion$enter(AbstractButton b, long now) {
		Screen screen = Game.screen();
		if (!(screen instanceof TitleScreen)) return 1f;
		if (aerion$enterFor != screen) {
			aerion$enterFor = screen;
			aerion$enterAt = now;
		}
		if (aerion$tx == Integer.MIN_VALUE) {
			aerion$tx = b.getX();
			aerion$ty = b.getY();
			aerion$born = now;
			aerion$entering = now - aerion$enterAt < 1_200_000_000L;
		}
		if (!aerion$entering) return 1f;
		float delay = Math.max(0f, Math.min(0.32f, (aerion$ty - 60) * 0.0022f));
		float p = ((now - aerion$born) / 1e9f - delay) / 0.5f;
		if (p >= 1f) {
			aerion$entering = false;
			b.setX(aerion$tx);
			b.setY(aerion$ty);
			return 1f;
		}
		float e = 1f - Motion.spring(Math.max(0f, p));
		int third = screen.width / 3;
		if (aerion$tx + b.getWidth() / 2 < third) b.setX(aerion$tx - Math.round(e * 22));
		else if (aerion$tx + b.getWidth() / 2 > screen.width - third) b.setX(aerion$tx + Math.round(e * 22));
		else b.setY(aerion$ty + Math.round(e * 12));
		return Motion.out(Math.max(0f, p) * 1.4f);
	}

	/** Labels in smooth text; anything too long or in an unknown script stays with the game. */
	@Inject(method = "extractDefaultLabel", at = @At("HEAD"), cancellable = true)
	private void aerion$label(ActiveTextCollector out, CallbackInfo ci) {
		if (AerionClient.colors() == null || Draw.current == null) return;
		AbstractButton b = (AbstractButton) (Object) this;
		int a = Math.round(b.getAlpha() * 255);
		if (Draw.label(Draw.current, b.getMessage().getString(), b.getX(), b.getY(), b.getWidth(), b.getHeight(), b.isActive(), a)) ci.cancel();
	}
}
