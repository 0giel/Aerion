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
}
