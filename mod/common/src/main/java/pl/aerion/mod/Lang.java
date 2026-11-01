package pl.aerion.mod;

import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * The mod's own texts (key names, editor hints) in Polish and English. Without Fabric API mod
 * language files are not loaded, so a small mixin on the game's language asks here first.
 */
public final class Lang {
	private Lang() {}

	private static final Map<String, String[]> TEXTS = Map.ofEntries(
			// key: { English, Polish }
			Map.entry("key.category.aerion.main", new String[] {"Aerion", "Aerion"}),
			Map.entry("key.aerion.hud_editor", new String[] {"Edit HUD", "Edytuj HUD"}),
			Map.entry("key.aerion.zoom", new String[] {"Zoom", "Przybliżenie"}),
			Map.entry("key.aerion.freelook", new String[] {"Freelook", "Swobodne rozglądanie"}),
			Map.entry("key.aerion.fullbright", new String[] {"Fullbright on/off", "Pełna jasność wł./wył."}),
			Map.entry("key.aerion.toggle_sprint", new String[] {"Toggle sprint", "Przełącz bieg"}),
			Map.entry("key.aerion.toggle_sneak", new String[] {"Toggle sneak", "Przełącz skradanie"}),
			Map.entry("aerion.editor.hint", new String[] {
				"Drag modules to move them, scroll to resize. More options in the Aerion launcher.",
				"Przeciągaj moduły, kółkiem zmieniaj rozmiar. Więcej opcji w launcherze Aerion."}),
			Map.entry("aerion.editor.done", new String[] {"Done", "Gotowe"}),
			Map.entry("aerion.editor.empty", new String[] {
				"All HUD modules are off. Turn some on in the Aerion launcher.",
				"Wszystkie moduły HUD są wyłączone. Włącz je w launcherze Aerion."}),
			Map.entry("aerion.toggle.sprint", new String[] {"Sprinting (toggled)", "Bieg (przełączony)"}),
			Map.entry("aerion.toggle.sneak", new String[] {"Sneaking (toggled)", "Skradanie (przełączone)"}),
			Map.entry("aerion.toggle.fullbright", new String[] {"Fullbright", "Pełna jasność"}),
			Map.entry("aerion.toggle.freelook", new String[] {"Freelook", "Swobodne rozglądanie"}));

	private static boolean polish() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.options != null && mc.options.languageCode != null && mc.options.languageCode.startsWith("pl");
	}

	/** The text for `key`, or null when it isn't one of the mod's. */
	public static String get(String key) {
		String[] t = TEXTS.get(key);
		return t == null ? null : t[polish() ? 1 : 0];
	}

	public static String tr(String key) {
		String t = get(key);
		return t == null ? key : t;
	}
}
