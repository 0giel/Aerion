package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.hud.HudConfig;

/** Chat options (hud.json "features.chat"): times, counting repeats, history across disconnects. */
public final class ChatTweaks {
	private ChatTweaks() {}

	private static String last;
	private static int repeats;

	private static boolean flag(String key, boolean fallback) {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return false;
		JsonObject f = cfg.feature("chat");
		return HudConfig.bool(f, "enabled", false) && "1".equals(HudConfig.opt(f, key, fallback ? "1" : "0"));
	}

	public static boolean keepHistory() {
		return flag("history", true);
	}

	/** The message as shown; `removeLast` takes the previous copy away when a repeat is counted. */
	public static Component process(Component message, Runnable removeLast) {
		String plain = message.getString();
		Component shown = message;
		if (flag("stack", true) && plain.equals(last)) {
			repeats++;
			removeLast.run();
			shown = message.copy().append(Component.literal(" (x" + repeats + ")").withStyle(ChatFormatting.GRAY));
		} else {
			repeats = 1;
		}
		last = plain;
		if (flag("time", true)) {
			String pattern = flag("seconds", false) ? "HH:mm:ss" : "HH:mm";
			String time = LocalTime.now().format(DateTimeFormatter.ofPattern(pattern));
			return Component.literal("[" + time + "] ").withStyle(ChatFormatting.GRAY).append(shown);
		}
		return shown;
	}
}
