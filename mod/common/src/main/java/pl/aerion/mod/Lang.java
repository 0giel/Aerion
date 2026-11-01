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
				"Drag modules, scroll to resize",
				"Przeciągaj moduły, kółkiem zmieniaj rozmiar"}),
			Map.entry("aerion.editor.done", new String[] {"Done", "Gotowe"}),
			Map.entry("aerion.editor.empty", new String[] {
				"All modules are off. Turn them on in the panel.",
				"Wszystkie moduły są wyłączone. Włącz je w panelu."}),
			Map.entry("aerion.editor.panel", new String[] {"Panel", "Panel"}),
			Map.entry("aerion.panel.modules", new String[] {"Modules", "Moduły"}),
			Map.entry("aerion.panel.module", new String[] {"Module", "Moduł"}),
			Map.entry("aerion.panel.look", new String[] {"Look", "Wygląd"}),
			Map.entry("aerion.panel.hud", new String[] {"Show HUD", "Pokazuj HUD"}),
			Map.entry("aerion.panel.features", new String[] {"Game features", "Funkcje gry"}),
			Map.entry("aerion.panel.enabled", new String[] {"Turned on", "Włączony"}),
			Map.entry("aerion.panel.scale", new String[] {"Size", "Rozmiar"}),
			Map.entry("aerion.panel.ownLook", new String[] {"Own look", "Własny wygląd"}),
			Map.entry("aerion.panel.ownLookHint", new String[] {"Off: uses the shared look from the Look tab.", "Wyłączone: moduł używa wspólnego wyglądu z zakładki Wygląd."}),
			Map.entry("aerion.panel.lookTitle", new String[] {"Shared by all modules", "Wspólny dla wszystkich modułów"}),
			Map.entry("aerion.panel.formatHint", new String[] {"Text format can be changed in the launcher.", "Format tekstu zmienisz w launcherze."}),
			Map.entry("aerion.panel.serverWarning", new String[] {"Some servers forbid freelook and fullbright. Check the rules first.", "Niektóre serwery zabraniają freelooka i pełnej jasności. Najpierw sprawdź regulamin."}),
			Map.entry("aerion.module.fps", new String[] {"FPS", "FPS"}),
			Map.entry("aerion.module.coords", new String[] {"Coordinates", "Współrzędne"}),
			Map.entry("aerion.module.direction", new String[] {"Direction", "Kierunek"}),
			Map.entry("aerion.module.cps", new String[] {"CPS", "CPS"}),
			Map.entry("aerion.module.keystrokes", new String[] {"Keystrokes", "Klawisze"}),
			Map.entry("aerion.module.armor", new String[] {"Armor", "Zbroja"}),
			Map.entry("aerion.module.potions", new String[] {"Potion effects", "Efekty mikstur"}),
			Map.entry("aerion.module.ping", new String[] {"Ping", "Ping"}),
			Map.entry("aerion.module.clock", new String[] {"Clock", "Zegar"}),
			Map.entry("aerion.module.memory", new String[] {"Memory", "Pamięć"}),
			Map.entry("aerion.module.minimap", new String[] {"Minimap", "Minimapa"}),
			Map.entry("aerion.module.toggles", new String[] {"Active toggles", "Przełączniki"}),
			Map.entry("aerion.feature.zoom", new String[] {"Zoom", "Zoom"}),
			Map.entry("aerion.feature.zoomFactor", new String[] {"Strength", "Siła"}),
			Map.entry("aerion.feature.zoomSmooth", new String[] {"Smooth zoom", "Płynny zoom"}),
			Map.entry("aerion.feature.toggleSprint", new String[] {"Toggle sprint", "Stały sprint"}),
			Map.entry("aerion.feature.toggleSneak", new String[] {"Toggle sneak", "Stałe skradanie"}),
			Map.entry("aerion.feature.freelook", new String[] {"Freelook", "Freelook"}),
			Map.entry("aerion.feature.freelookHold", new String[] {"Hold the key", "Trzymaj klawisz"}),
			Map.entry("aerion.feature.fullbright", new String[] {"Fullbright", "Pełna jasność"}),
			Map.entry("aerion.opt.coords.direction", new String[] {"Show direction", "Pokaż kierunek"}),
			Map.entry("aerion.opt.coords.decimals", new String[] {"Decimals", "Po przecinku"}),
			Map.entry("aerion.opt.coords.nether", new String[] {"Nether position", "Pozycja w Netherze"}),
			Map.entry("aerion.opt.direction.degrees", new String[] {"Show degrees", "Pokaż stopnie"}),
			Map.entry("aerion.opt.clock.h24", new String[] {"24-hour clock", "Zegar 24-godzinny"}),
			Map.entry("aerion.opt.clock.seconds", new String[] {"Seconds", "Sekundy"}),
			Map.entry("aerion.opt.potions.duration", new String[] {"Show time", "Pokaż czas"}),
			Map.entry("aerion.opt.potions.colors", new String[] {"Effect color", "Kolor efektu"}),
			Map.entry("aerion.opt.armor.layout", new String[] {"Layout", "Układ"}),
			Map.entry("aerion.opt.armor.durability", new String[] {"Durability", "Wytrzymałość"}),
			Map.entry("aerion.opt.armor.hand", new String[] {"Held item", "Trzymany przedmiot"}),
			Map.entry("aerion.opt.keystrokes.mouse", new String[] {"Mouse buttons", "Przyciski myszy"}),
			Map.entry("aerion.opt.keystrokes.space", new String[] {"Space bar", "Spacja"}),
			Map.entry("aerion.opt.keystrokes.cps", new String[] {"CPS on buttons", "CPS na przyciskach"}),
			Map.entry("aerion.opt.minimap.size", new String[] {"Size", "Rozmiar"}),
			Map.entry("aerion.opt.minimap.zoom", new String[] {"Pixels/block", "Pikseli na blok"}),
			Map.entry("aerion.opt.minimap.coords", new String[] {"Coordinates", "Współrzędne"}),
			Map.entry("aerion.choice.vertical", new String[] {"Vertical", "Pionowo"}),
			Map.entry("aerion.choice.horizontal", new String[] {"Horizontal", "Poziomo"}),
			Map.entry("aerion.choice.percent", new String[] {"Percent", "Procent"}),
			Map.entry("aerion.choice.value", new String[] {"Value", "Wartość"}),
			Map.entry("aerion.choice.none", new String[] {"None", "Brak"}),
			Map.entry("aerion.style.theme", new String[] {"Theme", "Motyw"}),
			Map.entry("aerion.style.none", new String[] {"None", "Brak"}),
			Map.entry("aerion.style.accent", new String[] {"Accent", "Akcent"}),
			Map.entry("aerion.style.text", new String[] {"Text color", "Kolor tekstu"}),
			Map.entry("aerion.style.background", new String[] {"Background", "Tło"}),
			Map.entry("aerion.style.opacity", new String[] {"Opacity", "Krycie"}),
			Map.entry("aerion.style.border", new String[] {"Border", "Ramka"}),
			Map.entry("aerion.style.radius", new String[] {"Corners", "Rogi"}),
			Map.entry("aerion.style.padding", new String[] {"Padding", "Odstęp"}),
			Map.entry("aerion.style.shadow", new String[] {"Text shadow", "Cień tekstu"}),
			Map.entry("aerion.style.align", new String[] {"Alignment", "Wyrównanie"}),
			Map.entry("aerion.style.left", new String[] {"Left", "Lewo"}),
			Map.entry("aerion.style.center", new String[] {"Center", "Środek"}),
			Map.entry("aerion.style.right", new String[] {"Right", "Prawo"}),
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
