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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Lang;
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
		"fps", "coords", "direction", "speed", "cps", "keystrokes", "armor", "totems", "items", "potions", "death", "armorWarn", "ping", "clock", "session", "day", "light", "memory", "packs", "minimap", "toggles", "bossbar", "scoreboard", "chatbox",
		"blockInfo", "fpsGraph", "playerList", "reach", "combo", "stopwatch", "server", "cooldowns"
	};

	private static KeyMapping[] keys;
	private static KeyMapping editor, zoom, freelook, fullbright, fullbrightUp, fullbrightDown, fovLock, hitboxes, overlays, sounds, crosshair, hurtCam, lightLevels, blockOutline, chat, perspective, fovKey, worldLook, hitColor, screens, waypointAdd, map, profile, chunkBorders, ownName, tntTimer, streamer, wheel, stopwatch, mirrors, mirrorGrab, mirrorType, mirrorArrange, mirrorHide;
	private static final Map<String, KeyMapping> MODULE_KEYS = new LinkedHashMap<>();

	/** Created on first use, while the game's options are being built (see OptionsMixin). */
	public static KeyMapping[] keys() {
		if (keys == null) {
			KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aerion", "main"));
			editor = new KeyMapping("key.aerion.hud_editor", InputConstants.KEY_RSHIFT, cat);
			zoom = new KeyMapping("key.aerion.zoom", InputConstants.KEY_C, cat);
			freelook = new KeyMapping("key.aerion.freelook", InputConstants.KEY_LALT, cat);
			fullbright = new KeyMapping("key.aerion.fullbright", InputConstants.UNKNOWN.getValue(), cat);
			fullbrightUp = new KeyMapping("key.aerion.fullbrightUp", InputConstants.UNKNOWN.getValue(), cat);
			fullbrightDown = new KeyMapping("key.aerion.fullbrightDown", InputConstants.UNKNOWN.getValue(), cat);
			fovLock = new KeyMapping("key.aerion.fovLock", InputConstants.UNKNOWN.getValue(), cat);
			hitboxes = new KeyMapping("key.aerion.hitboxes", InputConstants.UNKNOWN.getValue(), cat);
			overlays = new KeyMapping("key.aerion.overlays", InputConstants.UNKNOWN.getValue(), cat);
			sounds = new KeyMapping("key.aerion.sounds", InputConstants.UNKNOWN.getValue(), cat);
			crosshair = new KeyMapping("key.aerion.crosshair", InputConstants.UNKNOWN.getValue(), cat);
			hurtCam = new KeyMapping("key.aerion.hurtCam", InputConstants.UNKNOWN.getValue(), cat);
			lightLevels = new KeyMapping("key.aerion.lightLevels", InputConstants.UNKNOWN.getValue(), cat);
			blockOutline = new KeyMapping("key.aerion.blockOutline", InputConstants.UNKNOWN.getValue(), cat);
			chat = new KeyMapping("key.aerion.chat", InputConstants.UNKNOWN.getValue(), cat);
			perspective = new KeyMapping("key.aerion.perspective", InputConstants.KEY_V, cat);
			fovKey = new KeyMapping("key.aerion.fovKey", InputConstants.UNKNOWN.getValue(), cat);
			worldLook = new KeyMapping("key.aerion.worldLook", InputConstants.UNKNOWN.getValue(), cat);
			hitColor = new KeyMapping("key.aerion.hitColor", InputConstants.UNKNOWN.getValue(), cat);
			screens = new KeyMapping("key.aerion.screens", InputConstants.UNKNOWN.getValue(), cat);
			waypointAdd = new KeyMapping("key.aerion.waypointAdd", InputConstants.UNKNOWN.getValue(), cat);
			map = new KeyMapping("key.aerion.map", InputConstants.KEY_M, cat);
			profile = new KeyMapping("key.aerion.profile", InputConstants.UNKNOWN.getValue(), cat);
			chunkBorders = new KeyMapping("key.aerion.chunkBorders", InputConstants.UNKNOWN.getValue(), cat);
			ownName = new KeyMapping("key.aerion.ownName", InputConstants.UNKNOWN.getValue(), cat);
			tntTimer = new KeyMapping("key.aerion.tntTimer", InputConstants.UNKNOWN.getValue(), cat);
			streamer = new KeyMapping("key.aerion.streamer", InputConstants.KEY_F7, cat);
			wheel = new KeyMapping("key.aerion.wheel", InputConstants.KEY_GRAVE, cat);
			stopwatch = new KeyMapping("key.aerion.stopwatch", InputConstants.UNKNOWN.getValue(), cat);
			mirrors = new KeyMapping("key.aerion.mirrors", InputConstants.KEY_J, cat);
			mirrorGrab = new KeyMapping("key.aerion.mirrorGrab", InputConstants.KEY_Y, cat);
			mirrorType = new KeyMapping("key.aerion.mirrorType", InputConstants.KEY_U, cat);
			mirrorArrange = new KeyMapping("key.aerion.mirrorArrange", InputConstants.KEY_K, cat);
			mirrorHide = new KeyMapping("key.aerion.mirrorHide", InputConstants.KEY_R, cat);
			List<KeyMapping> all = new ArrayList<>(List.of(editor, zoom, freelook, fullbright, fullbrightUp, fullbrightDown, fovLock, hitboxes, overlays, sounds, crosshair, hurtCam, lightLevels,
					blockOutline, chat, perspective, fovKey, worldLook, hitColor, screens, waypointAdd, map, profile, chunkBorders, ownName, tntTimer, streamer, wheel, stopwatch, mirrors, mirrorGrab, mirrorType, mirrorArrange, mirrorHide));
			for (String id : MODULE_IDS) {
				KeyMapping k = new KeyMapping("key.aerion.module." + id, InputConstants.UNKNOWN.getValue(), cat);
				MODULE_KEYS.put(id, k);
				all.add(k);
			}
			keys = all.toArray(new KeyMapping[0]);
		}
		return keys;
	}

	/**
	 * Zoom and the game's "Save hotbar" both start on C, so a number pressed while zooming in
	 * creative overwrote a saved hotbar. Once per game folder, "Save hotbar" moves to B (or is
	 * cleared when B is taken); a player who later puts both on C keeps that choice.
	 */
	public static void sortKeysOnce(Minecraft mc) {
		Path marker = mc.gameDirectory.toPath().resolve("aerion-keys-sorted");
		if (Files.exists(marker) || zoom == null) return;
		KeyMapping save = mc.options.keySaveHotbarActivator;
		if (!zoom.isUnbound() && zoom.same(save)) {
			InputConstants.Key b = InputConstants.getKey("key.keyboard.b");
			boolean free = true;
			for (KeyMapping k : mc.options.keyMappings) {
				if (k.saveString().equals(b.getName()) && pl.aerion.mod.hud.KeyBoard.context(k).equals("game")) free = false;
			}
			save.setKey(free ? b : InputConstants.UNKNOWN);
			KeyMapping.resetMapping();
			mc.options.save();
			AerionClient.LOG.info("Aerion: \"Save hotbar\" moved off the zoom key to {}", free ? "B" : "none");
		}
		try {
			Files.writeString(marker, "Aerion moved \"Save hotbar\" off the zoom key once; delete to check again.\n");
		} catch (IOException e) {
			AerionClient.warnOnce("keys marker", e);
		}
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
			case "fullbrightUp" -> fullbrightUp;
			case "fullbrightDown" -> fullbrightDown;
			case "fovLock" -> fovLock;
			case "hitboxes" -> hitboxes;
			case "overlays" -> overlays;
			case "sounds" -> sounds;
			case "crosshair" -> crosshair;
			case "hurtCam" -> hurtCam;
			case "lightLevels" -> lightLevels;
			case "blockOutline" -> blockOutline;
			case "chat" -> chat;
			case "perspective" -> perspective;
			case "fovKey" -> fovKey;
			case "worldLook" -> worldLook;
			case "hitColor" -> hitColor;
			case "screens" -> screens;
			case "waypointAdd" -> waypointAdd;
			case "map" -> map;
			case "profile" -> profile;
			case "chunkBorders" -> chunkBorders;
			case "ownName" -> ownName;
			case "tntTimer" -> tntTimer;
			case "streamer" -> streamer;
			case "wheel" -> wheel;
			case "stopwatchStart" -> stopwatch;
			case "mirrors" -> mirrors;
			case "mirrorGrab" -> mirrorGrab;
			case "mirrorType" -> mirrorType;
			case "mirrorArrange" -> mirrorArrange;
			case "mirrorHide" -> mirrorHide;
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
	private static float zoomNow = 1f, fovNow = 1f;
	private static long lastFrame;
	/** The zoom factor while the key is held: starts at the setting, the mouse wheel changes it. */
	private static float scrolled = -1;

	private static boolean zooming() {
		return zoom != null && HudConfig.bool(feature("zoom"), "enabled", true) && zoom.isDown();
	}

	/** The mouse wheel while zooming: closer or further instead of the hotbar. True when used. */
	public static boolean zoomScroll(double amount) {
		JsonObject z = feature("zoom");
		if (amount == 0 || Game.screen() != null || !zooming() || !HudConfig.bool(z, "scroll", true)) return false;
		float base = scrolled > 0 ? scrolled : Math.max(2, Math.min(10, HudConfig.num(z, "factor", 4)));
		scrolled = Math.max(1.5f, Math.min(50f, base * (float) Math.pow(1.2, Math.signum(amount))));
		return true;
	}

	/** Multiplies the field of view; eases in and out when "smooth" is on. */
	public static float fovMultiplier() {
		JsonObject z = feature("zoom");
		boolean want = zooming();
		if (!want) scrolled = -1;
		float factor = scrolled > 0 ? scrolled : Math.max(2, Math.min(10, HudConfig.num(z, "factor", 4)));
		float target = want ? 1f / factor : 1f;
		float fovTarget = 1f;
		JsonObject fk = feature("fovKey");
		if (fovKey != null && HudConfig.bool(fk, "enabled", false) && fovKey.isDown()) {
			int base = Math.max(1, Minecraft.getInstance().options.fov().get());
			try {
				fovTarget = Math.max(10, Math.min(170, Integer.parseInt(HudConfig.opt(fk, "fov", "110")))) / (float) base;
			} catch (NumberFormatException e) {
				fovTarget = 1f;
			}
		}
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (HudConfig.bool(z, "smooth", true)) zoomNow += (target - zoomNow) * Math.min(1f, dt * 14f);
		else zoomNow = target;
		if (Math.abs(zoomNow - target) < 0.001f) zoomNow = target;
		fovNow += (fovTarget - fovNow) * Math.min(1f, dt * 14f);
		if (Math.abs(fovNow - fovTarget) < 0.001f) fovNow = fovTarget;
		return zoomNow * fovNow;
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

	// --- perspective ---------------------------------------------------------------------------
	private static CameraType beforePeek;

	private static void tickPerspective(Minecraft mc) {
		JsonObject f = feature("perspective");
		if (!HudConfig.bool(f, "enabled", false) || mc.player == null) {
			if (beforePeek != null) {
				mc.options.setCameraType(beforePeek);
				beforePeek = null;
			}
			while (perspective.consumeClick()) {
				// Nothing to do while switched off.
			}
			return;
		}
		boolean hold = "1".equals(HudConfig.opt(f, "hold", "1"));
		if (hold) {
			if (perspective.isDown() && beforePeek == null && Game.screen() == null) peek(mc, f);
			else if (!perspective.isDown() && beforePeek != null) unpeek(mc);
		} else {
			while (perspective.consumeClick()) {
				if (beforePeek == null) peek(mc, f);
				else unpeek(mc);
			}
		}
	}

	private static void peek(Minecraft mc, JsonObject f) {
		beforePeek = mc.options.getCameraType();
		mc.options.setCameraType("front".equals(HudConfig.opt(f, "view", "back")) ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
	}

	private static void unpeek(Minecraft mc) {
		mc.options.setCameraType(beforePeek);
		beforePeek = null;
	}

	// --- fullbright -------------------------------------------------------------------------
	private static boolean bright;
	private static Double gammaBefore;
	/** Gamma in percent while fullbright is on: 100 is the game's brightest, 1500 lights caves. */
	private static final int LEVEL_MIN = 100, LEVEL_MAX = 1500, LEVEL_STEP = 100;

	private static int level() {
		try {
			return Math.max(LEVEL_MIN, Math.min(LEVEL_MAX, Integer.parseInt(HudConfig.opt(feature("fullbright"), "level", "1500"))));
		} catch (NumberFormatException e) {
			return LEVEL_MAX;
		}
	}

	/**
	 * Keeps the game's gamma on the chosen level while fullbright is on. Other screens change it
	 * (Video Settings puts it back into the slider's range), so it is checked against the game's
	 * own value, and put back once no screen is open.
	 */
	@SuppressWarnings("unchecked")
	private static void applyLevel(Minecraft mc) {
		if (!bright || Game.screen() != null) return;
		double want = level() / 100.0;
		if (mc.options.gamma().get() != want) ((OptionInstanceAccessor<Double>) (Object) mc.options.gamma()).aerion$setValue(want);
	}

	/** A key raised or lowered the level: store it and say so above the hotbar. */
	private static void stepLevel(Minecraft mc, int direction) {
		int next = Math.max(LEVEL_MIN, Math.min(LEVEL_MAX, level() + direction * LEVEL_STEP));
		JsonObject f = feature("fullbright");
		JsonObject options = f.get("options") instanceof JsonObject o ? o : new JsonObject();
		options.addProperty("level", String.valueOf(next));
		f.add("options", options);
		AerionClient.saveHud();
		if (!bright) setBright(mc, true);
		applyLevel(mc);
		Game.overlayMessage(net.minecraft.network.chat.Component.literal(Lang.tr("aerion.fullbright.level").replace("{n}", String.valueOf(next))));
	}

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
			gamma.aerion$setValue(level() / 100.0);
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

	/** Whether panels of windows take clicks and the wheel now (feature on, no screen open). */
	public static boolean mirrorsActive() {
		return keys != null && Game.screen() == null && HudConfig.bool(feature("mirrors"), "enabled", false);
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
		toggleOnKey(chunkBorders, "chunkBorders", false);
		toggleOnKey(ownName, "ownName", false);
		toggleOnKey(tntTimer, "tntTimer", false);
		while (streamer.consumeClick()) Streamer.toggle();
		while (wheel.consumeClick()) {
			if (Game.screen() == null && mc.player != null && on("wheel")) Game.setScreen(new EditorScreen(new pl.aerion.mod.hud.WheelPage(wheelSlots()), null));
		}
		Streamer.sync();
		Effects.sync();
		pl.aerion.mod.hud.MapMemory.tick(mc);
		while (stopwatch.consumeClick()) Stopwatch.press();
		while (mirrors.consumeClick()) {
			if (Game.screen() == null && mc.player != null) {
				// Opening the picker is a clear wish: the feature turns on with it.
				JsonObject f = feature("mirrors");
				if (!HudConfig.bool(f, "enabled", false)) {
					f.addProperty("enabled", true);
					AerionClient.saveHud();
				}
				Game.setScreen(new EditorScreen(new pl.aerion.mod.mirror.MirrorsPage(), null));
			}
		}
		boolean mirrorsOn = HudConfig.bool(feature("mirrors"), "enabled", false);
		pl.aerion.mod.mirror.Mirrors.tick(mc, mirrorsOn && Game.screen() == null && mirrorGrab.isDown(), mirrorsOn);
		pl.aerion.mod.mirror.ScreenShare.tick(mc);
		while (mirrorArrange.consumeClick()) if (mirrorsOn) pl.aerion.mod.mirror.Mirrors.arrange();
		while (mirrorHide.consumeClick()) if (mirrorsOn) pl.aerion.mod.mirror.Mirrors.toggleHidden();
		while (mirrorType.consumeClick()) {
			long win = mirrorsOn && Game.screen() == null ? pl.aerion.mod.mirror.Mirrors.aimedWindow() : 0;
			if (win != 0) {
				var hit = pl.aerion.mod.mirror.Mirrors.aim();
				Game.setScreen(new EditorScreen(new pl.aerion.mod.mirror.TypePage(win, hit != null ? hit.panel().title : ""), null));
			}
		}
		Combat.tick(mc);
		toggleOnKey(lightLevels, "lightLevels", false);
		toggleOnKey(blockOutline, "blockOutline", false);
		toggleOnKey(chat, "chat", false);
		toggleOnKey(worldLook, "worldLook", false);
		toggleOnKey(hitColor, "hitColor", false);
		toggleOnKey(screens, "screens", false);
		HitColor.tick();
		Waypoints.tick(mc);
		while (profile.consumeClick()) pl.aerion.mod.hud.Profiles.cycle(AerionClient.hud());
		while (map.consumeClick()) {
			if (Game.screen() == null && mc.player != null) Game.setScreen(new EditorScreen(new pl.aerion.mod.hud.MapPage(), null));
		}
		while (waypointAdd.consumeClick()) {
			Waypoints.Point w = Waypoints.addHere(Lang.tr("aerion.waypoint.default"));
			if (w != null) Game.overlayMessage(Component.literal(w.name + " (" + w.x + ", " + w.y + ", " + w.z + ")"));
		}
		tickPerspective(mc);
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
		while (fullbrightUp.consumeClick()) {
			if (brightWanted) stepLevel(mc, 1);
		}
		while (fullbrightDown.consumeClick()) {
			if (brightWanted) stepLevel(mc, -1);
		}
		if (!brightWanted) setBright(mc, false);
		else if (gammaBefore == null) setBright(mc, true);
		applyLevel(mc);
		while (fovLock.consumeClick()) {
			JsonObject f = feature("fovLock");
			boolean next = !HudConfig.bool(f, "enabled", false);
			f.addProperty("enabled", next);
			AerionClient.saveHud();
			Game.overlayMessage(net.minecraft.network.chat.Component.literal(Lang.tr("aerion.feature.fovLock") + ": " + Lang.tr(next ? "aerion.state.on" : "aerion.state.off")));
		}
	}

	// --- The quick wheel ----------------------------------------------------------------------
	/** What a wheel slot can do, in the order offered in the menu ("none" leaves a slot out). */
	public static final List<String> WHEEL_ACTIONS = List.of("none", "fullbright", "hitboxes", "chunkBorders", "lightLevels", "perspective", "waypoint", "map",
			"profile", "mirrors", "streamer", "stopwatch");
	private static final String[] WHEEL_DEFAULTS = {"fullbright", "hitboxes", "waypoint", "map", "perspective", "streamer"};

	public static boolean isWheelKey(InputConstants.Key key) {
		return wheel != null && !wheel.isUnbound() && wheel.saveString().equals(key.getName());
	}

	/** The wheel's slots from the settings (slot1…slot8), empty ones left out. */
	private static List<String> wheelSlots() {
		JsonObject f = feature("wheel");
		List<String> out = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			String id = HudConfig.opt(f, "slot" + (i + 1), i < WHEEL_DEFAULTS.length ? WHEEL_DEFAULTS[i] : "none");
			if (!"none".equals(id) && WHEEL_ACTIONS.contains(id)) out.add(id);
		}
		return out;
	}

	public static String wheelLabel(String id) {
		return Lang.tr("aerion.choice." + id);
	}

	/** The icon (from the menu's icons) shown in a slot. */
	public static String wheelIcon(String id) {
		return switch (id) {
			case "fullbright" -> "light";
			case "perspective" -> "fovKey";
			case "waypoint" -> "waypoints";
			case "map" -> "minimap";
			case "profile" -> "layout";
			default -> id;
		};
	}

	/** Runs what a wheel slot stands for (the same as its own key). */
	public static void runWheel(String id) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		switch (id) {
			case "fullbright" -> {
				JsonObject f = feature("fullbright");
				if (!HudConfig.bool(f, "enabled", false)) {
					f.addProperty("enabled", true);
					AerionClient.saveHud();
				} else {
					setBright(mc, !bright);
				}
			}
			case "hitboxes", "chunkBorders", "lightLevels" -> {
				JsonObject f = feature(id);
				boolean next = !HudConfig.bool(f, "enabled", false);
				f.addProperty("enabled", next);
				AerionClient.saveHud();
				Game.overlayMessage(Component.literal(Lang.tr("aerion.feature." + id) + ": " + Lang.tr(next ? "aerion.state.on" : "aerion.state.off")));
			}
			case "perspective" -> mc.options.setCameraType(mc.options.getCameraType().cycle());
			case "waypoint" -> {
				Waypoints.Point w = Waypoints.addHere(Lang.tr("aerion.waypoint.default"));
				if (w != null) Game.overlayMessage(Component.literal(w.name + " (" + w.x + ", " + w.y + ", " + w.z + ")"));
			}
			case "map" -> Game.setScreen(new EditorScreen(new pl.aerion.mod.hud.MapPage(), null));
			case "profile" -> pl.aerion.mod.hud.Profiles.cycle(AerionClient.hud());
			case "mirrors" -> Game.setScreen(new EditorScreen(new pl.aerion.mod.mirror.MirrorsPage(), null));
			case "streamer" -> Streamer.toggle();
			case "stopwatch" -> Stopwatch.press();
			default -> {}
		}
	}

	// --- FOV lock ---------------------------------------------------------------------------
	/** True while the field of view should ignore sprinting, speed effects and bows. */
	public static boolean fovLocked(net.minecraft.world.entity.player.Player player) {
		JsonObject f = feature("fovLock");
		if (!HudConfig.bool(f, "enabled", false)) return false;
		// The spyglass still zooms, unless the player asked otherwise.
		return !(player.isScoping() && "1".equals(HudConfig.opt(f, "spyglass", "1")));
	}
}
