package pl.aerion.mod.platform;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;

/** Something drawn as one of a screen's own parts, so it sits in order among the widgets. */
public final class Painter {
	private Painter() {}

	public static Renderable of(Consumer<Gfx> paint) {
		return new Renderable() {
			@Override
			public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
				paint.accept(new Gfx(g));
			}
		};
	}
}
