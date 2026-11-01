package pl.aerion.mod.platform;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import pl.aerion.mod.hud.HudEditor;

/** The in-game HUD editor for Minecraft 26.x; the logic lives in {@link HudEditor}. */
public final class EditorScreen extends Screen {
	private final HudEditor editor = new HudEditor();

	public EditorScreen() {
		super(Component.literal("Aerion HUD"));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		editor.render(new Gfx(g), mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (editor.pressed(e.x(), e.y(), e.button())) onClose();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		editor.dragged(e.x(), e.y(), width, height);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		editor.released();
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double h, double v) {
		editor.scrolled(x, y, v);
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		editor.close();
		super.onClose();
	}
}
