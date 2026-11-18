package pl.aerion.screens.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Pictures given as ARGB pixels, row by row, as the game's textures. */
final class Pixels {
	private Pixels() {}

	private static final Map<Identifier, DynamicTexture> LIVE = new HashMap<>();

	/** A picture made once (the panels' buttons). */
	static void register(Identifier id, int w, int h, int[] argb) {
		NativeImage image = new NativeImage(w, h, false);
		fill(image, w, h, argb);
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
	}

	/**
	 * A picture that changes often (a window): the same texture is refilled and uploaded again while
	 * its size stays, instead of making a new one each time.
	 */
	static void update(Identifier id, int w, int h, int[] argb) {
		DynamicTexture t = LIVE.get(id);
		NativeImage image = t != null ? t.getPixels() : null;
		if (image == null || image.getWidth() != w || image.getHeight() != h) {
			image = new NativeImage(w, h, false);
			t = new DynamicTexture(id::toString, image);
			Minecraft.getInstance().getTextureManager().register(id, t);
			LIVE.put(id, t);
		}
		fill(image, w, h, argb);
		t.upload();
	}

	static void release(Identifier id) {
		LIVE.remove(id);
		Minecraft.getInstance().getTextureManager().release(id);
	}

	private static void fill(NativeImage image, int w, int h, int[] argb) {
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) image.setPixel(x, y, argb[y * w + x]);
		}
	}
}
