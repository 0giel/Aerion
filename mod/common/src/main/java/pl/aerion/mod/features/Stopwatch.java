package pl.aerion.mod.features;

/**
 * The stopwatch module's clock, worked with one key: start, stop, and from stopped a press starts
 * again from zero.
 */
public final class Stopwatch {
	private Stopwatch() {}

	private static long startedAt, stoppedAt;
	private static boolean running;

	static void press() {
		long now = System.currentTimeMillis();
		if (running) {
			stoppedAt = now;
			running = false;
		} else {
			startedAt = now;
			running = true;
		}
	}

	/** Milliseconds on the clock. */
	public static long millis() {
		if (startedAt == 0) return 0;
		return (running ? System.currentTimeMillis() : stoppedAt) - startedAt;
	}

	public static boolean running() {
		return running;
	}
}
