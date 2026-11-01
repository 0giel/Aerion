package pl.aerion.mod.features;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.hud.HudConfig;
import pl.aerion.mod.hud.HudEditor;
import pl.aerion.mod.mixin.OptionInstanceAccessor;
import pl.aerion.mod.platform.EditorScreen;
import pl.aerion.mod.platform.Game;

/**
 * Client features, each switched on or off in the launcher (hud.json "features"): zoom, freelook,
 * fullbright, and Minecraft's own toggle sprint / toggle sneak. Keys appear under "Aerion" in the
 * game's Controls screen and are rebound there like any other key.
 */
public final class Features {
	private Features() {}

	/** Every HUD module; each can have a key that switches it on and off. */
	public static final String[] MODULE_IDS = {
		"fps", "coords", "direction", "speed", "cps", "keystrokes", "armor", "totems", "items", "potions", "death", "ping", "clock", "session", "day", "light", "memory", "minimap", "toggles"
	};

	private static KeyMapping[] keys;
	private static KeyMapping editor, zoom, freelook, fullbright, hitboxes, overlays, sounds, crosshair, hurtCam;
	private static final Map<String, KeyMapping> MODULE_KEYS = new LinkedHashMap<>();

	/** Created on first use, while the game's options are being built (see OptionsMixin). */
	public static KeyMapping[] keys() {
		if (keys == null) {
			KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aerion", "main"));
			editor = new KeyMapping("key.aerion.hud_editor", InputConstants.KEY_RSHIFT, cat);
			zoom = new KeyMapping("key.aerion.zoom", InputConstants.KEY_C, cat);
			freelook = new KeyMapping("key.aerion.freelook", InputConstants.KEY_LALT, cat);
			fullbright = new KeyMapping("key.aerion.fullbright", InputConstants.UNKNOWN.getValue(), cat);
			hitboxes = new KeyMapping("key.aerion.hitboxes", InputConstants.UNKNOWN.getValue(), cat);
			overlays = new KeyMapping("key.aerion.overlays", InputConstants.UNKNOWN.getValue(), cat);
			sounds = new KeyMapping("key.aerion.sounds", InputConstants.UNKNOWN.getValue(), cat);
			crosshair = new KeyMapping("key.aerion.crosshair", InputConstants.UNKNOWN.getValue(), cat);
			hurtCam = new KeyMapping("key.aerion.hurtCam", InputConstants.UNKNOWN.getValue(), cat);
			List<KeyMapping> all = new ArrayList<>(List.of(editor, zoom, freelook, fullbright, hitboxes, overlays, sounds, crosshair, hurtCam));
			for (String id : MODULE_IDS) {
				KeyMapping k = new KeyMapping("key.aerion.module." + id, InputConstants.UNKNOWN.getValue(), cat);
				MODULE_KEYS.put(id, k);
				all.add(k);
			}
			keys = all.toArray(new KeyMapping[0]);
		}
		return keys;
	}

	public static boolean isMenuKey(InputConstants.Key key) {
		return !editor.isUnbound() && editor.saveString().equals(key.getName());
	}

	/** The key of a feature or module, for the menu; Minecraft's own for sprint and sneak. */
	public static KeyMapping key(String id) {
		Minecraft mc = Minecraft.getInstance();
		keys();
		return switch (id) {
			case "menu" -> editor;
			case "zoom" -> zoom;
			case "freelook" -> freelook;
			case "fullbright" -> fullbright;
			case "hitboxes" -> hitboxes;
			case "overlays" -> overlays;
			case "sounds" -> sounds;
			case "crosshair" -> crosshair;
			case "hurtCam" -> hurtCam;
			case "toggleSprint" -> mc.options.keySprint;
			case "toggleSneak" -> mc.options.keyShift;
			default -> MODULE_KEYS.get(id);
		};
	}

	private static JsonObject feature(String id) {
		HudConfig cfg = AerionClient.hud();
		return cfg == null ? new JsonObject() : cfg.feature(id);
	}

	private static boolean on(String id) {
		return HudConfig.bool(feature(id), "enabled", false);
	}

	// --- zoom -------------------------------------------------------------------------------
	private static float zoomNow = 1f;
	private static long lastFrame;

	/** Multiplies the field of view; eases in and out when "smooth" is on. */
	public static float fovMultiplier() {
		JsonObject z = feature("zoom");
		boolean want = zoom != null && HudConfig.bool(z, "enabled", true) && zoom.isDown();
		float factor = Math.max(2, Math.min(10, HudConfig.num(z, "factor", 4)));
		float target = want ? 1f / factor : 1f;
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (HudConfig.bool(z, "smooth", true)) zoomNow += (target - zoomNow) * Math.min(1f, dt * 14f);
		else zoomNow = target;
		if (Math.abs(zoomNow - target) < 0.001f) zoomNow = target;
		return zoomNow;
	}

	// --- freelook ---------------------------------------------------------------------------
	private static boolean looking;
	private static float yaw, pitch;
	private static CameraType before;

	public static boolean freelookActive() {
		return looking;
	}

	public static float freeYaw() {
		return yaw;
	}

	public static float freePitch() {
		return pitch;
	}

	/** Mouse movement while looking around turns the camera, not the player. */
	public static boolean turn(Entity e, double dy, double dx) {
		Minecraft mc = Minecraft.getInstance();
		if (!looking || e != mc.player) return false;
		yaw += (float) dy * 0.15f;
		pitch = Math.max(-90f, Math.min(90f, pitch + (float) dx * 0.15f));
		return true;
	}

