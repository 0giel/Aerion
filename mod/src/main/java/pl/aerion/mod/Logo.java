package pl.aerion.mod;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.io.InputStream;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The Aerion logo where the Minecraft logo would be: two white images rendered from the launcher's
 * glyph and the Inter typeface (cube + wordmark, and the sparkle), tinted with the theme and drawn
 * with linear filtering so they stay smooth at every GUI scale.
 */
public final class Logo {
	private Logo() {}

	private static final Identifier MAIN = Identifier.fromNamespaceAndPath("aerion", "logo_main");
	private static final Identifier SPARK = Identifier.fromNamespaceAndPath("aerion", "logo_spark");
	private static final int TEX_W = 640;
	private static final int TEX_H = 144;
	private static boolean loaded;
	private static boolean failed;

	/** A dynamic texture sampled linearly (Minecraft's default is nearest, i.e. pixelated). */
	private static final class SmoothTexture extends DynamicTexture {
		SmoothTexture(Supplier<String> label, NativeImage image) {
			super(label, image);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}

	// Without Fabric API mod assets are not part of the game's resources, so the images are
	// read from the mod's jar and registered by hand once.
	private static boolean ensureLoaded() {
		if (loaded || failed) return loaded;
		try {
			register(MAIN, "/assets/aerion/textures/gui/logo_main.png");
			register(SPARK, "/assets/aerion/textures/gui/logo_spark.png");
			loaded = true;
		} catch (Exception e) {
			failed = true;
			AerionClient.LOG.warn("Aerion UI: can't load the logo, keeping Minecraft's: {}", e.toString());
		}
		return loaded;
	}

	private static void register(Identifier id, String path) throws Exception {
		try (InputStream in = Logo.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing " + path);
			Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture(id::toString, NativeImage.read(in)));
		}
	}

	private static void blit(GuiGraphicsExtractor g, Identifier id, int x, int y, int w, int h, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, TEX_W, TEX_H, TEX_W, TEX_H, color);
	}

	/** Draw the logo centred in the Minecraft logo's 256×44 box at `top`; false if unavailable. */
	public static boolean draw(GuiGraphicsExtractor g, Theme t, int screenWidth, int top, float alpha) {
		if (!ensureLoaded()) return false;
		int a = Math.round(alpha * 255);
		int h = 40;
		int w = Math.round(h * (float) TEX_W / TEX_H);
		int x = (screenWidth - w) / 2;
		int y = top + 2;
		int ink = Colors.mix(0xFFFFFFFF, t.accent, 0.12f) & 0xFFFFFF;
		// A soft shadow, like the game's own title, then the logo and the accent sparkle.
		blit(g, MAIN, x + 1, y + 1, w, h, (a * 45 / 100) << 24);
		blit(g, MAIN, x, y, w, h, (a << 24) | ink);
		blit(g, SPARK, x, y, w, h, (a << 24) | (t.accent & 0xFFFFFF));
		return true;
	}
}
