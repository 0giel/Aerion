package pl.aerion.mod.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Drawing for Minecraft 26.x (GuiGraphicsExtractor); the shared code only sees this class. */
public final class Gfx {
	private final GuiGraphicsExtractor g;

	public Gfx(GuiGraphicsExtractor g) {
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
		g.text(Minecraft.getInstance().font, s, x, y, argb, shadow);
	}

	public static int textWidth(String s) {
		return Minecraft.getInstance().font.width(s);
	}

	public void item(ItemStack stack, int x, int y) {
		g.item(stack, x, y);
	}

	public void itemDecorations(ItemStack stack, int x, int y) {
		g.itemDecorations(Minecraft.getInstance().font, stack, x, y);
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

	/** A part of a texture, drawn at its own size in the current pose. */
	public void region(Identifier id, int x, int y, int w, int h, int u, int v, int texW, int texH, int argb) {
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, (float) u, (float) v, w, h, w, h, texW, texH, argb);
	}

	public void scissor(int x1, int y1, int x2, int y2) {
		g.enableScissor(x1, y1, x2, y2);
	}

	public void endScissor() {
		g.disableScissor();
	}

	private static int pixels;

	/** Screen pixels per drawing unit: the GUI scale, unless a menu sets its own size. */
	public static int guiScale() {
		return pixels > 0 ? pixels : realGuiScale();
	}

	public static int realGuiScale() {
		return Minecraft.getInstance().getWindow().getGuiScale();
	}

	/** Draw at `p` screen pixels per unit (0 goes back to the GUI scale). */
	public static void pixels(int p) {
		pixels = p;
	}
}
