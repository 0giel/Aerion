package pl.aerion.mod.platform;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import pl.aerion.mod.hud.Page;

/** Aerion's in-game screen, for Minecraft 1.21.11; what it shows lives in a {@link Page}. */
public final class EditorScreen extends Screen {
	private Page page;
	private final Screen parent;

	public EditorScreen(Page page, Screen parent) {
		super(Component.literal("Aerion"));
		this.page = page;
		this.parent = parent;
		// The game draws toasts over every screen, here right over the menu.
		Game.clearToasts();
	}

	public void show(Page next) {
		page.close();
		page = next;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		page.render(new Gfx(g), mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (page.pressed(e.x(), e.y(), e.button())) onClose();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		page.dragged(e.x(), e.y(), width, height);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		page.released();
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double h, double v) {
		page.scrolled(x, y, v);
		return true;
	}

	@Override
	public void onClose() {
		page.close();
		Game.setScreen(parent);
	}
}
