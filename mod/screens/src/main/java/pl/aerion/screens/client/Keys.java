package pl.aerion.screens.client;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Keys passed on to a window while typing into it: their names as the window sources know them
 * ("enter", "pagedown", "f5", "a"…), and which ones go as keys rather than as typed text.
 */
public final class Keys {
	private Keys() {}

	/** The name of a game key code, or null for keys not passed on (Shift alone, mouse…). */
	public static String name(int key) {
		if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) return String.valueOf((char) ('a' + key - InputConstants.KEY_A));
		if (key >= InputConstants.KEY_0 && key <= InputConstants.KEY_9) return String.valueOf((char) ('0' + key - InputConstants.KEY_0));
		if (key >= InputConstants.KEY_F1 && key <= InputConstants.KEY_F12) return "f" + (key - InputConstants.KEY_F1 + 1);
		return switch (key) {
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> "enter";
			case InputConstants.KEY_BACKSPACE -> "backspace";
			case InputConstants.KEY_TAB -> "tab";
			case InputConstants.KEY_ESCAPE -> "escape";
			case InputConstants.KEY_LEFT -> "left";
			case InputConstants.KEY_RIGHT -> "right";
			case InputConstants.KEY_UP -> "up";
			case InputConstants.KEY_DOWN -> "down";
			case InputConstants.KEY_HOME -> "home";
			case InputConstants.KEY_END -> "end";
			case InputConstants.KEY_PAGEUP -> "pageup";
			case InputConstants.KEY_PAGEDOWN -> "pagedown";
			case InputConstants.KEY_INSERT -> "insert";
			case InputConstants.KEY_DELETE -> "delete";
			case InputConstants.KEY_SPACE -> "space";
			default -> null;
		};
	}

	/**
	 * What to send as a key for a key press while typing, or null when it arrives as typed text
	 * instead (letters, digits and space without Ctrl, Alt or Super).
	 */
	public static String toSend(int key, int mods) {
		String n = name(key);
		if (n == null) return null;
		boolean plain = n.length() == 1 || n.equals("space");
		return plain && (mods & (WindowSource.CTRL | WindowSource.ALT | WindowSource.SUPER)) == 0 ? null : n;
	}
}
