package pl.aerion.mod.platform;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import pl.aerion.mod.features.Features;
import pl.aerion.mod.hud.Page;

/** Aerion's in-game screen, for Minecraft 26.x; what it shows lives in a {@link Page}. */
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
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
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

	// 26.x delivers typed characters only while a screen asks for text input (the sound search).
	@Override
	protected void init() {
		Minecraft.getInstance().textInputManager().startTextInput(this);
	}

	@Override
	public void removed() {
		Minecraft.getInstance().textInputManager().stopTextInput(this);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		return page.charTyped(e.codepoint()) || super.charTyped(e);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		InputConstants.Key key = InputConstants.getKey(e);
		if (page.keyPressed(key)) return true;
		if (Features.isMenuKey(key)) {
			onClose();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public void onClose() {
		page.close();
		Game.setScreen(parent);
	}
}
