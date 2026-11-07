package pl.aerion.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Leaves out the mixins of features that are off when the game starts, so a player's other mods
 * only meet the changes Aerion actually uses. A feature switched on later works after a restart
 * (the player is told so). Runs before Minecraft's classes load: reads hud.json with Gson only.
 */
public final class MixinGate implements IMixinConfigPlugin {
	/** Mixin class name → the features it serves; it is applied when any of them is on. */
	private static final Map<String, List<String>> SERVES = Map.ofEntries(
			Map.entry("EntityMixin", List.of("freelook")),
			Map.entry("CameraMixin", List.of("freelook", "zoom", "fovKey")),
			Map.entry("HurtCamMixin", List.of("hurtCam")),
			Map.entry("OverlayTextureMixin", List.of("hitColor")),
			Map.entry("PlayerFovMixin", List.of("fovLock")),
			Map.entry("SoundEngineMixin", List.of("sounds")),
			Map.entry("WeatherMixin", List.of("worldLook")),
			Map.entry("ClockInstanceMixin", List.of("worldLook")),
			Map.entry("LevelTimeMixin", List.of("worldLook")),
			Map.entry("ChatComponentMixin", List.of("chat")),
			Map.entry("LevelRendererMixin", List.of("blockOutline")),
			Map.entry("ScreenEffectRendererMixin", List.of("overlays")),
			Map.entry("DebugRendererMixin", List.of("hitboxes", "lightLevels", "blockOutline", "waypoints")),
			Map.entry("NameTagMixin", List.of("badges")),
			Map.entry("PlayerTabOverlayMixin", List.of("badges")));
	/** Features that are on unless hud.json says otherwise (as in HudConfig.DEFAULTS). */
	private static final Set<String> ON_BY_DEFAULT = Set.of("zoom", "sounds", "badges");

	private static JsonObject features;
	/** Features whose mixins were left out (in this copy of the class). */
	private static final Set<String> SKIPPED = new TreeSet<>();

	/** Where the left-out features are kept: this class is loaded twice (by Mixin and by the game). */
	private static final String PROPERTY = "aerion.mixins.skipped";

	/** Features that were off at start and have mixins of their own. */
	public static Set<String> skipped() {
		String v = System.getProperty(PROPERTY, "");
		return v.isEmpty() ? Set.of() : Set.of(v.split(","));
	}

	private static synchronized JsonObject features() {
		if (features == null) {
			features = new JsonObject();
			String p = System.getProperty("aerion.hud");
			if (p != null && !p.isBlank()) {
				try {
					JsonElement root = JsonParser.parseString(Files.readString(Path.of(p), StandardCharsets.UTF_8));
					if (root.getAsJsonObject().get("features") instanceof JsonObject f) features = f;
				} catch (Exception e) {
					// No or unreadable file: defaults; every feature mixin of a default-on feature applies.
				}
			}
		}
		return features;
	}

	static boolean on(String feature) {
		boolean fallback = ON_BY_DEFAULT.contains(feature);
		if (!(features().get(feature) instanceof JsonObject f) || !f.has("enabled")) return fallback;
		try {
			return f.get("enabled").getAsBoolean();
		} catch (Exception e) {
			return fallback;
		}
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		List<String> serves = SERVES.get(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
		if (serves == null || serves.stream().anyMatch(MixinGate::on)) return true;
		synchronized (MixinGate.class) {
			SKIPPED.addAll(serves);
			System.setProperty(PROPERTY, String.join(",", SKIPPED));
		}
		return false;
	}

	@Override
	public void onLoad(String mixinPackage) {}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
