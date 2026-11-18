package pl.aerion.screens.client.ui;

import java.util.Map;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import pl.aerion.screens.client.Mirrors;
import pl.aerion.screens.client.Platform;
import pl.aerion.screens.client.WindowSource;

/** Everything typed goes to the window being looked at, until Esc. */
public final class TypeScreen extends Screen {
	private static final Map<Integer, String> KEYS = Map.of(InputConstants.KEY_RETURN, "enter", InputConstants.KEY_NUMPADENTER, "enter", InputConstants.KEY_BACKSPACE, "backspace", InputConstants.KEY_TAB, "tab",
			InputConstants.KEY_LEFT, "left", InputConstants.KEY_RIGHT, "right", InputConstants.KEY_UP, "up", InputConstants.KEY_DOWN, "down");
	private final long win;

	public TypeScreen(long win, String name) {
		super(Component.translatable("aerion_screens.typing", name));
		this.win = win;
	}

	@Override
	protected void init() {
		Platform.startTextInput(this);
		addRenderableWidget(new MultiLineTextWidget(10, height - 40, title, font).setMaxWidth(width - 20));
	}

	@Override
	public void removed() {
		Platform.stopTextInput(this);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_ESCAPE) {
			onClose();
			return true;
		}
		String name = KEYS.get(e.key());
		WindowSource src = Mirrors.source();
		if (name != null && src != null) {
			src.key(win, name);
			return true;
		}
		return false;
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
