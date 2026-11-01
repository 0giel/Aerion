package pl.aerion.mod.hud;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayDeque;

/** Clicks per second over the last second, fed by the mouse mixin. */
public final class Cps {
	private Cps() {}

	private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
	private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();

	public static void click(int button) {
		long now = System.currentTimeMillis();
		if (button == InputConstants.MOUSE_BUTTON_LEFT) LEFT.addLast(now);
		else if (button == InputConstants.MOUSE_BUTTON_RIGHT) RIGHT.addLast(now);
	}

	private static int count(ArrayDeque<Long> q) {
		long cutoff = System.currentTimeMillis() - 1000;
		while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
		return q.size();
	}

	public static int left() {
		return count(LEFT);
	}

	public static int right() {
		return count(RIGHT);
	}
}
