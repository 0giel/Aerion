package pl.aerion.screens.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/** Texts in the game's language (assets/aerion_screens/lang). */
final class Text {
	private Text() {}

	static String tr(String key, Object... args) {
		String t = Lang.get(key);
		return t != null && args.length == 0 ? t : I18n.get(key, args);
	}

	static Component c(String key, Object... args) {
		return Component.translatable(key, args);
	}
}
