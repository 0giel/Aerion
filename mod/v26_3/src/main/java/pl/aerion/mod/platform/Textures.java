package pl.aerion.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Textures for Minecraft 26.x. */
public final class Textures {
	private Textures() {}

	/** A dynamic texture sampled linearly (Minecraft's default is nearest, i.e. pixelated). */
	private static final class SmoothTexture extends DynamicTexture {
		SmoothTexture(Supplier<String> label, NativeImage image) {
			super(label, image);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}

	public static void registerSmooth(Identifier id, InputStream png) throws IOException {
		Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture(id::toString, NativeImage.read(png)));
	}

	/** A picture given as ARGB pixels, row by row, sampled smoothly. */
	public static void registerSmooth(Identifier id, int w, int h, int[] argb) {
		NativeImage image = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) image.setPixel(x, y, argb[y * w + x]);
		}
		Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture(id::toString, image));
	}

	/** A picture given as ARGB pixels, row by row, kept pixel-sharp (maps). */
	public static void registerPixels(Identifier id, int w, int h, int[] argb) {
		NativeImage image = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) image.setPixel(x, y, argb[y * w + x]);
		}
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
	}

	private static final java.util.Map<Identifier, DynamicTexture> LIVE = new java.util.HashMap<>();

	/**
	 * A pixel-sharp picture that changes often (the minimap): the same texture is refilled and
	 * uploaded again while its size stays, instead of making a new one each time.
	 */
	public static void update(Identifier id, int w, int h, int[] argb) {
		DynamicTexture t = LIVE.get(id);
		NativeImage image = t != null ? t.getPixels() : null;
		if (image == null || image.getWidth() != w || image.getHeight() != h) {
			image = new NativeImage(w, h, false);
			t = new DynamicTexture(id::toString, image);
			Minecraft.getInstance().getTextureManager().register(id, t);
			LIVE.put(id, t);
		}
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) image.setPixel(x, y, argb[y * w + x]);
		}
		t.upload();
	}

	public static void release(Identifier id) {
		LIVE.remove(id);
		Minecraft.getInstance().getTextureManager().release(id);
	}
}
