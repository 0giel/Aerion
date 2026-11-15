package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.BuiltinPack;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.Gfx;
import pl.aerion.mod.platform.Textures;

/**
 * Food on the hunger bar, the way AppleSkin shows it: a gold rim around the drumsticks for the
 * hidden saturation, and, while holding food, the drumsticks it would fill blinking in. The game
 * keeps exhaustion on the server only, so that isn't shown. Drawn right after the game's own bar,
 * with the same icons (from the resource pack in use), so it fits any pack.
 */
public final class FoodOverlay {
	private FoodOverlay() {}

	private static final Identifier RIM_FULL = Identifier.fromNamespaceAndPath("aerion", "food_rim_full");
	private static final Identifier RIM_HALF = Identifier.fromNamespaceAndPath("aerion", "food_rim_half");
	private static final Identifier FULL = Identifier.fromNamespaceAndPath("aerion", "food_full_copy");
	private static final Identifier HALF = Identifier.fromNamespaceAndPath("aerion", "food_half_copy");
	private static final int GOLD = 0xFFFFD43B;
	private static int size = 9;
	private static boolean ready, failed;

	/**
	 * Called after the game drew the hunger bar: its icons run right to left from `right`, 8 apart,
	 * at `top`.
	 */
	public static void draw(Gfx g, Player player, int top, int right) {
		long started = System.nanoTime();
		try {
			paint(g, player, top, right);
		} finally {
			pl.aerion.mod.Cost.part("food").add(started);
		}
	}

	private static void paint(Gfx g, Player player, int top, int right) {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null || player == null) return;
		JsonObject f = cfg.feature("food");
		if (!HudConfig.bool(f, "enabled", true) || !ensure()) return;
		FoodData food = player.getFoodData();
		int level = food.getFoodLevel();
		float saturation = food.getSaturationLevel();
		boolean showSaturation = "1".equals(HudConfig.opt(f, "saturation", "1"));
		boolean preview = "1".equals(HudConfig.opt(f, "preview", "1"));

