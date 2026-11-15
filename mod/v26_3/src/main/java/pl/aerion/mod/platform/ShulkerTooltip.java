package pl.aerion.mod.platform;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import pl.aerion.mod.features.ShulkerPreview;

/** The shulker box grid in a tooltip ({@link ShulkerPreview}). */
public record ShulkerTooltip(ShulkerPreview.Contents contents) implements ClientTooltipComponent {
	@Override
	public int getHeight(Font font) {
		return contents.height();
	}

	@Override
	public int getWidth(Font font) {
		return contents.width();
	}

	@Override
	public void extractImage(Font font, int x, int y, int w, int h, GuiGraphicsExtractor g) {
		ShulkerPreview.draw(new Gfx(g), contents, x, y);
	}
}
