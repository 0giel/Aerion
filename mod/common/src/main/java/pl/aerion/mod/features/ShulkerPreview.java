package pl.aerion.mod.features;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.GameLook;
import pl.aerion.mod.Theme;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.Gfx;

/**
 * What is inside a shulker box, as a 9×3 grid of the items in its tooltip (instead of the game's
 * list of five names). Only reads the item; nothing about the game changes.
 */
public final class ShulkerPreview {
	private ShulkerPreview() {}

	public static final int SLOT = 18, COLUMNS = 9, ROWS = 3;

	/** The tooltip part: the box's slots and its colour (as the game's dye colour, or 0). */
	public record Contents(List<ItemStack> items, int tint) implements TooltipComponent {
		public int width() {
			return COLUMNS * SLOT + 2;
		}

		public int height() {
			return ROWS * SLOT + 4;
		}
	}

	private static final ThreadLocal<ItemStack> BUILDING = new ThreadLocal<>();

	/** The stack whose tooltip lines are being built (null when done). */
	public static void building(ItemStack stack) {
		if (stack == null) BUILDING.remove();
		else BUILDING.set(stack);
	}

	public static ItemStack building() {
		return BUILDING.get();
	}

	public static boolean on() {
		HudConfig cfg = AerionClient.hud();
		return cfg != null && HudConfig.bool(cfg.feature("shulkerPreview"), "enabled", true);
	}

	private static ShulkerBoxBlock box(ItemStack stack) {
		return stack.getItem() instanceof BlockItem b && b.getBlock() instanceof ShulkerBoxBlock s ? s : null;
	}

	/** Whether this stack gets the grid (so the game's list of names is left out). */
	public static boolean shows(ItemStack stack) {
		if (!on() || box(stack) == null) return false;
		ItemContainerContents c = stack.get(DataComponents.CONTAINER);
		return c != null && c.nonEmptyItems().iterator().hasNext();
	}

	/** The grid for a filled shulker box, empty for anything else. */
	public static Optional<TooltipComponent> image(ItemStack stack) {
		if (!shows(stack)) return Optional.empty();
		NonNullList<ItemStack> items = NonNullList.withSize(COLUMNS * ROWS, ItemStack.EMPTY);
		stack.get(DataComponents.CONTAINER).copyInto(items);
		DyeColor dye = box(stack).getColor();
		return Optional.of(new Contents(List.copyOf(items), dye == null ? 0 : dye.getTextureDiffuseColor() | 0xFF000000));
	}

	/** The slots and the items in them, at the tooltip's position. */
	public static void draw(Gfx g, Contents c, int x, int y) {
		long started = System.nanoTime();
		try {
			Theme t = GameLook.themed("tooltips");
			int base = c.tint() != 0 ? c.tint() : t != null ? t.accent : 0xFF8A6FB0;
			int panel = Colors.alpha(Colors.mix(0xFF1A1A1A, base, 0.35f), 0xC8);
			int slot = Colors.alpha(Colors.mix(0xFF000000, base, 0.22f), 0xD0);
			g.fill(x, y + 1, x + c.width(), y + c.height() - 1, panel);
			for (int i = 0; i < c.items().size(); i++) {
				int sx = x + 1 + (i % COLUMNS) * SLOT, sy = y + 2 + (i / COLUMNS) * SLOT;
				g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, slot);
			}
			for (int i = 0; i < c.items().size(); i++) {
				ItemStack s = c.items().get(i);
				if (s.isEmpty()) continue;
				int sx = x + 2 + (i % COLUMNS) * SLOT, sy = y + 3 + (i / COLUMNS) * SLOT;
				g.item(s, sx, sy);
				g.itemDecorations(s, sx, sy);
			}
		} finally {
			pl.aerion.mod.Cost.part("shulkerPreview").add(started);
		}
	}
}
