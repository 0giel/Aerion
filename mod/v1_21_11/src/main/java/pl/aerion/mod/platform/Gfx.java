package pl.aerion.mod.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Drawing for Minecraft 1.21.11 (GuiGraphics); the shared code only sees this class. */
public final class Gfx {
	private final GuiGraphics g;

	public Gfx(GuiGraphics g) {
		this.g = g;
	}

	public int width() {
		return g.guiWidth();
	}

	public int height() {
		return g.guiHeight();
	}

	public void fill(int x1, int y1, int x2, int y2, int argb) {
		g.fill(x1, y1, x2, y2, argb);
	}

	public void gradient(int x1, int y1, int x2, int y2, int top, int bottom) {
		g.fillGradient(x1, y1, x2, y2, top, bottom);
	}

	public void text(String s, int x, int y, int argb, boolean shadow) {
		g.drawString(Minecraft.getInstance().font, s, x, y, argb, shadow);
	}

	public static int textWidth(String s) {
		return Minecraft.getInstance().font.width(s);
	}

	public void item(ItemStack stack, int x, int y) {
		g.renderItem(stack, x, y);
	}

	public void itemDecorations(ItemStack stack, int x, int y) {
		g.renderItemDecorations(Minecraft.getInstance().font, stack, x, y);
	}

	public void push() {
		g.pose().pushMatrix();
	}

	public void pop() {
		g.pose().popMatrix();
	}

	public void translate(float x, float y) {
		g.pose().translate(x, y);
	}

	public void scale(float s) {
		g.pose().scale(s, s);
	}

	public void texture(Identifier id, int x, int y, int w, int h, int texW, int texH, int argb) {
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, texW, texH, texW, texH, argb);
	}
}
