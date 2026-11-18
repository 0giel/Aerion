package pl.aerion.screens.client;

import pl.aerion.screens.client.ui.PickerScreen;
import pl.aerion.screens.client.ui.TypeScreen;

/** Aerion Screens' own menus, in the game's look. */
final class VanillaUi implements Screens.Ui {
	static final VanillaUi INSTANCE = new VanillaUi();

	private VanillaUi() {}

	@Override
	public void openPicker() {
		Platform.setScreen(new PickerScreen(null));
	}

	@Override
	public void openTyping(long window, String title) {
		Platform.setScreen(new TypeScreen(window, title));
	}
}