	private static void startLooking(Minecraft mc) {
		looking = true;
		yaw = mc.player.getYRot();
		pitch = mc.player.getXRot();
		before = mc.options.getCameraType();
		if (before.isFirstPerson()) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
	}

	private static void stopLooking(Minecraft mc) {
		looking = false;
		if (before != null) mc.options.setCameraType(before);
		before = null;
	}

	// --- fullbright -------------------------------------------------------------------------
	private static boolean bright;
	private static Double gammaBefore;

	public static boolean fullbrightOn() {
		return bright;
	}

	private static void setBright(Minecraft mc, boolean on) {
		if (on == bright) return;
		@SuppressWarnings("unchecked")
		OptionInstanceAccessor<Double> gamma = (OptionInstanceAccessor<Double>) (Object) mc.options.gamma();
		if (on) {
			gammaBefore = mc.options.gamma().get();
			// Past the slider's range on purpose; the game clamps it again when options reload.
			gamma.aerion$setValue(15.0);
		} else if (gammaBefore != null) {
			gamma.aerion$setValue(gammaBefore);
		}
		bright = on;
	}

	// --- toggle sprint / sneak (Minecraft's own options) -----------------------------------
	public static boolean sprintToggled() {
		Minecraft mc = Minecraft.getInstance();
		return mc.options.toggleSprint().get() && mc.options.keySprint.isDown();
	}

	public static boolean sneakToggled() {
		Minecraft mc = Minecraft.getInstance();
		return mc.options.toggleCrouch().get() && mc.options.keyShift.isDown();
	}

	private static boolean ownLoaded, ownSprint, ownSneak;

	/** Turns the option on for the feature; off again only if Aerion was the one to turn it on. */
	private static boolean sync(OptionInstance<Boolean> option, boolean wanted, boolean owned) {
		if (wanted && !option.get()) {
			option.set(true);
			return true;
		}
		if (!wanted && owned) {
			option.set(false);
			return false;
		}
		return owned;
	}

	/** Which options Aerion turned on is kept per instance, like the options themselves. */
	private static void syncToggles(Minecraft mc) {
		Path file = mc.gameDirectory.toPath().resolve("aerion-toggles");
		if (!ownLoaded) {
			ownLoaded = true;
			try {
				String saved = Files.readString(file);
				ownSprint = saved.contains("sprint");
				ownSneak = saved.contains("sneak");
			} catch (IOException e) {
				// Nothing was turned on yet.
			}
		}
		boolean sprintWas = mc.options.toggleSprint().get(), sneakWas = mc.options.toggleCrouch().get();
		ownSprint = sync(mc.options.toggleSprint(), on("toggleSprint"), ownSprint);
		ownSneak = sync(mc.options.toggleCrouch(), on("toggleSneak"), ownSneak);
		if (mc.options.toggleSprint().get() == sprintWas && mc.options.toggleCrouch().get() == sneakWas) return;
		mc.options.save();
		try {
			Files.writeString(file, (ownSprint ? "sprint " : "") + (ownSneak ? "sneak" : ""));
		} catch (IOException e) {
			AerionClient.warnOnce("toggles", e);
		}
	}

	private static long joined;

	/** Milliseconds since joining the current world or server. */
	public static long sessionMillis() {
		return joined == 0 ? 0 : System.currentTimeMillis() - joined;
	}

	private static void toggleOnKey(KeyMapping key, String id, boolean fallback) {
		while (key.consumeClick()) {
			JsonObject f = feature(id);
			f.addProperty("enabled", !HudConfig.bool(f, "enabled", fallback));
			AerionClient.saveHud();
		}
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		if (keys == null) return;
		while (editor.consumeClick()) {
			if (Game.screen() == null) Game.setScreen(new EditorScreen(new HudEditor(), null));
		}
		syncToggles(mc);
		toggleOnKey(hitboxes, "hitboxes", false);
		toggleOnKey(overlays, "overlays", false);
		toggleOnKey(sounds, "sounds", true);
		toggleOnKey(crosshair, "crosshair", false);
		toggleOnKey(hurtCam, "hurtCam", false);
		if (mc.player == null) joined = 0;
		else if (joined == 0) joined = System.currentTimeMillis();
		for (var e : MODULE_KEYS.entrySet()) {
			while (e.getValue().consumeClick()) {
				HudConfig cfg = AerionClient.hud();
				if (cfg == null) continue;
				JsonObject m = cfg.module(e.getKey());
				m.addProperty("enabled", !HudConfig.bool(m, "enabled", false));
				AerionClient.saveHud();
			}
		}
		if (mc.player == null) {
			if (looking) stopLooking(mc);
			return;
		}
		JsonObject fl = feature("freelook");
		if (HudConfig.bool(fl, "enabled", false)) {
			if (HudConfig.bool(fl, "hold", true)) {
				if (freelook.isDown() && !looking && Game.screen() == null) startLooking(mc);
				else if (!freelook.isDown() && looking) stopLooking(mc);
			} else {
				while (freelook.consumeClick()) {
					if (looking) stopLooking(mc);
					else startLooking(mc);
				}
			}
		} else if (looking) {
			stopLooking(mc);
		}
		boolean brightWanted = on("fullbright");
		while (fullbright.consumeClick()) {
			if (brightWanted) setBright(mc, !bright);
		}
		if (!brightWanted) setBright(mc, false);
		else if (gammaBefore == null) setBright(mc, true);
	}
}
