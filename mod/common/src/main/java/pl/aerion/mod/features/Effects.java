package pl.aerion.mod.features;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.platform.Game;

/**
 * Effects (Game look): the colour of the enchantment glint and fewer particles. The glint is the
 * game's own texture recoloured into a small resource pack in the game folder (always present,
 * empty while the glint keeps its colour), so packs, mipmaps and the texture's own settings keep
 * working; a new colour reloads the resources once the menus are closed.
 */
public final class Effects {
	private Effects() {}

	/** Glint colours offered in the menu; "default" keeps the game's purple. */
	public static final Map<String, Integer> GLINTS = Map.of("red", 0xFF4040, "gold", 0xFFB020, "green", 0x40E060, "aqua", 0x30D8E8, "blue", 0x4060FF,
			"pink", 0xFF60C0, "white", 0xE8E8F0);
	private static final String[] TEXTURES = {"enchanted_glint_item", "enchanted_glint_armor"};

	private static boolean on() {
		HudConfig cfg = AerionClient.hud();
		return cfg != null && HudConfig.bool(cfg.feature("effects"), "enabled", false);
	}

	/** The chosen glint colour (0xRRGGBB), or -1 for the game's own. */
	public static int glint() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null || !on()) return -1;
		return GLINTS.getOrDefault(HudConfig.opt(cfg.feature("effects"), "glint", "default"), -1);
	}

	/** Share of new particles that are kept (1 = all). */
	public static float particles() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null || !on()) return 1f;
		try {
			return Math.max(0, Math.min(100, Integer.parseInt(HudConfig.opt(cfg.feature("effects"), "particles", "100")))) / 100f;
		} catch (NumberFormatException e) {
			return 1f;
		}
	}

	private static int kept;

	/** Whether to let this particle in: the chosen share, spread evenly. */
	public static boolean keepParticle() {
		float share = particles();
		if (share >= 1f) return true;
		kept += Math.round(share * 100);
		if (kept >= 100) {
			kept -= 100;
			return true;
		}
		return false;
	}

	// --- The glint pack -------------------------------------------------------------------------

	private static Path dir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("aerion-effects-pack");
	}

	/** The pack with the recoloured glint (written now); nothing when it can't be written. */
	public static Optional<Pack> pack() {
		try {
			write(glint());
			PackLocationInfo info = new PackLocationInfo("aerion_effects", Component.literal("Aerion effects"), PackSource.BUILT_IN, Optional.empty());
			Pack pack = Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(dir()), PackType.CLIENT_RESOURCES,
					new PackSelectionConfig(true, Pack.Position.TOP, false));
			return Optional.ofNullable(pack);
		} catch (Exception e) {
			AerionClient.warnOnce("effects-pack", e);
			return Optional.empty();
		}
	}

	private static int written = Integer.MIN_VALUE;

	/** Writes the pack for this colour (-1: an empty pack, the game's own glint shows). */
	private static void write(int colour) throws Exception {
		Path root = dir(), misc = root.resolve("assets/minecraft/textures/misc");
		Files.createDirectories(root);
		Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Aerion effects\",\"min_format\":75,\"max_format\":97}}", StandardCharsets.UTF_8);
		for (String name : TEXTURES) {
			Path png = misc.resolve(name + ".png"), meta = misc.resolve(name + ".png.mcmeta");
			if (colour < 0) {
				Files.deleteIfExists(png);
				Files.deleteIfExists(meta);
				continue;
			}
			BufferedImage in;
			try (InputStream s = Game.vanillaResource(Identifier.withDefaultNamespace("textures/misc/" + name + ".png"))) {
				if (s == null) continue;
				in = ImageIO.read(s);
			}
			if (in == null) continue;
			BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
			int cr = (colour >> 16) & 0xFF, cg = (colour >> 8) & 0xFF, cb = colour & 0xFF;
			for (int y = 0; y < in.getHeight(); y++) {
				for (int x = 0; x < in.getWidth(); x++) {
					int p = in.getRGB(x, y);
					int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
					// The glint is purple: its brightness, carried over to the new colour.
					float l = Math.min(1f, Math.max(r, Math.max(g, b)) / 255f * 1.1f);
					out.setRGB(x, y, (p & 0xFF000000) | Math.round(cr * l) << 16 | Math.round(cg * l) << 8 | Math.round(cb * l));
				}
			}
			Files.createDirectories(misc);
			ImageIO.write(out, "png", png.toFile());
			Files.writeString(meta, "{\"texture\":{\"blur\":true}}", StandardCharsets.UTF_8);
		}
		written = colour;
	}

	/**
	 * Every tick: a glint colour changed in the menu (or the launcher) is written and the
	 * resources reloaded, once no menu is open.
	 */
	public static void sync() {
		if (written == Integer.MIN_VALUE || Game.screen() != null) return;
		int want = glint();
		if (want == written) return;
		try {
			write(want);
			Minecraft.getInstance().reloadResourcePacks();
		} catch (Exception e) {
			AerionClient.warnOnce("effects-glint", e);
			written = want;
		}
	}
}