		FoodProperties held = preview ? held(player) : null;
		int newLevel = held == null ? level : Math.min(20, level + held.nutrition());
		float newSaturation = held == null ? saturation : Math.min(newLevel, saturation + held.saturation());
		// Blinks between faint and strong, like AppleSkin's preview.
		float pulse = 0.35f + 0.45f * (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 160.0));
		int blink = ((int) (pulse * 255) << 24) | 0xFFFFFF;

		for (int i = 0; i < 10; i++) {
			int x = right - i * 8 - 9;
			// Drumsticks the held food would fill.
			if (held != null) {
				int now = level - 2 * i, then = newLevel - 2 * i;
				if (then >= 2 && now < 2) g.texture(FULL, x, top, size, size, size, size, blink);
				else if (then == 1 && now < 1) g.texture(HALF, x, top, size, size, size, size, blink);
			}
			if (showSaturation) {
				float s = saturation - 2 * i;
				if (s >= 2) g.texture(RIM_FULL, x, top, size, size, size, size, GOLD);
				else if (s > 0) g.texture(RIM_HALF, x, top, size, size, size, size, GOLD);
				// What the held food would add, blinking.
				if (held != null && newSaturation > saturation) {
					float t = newSaturation - 2 * i;
					if (s < 2 && t >= 2) g.texture(RIM_FULL, x, top, size, size, size, size, (blink & 0xFF000000) | (GOLD & 0xFFFFFF));
					else if (s <= 0 && t > 0) g.texture(RIM_HALF, x, top, size, size, size, size, (blink & 0xFF000000) | (GOLD & 0xFFFFFF));
				}
			}
		}
	}

	private static final FontDescription FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("aerion", "food"));

	/**
	 * Adds a line under the item's name with what eating it gives: the hunger it fills as the
	 * hunger bar's own drumsticks, and the saturation in gold.
	 */
	public static void tooltip(ItemStack stack, List<Component> lines) {
		if (stack.isEmpty() || lines.isEmpty()) return;
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return;
		JsonObject f = cfg.feature("food");
		if (!HudConfig.bool(f, "enabled", true) || !"1".equals(HudConfig.opt(f, "tooltip", "1"))) return;
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null || (food.nutrition() <= 0 && food.saturation() <= 0)) return;
		try {
			lines.add(1, line(food.nutrition(), food.saturation()));
		} catch (RuntimeException e) {
			AerionClient.warnOnce("food-tooltip", e);
		}
	}

	private static Component line(int nutrition, float saturation) {
		MutableComponent out = Component.empty();
		int n = Math.max(0, nutrition);
		if (BuiltinPack.ready && n <= 20) {
			// The bar's drumsticks, 8 apart like on the bar: a full one per 2 points, a half for 1.
			StringBuilder icons = new StringBuilder();
			for (int i = 0; i < n / 2; i++) icons.append(i > 0 ? "" : "");
			if (n % 2 == 1) icons.append(n > 1 ? "" : "");
			if (!icons.isEmpty()) out.append(Component.literal(icons.toString()).withStyle(s -> s.withFont(FONT).withColor(0xFFFFFF)));
			out.append(Component.literal(" " + n).withStyle(ChatFormatting.GRAY));
		} else {
			out.append(Component.literal("🍖 " + n).withStyle(ChatFormatting.GRAY));
		}
		if (saturation > 0) out.append(Component.literal("  ✦ " + number(saturation)).withColor(GOLD & 0xFFFFFF));
		return out;
	}

	private static String number(float v) {
		String s = String.format(Locale.ROOT, "%.1f", v);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	/** Food in the main hand, else in the off hand; null when neither is food. */
	private static FoodProperties held(Player player) {
		for (ItemStack s : new ItemStack[] {player.getMainHandItem(), player.getOffhandItem()}) {
			FoodProperties p = s.isEmpty() ? null : s.get(DataComponents.FOOD);
			if (p != null) return p;
		}
		return null;
	}

	/** The game's own drumstick pictures, copied, and gold rims made from their outline. */
	private static boolean ensure() {
		if (ready || failed) return ready;
		try {
			int[] full = read("food_full"), half = read("food_half");
			Textures.registerPixels(FULL, size, size, full);
			Textures.registerPixels(HALF, size, size, half);
			Textures.registerPixels(RIM_FULL, size, size, rim(full));
			Textures.registerPixels(RIM_HALF, size, size, rim(half));
			ready = true;
		} catch (Exception | LinkageError e) {
			failed = true;
			AerionClient.LOG.warn("Aerion: food overlay off, can't read the hunger icons: {}", e.toString());
		}
		return ready;
	}

	private static int[] read(String name) throws Exception {
		Identifier id = Identifier.withDefaultNamespace("textures/gui/sprites/hud/" + name + ".png");
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(id);
		if (res.isEmpty()) throw new IllegalStateException("missing " + id);
		BufferedImage img;
		try (InputStream in = res.get().open()) {
			img = ImageIO.read(in);
		}
		if (img == null || img.getWidth() != img.getHeight()) throw new IllegalStateException("odd picture " + id);
		size = img.getWidth();
		return img.getRGB(0, 0, size, size, null, 0, size);
	}

	/** The outermost pixels of a picture's shape, white (tinted gold when drawn). */
	private static int[] rim(int[] px) {
		int[] out = new int[px.length];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				if (!solid(px, x, y)) continue;
				boolean edge = !solid(px, x - 1, y) || !solid(px, x + 1, y) || !solid(px, x, y - 1) || !solid(px, x, y + 1);
				if (edge) out[y * size + x] = 0xFFFFFFFF;
			}
		}
		return out;
	}

	private static boolean solid(int[] px, int x, int y) {
		return x >= 0 && y >= 0 && x < size && y < size && (px[y * size + x] >>> 24) > 40;
	}
}
