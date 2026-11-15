package pl.aerion.mod;

import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

/**
 * The mod's own resources (the badge font) as a built-in resource pack that is always on. The mod
 * runs without Fabric API, which is what normally makes a mod's assets visible to the game; this
 * pack is the `aerionpack/` folder of the mod's jar. Its id is not the mod id: Fabric API, when
 * installed, adds a pack named after each mod, and one would replace the other.
 */
public final class BuiltinPack {
	private static Path root;
	/** Set when the pack was created, so its fonts can be used. */
	public static volatile boolean ready;

	private BuiltinPack() {}

	/** The pack, or nothing when the folder can't be opened (the game then shows the old glyphs). */
	public static Optional<Pack> create() {
		try {
			Path path = root();
			if (path == null) return Optional.empty();
			PackLocationInfo info = new PackLocationInfo("aerion_builtin", Component.literal("Aerion"), PackSource.BUILT_IN, Optional.empty());
			Pack pack = Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(path), PackType.CLIENT_RESOURCES,
					new PackSelectionConfig(true, Pack.Position.TOP, false));
			Badges.packReady = pack != null;
			ready = pack != null;
			return Optional.ofNullable(pack);
		} catch (Exception e) {
			AerionClient.LOG.warn("Aerion: built-in resources not loaded: {}", e.toString());
			return Optional.empty();
		}
	}

	private static synchronized Path root() throws Exception {
		if (root != null) return root;
		URL meta = BuiltinPack.class.getResource("/aerionpack/pack.mcmeta");
		if (meta == null) return null;
		URI uri = meta.toURI();
		Path file;
		try {
			file = Path.of(uri);
		} catch (FileSystemNotFoundException e) {
			// Inside a jar whose file system isn't open yet.
			FileSystems.newFileSystem(uri, Map.of());
			file = Path.of(uri);
		}
		root = file.getParent();
		return root;
	}
}
