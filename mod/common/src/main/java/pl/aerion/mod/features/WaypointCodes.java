package pl.aerion.mod.features;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import pl.aerion.mod.Lang;
import pl.aerion.mod.platform.Game;

/**
 * Waypoints shared in the chat ("Base: 120, 64, -300 (nether)", as {@link Waypoints#share} posts
 * them, readable without Aerion too) get a "[+]" that adds the point with one click. In streamer
 * mode the numbers of such messages are covered.
 */
public final class WaypointCodes {
	private WaypointCodes() {}

	/** Name, x, y, z and an optional dimension at the end of a message. */
	static final Pattern CODE = Pattern.compile("([^<>:\\[\\]]{1,32}): (-?\\d{1,8}), (-?\\d{1,4}), (-?\\d{1,8})(?: \\(([a-z0-9_:.\\-]{1,40})\\))?\\s*$");
	private static final Pattern NUMBERS = Pattern.compile("-?\\d{1,8}, -?\\d{1,4}, -?\\d{1,8}");
	/** What the click carries; the game would only copy it, Aerion adds the point instead. */
	private static final String MARK = "aerion-waypoint:";

	/** The message with a "[+]" when it ends with a shared point. */
	public static Component decorate(Component message) {
		String plain = message.getString();
		Matcher m = CODE.matcher(plain);
		if (!m.find()) return message;
		String name = m.group(1).trim();
		if (name.isEmpty()) return message;
		String dimension = dimension(m.group(5));
		String payload = MARK + m.group(2) + " " + m.group(3) + " " + m.group(4) + " " + dimension + " " + name;
		Component shown = Streamer.active() ? coverNumbers(message) : message;
		MutableComponent add = Component.literal(" [+]").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.CopyToClipboard(payload))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(Lang.tr("aerion.waypoint.addShared") + ": " + name))));
		return Component.empty().append(shown).append(add);
	}

	/** "nether" / "end" (as shared) back to the game's dimension ids; nothing means the overworld. */
	static String dimension(String shared) {
		if (shared == null || shared.isEmpty()) return "minecraft:overworld";
		if (shared.contains(":")) return shared;
		return switch (shared) {
			case "nether" -> "minecraft:the_nether";
			case "end" -> "minecraft:the_end";
			default -> "minecraft:" + shared;
		};
	}

	private static Component coverNumbers(Component message) {
		MutableComponent out = Component.empty();
		message.visit((style, text) -> {
			out.append(Component.literal(NUMBERS.matcher(text).replaceAll("•••")).setStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	/** A click on a "[+]": true when it was one (then the point is added, nothing is copied). */
	public static boolean clicked(ClickEvent event) {
		if (!(event instanceof ClickEvent.CopyToClipboard copy) || !copy.value().startsWith(MARK)) return false;
		String[] parts = copy.value().substring(MARK.length()).split(" ", 5);
		if (parts.length < 5) return true;
		try {
			Waypoints.Point w = Waypoints.addNamed(parts[4], Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3]);
			if (w != null) Game.overlayMessage(Component.literal(Lang.tr("aerion.waypoint.added") + ": " + w.name));
		} catch (NumberFormatException e) {
			// A changed message: nothing to add.
		}
		return true;
	}
}
