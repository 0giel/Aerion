package pl.aerion.mod.hud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * The keyboard and mouse of the in-game key editor, laid out like the launcher's
 * (src/lib/components/KeybindEditor.svelte), and which actions share a key. Key names are the
 * game's own (`key.keyboard.c`), as options.txt stores them.
 */
public final class KeyBoard {
	private KeyBoard() {}

	/** One key cap, or a gap when `name` is null; widths in key units. */
	record Cap(String name, String label, float w) {}

	private static Cap k(String name, String label) {
		return new Cap("key.keyboard." + name, label, 1);
	}

	private static Cap k(String name, String label, float w) {
		return new Cap("key.keyboard." + name, label, w);
	}

	private static Cap gap(float w) {
		return new Cap(null, "", w);
	}

	private static List<Cap> letters(String s) {
		List<Cap> out = new ArrayList<>();
		for (char c : s.toCharArray()) out.add(k(String.valueOf(c), String.valueOf(Character.toUpperCase(c))));
		return out;
	}

	private static List<Cap> row(Object... parts) {
		List<Cap> out = new ArrayList<>();
		for (Object p : parts) {
			if (p instanceof Cap c) out.add(c);
			else if (p instanceof List<?> l) for (Object o : l) out.add((Cap) o);
		}
		return out;
	}

	static final List<List<Cap>> MAIN = List.of(
			row(k("escape", "Esc"), gap(0.75f), k("f1", "F1"), k("f2", "F2"), k("f3", "F3"), k("f4", "F4"), gap(0.5f),
					k("f5", "F5"), k("f6", "F6"), k("f7", "F7"), k("f8", "F8"), gap(0.5f), k("f9", "F9"), k("f10", "F10"), k("f11", "F11"), k("f12", "F12")),
			row(k("grave.accent", "`"), letters("1234567890"), k("minus", "-"), k("equal", "="), k("backspace", "Back", 2)),
			row(k("tab", "Tab", 1.5f), letters("qwertyuiop"), k("left.bracket", "["), k("right.bracket", "]"), k("backslash", "\\", 1.5f)),
			row(k("caps.lock", "Caps", 1.75f), letters("asdfghjkl"), k("semicolon", ";"), k("apostrophe", "'"), k("enter", "Enter", 2.25f)),
			row(k("left.shift", "Shift", 2.25f), letters("zxcvbnm"), k("comma", ","), k("period", "."), k("slash", "/"), k("right.shift", "Shift", 2.75f)),
			row(k("left.control", "Ctrl", 1.25f), k("left.win", "Win", 1.25f), k("left.alt", "Alt", 1.25f), k("space", "", 6.25f),
					k("right.alt", "Alt", 1.25f), k("right.win", "Win", 1.25f), k("menu", "Menu", 1.25f), k("right.control", "Ctrl", 1.25f)));

	static final List<List<Cap>> NAV = List.of(
			row(k("print.screen", "Prt"), k("scroll.lock", "Scr"), k("pause", "Pau")),
			row(k("insert", "Ins"), k("home", "Hom"), k("page.up", "PgU")),
			row(k("delete", "Del"), k("end", "End"), k("page.down", "PgD")),
			row(gap(1), gap(1), gap(1)),
			row(gap(1), k("up", "Up"), gap(1)),
			row(k("left", "Lt"), k("down", "Dn"), k("right", "Rt")));

	static final List<Cap> MOUSE = List.of(
			new Cap("key.mouse.left", "LMB", 1.5f), new Cap("key.mouse.middle", "MMB", 1.5f), new Cap("key.mouse.right", "RMB", 1.5f),
			new Cap("key.mouse.4", "M4", 1.5f), new Cap("key.mouse.5", "M5", 1.5f));

	/** Width of the main block plus the gap and the navigation block, in key units. */
	static final float WIDTH = 15 + 0.5f + 3;

	/**
	 * Where an action works: F3 combinations only with F3 held, spectator actions only in
	 * spectator mode. Keys clash only within one context: A for "left" and "F3 + A" is fine.
	 */
	public static String context(KeyMapping k) {
		String a = k.getName();
		if (a.equals("key.debug.modifier")) return "modifier";
		// F3 + C copies the position, and held for six seconds crashes the game: one key by design.
		if (a.equals("key.debug.crash")) return "debug-hold";
		if (a.startsWith("key.debug.") && !a.equals("key.debug.overlay")) return "debug";
		if (a.toLowerCase(java.util.Locale.ROOT).contains("spectator")) return "spectator";
		return "game";
	}

	/** Bound actions per key name. */
	static Map<String, List<KeyMapping>> byKey() {
		Map<String, List<KeyMapping>> out = new HashMap<>();
		for (KeyMapping k : Minecraft.getInstance().options.keyMappings) {
			if (!k.isUnbound()) out.computeIfAbsent(k.saveString(), x -> new ArrayList<>()).add(k);
		}
		return out;
	}

	/** Another action on the same key works at the same time as this one. */
	static boolean clashes(KeyMapping k, List<KeyMapping> sameKey) {
		if (sameKey == null) return false;
		for (KeyMapping o : sameKey) if (o != k && context(o).equals(context(k))) return true;
		return false;
	}

	static boolean clashes(List<KeyMapping> sameKey) {
		if (sameKey == null) return false;
		for (KeyMapping k : sameKey) if (clashes(k, sameKey)) return true;
		return false;
	}
}
