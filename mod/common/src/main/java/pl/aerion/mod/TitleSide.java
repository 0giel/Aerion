package pl.aerion.mod;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import pl.aerion.mod.platform.Gfx;

/**
 * The left side of the title screen, the mirror of {@link TitleCard}: a greeting, the clock, a few
 * facts about this game, and shortcut buttons (back to the last world or server, screenshots, the
 * game folder; the buttons are real widgets added in {@code MenuButtonMixin}). On no panel of its
 * own. Lines that don't fit are dropped, and the whole side when the window is too small.
 */
public final class TitleSide {
	private TitleSide() {}

	public static final int BUTTON = 18, GAP = 3, PAD = 6;
	private static final int MAX_W = 106, MIN_W = 82;

	/** x, y, width, height of the side, the number of shortcut buttons below the facts. */
	public static int[] place(int width, int height, int buttons) {
		// Between the screen's edge and the button column (which is 200 wide, in the middle).
		int right = width / 2 - 100 - 8, left = PAD;
		int w = Math.min(MAX_W, right - left);
		if (w < MIN_W) return null;
		int top = 30, bottom = height - 13 - PAD;
		int h = bottom - top;
		if (h < buttons * BUTTON + (buttons - 1) * GAP) return null;
		return new int[] {right - w, top, w, h, buttons};
	}

	/** Where shortcut button `i` goes: x, y, width, height. */
	public static int[] button(int[] side, int i) {
		int n = side[4];
		int y = side[1] + side[3] - (n - i) * BUTTON - (n - 1 - i) * GAP;
		return new int[] {side[0], y, side[2], BUTTON};
	}

	private static String clock = "", date = "", greeting = "";
	private static long clockMinute = -1;

	private static void refresh() {
		LocalDateTime now = LocalDateTime.now();
		long minute = now.toLocalDate().toEpochDay() * 1440L + now.getHour() * 60L + now.getMinute();
		if (minute == clockMinute) return;
		clockMinute = minute;
		boolean pl = Lang.polish();
		Locale loc = pl ? Locale.forLanguageTag("pl") : Locale.ENGLISH;
		clock = now.format(DateTimeFormatter.ofPattern("HH:mm"));
		date = now.format(DateTimeFormatter.ofPattern("EEE, d MMM", loc));
		int h = now.getHour();
		greeting = Lang.tr(h < 5 ? "aerion.title.night" : h < 12 ? "aerion.title.morning" : h < 18 ? "aerion.title.afternoon" : h < 22 ? "aerion.title.evening" : "aerion.title.night");
	}

	private static int mods = -1;

	/** Mods the player installed: top-level ones that aren't Minecraft, Java or the loader. */
	private static int modCount() {
		if (mods >= 0) return mods;
		int n = 0;
		for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
			String id = m.getMetadata().getId();
			if (m.getContainingMod().isPresent() || id.equals("minecraft") || id.equals("java") || id.equals("fabricloader")) continue;
			if (id.startsWith("fabric-") || id.equals("mixinextras")) continue;
			n++;
		}
		return mods = n;
	}

	private static String aerionVersion() {
		return FabricLoader.getInstance().getModContainer("aerion").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
	}

	public static void draw(Gfx g, int width, int height, int buttons, float alpha) {
		int[] c = place(width, height, buttons);
		if (c == null) return;
		Theme t = AerionClient.theme();
		int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
		if (a <= 4) return;
		refresh();
		int text = Colors.alpha(t != null ? t.text : 0xFFE9ECF2, a);
		int muted = Colors.alpha(t != null ? t.textMuted : 0xFFA3A9B6, a);
		int accent = Colors.alpha(t != null ? t.accent : 0xFF7AA2FF, a);
		int x = c[0], y = c[1], w = c[2];
		int room = button(c, 0)[1] - GAP - y;
		boolean motion = Motion.on();
		String name = Minecraft.getInstance().getUser().getName();
		float cx = x + w / 2f;

		// Lines in the order they are dropped when the side is short: facts, date, greeting.
		int need = 15 + 11;
		boolean facts = room >= need + 24 + 25;
		boolean dateLine = room >= need + 11;
		// Clock first: it is the one thing worth keeping on a low window.
		int cy = y + 8;
		float slide = motion ? (1f - Motion.out(Math.min(1f, (System.nanoTime() - open) / 5.5e8f))) * 6f : 0f;
		int off = Math.round(slide);
		shadowed(g, clock, cx - off, cy, 14f, text, a);
		if (dateLine) shadowed(g, date, cx - off, cy + 12, 6.5f, muted, a);
		int ly = cy + (dateLine ? 22 : 13);
		if (room >= need) {
			shadowed(g, greeting, cx - off, ly + 2, 6.5f, muted, a);
			shadowed(g, name, cx - off, ly + 11, 8.5f, accent, a);
			ly += 20;
		}
		if (facts) {
			Draw.hline(g, x + 12, ly + 1, w - 24, Colors.alpha(t != null ? t.border : 0x33FFFFFF, a * 2 / 3));
			ly += 9;
			String[] lines = {
				Lang.tr("aerion.title.mods").replace("{n}", String.valueOf(modCount())),
				"Minecraft " + FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(""),
				"Aerion " + aerionVersion(),
			};
			for (String s : lines) {
				if (ly + 6 > y + room) break;
				shadowed(g, s, cx, ly, 6.2f, muted, a);
				ly += 9;
			}
		}
	}

	private static long open = System.nanoTime();

	/** Call when the title screen opens, so the side's entrance restarts. */
	public static void opened() {
		open = System.nanoTime();
	}

	private static void shadowed(Gfx g, String s, float cx, float cy, float size, int color, int alpha) {
		Txt.center(g, s, cx + 0.6f, cy + 0.6f, size, Colors.alpha(0xFF000000, (color >>> 24) * 0x90 / 0xFF));
		Txt.center(g, s, cx, cy, size, color);
	}

	/** Opens a folder of the game in the system's file manager. */
	public static void openFolder(String name) {
		File dir = new File(Minecraft.getInstance().gameDirectory, name);
		if (!dir.isDirectory() && !dir.mkdirs()) return;
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String[] cmd = os.contains("win") ? new String[] {"explorer", dir.getAbsolutePath()} : os.contains("mac") ? new String[] {"open", dir.getAbsolutePath()} : new String[] {"xdg-open", dir.getAbsolutePath()};
			new ProcessBuilder(cmd).start();
		} catch (Exception e) {
			AerionClient.LOG.debug("Aerion UI: can't open {}: {}", dir, e.toString());
		}
	}

	/** A label cut to fit `max` pixels, with dots when it was cut. */
	public static String fit(String s, float max) {
		if (Txt.width(s, 8.5f) <= max) return s;
		while (s.length() > 1 && Txt.width(s + "...", 8.5f) > max) s = s.substring(0, s.length() - 1);
		return s + "...";
	}
}
