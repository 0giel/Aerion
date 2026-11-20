package pl.aerion.screens.client.ui;

import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import pl.aerion.screens.client.Keys;
import pl.aerion.screens.client.Mirrors;
import pl.aerion.screens.client.Platform;
import pl.aerion.screens.client.WindowSource;

/** Everything typed goes to the window being looked at, until Esc. */
public final class TypeScreen extends Screen {
	private final long win;
	private final String name;
	private boolean held, asked;

	public TypeScreen(long win, String name) {
		super(Component.translatable("aerion_screens.typing", name));
		this.win = win;
		this.name = name;
	}

	@Override
	protected void init() {
		if (!asked) {
			asked = true;
			// The window takes the keyboard: every key and shortcut goes straight to it until Esc.
			WindowSource src = Mirrors.source();
			held = src != null && src.holdKeyboard(win, () -> net.minecraft.client.Minecraft.getInstance().execute(() -> Platform.setScreen(null)));
		}
		Platform.startTextInput(this);
		Component text = held ? Component.translatable("aerion_screens.typingHeld", name) : title;
		addRenderableWidget(new MultiLineTextWidget(10, height - 40, text, font).setMaxWidth(width - 20));
	}

	@Override
	public void removed() {
		Platform.stopTextInput(this);
		WindowSource src = Mirrors.source();
		if (held && src != null) src.releaseKeyboard();
		held = false;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int mods = e.modifiers() & 0xF;
		WindowSource src = Mirrors.source();
		// Esc ends typing; Shift+Esc sends Esc to the window.
		if (e.key() == InputConstants.KEY_ESCAPE && (mods & WindowSource.SHIFT) == 0) {
			onClose();
			return true;
		}
		String name = e.key() == InputConstants.KEY_ESCAPE ? "escape" : Keys.toSend(e.key(), mods);
		if (name == null || src == null) return false;
		src.key(win, name, e.key() == InputConstants.KEY_ESCAPE ? mods & ~WindowSource.SHIFT : mods);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		WindowSource src = Mirrors.source();
		if (src != null) src.type(win, e.codepointAsString());
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
